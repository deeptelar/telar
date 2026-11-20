package dev.deeptelar.telar.otel

import dev.deeptelar.telar.END
import dev.deeptelar.telar.EdgeConditionException
import dev.deeptelar.telar.GraphConfig
import dev.deeptelar.telar.GraphValidationException
import dev.deeptelar.telar.MemoryCheckpointer
import dev.deeptelar.telar.NodeExecutionException
import dev.deeptelar.telar.Retry
import dev.deeptelar.telar.START
import dev.deeptelar.telar.StateGraph
import dev.deeptelar.telar.agent.AgentState
import dev.deeptelar.telar.agent.Attachment
import dev.deeptelar.telar.agent.ChatDecisionModel
import dev.deeptelar.telar.agent.ChatMessage
import dev.deeptelar.telar.agent.ChatModel
import dev.deeptelar.telar.agent.ChatModelException
import dev.deeptelar.telar.agent.ChatRequest
import dev.deeptelar.telar.agent.ChatResponse
import dev.deeptelar.telar.agent.TokenUsage
import dev.deeptelar.telar.agent.Tool
import dev.deeptelar.telar.agent.ToolCall
import dev.deeptelar.telar.agent.decisionEdge
import dev.deeptelar.telar.agent.toolAgent
import dev.deeptelar.telar.interrupt
import dev.deeptelar.telar.test.ScriptedChatModel
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    private fun SpanData.long(key: String): Long? = attributes.get(AttributeKey.longKey(key))

    private val agentConfig = GraphConfig<AgentState>(observers = listOf(tracing))

    @AfterTest
    fun close() = tracerProvider.close()

    @Test
    fun `each call to a model and each tool call is a span inside its node`() =
        runTest {
            val orderStatus = Tool<Unit>("order_status", "Returns where the order is.") { "The driver is 5 minutes away." }
            val model =
                ScriptedChatModel {
                    calls(call("order_status"), usage = TokenUsage(inputTokens = 812, outputTokens = 64, cacheReadTokens = 700))
                    says("5 minutes.", usage = TokenUsage(inputTokens = 901, outputTokens = 38))
                }

            toolAgent(model, listOf(orderStatus)).invoke(AgentState("Where is my pizza?"), agentConfig)

            val nodes = spans.filter { it.name.startsWith("telar.node") }.associateBy { it.long("telar.step") }
            val chats = spans.filter { it.name == "chat" }.sortedBy { it.startEpochNanos }
            assertEquals(listOf(nodes[1]!!.spanId, nodes[3]!!.spanId), chats.map { it.parentSpanId })
            assertEquals(listOf(SpanKind.CLIENT, SpanKind.CLIENT), chats.map { it.kind })
            assertEquals(listOf("chat", "chat"), chats.map { it.string("gen_ai.operation.name") })
            assertEquals(listOf(812L, 901L), chats.map { it.long("gen_ai.usage.input_tokens") })
            assertEquals(listOf(64L, 38L), chats.map { it.long("gen_ai.usage.output_tokens") })
            assertEquals(listOf(700L, null), chats.map { it.long("gen_ai.usage.cache_read.input_tokens") })
            assertEquals(listOf(null, null), chats.map { it.long("gen_ai.usage.cache_write.input_tokens") })

            val tool = span("execute_tool order_status")
            assertEquals(nodes[2]!!.spanId, tool.parentSpanId)
            assertEquals(SpanKind.INTERNAL, tool.kind)
            assertEquals("order_status", tool.string("gen_ai.tool.name"))
            assertEquals("call-1", tool.string("gen_ai.tool.call.id"))
            assertEquals(StatusCode.UNSET, tool.status.statusCode)
        }

    @Test
    fun `a call to a model that knows its name is named after it`() =
        runTest {
            val model =
                object : ChatModel {
                    override val name: String = "gpt-4o-mini"

                    override suspend fun chat(request: ChatRequest): ChatResponse =
                        ChatResponse(ChatMessage.Assistant("5 minutes."), TokenUsage(20, 3), model = "gpt-4o-mini-2024-07-18")
                }

            toolAgent(model).invoke(AgentState("Where is my pizza?"), agentConfig)

            val chat = span("chat gpt-4o-mini")
            assertEquals("chat", chat.string("gen_ai.operation.name"))
            assertEquals("gpt-4o-mini", chat.string("gen_ai.request.model"))
            assertEquals("gpt-4o-mini-2024-07-18", chat.string("gen_ai.response.model"))
        }

    @Test
    fun `an edge with a condition is a span of the run with the call of its model inside`() =
        runTest {
            val chat = ScriptedChatModel { says("""{"decision": {"refund": 0.1, "technical": 0.9}}""", TokenUsage(30, 4)) }
            var current: String? = null
            val graph =
                StateGraph<Ticket> {
                    val refund = node("refund") { it }
                    val technical = node("technical") { it }
                    decisionEdge(START, ChatDecisionModel(chat), "Which team?", routes = mapOf(refund to null, technical to null)) {
                        current = Span.current().spanContext.spanId
                        "It crashes"
                    }
                }.compile()

            graph.invoke(Ticket(), config())

            val edge = span("telar.edge __START__")
            assertEquals(span("telar.run").spanId, edge.parentSpanId)
            assertEquals(0L, edge.long("telar.step"))
            assertEquals(listOf("technical"), edge.strings("telar.next_nodes"))
            assertEquals(edge.spanId, current)
            assertEquals(edge.spanId, span("chat").parentSpanId)
        }

    @Test
    fun `a condition that fails is an error of its edge`() =
        runTest {
            val graph =
                StateGraph<Ticket> {
                    val read = node("read") { it }
                    START then read
                    conditionalEdge(read, setOf(END)) { error("No route for this ticket.") }
                }.compile()

            assertFailsWith<EdgeConditionException> { graph.invoke(Ticket(), config()) }

            val edge = span("telar.edge read")
            assertEquals("failed", edge.string("telar.outcome"))
            assertEquals(StatusCode.ERROR, edge.status.statusCode)
            assertEquals("No route for this ticket.", edge.status.description)
        }

    @Test
    fun `a call to a model that fails is an error`() =
        runTest {
            val model = ScriptedChatModel { fails("API error 529: Overloaded") }

            assertFailsWith<NodeExecutionException> { toolAgent(model).invoke(AgentState("Where is my pizza?"), agentConfig) }

            val chat = span("chat")
            assertEquals(StatusCode.ERROR, chat.status.statusCode)
            assertEquals(ChatModelException::class.java.name, chat.string("error.type"))
            assertEquals(listOf("exception"), chat.events.map { it.name })
        }

    @Test
    fun `a tool that returns an error is an error of its span and the run goes on`() =
        runTest {
            val broken = Tool<Unit>("broken", "Always fails.") { error("The service is down.") }
            val model =
                ScriptedChatModel {
                    calls(call("broken"))
                    says("The service is down, sorry.")
                }

            toolAgent(model, listOf(broken)).invoke(AgentState("Where is my pizza?"), agentConfig)

            val tool = span("execute_tool broken")
            assertEquals(StatusCode.ERROR, tool.status.statusCode)
            assertEquals("tool_error", tool.string("error.type"))
            assertEquals("completed", span("telar.run").string("telar.outcome"))
        }

    @Test
    fun `a cancelled run ends the spans of its calls without an error`() =
        runTest {
            val waits = Tool<Unit>("wait", "Never returns.") { awaitCancellation() }
            val model = ScriptedChatModel { calls(call("wait")) }

            val job = launch { toolAgent(model, listOf(waits)).invoke(AgentState("Where is my pizza?"), agentConfig) }
            testScheduler.runCurrent()
            job.cancel()
            job.join()

            assertEquals(StatusCode.UNSET, span("execute_tool wait").status.statusCode)
            assertEquals("cancelled", span("telar.run").string("telar.outcome"))
        }

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

    private val recording = OpenTelemetryObserver(openTelemetry, recordContent = true)

    private val orderStatus = Tool<Unit>("order_status", "Returns where the order is.") { "The driver is 5 minutes away." }

    @Test
    fun `the conversation is not recorded unless it is asked for`() =
        runTest {
            val model =
                ScriptedChatModel {
                    calls(call("order_status"))
                    says("5 minutes.")
                }

            toolAgent(model, listOf(orderStatus), system = "Be brief.").invoke(AgentState("Where is my pizza?"), agentConfig)

            val content =
                listOf(
                    "gen_ai.system_instructions",
                    "gen_ai.input.messages",
                    "gen_ai.output.messages",
                    "gen_ai.tool.call.arguments",
                    "gen_ai.tool.call.result",
                )
            assertEquals(emptyList(), spans.flatMap { span -> content.filter { span.string(it) != null } })
        }

    @Test
    fun `with recordContent the spans of the calls have the conversation`() =
        runTest {
            val model =
                ScriptedChatModel {
                    calls(ToolCall("call-1", "order_status", JsonObject(emptyMap()), invalidInput = "{order: 42"))
                    says("Sorry.")
                }

            toolAgent(model, listOf(orderStatus), system = "Be brief.")
                .invoke(AgentState("Where is my pizza?"), GraphConfig(observers = listOf(recording)))

            val (first, second) = spans.filter { it.name == "chat" }.sortedBy { it.startEpochNanos }
            assertEquals("""[{"type":"text","content":"Be brief."}]""", first.string("gen_ai.system_instructions"))
            assertEquals(
                """[{"role":"user","parts":[{"type":"text","content":"Where is my pizza?"}]}]""",
                first.string("gen_ai.input.messages"),
            )
            assertEquals(
                """[{"role":"assistant","parts":[{"type":"tool_call","id":"call-1","name":"order_status","arguments":"{order: 42"}],""" +
                    """"finish_reason":"tool_call"}]""",
                first.string("gen_ai.output.messages"),
            )
            assertEquals(3, Json.parseToJsonElement(second.string("gen_ai.input.messages")!!).jsonArray.size)
            val tool = span("execute_tool order_status")
            assertEquals("{order: 42", tool.string("gen_ai.tool.call.arguments"))
            assertEquals("The input is not a JSON object: {order: 42", tool.string("gen_ai.tool.call.result"))
        }

    @Test
    fun `an attachment is recorded without its bytes and a long text is cut`() =
        runTest {
            val photo = "iVBORw0K".repeat(200_000)
            val question = ChatMessage.User("Is this pizza burnt? ".repeat(10), listOf(Attachment.Data(photo, "image/jpeg")))
            val cutting = OpenTelemetryObserver(openTelemetry, recordContent = true, maxContentLength = 20)

            toolAgent(
                ScriptedChatModel { says("A little.") },
            ).invoke(AgentState(listOf(question)), GraphConfig(observers = listOf(cutting)))

            assertEquals(
                """[{"role":"user","parts":[{"type":"text","content":"Is this pizza burnt?… [cut, 210 characters in all]"},""" +
                    """{"type":"text","content":"[image/jpeg, 1.1 MB]"}]}]""",
                span("chat").string("gen_ai.input.messages"),
            )
        }

    @Test
    fun `a call to a model that fails keeps what it sent`() =
        runTest {
            val model = ScriptedChatModel { fails("API error 529: Overloaded") }

            assertFailsWith<NodeExecutionException> {
                toolAgent(model).invoke(AgentState("Where is my pizza?"), GraphConfig(observers = listOf(recording)))
            }

            assertTrue(span("chat").string("gen_ai.input.messages")!!.contains("Where is my pizza?"))
            assertNull(span("chat").string("gen_ai.output.messages"))
            assertFailsWith<GraphValidationException> { OpenTelemetryObserver(openTelemetry, maxContentLength = 0) }
        }
}
