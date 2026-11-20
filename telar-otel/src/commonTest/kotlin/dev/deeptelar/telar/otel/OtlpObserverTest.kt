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
import dev.deeptelar.telar.agent.ChatRequest
import dev.deeptelar.telar.agent.ChatResponse
import dev.deeptelar.telar.agent.TokenUsage
import dev.deeptelar.telar.agent.Tool
import dev.deeptelar.telar.agent.ToolCall
import dev.deeptelar.telar.agent.decisionEdge
import dev.deeptelar.telar.agent.toolAgent
import dev.deeptelar.telar.interrupt
import dev.deeptelar.telar.test.ScriptedChatModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

internal data class Ticket(
    val text: String = "",
    val answer: String? = null,
)

/** A span as the collector got it. */
private class Sent(
    val json: JsonObject,
) {
    val name: String get() = json.text("name")!!
    val traceId: String get() = json.text("traceId")!!
    val spanId: String get() = json.text("spanId")!!
    val parentSpanId: String? get() = json.text("parentSpanId")
    val statusCode: Int? get() =
        json["status"]
            ?.jsonObject
            ?.get("code")
            ?.jsonPrimitive
            ?.content
            ?.toInt()
    val statusMessage: String? get() = json["status"]?.jsonObject?.text("message")
    val attributes: Map<String, JsonObject> get() = json["attributes"]!!.attributes()
    val events: List<Pair<String, Map<String, JsonObject>>>
        get() = json["events"]!!.jsonArray.map { it.jsonObject.text("name")!! to it.jsonObject["attributes"]!!.attributes() }

    fun string(key: String): String? = attributes[key]?.text("stringValue")

    fun strings(key: String): List<String>? = attributes[key]?.strings()

    fun long(key: String): Long? = attributes[key]?.text("intValue")?.toLong()

    fun event(name: String): Map<String, JsonObject> = events.single { it.first == name }.second
}

private fun JsonObject.text(key: String): String? = get(key)?.jsonPrimitive?.content

private fun kotlinx.serialization.json.JsonElement.attributes(): Map<String, JsonObject> =
    jsonArray.associate { it.jsonObject.text("key")!! to it.jsonObject["value"]!!.jsonObject }

private fun JsonObject.strings(): List<String> =
    get("arrayValue")!!.jsonObject["values"]!!.jsonArray.map {
        it.jsonObject.text("stringValue")!!
    }

class OtlpObserverTest {
    private val requests = Channel<HttpRequestData>(Channel.UNLIMITED)
    private var status = HttpStatusCode.OK

    private val client =
        HttpClient(
            MockEngine { request ->
                requests.send(request)
                respond("{}", status)
            },
        )

    private val failures = mutableListOf<OtlpExportException>()

    private fun TestScope.tracing(scope: CoroutineScope = backgroundScope, recordContent: Boolean = false, maxContentLength: Int = 8192) =
        OtlpObserver(
            client,
            scope,
            endpoint = "https://collector.test/v1/traces",
            recordContent = recordContent,
            maxContentLength = maxContentLength,
            onExportFailed = { failures += it },
        )

    private fun config(tracing: OtlpObserver, checkpointer: MemoryCheckpointer<Ticket>? = null) =
        GraphConfig(threadId = "ticket-7", checkpointer = checkpointer, observers = listOf(tracing))

    /** The bodies of the requests the collector got so far. */
    private fun bodies(): List<JsonObject> =
        generateSequence {
            requests.tryReceive().getOrNull()
        }.map { Json.parseToJsonElement((it.body as TextContent).text).jsonObject }.toList()

    private fun JsonObject.spans(): List<Sent> =
        get("resourceSpans")!!.jsonArray.flatMap { resource ->
            resource.jsonObject["scopeSpans"]!!.jsonArray.flatMap { scope ->
                scope.jsonObject["spans"]!!.jsonArray.map { Sent(it.jsonObject) }
            }
        }

    /** Sends what ended, and returns every span the collector got. */
    private suspend fun OtlpObserver.sent(): List<Sent> {
        flush()
        return bodies().flatMap { it.spans() }
    }

    private fun List<Sent>.span(name: String): Sent = single { it.name == name }

    private val orderStatus = Tool<Unit>("order_status", "Returns where the order is.") { "The driver is 5 minutes away." }

