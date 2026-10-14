package dev.deeptelar.telar.otel

import dev.deeptelar.telar.GraphConfig
import dev.deeptelar.telar.GraphObserver
import dev.deeptelar.telar.NodeExecutionException
import dev.deeptelar.telar.ObservedNode
import dev.deeptelar.telar.ObservedRun
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
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
 * - **A failure** is recorded on the span of the node and of the run, with the status `ERROR`. A
 *   cancelled node or run is not an error.
 *
 * The span of a node is current while the node runs, so the spans of what it calls, such as an HTTP
 * client with OpenTelemetry instrumentation, are inside it.
 *
 * The state of the run is never recorded, nor what a node reports with `reportProgress`: both can
 * hold personal data.
 *
 * @param openTelemetry where the spans go. The default is the instance that the OpenTelemetry Java
 * agent or `GlobalOpenTelemetry.set` registered, which records nothing when there is none.
 */
public class OpenTelemetryObserver(
    openTelemetry: OpenTelemetry = GlobalOpenTelemetry.get(),
) : GraphObserver {
    private val tracer: Tracer = openTelemetry.getTracer(INSTRUMENTATION_SCOPE)

    /** The context of each running run, which holds its span. */
    private val runs = ConcurrentHashMap<ObservedRun, Context>()

    /** The context of each running node, which holds its span. */
    private val nodes = ConcurrentHashMap<ObservedNode, Context>()

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

    private inline fun endRun(run: ObservedRun, finish: (Span) -> Unit) {
        val span = runs.remove(run)?.let(Span::fromContext) ?: return
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
private val NODES: AttributeKey<List<String>> = AttributeKey.stringArrayKey("telar.nodes")
private val NEXT_NODES: AttributeKey<List<String>> = AttributeKey.stringArrayKey("telar.next_nodes")
private val STEP: AttributeKey<Long> = AttributeKey.longKey("telar.step")
private val OUTCOME: AttributeKey<String> = AttributeKey.stringKey("telar.outcome")
private val ATTEMPT: AttributeKey<Long> = AttributeKey.longKey("telar.attempt")
private val PAUSE_MS: AttributeKey<Long> = AttributeKey.longKey("telar.pause_ms")
private val EXCEPTION_TYPE: AttributeKey<String> = AttributeKey.stringKey("exception.type")
private val EXCEPTION_MESSAGE: AttributeKey<String> = AttributeKey.stringKey("exception.message")
