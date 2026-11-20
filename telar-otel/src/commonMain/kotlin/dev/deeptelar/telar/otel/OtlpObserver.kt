package dev.deeptelar.telar.otel

import dev.deeptelar.telar.EdgeConditionException
import dev.deeptelar.telar.GraphConfig
import dev.deeptelar.telar.GraphValidationException
import dev.deeptelar.telar.NodeExecutionException
import dev.deeptelar.telar.ObservedEdge
import dev.deeptelar.telar.ObservedNode
import dev.deeptelar.telar.ObservedRun
import dev.deeptelar.telar.TelarException
import dev.deeptelar.telar.agent.ChatMessage
import dev.deeptelar.telar.agent.ChatRequest
import dev.deeptelar.telar.agent.ChatResponse
import dev.deeptelar.telar.agent.ConversationObserver
import dev.deeptelar.telar.agent.ObservedModelCall
import dev.deeptelar.telar.agent.ObservedToolCall
import dev.deeptelar.telar.agent.TokenUsage
import dev.deeptelar.telar.agent.ToolCall
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Sends the runs of a graph as traces to an OpenTelemetry collector, or to any backend that receives
 * OTLP over HTTP, on every platform: Android, iOS, desktop, servers and the browser. Give it to a run
 * with [GraphConfig.observers]:
 *
 * ```kotlin
 * val tracing = OtlpObserver(httpClient, appScope, endpoint = "https://collector.example.com/v1/traces")
 * graph.invoke(order, GraphConfig(observers = listOf(tracing)))
 * ```
 *
 * The spans are the same as those of `OpenTelemetryObserver`, which is for the JVM. A
 * run is a span named `telar.run`, and each node a span named `telar.node <name>` inside it. A
 * subgraph is a `telar.run` inside the span of the node that runs it.
 *
 * - **The run** has the attributes `telar.thread_id` and `telar.outcome` (`completed`,
 *   `interrupted`, `failed` or `cancelled`), and `telar.next_nodes` when it paused. It has an event
 *   `telar.step` for each step, with the nodes that ran and those that the edges chose next, and an
 *   event `telar.checkpoint` for each saved checkpoint.
 * - **A node** has the attributes `telar.node`, `telar.step` and `telar.outcome`, and an event
 *   `telar.retry` for each try that failed and was followed by another.
 * - **An edge with a condition** is a span `telar.edge <from>` inside the run, with
 *   `telar.edge.from`, `telar.step` (the step whose results it routes, 0 for an edge from `START`),
 *   `telar.outcome`, and `telar.next_nodes`, what the condition chose. A plain edge has no span.
 * - **A failure** is recorded on the span of the node and of the run, as an event `exception` and
 *   the status `ERROR`. A cancelled node or run is not an error.
 *
 * **Calls to models and tools.** It is also an `AgentObserver`, so the calls that a node of
 * `toolLoop` or `toolAgent`, `ask`, `chatWithProgress` or a `ChatDecisionModel` makes are spans inside
 * the span of the node, or of the edge for the condition of a `decisionEdge`, named as the
 * [semantic conventions for generative AI](https://opentelemetry.io/docs/specs/semconv/gen-ai/) name
 * them, so that a backend that knows them shows them as calls to a model:
 *
 * ```
 * telar.run                        telar.thread_id=ticket-42
 * ├─ telar.node model              telar.step=1
 * │  └─ chat gpt-5                 gen_ai.request.model=gpt-5  gen_ai.usage.input_tokens=812
 * ├─ telar.node tools              telar.step=2
 * │  └─ execute_tool order_status  gen_ai.tool.call.id=toolu_01...
 * └─ telar.node model              telar.step=3
 *    └─ chat gpt-5                 gen_ai.request.model=gpt-5  gen_ai.usage.input_tokens=901
 * ```
 *
 * - **A call to a model** is a span `chat <model>` of kind `CLIENT`, with `gen_ai.operation.name`,
 *   `gen_ai.request.model` (the [name][dev.deeptelar.telar.agent.ChatModel.name] of the model),
 *   `gen_ai.response.model` (the model that answered, when the provider says), and the tokens its
 *   provider reported: `gen_ai.usage.input_tokens` (the tokens read from a cache included) and
 *   `gen_ai.usage.output_tokens`, and `gen_ai.usage.cache_read.input_tokens` and
 *   `gen_ai.usage.cache_write.input_tokens` when they are not 0. A backend prices the call with the
 *   model and the tokens. A model whose name is not known, such as one of your own that does not
 *   override it, gives a span `chat` without `gen_ai.request.model`. A call that failed has the
 *   status `ERROR`, an `error.type` and an event `exception`.
 * - **A tool call** is a span `execute_tool <name>`, with `gen_ai.tool.name` and
 *   `gen_ai.tool.call.id`. A tool that returned an error to the model has the status `ERROR` and
 *   `error.type` `tool_error`, although the run goes on. A call that ended without a result, because
 *   the run was cancelled or paused, has no status: the span of the node says what happened.
 *
 * Observer functions are not `suspend`, so the span of a call cannot be current while it runs: the
 * HTTP request of a model is inside the span of the node, next to the span `chat`.
 *
 * The state of the run is never recorded, nor what a node reports with `reportProgress`: both can
 * hold personal data.
 *
 * **The conversation**, with [recordContent]: the span of a model call gets what it sent
 * (`gen_ai.system_instructions` and `gen_ai.input.messages`) and the answer
 * (`gen_ai.output.messages`), and the span of a tool call its input (`gen_ai.tool.call.arguments`)
 * and result (`gen_ai.tool.call.result`), as JSON in strings, as the conventions write them. A
 * backend such as Langfuse shows them as the input and output of the call. Each text is cut at
 * [maxContentLength] characters, with a note of how long it was. An image or a PDF becomes a note of
 * its type and size, such as `[image/png, 47 KB]`: its bytes are never recorded. Nor is the
 * `providerContent` of a message, such as the thinking of Claude, nor the tools the model was offered.
 * The conversation can hold personal data, such as what the users of the app wrote, so it is off
 * by default: turn it on only when you know who can read the traces.
 *
 * **Sending.** A span is sent when it ends, together with the others that end within [exportDelay],
 * as OTLP/HTTP with JSON, in a coroutine of [scope]. The app creates the [client], with the engine of
 * its platform, and closes it. Nothing is ever thrown into a run: a request that fails is reported to
 * [onExportFailed] and its spans are dropped. When more spans wait than can be sent, the oldest are
 * dropped. Call [flush] before the app ends, so that the last spans are sent.
 *
 * **In the browser**, the collector has to allow the origin of the page, for example with
 * `cors.allowed_origins` in the `http` protocol of the `otlp` receiver of the OpenTelemetry
 * Collector. A key in [headers] can be read by everyone who opens the page.
 *
 * **What a node calls.** While a node or the condition of an edge runs, its coroutine context holds
 * a [TraceContext] with the ids of its span. The [Traceparent] plugin of Ktor sends them with each request, so that a server
 * of the app records its spans inside the node.
 *
 * **Inside a trace of the app.** A run is a trace of its own, unless its coroutine context holds a
 * [TraceContext]: then the run is inside that span. The app puts one there, for example from the
 * `traceparent` header of the request that started the run (see [TraceContext.parse]), and a run
 * started inside a node traced by an `OtlpObserver` already has the one of that node.
 *
 * @param client sends the spans. Its engine decides the platform: `Js` in the browser, `Darwin` on
 * iOS, `OkHttp` on Android, `CIO` or `Java` on the JVM.
 * @param scope the coroutines that send the spans run in it. Cancel it, and nothing more is sent
 * except by [flush].
 * @param endpoint the URL of the traces, `/v1/traces` of the collector. The default is a collector
 * on the same machine.
 * @param serviceName the `service.name` of the spans, the name a backend shows for the app.
 * @param resource more attributes of the app, such as `service.version` or `deployment.environment`.
 * @param headers sent with each request, for example the key of a backend that receives OTLP itself.
 * @param exportDelay how long a span that ended waits for others before they are sent together.
 * @param recordContent records the messages of each model call and the input and result of each
 * tool call. Off by default, because they can hold personal data.
 * @param maxContentLength the most characters of each text that [recordContent] records.
 * @param onExportFailed told when spans could not be sent. It must not throw.
 * @throws GraphValidationException if [endpoint] is blank, [exportDelay] is negative or
 * [maxContentLength] is less than 1.
 */
@OptIn(ExperimentalAtomicApi::class)
public class OtlpObserver(
    private val client: HttpClient,
    scope: CoroutineScope,
    private val endpoint: String = DEFAULT_ENDPOINT,
    serviceName: String = DEFAULT_SERVICE_NAME,
    resource: Map<String, String> = emptyMap(),
    headers: Map<String, String> = emptyMap(),
    private val exportDelay: Duration = 1.seconds,
    private val recordContent: Boolean = false,
    maxContentLength: Int = ConversationAttributes.DEFAULT_MAX_LENGTH,
    private val onExportFailed: (OtlpExportException) -> Unit = {},
) : ConversationObserver {
    private val content = ConversationAttributes(maxContentLength)

    private val resourceAttributes: List<Attribute> =
        (mapOf("service.name" to serviceName) + resource).map { (key, value) -> Attribute(key, AttributeValue.Text(value)) }

    private val requestHeaders: Map<String, String> = headers.toMap()

    /** The spans that started and have not ended, by the run, node, edge or call they belong to. */
    private val open = AtomicReference<Map<Any, SpanRecord>>(emptyMap())

    /** The spans that ended and wait to be sent. */
    private val ended = Channel<SpanRecord>(MAX_QUEUED_SPANS, BufferOverflow.DROP_OLDEST)

    /** Wakes the coroutine that sends the spans. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** One request at a time, from the coroutine of [scope] or from [flush]. */
    private val sending = Mutex()

    init {
        if (endpoint.isBlank()) throw GraphValidationException("endpoint must not be blank.")
        if (exportDelay.isNegative()) throw GraphValidationException("exportDelay must not be negative, was $exportDelay.")
        scope.launch {
            while (true) {
                wake.receive()
                delay(exportDelay)
                flush()
            }
        }
    }

    /**
     * Sends every span that ended and was not sent yet, and returns when the collector answered.
     * Call it before the app ends, or when a page in the browser is hidden. A span that has not ended,
     * of a run that is still going, is sent when it ends. Failures go to `onExportFailed`.
     */
    public suspend fun flush() {
        sending.withLock {
            while (true) {
                val batch = generateSequence { ended.tryReceive().getOrNull() }.take(MAX_SPANS_PER_REQUEST).toList()
                if (batch.isEmpty()) return
                send(batch)
            }
        }
    }

    override fun onRunStarted(run: ObservedRun) {
        // A subgraph is inside its node; another run is inside the span the app or a node put around it.
        val parent = run.parent?.let { open.load()[it]?.context } ?: run.context[TraceContext]
        start(
            run,
            name = "telar.run",
            parent = parent,
            attributes = listOf(Attribute(THREAD_ID, AttributeValue.Text(run.threadId))),
        )
    }

    override fun onRunCompleted(run: ObservedRun) {
        end(run) { it.with(OUTCOME, "completed") }
    }

    override fun onRunInterrupted(run: ObservedRun, nextNodes: List<String>) {
        end(run) { it.with(OUTCOME, "interrupted").with(Attribute(NEXT_NODES, AttributeValue.Texts(nextNodes))) }
    }

    override fun onRunFailed(run: ObservedRun, cause: Throwable) {
        end(run) { it.failed(cause) }
    }

    override fun onNodeStarted(node: ObservedNode) {
        val parent = open.load()[node.run] ?: return
        start(
            node,
            name = "telar.node ${node.name}",
            parent = parent.context,
            attributes =
                listOf(
                    Attribute(NODE, AttributeValue.Text(node.name)),
                    Attribute(STEP, AttributeValue.Number(node.step.toLong())),
                ),
        )
    }

    override fun onNodeRetry(node: ObservedNode, attempt: Int, cause: Throwable, pause: Duration) {
        update(node) {
            it.withEvent(
                "telar.retry",
                Attribute(ATTEMPT, AttributeValue.Number(attempt.toLong())),
                Attribute(PAUSE_MS, AttributeValue.Number(pause.inWholeMilliseconds)),
                Attribute(EXCEPTION_TYPE, AttributeValue.Text(cause.typeName())),
                Attribute(EXCEPTION_MESSAGE, AttributeValue.Text(cause.message.orEmpty())),
            )
        }
    }

    override fun onNodeCompleted(node: ObservedNode) {
        end(node) { it.with(OUTCOME, "completed") }
    }

    override fun onNodeInterrupted(node: ObservedNode) {
        end(node) { it.with(OUTCOME, "interrupted") }
    }

    override fun onNodeFailed(node: ObservedNode, cause: Throwable) {
        // A NodeExecutionException only says which node failed, and the span says that already.
        end(node) { it.failed(if (cause is NodeExecutionException) cause.cause ?: cause else cause) }
    }

    override fun onStepCompleted(run: ObservedRun, step: Int, nodes: List<String>, nextNodes: List<String>) {
        update(run) {
            it.withEvent(
                "telar.step",
                Attribute(STEP, AttributeValue.Number(step.toLong())),
                Attribute(NODES, AttributeValue.Texts(nodes)),
                Attribute(NEXT_NODES, AttributeValue.Texts(nextNodes)),
            )
        }
    }

    override fun onCheckpointSaved(run: ObservedRun, step: Int, nextNodes: List<String>) {
        update(run) {
            it.withEvent(
                "telar.checkpoint",
                Attribute(STEP, AttributeValue.Number(step.toLong())),
                Attribute(NEXT_NODES, AttributeValue.Texts(nextNodes)),
            )
        }
    }

    override fun nodeContext(node: ObservedNode): CoroutineContext = open.load()[node]?.context ?: EmptyCoroutineContext

    override fun onEdgeStarted(edge: ObservedEdge) {
        val parent = open.load()[edge.run] ?: return
        start(
            edge,
            name = "telar.edge ${edge.from}",
            parent = parent.context,
            attributes =
                listOf(
                    Attribute(EDGE_FROM, AttributeValue.Text(edge.from)),
                    Attribute(STEP, AttributeValue.Number(edge.step.toLong())),
                ),
        )
    }

    override fun onEdgeCompleted(edge: ObservedEdge, targets: List<String>) {
        end(edge) { it.with(OUTCOME, "completed").with(Attribute(NEXT_NODES, AttributeValue.Texts(targets))) }
    }

    override fun onEdgeFailed(edge: ObservedEdge, cause: Throwable) {
        // An EdgeConditionException only says which edge failed, and the span says that already.
        end(edge) { it.failed(if (cause is EdgeConditionException) cause.cause ?: cause else cause) }
    }

    override fun edgeContext(edge: ObservedEdge): CoroutineContext = open.load()[edge]?.context ?: EmptyCoroutineContext

    override fun onModelCallStarted(call: ObservedModelCall) {
        val parent = open.load()[call.caller] ?: return
        val model = call.model.name?.takeIf { it.isNotBlank() }
        start(
            call,
            name = model?.let { "$CHAT $it" } ?: CHAT,
            parent = parent.context,
            attributes =
                listOfNotNull(
                    Attribute(GEN_AI_OPERATION, AttributeValue.Text(CHAT)),
                    model?.let { Attribute(GEN_AI_REQUEST_MODEL, AttributeValue.Text(it)) },
                ),
            kind = SpanKind.CLIENT,
        )
    }

    override fun onModelCallRequest(call: ObservedModelCall, request: ChatRequest) {
        if (recordContent) addContent(call, content.request(request))
    }

    override fun onModelCallResponse(call: ObservedModelCall, response: ChatResponse) {
        if (recordContent) addContent(call, content.response(response))
    }

    override fun onModelCallCompleted(call: ObservedModelCall, usage: TokenUsage?) {
        end(call) { span ->
            val answered = call.responseModel?.takeIf { it.isNotBlank() }
            val named = answered?.let { span.with(Attribute(GEN_AI_RESPONSE_MODEL, AttributeValue.Text(it))) } ?: span
            usage?.let { named.withUsage(it) } ?: named
        }
    }

    override fun onModelCallFailed(call: ObservedModelCall, cause: Throwable) {
        end(call) { if (cause is CancellationException) it else it.callFailed(cause) }
    }

    override fun onToolCallStarted(call: ObservedToolCall) {
        val parent = open.load()[call.caller] ?: return
        val attributes =
            listOfNotNull(
                Attribute(GEN_AI_OPERATION, AttributeValue.Text(EXECUTE_TOOL)),
                Attribute(GEN_AI_TOOL_NAME, AttributeValue.Text(call.name)),
                Attribute(GEN_AI_TOOL_TYPE, AttributeValue.Text("function")),
                call.id.takeIf { it.isNotEmpty() }?.let { Attribute(GEN_AI_TOOL_CALL_ID, AttributeValue.Text(it)) },
            )
        start(call, name = "$EXECUTE_TOOL ${call.name}", parent = parent.context, attributes = attributes)
    }

    override fun onToolCallInput(call: ObservedToolCall, toolCall: ToolCall) {
        if (recordContent) addContent(call, content.toolInput(toolCall))
    }

    override fun onToolCallResult(call: ObservedToolCall, result: ChatMessage.ToolResult) {
        if (recordContent) addContent(call, content.toolResult(result))
    }

    override fun onToolCallCompleted(call: ObservedToolCall, isError: Boolean) {
        end(call) { if (isError) it.with(ERROR_TYPE, TOOL_ERROR).copy(error = "The tool returned an error to the model.") else it }
    }

    override fun onToolCallFailed(call: ObservedToolCall, cause: Throwable) {
        // Cancelled, paused with `interrupt`, or an error that ends the run: the span of the node says which.
        end(call) { it }
    }

    private fun addContent(call: Any, attributes: Map<String, String>) {
        update(call) { span -> attributes.entries.fold(span) { it, (key, value) -> it.with(key, value) } }
    }

    private fun start(key: Any, name: String, parent: TraceContext?, attributes: List<Attribute>, kind: SpanKind = SpanKind.INTERNAL) {
        val span =
            SpanRecord(
                traceId = parent?.traceId ?: randomId(TRACE_ID_BYTES),
                spanId = randomId(SPAN_ID_BYTES),
                parentSpanId = parent?.spanId,
                name = name,
                startNanos = Clock.System.now().let { it.epochSeconds * NANOS_PER_SECOND + it.nanosecondsOfSecond },
                started = TimeSource.Monotonic.markNow(),
                attributes = attributes,
                kind = kind,
            )
        while (true) {
            val current = open.load()
            if (open.compareAndSet(current, current + (key to span))) return
        }
    }

    /** Replaces the open span of [key] with what [change] makes of it. [change] may run more than once. */
    private inline fun update(key: Any, change: (SpanRecord) -> SpanRecord) {
        while (true) {
            val current = open.load()
            val span = current[key] ?: return
            if (open.compareAndSet(current, current + (key to change(span)))) return
        }
    }

    /** Ends the open span of [key] with what [finish] adds to it, and queues it to be sent. */
    private inline fun end(key: Any, finish: (SpanRecord) -> SpanRecord) {
        var span: SpanRecord
        while (true) {
            val current = open.load()
            span = current[key] ?: return
            if (open.compareAndSet(current, current - key)) break
        }
        ended.trySend(finish(span).copy(endNanos = span.nanosNow()))
        wake.trySend(Unit)
    }

    private suspend fun send(batch: List<SpanRecord>) {
        val body = encodeTraces(resourceAttributes, INSTRUMENTATION_SCOPE, batch)
        val failure =
            try {
                val response =
                    client.post(endpoint) {
                        requestHeaders.forEach { (name, value) -> header(name, value) }
                        setBody(TextContent(body, ContentType.Application.Json))
                    }
                if (response.status.isSuccess()) {
                    null
                } else {
                    OtlpExportException(
                        "$endpoint answered ${response.status} to ${batch.size} spans: ${response.bodyAsText().take(MAX_ERROR_LENGTH)}",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // An error of the program goes on, as in the engine. Ktor in a browser reports a request
                // that got no response as a plain Error, and that is a failure to send.
                if (e is Error && e::class != Error::class) throw e
                OtlpExportException("Could not send ${batch.size} spans to $endpoint: ${e.message}", e)
            }
        failure?.let(onExportFailed)
    }
}

/**
 * Spans could not be sent to the collector of an [OtlpObserver]: it could not be reached, or it did
 * not accept them. The run they belong to is not affected.
 */
public class OtlpExportException(
    message: String,
    cause: Throwable? = null,
) : TelarException(message, cause)

/** A span that started, as it is sent once it ended. */
internal data class SpanRecord(
    val traceId: String,
    val spanId: String,
    val parentSpanId: String?,
    val name: String,
    val startNanos: Long,
    val started: TimeMark,
    val attributes: List<Attribute>,
    val events: List<SpanEvent> = emptyList(),
    val error: String? = null,
    val endNanos: Long? = null,
    val kind: SpanKind = SpanKind.INTERNAL,
) {
    /** The ids of this span, as the parent of another. */
    val context: TraceContext get() = TraceContext(traceId, spanId)

    /** The time since the epoch, measured from the start on a clock that never jumps. */
    fun nanosNow(): Long = startNanos + started.elapsedNow().inWholeNanoseconds

    fun with(attribute: Attribute): SpanRecord = copy(attributes = attributes.filterNot { it.key == attribute.key } + attribute)

    fun with(key: String, value: String): SpanRecord = with(Attribute(key, AttributeValue.Text(value)))

    fun withEvent(name: String, vararg attributes: Attribute): SpanRecord =
        copy(
            events =
                events + SpanEvent(name, nanosNow(), attributes.toList()),
        )

    fun failed(cause: Throwable): SpanRecord =
        if (cause is CancellationException) {
            with(OUTCOME, "cancelled")
        } else {
            with(OUTCOME, "failed").withException(cause)
        }

    /** A call that failed, as the semantic conventions for generative AI record it. */
    fun callFailed(cause: Throwable): SpanRecord = with(ERROR_TYPE, cause.typeName()).withException(cause)

    fun withUsage(usage: TokenUsage): SpanRecord =
        listOfNotNull(
            GEN_AI_INPUT_TOKENS to usage.inputTokens,
            GEN_AI_OUTPUT_TOKENS to usage.outputTokens,
            (GEN_AI_CACHE_READ_TOKENS to usage.cacheReadTokens).takeIf { it.second > 0 },
            (GEN_AI_CACHE_WRITE_TOKENS to usage.cacheWriteTokens).takeIf { it.second > 0 },
        ).fold(this) { span, (key, tokens) -> span.with(Attribute(key, AttributeValue.Number(tokens.toLong()))) }

    private fun withException(cause: Throwable): SpanRecord =
        withEvent(
            "exception",
            Attribute(EXCEPTION_TYPE, AttributeValue.Text(cause.typeName())),
            Attribute(EXCEPTION_MESSAGE, AttributeValue.Text(cause.message.orEmpty())),
            Attribute(EXCEPTION_STACKTRACE, AttributeValue.Text(cause.stackTraceToString())),
        ).copy(error = cause.message.orEmpty())
}

/** What a span is to its trace, with its number in OTLP. */
internal enum class SpanKind(
    val code: Int,
) {
    /** Work inside the app: a run, a node or a tool call. */
    INTERNAL(1),

    /** A request to another service: a call to a model. */
    CLIENT(3),
}

internal data class SpanEvent(
    val name: String,
    val timeNanos: Long,
    val attributes: List<Attribute>,
)

internal data class Attribute(
    val key: String,
    val value: AttributeValue,
)

internal sealed interface AttributeValue {
    data class Text(
        val value: String,
    ) : AttributeValue

    data class Number(
        val value: Long,
    ) : AttributeValue

    data class Texts(
        val values: List<String>,
    ) : AttributeValue
}

/** The name of the class of this exception, with its package where the platform knows it. */
internal expect fun Throwable.typeName(): String

@OptIn(ExperimentalStdlibApi::class)
private fun randomId(bytes: Int): String {
    while (true) {
        val id = Random.nextBytes(bytes)
        // An id of zeros is invalid in OpenTelemetry.
        if (id.any { it != 0.toByte() }) return id.toHexString()
    }
}

private const val DEFAULT_ENDPOINT = "http://localhost:4318/v1/traces"
private const val DEFAULT_SERVICE_NAME = "telar"
private const val INSTRUMENTATION_SCOPE = "dev.deeptelar.telar"
private const val MAX_QUEUED_SPANS = 2048
private const val MAX_SPANS_PER_REQUEST = 512
private const val MAX_ERROR_LENGTH = 500
private const val TRACE_ID_BYTES = 16
private const val SPAN_ID_BYTES = 8
private const val NANOS_PER_SECOND = 1_000_000_000L

private const val THREAD_ID = "telar.thread_id"
private const val NODE = "telar.node"
private const val EDGE_FROM = "telar.edge.from"
private const val NODES = "telar.nodes"
private const val NEXT_NODES = "telar.next_nodes"
private const val STEP = "telar.step"
private const val OUTCOME = "telar.outcome"
private const val ATTEMPT = "telar.attempt"
private const val PAUSE_MS = "telar.pause_ms"
private const val EXCEPTION_TYPE = "exception.type"
private const val EXCEPTION_MESSAGE = "exception.message"
private const val EXCEPTION_STACKTRACE = "exception.stacktrace"
private const val ERROR_TYPE = "error.type"
private const val TOOL_ERROR = "tool_error"
private const val CHAT = "chat"
private const val EXECUTE_TOOL = "execute_tool"
private const val GEN_AI_OPERATION = "gen_ai.operation.name"
private const val GEN_AI_REQUEST_MODEL = "gen_ai.request.model"
private const val GEN_AI_RESPONSE_MODEL = "gen_ai.response.model"
private const val GEN_AI_INPUT_TOKENS = "gen_ai.usage.input_tokens"
private const val GEN_AI_OUTPUT_TOKENS = "gen_ai.usage.output_tokens"
private const val GEN_AI_CACHE_READ_TOKENS = "gen_ai.usage.cache_read.input_tokens"
private const val GEN_AI_CACHE_WRITE_TOKENS = "gen_ai.usage.cache_write.input_tokens"
private const val GEN_AI_TOOL_NAME = "gen_ai.tool.name"
private const val GEN_AI_TOOL_TYPE = "gen_ai.tool.type"
private const val GEN_AI_TOOL_CALL_ID = "gen_ai.tool.call.id"