    @Test
    fun `each call to a model and each tool call is a span inside its node`() =
        runTest {
            val tracing = tracing()
            val model =
                ScriptedChatModel {
                    calls(call("order_status"), usage = TokenUsage(inputTokens = 812, outputTokens = 64, cacheReadTokens = 700))
                    says("5 minutes.", usage = TokenUsage(inputTokens = 901, outputTokens = 38, cacheWriteTokens = 120))
                }

            toolAgent(model, listOf(orderStatus)).invoke(AgentState("Where is my pizza?"), GraphConfig(observers = listOf(tracing)))
            val spans = tracing.sent()

            val nodes = spans.filter { it.name.startsWith("telar.node") }.associateBy { it.long("telar.step") }
            val chats = spans.filter { it.name == "chat" }
            assertEquals(listOf(nodes[1]!!.spanId, nodes[3]!!.spanId), chats.map { it.parentSpanId })
            assertEquals(listOf("chat", "chat"), chats.map { it.string("gen_ai.operation.name") })
            assertEquals(listOf(3, 3), chats.map { it.json.text("kind")!!.toInt() })
            assertEquals(listOf(812L, 901L), chats.map { it.long("gen_ai.usage.input_tokens") })
            assertEquals(listOf(64L, 38L), chats.map { it.long("gen_ai.usage.output_tokens") })
            assertEquals(listOf(700L, null), chats.map { it.long("gen_ai.usage.cache_read.input_tokens") })
            assertEquals(listOf(null, 120L), chats.map { it.long("gen_ai.usage.cache_write.input_tokens") })
            assertEquals(listOf(null, null), chats.map { it.statusCode })

            val tool = spans.span("execute_tool order_status")
            assertEquals(nodes[2]!!.spanId, tool.parentSpanId)
            assertEquals("execute_tool", tool.string("gen_ai.operation.name"))
            assertEquals("order_status", tool.string("gen_ai.tool.name"))
            assertEquals("call-1", tool.string("gen_ai.tool.call.id"))
            assertEquals("function", tool.string("gen_ai.tool.type"))
            assertEquals(1, tool.json.text("kind")!!.toInt())
            assertNull(tool.statusCode)
            assertEquals(setOf(spans.span("telar.run").traceId), spans.map { it.traceId }.toSet())
        }

    @Test
    fun `a call to a model that knows its name is named after it`() =
        runTest {
            val tracing = tracing()
            val model =
                object : ChatModel {
                    override val name: String = "gpt-4o-mini"

                    override suspend fun chat(request: ChatRequest): ChatResponse =
                        ChatResponse(ChatMessage.Assistant("5 minutes."), TokenUsage(20, 3), model = "gpt-4o-mini-2024-07-18")
                }

            toolAgent(model).invoke(AgentState("Where is my pizza?"), GraphConfig(observers = listOf(tracing)))
            val chat = tracing.sent().span("chat gpt-4o-mini")

            assertEquals("chat", chat.string("gen_ai.operation.name"))
            assertEquals("gpt-4o-mini", chat.string("gen_ai.request.model"))
            assertEquals("gpt-4o-mini-2024-07-18", chat.string("gen_ai.response.model"))
            assertEquals(20L, chat.long("gen_ai.usage.input_tokens"))
        }

    @Test
    fun `an edge with a condition is a span of the run with the call of its model inside`() =
        runTest {
            val tracing = tracing()
            val chat = ScriptedChatModel { says("""{"decision": {"refund": 0.1, "technical": 0.9}}""", TokenUsage(30, 4)) }
            val graph =
                StateGraph<Ticket> {
                    val read = node("read") { it.copy(text = "It crashes") }
                    val refund = node("refund") { it }
                    val technical = node("technical") { it }
                    START then read
                    decisionEdge(
                        read,
                        ChatDecisionModel(chat),
                        "Which team?",
                        routes = mapOf(refund to null, technical to null),
                    ) { it.text }
                }.compile()

            graph.invoke(Ticket(), config(tracing))
            val spans = tracing.sent()

            val edge = spans.span("telar.edge read")
            assertEquals(spans.span("telar.run").spanId, edge.parentSpanId)
            assertEquals("read", edge.string("telar.edge.from"))
            assertEquals(1L, edge.long("telar.step"))
            assertEquals(listOf("technical"), edge.strings("telar.next_nodes"))
            assertEquals("completed", edge.string("telar.outcome"))
            val call = spans.span("chat")
            assertEquals(edge.spanId, call.parentSpanId)
            assertEquals(30L, call.long("gen_ai.usage.input_tokens"))
        }

