package dev.deeptelar.telar.otel

import dev.deeptelar.telar.EdgeConditionException
import dev.deeptelar.telar.GraphConfig
import dev.deeptelar.telar.NodeExecutionException
import dev.deeptelar.telar.ObservedCaller
import dev.deeptelar.telar.ObservedEdge
import dev.deeptelar.telar.ObservedNode
import dev.deeptelar.telar.ObservedRun
import dev.deeptelar.telar.agent.AgentObserver
import dev.deeptelar.telar.agent.ObservedModelCall
import dev.deeptelar.telar.agent.ObservedToolCall
import dev.deeptelar.telar.agent.TokenUsage
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import io.opentelemetry.extension.kotlin.asContextElement
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration

/**
 * Sends the runs of a graph to OpenTelemetry as traces. Give it to a run with
 * [GraphConfig.observers]:
 *
 * ```kotlin
 * val tracing = OpenTelemetryObserver(openTelemetry)
 * graph.invoke(order, GraphConfig(observers = listOf(tracing)))
 * ```
 *
 * A run is a span named `telar.run`, and each node a span named `telar.node <name>` inside it. A
 * subgraph is a `telar.run` inside the span of the node that runs it. A run started inside a span,
 * such as the span of a request to a server, is a child of that span.
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
 * - **A failure** is recorded on the span of the node and of the run, with the status `ERROR`. A
 *   cancelled node or run is not an error.
 *
 * The span of a node is current while the node runs, and that of an edge while its condition runs,
 * so the spans of what they call, such as an HTTP client with OpenTelemetry instrumentation, are
 * inside them.
 *
 * **Calls to models and tools.** It is also an `AgentObserver`, so the calls that a node of
 * `toolLoop` or `toolAgent`, `ask`, `chatWithProgress` or a `ChatDecisionModel` makes are spans inside
 * the span of the node, or of the edge for the condition of a `decisionEdge`, named as the
 * [semantic conventions for generative AI](https://opentelemetry.io/docs/specs/semconv/gen-ai/) name
 * them:
 *
 * - **A call to a model** is a span `chat <model>` of kind `CLIENT`, with `gen_ai.operation.name`,
 *   `gen_ai.request.model` (the [name][dev.deeptelar.telar.agent.ChatModel.name] of the model),
 *   `gen_ai.response.model` (the model that answered, when the provider says), and the tokens its
 *   provider reported: `gen_ai.usage.input_tokens` (the tokens read from a cache included) and
 *   `gen_ai.usage.output_tokens`, and `gen_ai.usage.cache_read.input_tokens` and
 *   `gen_ai.usage.cache_write.input_tokens` when they are not 0. A backend prices the call with the
 *   model and the tokens. A model whose name is not known, such as one of your own that does not
 *   override it, gives a span `chat` without `gen_ai.request.model`. A call that failed has the
 *   status `ERROR`, an `error.type` and the exception.
 * - **A tool call** is a span `execute_tool <name>`, with `gen_ai.tool.name` and
 *   `gen_ai.tool.call.id`. A tool that returned an error to the model has the status `ERROR` and
 *   `error.type` `tool_error`, although the run goes on. A call that ended without a result, because
 *   the run was cancelled or paused, has no status: the span of the node says what happened.
 *
 * Observer functions are not `suspend`, so the span of a call is not current while it runs: the span
 * of the HTTP request of a model is inside the span of the node, next to the span `chat`.
 *
 * The state of the run is never recorded, nor what a node reports with `reportProgress`: both can
 * hold personal data. Nor are the messages, the input of a tool or its result.
 *
 * @param openTelemetry where the spans go. The default is the instance that the OpenTelemetry Java
 * agent or `GlobalOpenTelemetry.set` registered, which records nothing when there is none.
 */
