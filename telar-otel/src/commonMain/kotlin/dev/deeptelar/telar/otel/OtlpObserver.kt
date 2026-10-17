package dev.deeptelar.telar.otel

import dev.deeptelar.telar.GraphConfig
import dev.deeptelar.telar.GraphObserver
import dev.deeptelar.telar.GraphValidationException
import dev.deeptelar.telar.NodeExecutionException
import dev.deeptelar.telar.ObservedNode
import dev.deeptelar.telar.ObservedRun
import dev.deeptelar.telar.TelarException
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
 * - **A failure** is recorded on the span of the node and of the run, as an event `exception` and
 *   the status `ERROR`. A cancelled node or run is not an error.
 *
 * The state of the run is never recorded, nor what a node reports with `reportProgress`: both can
 * hold personal data.
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
 * **What a node calls.** While a node runs, its coroutine context holds a [TraceContext] with the
 * ids of its span. The [Traceparent] plugin of Ktor sends them with each request, so that a server
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
 * @param onExportFailed told when spans could not be sent. It must not throw.
 * @throws GraphValidationException if [endpoint] is blank or [exportDelay] is negative.
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
    private val onExportFailed: (OtlpExportException) -> Unit = {},
) : GraphObserver {
    private val resourceAttributes: List<Attribute> =
        (mapOf("service.name" to serviceName) + resource).map { (key, value) -> Attribute(key, AttributeValue.Text(value)) }

    private val requestHeaders: Map<String, String> = headers.toMap()

    /** The spans that started and have not ended, by the run or node they belong to. */
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

    private fun start(key: Any, name: String, parent: TraceContext?, attributes: List<Attribute>) {
        val span =
            SpanRecord(
                traceId = parent?.traceId ?: randomId(TRACE_ID_BYTES),
                spanId = randomId(SPAN_ID_BYTES),
                parentSpanId = parent?.spanId,
                name = name,
                startNanos = Clock.System.now().let { it.epochSeconds * NANOS_PER_SECOND + it.nanosecondsOfSecond },
                started = TimeSource.Monotonic.markNow(),
                attributes = attributes,
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
            with(OUTCOME, "failed")
                .withEvent(
                    "exception",
                    Attribute(EXCEPTION_TYPE, AttributeValue.Text(cause.typeName())),
                    Attribute(EXCEPTION_MESSAGE, AttributeValue.Text(cause.message.orEmpty())),
                    Attribute(EXCEPTION_STACKTRACE, AttributeValue.Text(cause.stackTraceToString())),
                ).copy(error = cause.message.orEmpty())
        }
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
private const val NODES = "telar.nodes"
private const val NEXT_NODES = "telar.next_nodes"
private const val STEP = "telar.step"
private const val OUTCOME = "telar.outcome"
private const val ATTEMPT = "telar.attempt"
private const val PAUSE_MS = "telar.pause_ms"
private const val EXCEPTION_TYPE = "exception.type"
private const val EXCEPTION_MESSAGE = "exception.message"
private const val EXCEPTION_STACKTRACE = "exception.stacktrace"