    @Test
    fun `a condition that fails is an error of its edge`() =
        runTest {
            val tracing = tracing()
            val graph =
                StateGraph<Ticket> {
                    val read = node("read") { it }
                    START then read
                    conditionalEdge(read, setOf(END)) { error("No route for this ticket.") }
                }.compile()

            assertFailsWith<EdgeConditionException> { graph.invoke(Ticket(), config(tracing)) }
            val edge = tracing.sent().span("telar.edge read")

            assertEquals("failed", edge.string("telar.outcome"))
            assertEquals(2, edge.statusCode)
            assertEquals("No route for this ticket.", edge.statusMessage)
        }

    @Test
    fun `a condition runs with the ids of its span`() =
        runTest {
            val tracing = tracing()
            var seen: TraceContext? = null
            val graph =
                StateGraph<Ticket> {
                    val read = node("read") { it }
                    START then read
                    conditionalEdge(read, setOf(END)) {
                        seen = currentCoroutineContext()[TraceContext]
                        END
                    }
                }.compile()

            graph.invoke(Ticket(), config(tracing))
            val edge = tracing.sent().span("telar.edge read")

            assertEquals(edge.traceId, seen?.traceId)
            assertEquals(edge.spanId, seen?.spanId)
        }

    @Test
    fun `a call to a model that fails is an error`() =
        runTest {
            val tracing = tracing()
            val model = ScriptedChatModel { fails("API error 529: Overloaded") }

            assertFailsWith<NodeExecutionException> {
                toolAgent(model).invoke(AgentState("Where is my pizza?"), GraphConfig(observers = listOf(tracing)))
            }
            val chat = tracing.sent().span("chat")

            assertEquals(2, chat.statusCode)
            assertEquals("API error 529: Overloaded", chat.statusMessage)
            assertTrue(chat.string("error.type")!!.endsWith("ChatModelException"))
            assertEquals("API error 529: Overloaded", chat.event("exception")["exception.message"]!!.text("stringValue"))
            assertNull(chat.long("gen_ai.usage.input_tokens"))
        }

    @Test
    fun `a tool that returns an error is an error of its span and the run goes on`() =
        runTest {
            val tracing = tracing()
            val broken = Tool<Unit>("broken", "Always fails.") { error("The service is down.") }
            val model =
                ScriptedChatModel {
                    calls(call("broken"))
                    says("The service is down, sorry.")
                }

            toolAgent(model, listOf(broken)).invoke(AgentState("Where is my pizza?"), GraphConfig(observers = listOf(tracing)))
            val spans = tracing.sent()

            val tool = spans.span("execute_tool broken")
            assertEquals(2, tool.statusCode)
            assertEquals("tool_error", tool.string("error.type"))
            assertEquals("completed", spans.span("telar.run").string("telar.outcome"))
        }

    @Test
    fun `a cancelled run ends the spans of its calls without an error`() =
        runTest {
            val tracing = tracing()
            val waits = Tool<Unit>("wait", "Never returns.") { awaitCancellation() }
            val model = ScriptedChatModel { calls(call("wait")) }

            val job =
                launch {
                    toolAgent(
                        model,
                        listOf(waits),
                    ).invoke(AgentState("Where is my pizza?"), GraphConfig(observers = listOf(tracing)))
                }
            testScheduler.runCurrent()
            job.cancel()
            job.join()
            val spans = tracing.sent()

            val tool = spans.span("execute_tool wait")
            assertNull(tool.statusCode)
            assertEquals(listOf(null), spans.filter { it.name == "chat" }.map { it.statusCode })
            assertEquals("cancelled", spans.span("telar.run").string("telar.outcome"))
        }

