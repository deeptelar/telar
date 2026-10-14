package dev.deeptelar.telar.otel

import dev.deeptelar.telar.END
import dev.deeptelar.telar.GraphConfig
import dev.deeptelar.telar.MemoryCheckpointer
import dev.deeptelar.telar.NodeExecutionException
import dev.deeptelar.telar.Retry
import dev.deeptelar.telar.START
import dev.deeptelar.telar.StateGraph
import dev.deeptelar.telar.interrupt
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.extension.kotlin.asContextElement
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private data class Ticket(
    val text: String = "",
    val answer: String? = null,
)

class OpenTelemetryObserverTest {
    private val exporter = InMemorySpanExporter.create()
    private val tracerProvider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()
    private val openTelemetry = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build()
    private val tracing = OpenTelemetryObserver(openTelemetry)

    private fun config(checkpointer: MemoryCheckpointer<Ticket>? = null) =
        GraphConfig(threadId = "ticket-7", checkpointer = checkpointer, observers = listOf(tracing))

    private val spans: List<SpanData> get() = exporter.finishedSpanItems

    private fun span(name: String): SpanData = spans.single { it.name == name }

    private fun SpanData.string(key: String): String? = attributes.get(AttributeKey.stringKey(key))

    private fun SpanData.strings(key: String): List<String>? = attributes.get(AttributeKey.stringArrayKey(key))

    @AfterTest
    fun close() = tracerProvider.close()

    @Test
    fun `a run is a span with a span for each node inside it`() =
        runTest {
            val graph =
                StateGraph<Ticket> {
                    START then node("read") { it.copy(text = "late order") } then node("reply") { it.copy(answer = "sorry") } then END
                }.compile()

            graph.invoke(Ticket(), config(MemoryCheckpointer()))

            val run = span("telar.run")
            assertEquals("ticket-7", run.string("telar.thread_id"))
            assertEquals("completed", run.string("telar.outcome"))
            assertEquals(
                listOf("telar.checkpoint", "telar.step", "telar.checkpoint", "telar.step", "telar.checkpoint"),
                run.events.map { it.name },
            )
            val firstStep = run.events.first { it.name == "telar.step" }.attributes
            assertEquals(listOf("read"), firstStep.get(AttributeKey.stringArrayKey("telar.nodes")))
            assertEquals(listOf("reply"), firstStep.get(AttributeKey.stringArrayKey("telar.next_nodes")))

            val read = span("telar.node read")
            val reply = span("telar.node reply")
            assertEquals(listOf(run.spanId, run.spanId), listOf(read.parentSpanId, reply.parentSpanId))
            assertEquals(run.traceId, read.traceId)
            assertEquals(2L, reply.attributes.get(AttributeKey.longKey("telar.step")))
            assertEquals("reply", reply.string("telar.node"))
            assertEquals("completed", reply.string("telar.outcome"))
        }

    @Test
    fun `a failure is an error of the node and of the run`() =
        runTest {
            val graph =
                StateGraph<Ticket> {
                    START then node("call", retry = Retry(attempts = 2)) { throw IllegalStateException("The service is down.") } then END
                }.compile()

            assertFailsWith<NodeExecutionException> { graph.invoke(Ticket(), config()) }

            val node = span("telar.node call")
            assertEquals(StatusCode.ERROR, node.status.statusCode)
            assertEquals("The service is down.", node.status.description)
            assertEquals("failed", node.string("telar.outcome"))
            val retry = node.events.single { it.name == "telar.retry" }.attributes
            assertEquals(1L, retry.get(AttributeKey.longKey("telar.attempt")))
            assertEquals(1000L, retry.get(AttributeKey.longKey("telar.pause_ms")))
            val exception = node.events.single { it.name == "exception" }.attributes
            assertEquals("java.lang.IllegalStateException", exception.get(AttributeKey.stringKey("exception.type")))

            val run = span("telar.run")
            assertEquals(StatusCode.ERROR, run.status.statusCode)
            assertEquals("failed", run.string("telar.outcome"))
        }

    @Test
    fun `a paused run says which nodes come next`() =
        runTest {
            val graph =
                StateGraph<Ticket> {
                    START then node("ask") { if (it.answer == null) interrupt(it) else it } then END
                }.compile()

            graph.invoke(Ticket(), config(MemoryCheckpointer()))

            assertEquals("interrupted", span("telar.node ask").string("telar.outcome"))
            val run = span("telar.run")
            assertEquals("interrupted", run.string("telar.outcome"))
            assertEquals(listOf("ask"), run.strings("telar.next_nodes"))
            assertEquals(StatusCode.UNSET, run.status.statusCode)
        }

    @Test
    fun `a cancelled run is no error`() =
        runTest {
            val graph =
                StateGraph<Ticket> {
                    START then node("wait") { awaitCancellation() } then END
                }.compile()

            val job = launch { graph.invoke(Ticket(), config()) }
            testScheduler.runCurrent()
            job.cancel()
            job.join()

            assertEquals(listOf("cancelled", "cancelled"), spans.map { it.string("telar.outcome") })
            assertEquals(listOf(StatusCode.UNSET, StatusCode.UNSET), spans.map { it.status.statusCode })
        }

    @Test
    fun `the run of a subgraph is inside the span of its node`() =
        runTest {
            val inner =
                StateGraph<Ticket> {
                    START then node("draft") { it.copy(answer = "draft") } then END
                }.compile()
            val graph =
                StateGraph<Ticket> {
                    START then subgraph("write", inner) then END
                }.compile()

            graph.invoke(Ticket(), config())

            val (innerRun, outerRun) = spans.filter { it.name == "telar.run" }
            val write = span("telar.node write")
            assertEquals(outerRun.spanId, write.parentSpanId)
            assertEquals(write.spanId, innerRun.parentSpanId)
            assertEquals(innerRun.spanId, span("telar.node draft").parentSpanId)
        }

    @Test
    fun `the span of a node is current while it runs`() =
        runTest {
            val tracer = openTelemetry.getTracer("app")
            val graph =
                StateGraph<Ticket> {
                    START then
                        node("call") {
                            // What the node calls opens a span of its own, as an instrumented HTTP client does.
                            tracer.spanBuilder("http POST").startSpan().end()
                            it
                        } then END
                }.compile()

            graph.invoke(Ticket(), config())

            assertEquals(span("telar.node call").spanId, span("http POST").parentSpanId)
        }

    @Test
    fun `a run started inside a span is a child of it`() =
        runTest {
            val graph =
                StateGraph<Ticket> {
                    START then node("read") { it } then END
                }.compile()
            val request = openTelemetry.getTracer("app").spanBuilder("GET /tickets").startSpan()

            withContext(request.asContextElement()) { graph.invoke(Ticket(), config()) }
            request.end()

            assertEquals(request.spanContext.spanId, span("telar.run").parentSpanId)
        }

    @Test
    fun `a node outside a traced run is not current anywhere`() =
        runTest {
            val graph =
                StateGraph<Ticket> {
                    START then node("read") { it.also { assertEquals(false, Span.current().spanContext.isValid) } } then END
                }.compile()

            graph.invoke(Ticket(), GraphConfig())

            assertEquals(emptyList(), spans)
        }

    @Test
    fun `without an OpenTelemetry of the app nothing is recorded`() =
        runTest {
            val graph =
                StateGraph<Ticket> {
                    START then node("read") { it } then END
                }.compile()

            graph.invoke(Ticket(), GraphConfig(observers = listOf(OpenTelemetryObserver())))

            assertEquals(emptyList(), spans)
        }
}