public class OpenTelemetryObserver(
    openTelemetry: OpenTelemetry = GlobalOpenTelemetry.get(),
) : AgentObserver {
    private val tracer: Tracer = openTelemetry.getTracer(INSTRUMENTATION_SCOPE)

    /** The context of each running run, which holds its span. */
    private val runs = ConcurrentHashMap<ObservedRun, Context>()

    /** The context of each running node, which holds its span. */
    private val nodes = ConcurrentHashMap<ObservedNode, Context>()

    /** The context of each edge whose condition runs, which holds its span. */
    private val edges = ConcurrentHashMap<ObservedEdge, Context>()

    /** The span of each call to a model or a tool that has not ended. */
    private val calls = ConcurrentHashMap<Any, Span>()

    override fun onRunStarted(run: ObservedRun) {
        val parent = run.parent?.let { nodes[it] } ?: Context.current()
        val span =
            tracer
                .spanBuilder("telar.run")
                .setParent(parent)
                .setAttribute(THREAD_ID, run.threadId)
                .startSpan()
        runs[run] = parent.with(span)
    }

    override fun onRunCompleted(run: ObservedRun) {
        endRun(run) { it.setAttribute(OUTCOME, "completed") }
    }

    override fun onRunInterrupted(run: ObservedRun, nextNodes: List<String>) {
        endRun(run) { it.setAttribute(OUTCOME, "interrupted").setAttribute(NEXT_NODES, nextNodes) }
    }

    override fun onRunFailed(run: ObservedRun, cause: Throwable) {
        endRun(run) { it.recordFailure(cause) }
    }

    override fun onNodeStarted(node: ObservedNode) {
        val parent = runs[node.run] ?: return
        val span =
            tracer
                .spanBuilder("telar.node ${node.name}")
                .setParent(parent)
                .setAttribute(NODE, node.name)
                .setAttribute(STEP, node.step.toLong())
                .startSpan()
        nodes[node] = parent.with(span)
    }

    override fun onNodeRetry(node: ObservedNode, attempt: Int, cause: Throwable, pause: Duration) {
        val span = nodes[node]?.let(Span::fromContext) ?: return
        val attributes =
            Attributes
                .builder()
                .put(ATTEMPT, attempt.toLong())
                .put(PAUSE_MS, pause.inWholeMilliseconds)
                .put(EXCEPTION_TYPE, cause::class.java.name)
                .put(EXCEPTION_MESSAGE, cause.message.orEmpty())
                .build()
        span.addEvent("telar.retry", attributes)
    }

    override fun onNodeCompleted(node: ObservedNode) {
        endNode(node) { it.setAttribute(OUTCOME, "completed") }
    }

    override fun onNodeInterrupted(node: ObservedNode) {
        endNode(node) { it.setAttribute(OUTCOME, "interrupted") }
    }

    override fun onNodeFailed(node: ObservedNode, cause: Throwable) {
        // A NodeExecutionException only says which node failed, and the span says that already.
        endNode(node) { it.recordFailure(if (cause is NodeExecutionException) cause.cause ?: cause else cause) }
    }

    override fun onStepCompleted(run: ObservedRun, step: Int, nodes: List<String>, nextNodes: List<String>) {
        val span = runs[run]?.let(Span::fromContext) ?: return
        span.addEvent("telar.step", Attributes.of(STEP, step.toLong(), NODES, nodes, NEXT_NODES, nextNodes))
    }

    override fun onCheckpointSaved(run: ObservedRun, step: Int, nextNodes: List<String>) {
        val span = runs[run]?.let(Span::fromContext) ?: return
        span.addEvent("telar.checkpoint", Attributes.of(STEP, step.toLong(), NEXT_NODES, nextNodes))
    }

    override fun nodeContext(node: ObservedNode): CoroutineContext = nodes[node]?.asContextElement() ?: EmptyCoroutineContext

    override fun onEdgeStarted(edge: ObservedEdge) {
        val parent = runs[edge.run] ?: return
        val span =
            tracer
                .spanBuilder("telar.edge ${edge.from}")
                .setParent(parent)
                .setAttribute(EDGE_FROM, edge.from)
                .setAttribute(STEP, edge.step.toLong())
                .startSpan()
        edges[edge] = parent.with(span)
    }

    override fun onEdgeCompleted(edge: ObservedEdge, targets: List<String>) {
        endEdge(edge) { it.setAttribute(OUTCOME, "completed").setAttribute(NEXT_NODES, targets) }
    }

    override fun onEdgeFailed(edge: ObservedEdge, cause: Throwable) {
        // An EdgeConditionException only says which edge failed, and the span says that already.
        endEdge(edge) { it.recordFailure(if (cause is EdgeConditionException) cause.cause ?: cause else cause) }
    }

    override fun edgeContext(edge: ObservedEdge): CoroutineContext = edges[edge]?.asContextElement() ?: EmptyCoroutineContext

    /** The context of the node or edge that makes a call, which holds its span. */
    private fun contextOf(caller: ObservedCaller): Context? =
        when (caller) {
            is ObservedNode -> nodes[caller]
            is ObservedEdge -> edges[caller]
        }

    override fun onModelCallStarted(call: ObservedModelCall) {
        val parent = contextOf(call.caller) ?: return
        val model = call.model.name?.takeIf { it.isNotBlank() }
        calls[call] =
            tracer
                .spanBuilder(model?.let { "$CHAT $it" } ?: CHAT)
                .setParent(parent)
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute(GEN_AI_OPERATION, CHAT)
                .apply { if (model != null) setAttribute(GEN_AI_REQUEST_MODEL, model) }
                .startSpan()
    }

    override fun onModelCallCompleted(call: ObservedModelCall, usage: TokenUsage?) {
        endCall(call) { span ->
            call.responseModel?.takeIf { it.isNotBlank() }?.let { span.setAttribute(GEN_AI_RESPONSE_MODEL, it) }
            if (usage != null) {
                span.setAttribute(GEN_AI_INPUT_TOKENS, usage.inputTokens.toLong())
                span.setAttribute(GEN_AI_OUTPUT_TOKENS, usage.outputTokens.toLong())
                if (usage.cacheReadTokens > 0) span.setAttribute(GEN_AI_CACHE_READ_TOKENS, usage.cacheReadTokens.toLong())
                if (usage.cacheWriteTokens > 0) span.setAttribute(GEN_AI_CACHE_WRITE_TOKENS, usage.cacheWriteTokens.toLong())
            }
        }
    }

    override fun onModelCallFailed(call: ObservedModelCall, cause: Throwable) {
        endCall(call) { span ->
            if (cause !is CancellationException) {
                span.setAttribute(ERROR_TYPE, cause::class.java.name)
                span.recordException(cause)
                span.setStatus(StatusCode.ERROR, cause.message.orEmpty())
            }
        }
    }

    override fun onToolCallStarted(call: ObservedToolCall) {
        val parent = contextOf(call.caller) ?: return
        val builder =
            tracer
                .spanBuilder("$EXECUTE_TOOL ${call.name}")
                .setParent(parent)
                .setAttribute(GEN_AI_OPERATION, EXECUTE_TOOL)
                .setAttribute(GEN_AI_TOOL_NAME, call.name)
                .setAttribute(GEN_AI_TOOL_TYPE, "function")
        if (call.id.isNotEmpty()) builder.setAttribute(GEN_AI_TOOL_CALL_ID, call.id)
        calls[call] = builder.startSpan()
    }

    override fun onToolCallCompleted(call: ObservedToolCall, isError: Boolean) {
        endCall(call) { span ->
            if (isError) {
                span.setAttribute(ERROR_TYPE, TOOL_ERROR)
                span.setStatus(StatusCode.ERROR, "The tool returned an error to the model.")
            }
        }
    }

    override fun onToolCallFailed(call: ObservedToolCall, cause: Throwable) {
        // Cancelled, paused with `interrupt`, or an error that ends the run: the span of the node says which.
        endCall(call) {}
    }

    private inline fun endCall(call: Any, finish: (Span) -> Unit) {
        val span = calls.remove(call) ?: return
        finish(span)
        span.end()
    }

    private inline fun endRun(run: ObservedRun, finish: (Span) -> Unit) {
        val span = runs.remove(run)?.let(Span::fromContext) ?: return
        finish(span)
        span.end()
    }

    private inline fun endEdge(edge: ObservedEdge, finish: (Span) -> Unit) {
        val span = edges.remove(edge)?.let(Span::fromContext) ?: return
        finish(span)
        span.end()
    }

    private inline fun endNode(node: ObservedNode, finish: (Span) -> Unit) {
        val span = nodes.remove(node)?.let(Span::fromContext) ?: return
        finish(span)
        span.end()
    }

    private fun Span.recordFailure(cause: Throwable) {
        if (cause is CancellationException) {
            setAttribute(OUTCOME, "cancelled")
        } else {
            setAttribute(OUTCOME, "failed")
            recordException(cause)
            setStatus(StatusCode.ERROR, cause.message.orEmpty())
        }
    }
}