    @Test
    fun `a run is a span with a span for each node inside it`() =
        runTest {
            val tracing = tracing()
            val graph =
                StateGraph<Ticket> {
                    START then node("read") { it.copy(text = "late order") } then node("reply") { it.copy(answer = "sorry") } then END
                }.compile()

            graph.invoke(Ticket(), config(tracing, MemoryCheckpointer()))
            val spans = tracing.sent()

            val run = spans.span("telar.run")
            assertEquals("ticket-7", run.string("telar.thread_id"))
            assertEquals("completed", run.string("telar.outcome"))
            assertNull(run.parentSpanId)
            assertEquals(
                listOf("telar.checkpoint", "telar.step", "telar.checkpoint", "telar.step", "telar.checkpoint"),
                run.events.map { it.first },
            )
            val firstStep = run.events.first { it.first == "telar.step" }.second
            assertEquals(listOf("read"), firstStep.getValue("telar.nodes").strings())
            assertEquals(listOf("reply"), firstStep.getValue("telar.next_nodes").strings())

            val read = spans.span("telar.node read")
            val reply = spans.span("telar.node reply")
            assertEquals(listOf(run.spanId, run.spanId), listOf(read.parentSpanId, reply.parentSpanId))
            assertEquals(run.traceId, read.traceId)
            assertEquals(2L, reply.long("telar.step"))
            assertEquals("reply", reply.string("telar.node"))
            assertEquals("completed", reply.string("telar.outcome"))
            assertNull(reply.statusCode)
        }

    @Test
    fun `the spans are OTLP in JSON`() =
        runTest {
            val tracing =
                OtlpObserver(
                    client,
                    backgroundScope,
                    endpoint = "https://collector.test/v1/traces",
                    serviceName = "help-desk",
                    resource = mapOf("service.version" to "1.2"),
                    headers = mapOf("x-api-key" to "secret"),
                )
            val graph = StateGraph<Ticket> { START then node("read") { it } then END }.compile()

            graph.invoke(Ticket(), config(tracing))
            tracing.flush()

            val request = requests.tryReceive().getOrThrow()
            assertEquals("https://collector.test/v1/traces", request.url.toString())
            assertEquals("secret", request.headers["x-api-key"])
            assertEquals("application/json", (request.body as TextContent).contentType.toString())
            val resource =
                Json
                    .parseToJsonElement(
                        (request.body as TextContent).text,
                    ).jsonObject["resourceSpans"]!!
                    .jsonArray
                    .single()
                    .jsonObject
            val resourceAttributes = resource["resource"]!!.jsonObject["attributes"]!!.attributes()
            assertEquals("help-desk", resourceAttributes.getValue("service.name").text("stringValue"))
            assertEquals("1.2", resourceAttributes.getValue("service.version").text("stringValue"))
            val scope = resource["scopeSpans"]!!.jsonArray.single().jsonObject
            assertEquals("dev.deeptelar.telar", scope["scope"]!!.jsonObject.text("name"))

            val node = Sent(scope["spans"]!!.jsonArray.first().jsonObject)
            assertEquals("telar.node read", node.name)
            assertEquals(32, node.traceId.length)
            assertEquals(16, node.spanId.length)
            assertTrue((node.traceId + node.spanId).all { it in "0123456789abcdef" })
            assertEquals("1", node.json.text("kind"))
            val start = node.json.text("startTimeUnixNano")!!.toLong()
            val end = node.json.text("endTimeUnixNano")!!.toLong()
            assertTrue(start > 1_700_000_000_000_000_000L, "start $start is in nanoseconds since the epoch")
            assertTrue(end >= start)
        }

    @Test
    fun `a failure is an error of the node and of the run`() =
        runTest {
            val tracing = tracing()
            val graph =
                StateGraph<Ticket> {
                    START then node("call", retry = Retry(attempts = 2)) { throw IllegalStateException("The service is down.") } then END
                }.compile()

            assertFailsWith<NodeExecutionException> { graph.invoke(Ticket(), config(tracing)) }
            val spans = tracing.sent()

            val node = spans.span("telar.node call")
            assertEquals(2, node.statusCode)
            assertEquals("The service is down.", node.statusMessage)
            assertEquals("failed", node.string("telar.outcome"))
            val retry = node.event("telar.retry")
            assertEquals("1", retry.getValue("telar.attempt").text("intValue"))
            assertEquals("1000", retry.getValue("telar.pause_ms").text("intValue"))
            val exception = node.event("exception")
            assertTrue(exception.getValue("exception.type").text("stringValue")!!.endsWith("IllegalStateException"))
            assertEquals("The service is down.", exception.getValue("exception.message").text("stringValue"))
            assertTrue(exception.getValue("exception.stacktrace").text("stringValue")!!.contains("The service is down."))

            val run = spans.span("telar.run")
            assertEquals(2, run.statusCode)
            assertEquals("failed", run.string("telar.outcome"))
        }

