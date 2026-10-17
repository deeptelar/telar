package dev.deeptelar.telar.otel

import dev.deeptelar.telar.END
import dev.deeptelar.telar.GraphConfig
import dev.deeptelar.telar.START
import dev.deeptelar.telar.StateGraph
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals

private data class Order(
    val done: Int = 0,
)

class OtlpObserverThreadsTest {
    @Test
    fun `nodes that end at the same time on different threads are all sent`() =
        runBlocking {
            val bodies = ConcurrentLinkedQueue<String>()
            val client = HttpClient(MockEngine { request -> respond("{}").also { bodies += (request.body as TextContent).text } })
            val scope = CoroutineScope(Dispatchers.Default)
            val tracing = OtlpObserver(client, scope)
            val names = (1..32).map { "station$it" }
            val graph =
                StateGraph<Order> {
                    val join = node("join") { it }
                    names.forEach { name ->
                        val station =
                            node(name, work = { withContext(Dispatchers.Default) { Thread.sleep(5) } }) { order, _ ->
                                order.copy(done = order.done + 1)
                            }
                        START then station then join
                    }
                    join then END
                }.compile()

            repeat(10) { graph.invoke(Order(), GraphConfig(observers = listOf(tracing))) }
            tracing.flush()
            scope.cancel()

            val spans =
                bodies.flatMap { body ->
                    Json
                        .parseToJsonElement(body)
                        .jsonObject["resourceSpans"]!!
                        .jsonArray
                        .flatMap { it.jsonObject["scopeSpans"]!!.jsonArray }
                        .flatMap { it.jsonObject["spans"]!!.jsonArray }
                        .map { it.jsonObject["name"]!!.jsonPrimitive.content }
                }
            assertEquals(10 * (names.size + 2), spans.size)
            assertEquals(10, spans.count { it == "telar.run" })
        }
}