private const val INSTRUMENTATION_SCOPE = "dev.deeptelar.telar"

private val THREAD_ID: AttributeKey<String> = AttributeKey.stringKey("telar.thread_id")
private val NODE: AttributeKey<String> = AttributeKey.stringKey("telar.node")
private val EDGE_FROM: AttributeKey<String> = AttributeKey.stringKey("telar.edge.from")
private val NODES: AttributeKey<List<String>> = AttributeKey.stringArrayKey("telar.nodes")
private val NEXT_NODES: AttributeKey<List<String>> = AttributeKey.stringArrayKey("telar.next_nodes")
private val STEP: AttributeKey<Long> = AttributeKey.longKey("telar.step")
private val OUTCOME: AttributeKey<String> = AttributeKey.stringKey("telar.outcome")
private val ATTEMPT: AttributeKey<Long> = AttributeKey.longKey("telar.attempt")
private val PAUSE_MS: AttributeKey<Long> = AttributeKey.longKey("telar.pause_ms")
private val EXCEPTION_TYPE: AttributeKey<String> = AttributeKey.stringKey("exception.type")
private val EXCEPTION_MESSAGE: AttributeKey<String> = AttributeKey.stringKey("exception.message")
private val ERROR_TYPE: AttributeKey<String> = AttributeKey.stringKey("error.type")
private val GEN_AI_OPERATION: AttributeKey<String> = AttributeKey.stringKey("gen_ai.operation.name")
private val GEN_AI_REQUEST_MODEL: AttributeKey<String> = AttributeKey.stringKey("gen_ai.request.model")
private val GEN_AI_RESPONSE_MODEL: AttributeKey<String> = AttributeKey.stringKey("gen_ai.response.model")
private val GEN_AI_INPUT_TOKENS: AttributeKey<Long> = AttributeKey.longKey("gen_ai.usage.input_tokens")
private val GEN_AI_OUTPUT_TOKENS: AttributeKey<Long> = AttributeKey.longKey("gen_ai.usage.output_tokens")
private val GEN_AI_CACHE_READ_TOKENS: AttributeKey<Long> = AttributeKey.longKey("gen_ai.usage.cache_read.input_tokens")
private val GEN_AI_CACHE_WRITE_TOKENS: AttributeKey<Long> = AttributeKey.longKey("gen_ai.usage.cache_write.input_tokens")
private val GEN_AI_TOOL_NAME: AttributeKey<String> = AttributeKey.stringKey("gen_ai.tool.name")
private val GEN_AI_TOOL_TYPE: AttributeKey<String> = AttributeKey.stringKey("gen_ai.tool.type")
private val GEN_AI_TOOL_CALL_ID: AttributeKey<String> = AttributeKey.stringKey("gen_ai.tool.call.id")
private const val CHAT = "chat"
private const val EXECUTE_TOOL = "execute_tool"
private const val TOOL_ERROR = "tool_error"