    @Test
    fun `a paused run says which nodes come next`() =
        runTest {
            val tracing = tracing()
            val graph =
                StateGraph<Ticket> {
                    START then node("ask") { if (it.answer == null) interrupt(it) else it } then END
                }.compile()

            graph.invoke(Ticket(), config(tracing, MemoryCheckpointer()))
            val spans = tracing.sent()

            assertEquals("interrupted", spans.span("telar.node ask").string("telar.outcome"))
            val run = spans.span("telar.run")
            assertEquals("interrupted", run.string("telar.outcome"))
            assertEquals(listOf("ask"), run.strings("telar.next_nodes"))
            assertNull(run.statusCode)
        }

    @Test
    fun `a cancelled run is no error`() =
        runTest {
            val tracing = tracing()
            val graph =
                StateGraph<Ticket> {
                    START then node("wait") { awaitCancellation() } then END
                }.compile()

            val job = launch { graph.invoke(Ticket(), config(tracing)) }
            testScheduler.runCurrent()
            job.cancel()
            job.join()
            val spans = tracing.sent()

            assertEquals(listOf("cancelled", "cancelled"), spans.map { it.string("telar.outcome") })
            assertEquals(listOf(null, null), spans.map { it.statusCode })
        }

    @Test
    fun `the run of a subgraph is inside the span of its node`() =
        runTest {
            val tracing = tracing()
            val inner =
                StateGraph<Ticket> {
                    START then node("draft") { it.copy(answer = "draft") } then END
                }.compile()
            val graph =
                StateGraph<Ticket> {
                    START then subgraph("write", inner) then END
                }.compile()

            graph.invoke(Ticket(), config(tracing))
            val spans = tracing.sent()

            val (innerRun, outerRun) = spans.filter { it.name == "telar.run" }
            val write = spans.span("telar.node write")
            assertEquals(outerRun.spanId, write.parentSpanId)
            assertEquals(write.spanId, innerRun.parentSpanId)
            assertEquals(innerRun.spanId, spans.span("telar.node draft").parentSpanId)
            assertEquals(setOf(outerRun.traceId), spans.map { it.traceId }.toSet())
        }

    @Test
    fun `a node runs with the ids of its span`() =
        runTest {
            val tracing = tracing()
            var seen: TraceContext? = null
            val graph =
                StateGraph<Ticket> {
                    START then node("call") { it.also { seen = currentCoroutineContext()[TraceContext] } } then END
                }.compile()

            graph.invoke(Ticket(), config(tracing))
            val node = tracing.sent().span("telar.node call")

            val trace = assertIs<TraceContext>(seen)
            assertEquals(node.traceId, trace.traceId)
            assertEquals(node.spanId, trace.spanId)
            assertEquals("00-${node.traceId}-${node.spanId}-01", trace.traceparent)
        }

    @Test
    fun `a run in a TraceContext is inside that span`() =
        runTest {
            val tracing = tracing()
            val request = TraceContext("4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7")
            val graph = StateGraph<Ticket> { START then node("read") { it } then END }.compile()

            withContext(request) { graph.invoke(Ticket(), config(tracing)) }
            val spans = tracing.sent()

            val run = spans.span("telar.run")
            assertEquals(request.spanId, run.parentSpanId)
            assertEquals(setOf(request.traceId), spans.map { it.traceId }.toSet())
            assertEquals(run.spanId, spans.span("telar.node read").parentSpanId)
        }

    @Test
    fun `a run started inside a traced node is inside the span of that node`() =
        runTest {
            val tracing = tracing()
            val lookup = StateGraph<Ticket> { START then node("search") { it.copy(answer = "found") } then END }.compile()
            val graph =
                StateGraph<Ticket> {
                    START then node("tool") { lookup.invoke(it, config(tracing)).state } then END
                }.compile()

            graph.invoke(Ticket(), config(tracing))
            val spans = tracing.sent()

            val (lookupRun, outerRun) = spans.filter { it.name == "telar.run" }
            assertNull(outerRun.parentSpanId)
            assertEquals(spans.span("telar.node tool").spanId, lookupRun.parentSpanId)
            assertEquals(setOf(outerRun.traceId), spans.map { it.traceId }.toSet())
        }

    @Test
    fun `a traceparent header is read and an invalid one is ignored`() {
        val trace = TraceContext.parse("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")!!
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", trace.traceId)
        assertEquals("00f067aa0ba902b7", trace.spanId)
        assertEquals("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", trace.traceparent)
        // A later version may add fields, and the flags do not matter.
        assertEquals(trace.spanId, TraceContext.parse("cc-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-00-what")?.spanId)

        val invalid =
            listOf(
                null,
                "",
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7",
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01-more",
                "ff-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                "00-4BF92F3577B34DA6A3CE929D0E0E4736-00f067aa0ba902b7-01",
                "00-00000000000000000000000000000000-00f067aa0ba902b7-01",
                "00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01",
                "00-4bf92f3577b34da6a3ce929d0e0e473-00f067aa0ba902b7-01",
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-1",
            )
        invalid.forEach { assertNull(TraceContext.parse(it), "$it") }
    }

    @Test
    fun `a TraceContext needs valid ids`() {
        assertFailsWith<GraphValidationException> { TraceContext("trace", "00f067aa0ba902b7") }
        assertFailsWith<GraphValidationException> { TraceContext("4bf92f3577b34da6a3ce929d0e0e4736", "0000000000000000") }
    }

    @Test
    fun `Traceparent sends the span of the node with each request`() =
        runTest {
            val tracing = tracing()
            val calls = mutableListOf<HttpRequestData>()
            val api =
                HttpClient(
                    MockEngine { request ->
                        calls += request
                        respond("ok")
                    },
                ) { install(Traceparent) }
            val graph =
                StateGraph<Ticket> {
                    START then
                        node("call") {
                            api.get("https://api.test/orders")
                            api.get("https://api.test/own") { header("traceparent", "00-${"1".repeat(32)}-${"2".repeat(16)}-01") }
                            it
                        } then END
                }.compile()

            graph.invoke(Ticket(), config(tracing))
            api.get("https://api.test/outside")
            val node = tracing.sent().span("telar.node call")

            assertEquals(
                listOf("00-${node.traceId}-${node.spanId}-01", "00-${"1".repeat(32)}-${"2".repeat(16)}-01", null),
                calls.map { it.headers["traceparent"] },
            )
        }

    @Test
    fun `spans that end close together are sent in one request`() =
        runTest {
            OtlpObserver(client, backgroundScope, exportDelay = 5.seconds).let { tracing ->
                val graph = StateGraph<Ticket> { START then node("read") { it } then node("reply") { it } then END }.compile()

                graph.invoke(Ticket(), config(tracing))
                testScheduler.advanceTimeBy(4.seconds)
                testScheduler.runCurrent()
                assertTrue(requests.tryReceive().isFailure, "nothing is sent before the delay")

                // The request is sent by the coroutine of the observer, after the delay.
                val request = requests.receive()
                val spans = Json.parseToJsonElement((request.body as TextContent).text).jsonObject.spans()
                assertEquals(listOf("telar.node read", "telar.node reply", "telar.run"), spans.map { it.name })
            }
        }

    @Test
    fun `many spans are sent in more than one request`() =
        runTest {
            val tracing = tracing()
            val graph = StateGraph<Ticket> { START then node("read") { it } then END }.compile()

            repeat(300) { graph.invoke(Ticket(), config(tracing)) }
            tracing.flush()

            assertEquals(listOf(512, 88), bodies().map { it.spans().size })
        }

    @Test
    fun `a collector that refuses the spans is reported and the run goes on`() =
        runTest {
            status = HttpStatusCode.Forbidden
            val tracing = tracing()
            val graph = StateGraph<Ticket> { START then node("read") { it.copy(answer = "done") } then END }.compile()

            val result = graph.invoke(Ticket(), config(tracing))
            tracing.flush()

            assertEquals("done", result.state.answer)
            val failure = failures.single()
            assertTrue(failure.message!!.contains("403"), failure.message)
            assertTrue(failure.message!!.contains("https://collector.test/v1/traces"), failure.message)
        }

    @Test
    fun `a collector that cannot be reached is reported`() =
        runTest {
            val unreachable = HttpClient(MockEngine { throw IllegalStateException("Connection refused") })
            val tracing = OtlpObserver(unreachable, backgroundScope, onExportFailed = { failures += it })
            val graph = StateGraph<Ticket> { START then node("read") { it } then END }.compile()

            graph.invoke(Ticket(), config(tracing))
            tracing.flush()

            val failure = failures.single()
            assertTrue(failure.message!!.contains("Could not send 2 spans"), failure.message)
            assertIs<IllegalStateException>(failure.cause)
        }

    @Test
    fun `a node outside a traced run has no trace`() =
        runTest {
            var seen: TraceContext? = null
            val graph =
                StateGraph<Ticket> {
                    START then node("read") { it.also { seen = currentCoroutineContext()[TraceContext] } } then END
                }.compile()

            graph.invoke(Ticket(), GraphConfig())

            assertNull(seen)
            assertTrue(requests.tryReceive().isFailure)
        }

    @Test
    fun `the settings are checked`() =
        runTest {
            assertFailsWith<GraphValidationException> { OtlpObserver(client, backgroundScope, endpoint = " ") }
            assertFailsWith<GraphValidationException> { OtlpObserver(client, backgroundScope, exportDelay = (-1).seconds) }
            assertFailsWith<GraphValidationException> { OtlpObserver(client, backgroundScope, maxContentLength = 0) }
        }

    private fun Sent.json(key: String): JsonElement? = string(key)?.let(Json::parseToJsonElement)

    /** The parts of the only message in the attribute [key]. */
    private fun Sent.parts(key: String): List<JsonObject> =
        json(key)!!
            .jsonArray
            .single()
            .jsonObject["parts"]!!
            .jsonArray
            .map { it.jsonObject }

    private fun order(id: Any): JsonObject = buildJsonObject { if (id is Int) put("order", id) else put("order", id.toString()) }

    private val contentKeys =
        listOf(
            "gen_ai.system_instructions",
            "gen_ai.input.messages",
            "gen_ai.output.messages",
            "gen_ai.tool.call.arguments",
            "gen_ai.tool.call.result",
        )

    @Test
    fun `the conversation is not recorded unless it is asked for`() =
        runTest {
            val tracing = tracing()
            val model =
                ScriptedChatModel {
                    calls(call("order_status"))
                    says("5 minutes.")
                }

            toolAgent(model, listOf(orderStatus), system = "Be brief.")
                .invoke(AgentState("Where is my pizza?"), GraphConfig(observers = listOf(tracing)))

            assertEquals(emptyList(), tracing.sent().flatMap { span -> contentKeys.filter { it in span.attributes } })
        }

    @Test
    fun `with recordContent the spans of the calls have the conversation`() =
        runTest {
            val tracing = tracing(recordContent = true)
            val model =
                ScriptedChatModel {
                    calls(ToolCall("call-1", "order_status", order(42)), text = "Let me look.")
                    says("5 minutes.")
                }

            toolAgent(model, listOf(orderStatus), system = "Be brief.")
                .invoke(AgentState("Where is my pizza?"), GraphConfig(observers = listOf(tracing)))
            val spans = tracing.sent()

            val (first, second) = spans.filter { it.name == "chat" }
            assertEquals(Json.parseToJsonElement("""[{"type":"text","content":"Be brief."}]"""), first.json("gen_ai.system_instructions"))
            assertEquals(
                Json.parseToJsonElement("""[{"role":"user","parts":[{"type":"text","content":"Where is my pizza?"}]}]"""),
                first.json("gen_ai.input.messages"),
            )
            val call = """{"type":"tool_call","id":"call-1","name":"order_status","arguments":{"order":42}}"""
            assertEquals(
                Json.parseToJsonElement(
                    """[{"role":"assistant","parts":[{"type":"text","content":"Let me look."},$call],"finish_reason":"tool_call"}]""",
                ),
                first.json("gen_ai.output.messages"),
            )
            assertEquals(
                Json.parseToJsonElement(
                    """[
                        {"role":"user","parts":[{"type":"text","content":"Where is my pizza?"}]},
                        {"role":"assistant","parts":[{"type":"text","content":"Let me look."},$call]},
                        {"role":"tool","parts":[{"type":"tool_call_response","id":"call-1","response":"The driver is 5 minutes away."}]}
                    ]""",
                ),
                second.json("gen_ai.input.messages"),
            )
            assertEquals(
                Json.parseToJsonElement(
                    """[{"role":"assistant","parts":[{"type":"text","content":"5 minutes."}],"finish_reason":"stop"}]""",
                ),
                second.json("gen_ai.output.messages"),
            )

            val tool = spans.span("execute_tool order_status")
            assertEquals("""{"order":42}""", tool.string("gen_ai.tool.call.arguments"))
            assertEquals("The driver is 5 minutes away.", tool.string("gen_ai.tool.call.result"))
        }

    @Test
    fun `an attachment is recorded as its type and size and never as its bytes`() =
        runTest {
            val tracing = tracing(recordContent = true)
            val photo = "iVBORw0K".repeat(8000)
            val question =
                ChatMessage.User(
                    "Is this pizza burnt?",
                    listOf(Attachment.Data(photo, "image/png"), Attachment.Url("https://example.com/menu.pdf", "application/pdf")),
                )

            toolAgent(
                ScriptedChatModel { says("A little.") },
            ).invoke(AgentState(listOf(question)), GraphConfig(observers = listOf(tracing)))
            val chat = tracing.sent().span("chat")

            assertEquals(
                listOf("Is this pizza burnt?", "[image/png, 47 KB]", "[application/pdf: https://example.com/menu.pdf]"),
                chat.parts("gen_ai.input.messages").map { it.text("content") },
            )
            assertTrue(photo.take(16) !in chat.string("gen_ai.input.messages")!!)
        }

    @Test
    fun `an input that is not JSON is recorded as the model wrote it`() =
        runTest {
            val tracing = tracing(recordContent = true)
            val model =
                ScriptedChatModel {
                    calls(ToolCall("call-1", "order_status", JsonObject(emptyMap()), invalidInput = "{order: 42"))
                    says("Sorry.")
                }

            toolAgent(model, listOf(orderStatus)).invoke(AgentState("Where is my pizza?"), GraphConfig(observers = listOf(tracing)))
            val spans = tracing.sent()

            val tool = spans.span("execute_tool order_status")
            assertEquals("{order: 42", tool.string("gen_ai.tool.call.arguments"))
            assertEquals("The input is not a JSON object: {order: 42", tool.string("gen_ai.tool.call.result"))
            val part = spans.first { it.name == "chat" }.parts("gen_ai.output.messages").single()
            assertEquals("{order: 42", part.text("arguments"))
        }

    @Test
    fun `a long text is cut and says how long it was`() =
        runTest {
            val tracing = tracing(recordContent = true, maxContentLength = 10)
            val model =
                ScriptedChatModel {
                    calls(ToolCall("call-1", "order_status", order("1234567890")))
                    says("5 minutes.")
                }

            toolAgent(model, listOf(orderStatus)).invoke(AgentState("Where is my pizza?"), GraphConfig(observers = listOf(tracing)))
            val spans = tracing.sent()

            val (first, second) = spans.filter { it.name == "chat" }
            assertEquals("Where is m… [cut, 18 characters in all]", first.parts("gen_ai.input.messages").single().text("content"))
            val tool = spans.span("execute_tool order_status")
            assertEquals("""{"order":"… [cut, 22 characters in all]""", tool.string("gen_ai.tool.call.arguments"))
            assertEquals("The driver… [cut, 29 characters in all]", tool.string("gen_ai.tool.call.result"))
            assertEquals("5 minutes.", second.parts("gen_ai.output.messages").single().text("content"))
        }

    @Test
    fun `a call to a model that fails keeps what it sent`() =
        runTest {
            val tracing = tracing(recordContent = true)
            val model = ScriptedChatModel { fails("API error 529: Overloaded") }

            assertFailsWith<NodeExecutionException> {
                toolAgent(model).invoke(AgentState("Where is my pizza?"), GraphConfig(observers = listOf(tracing)))
            }
            val chat = tracing.sent().span("chat")

            assertEquals(
                Json.parseToJsonElement("""[{"role":"user","parts":[{"type":"text","content":"Where is my pizza?"}]}]"""),
                chat.json("gen_ai.input.messages"),
            )
            assertNull(chat.string("gen_ai.output.messages"))
        }
}
