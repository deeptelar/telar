package dev.deeptelar.telar.agent

import dev.deeptelar.telar.GraphValidationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.jvm.JvmInline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

enum class Size { SMALL, LARGE }

@Serializable
@JvmInline
value class Coupon(
    val code: String,
)

@Serializable
data class Topping(
    val name: String,
    val extra: Boolean = false,
)

@Serializable
data class Order(
    @Description("Who the pizza is for") val customer: String,
    val size: Size,
    val quantity: Int,
    val price: Double,
    val delivery: Boolean,
    val toppings: List<Topping>,
    val notes: Map<String, String> = emptyMap(),
    val coupon: Coupon? = null,
    @Description("Minutes from now") val pickUpIn: Long? = null,
)

@Serializable
data class Folder(
    val name: String,
    val folders: List<Folder> = emptyList(),
)

@Serializable
data class Lookup(
    val item: String,
)

@Serializable
data class Raw(
    val anything: JsonElement,
)

class ToolTest {
    private fun json(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

    private val menuPrice =
        Tool<Lookup>("menu_price", "Returns the price of an item.") { lookup ->
            if (lookup.item == "cola") "2 euros" else throw IllegalArgumentException("We do not sell ${lookup.item}.")
        }

    @Test
    fun `the schema of a tool describes every property of its input class`() {
        val tool = Tool<Order>("order", "Places an order.") { "ok" }

        assertEquals("order", tool.spec.name)
        assertEquals("Places an order.", tool.spec.description)
        assertEquals(
            json(
                """
                {
                  "type": "object",
                  "properties": {
                    "customer": {"type": "string", "description": "Who the pizza is for"},
                    "size": {"type": "string", "enum": ["SMALL", "LARGE"]},
                    "quantity": {"type": "integer"},
                    "price": {"type": "number"},
                    "delivery": {"type": "boolean"},
                    "toppings": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "properties": {"name": {"type": "string"}, "extra": {"type": "boolean"}},
                        "required": ["name"],
                        "additionalProperties": false
                      }
                    },
                    "notes": {"type": "object", "additionalProperties": {"type": "string"}},
                    "coupon": {"type": "string"},
                    "pickUpIn": {"type": "integer", "description": "Minutes from now"}
                  },
                  "required": ["customer", "size", "quantity", "price", "delivery", "toppings"],
                  "additionalProperties": false
                }
                """,
            ),
            tool.spec.inputSchema,
        )
    }

    @Test
    fun `a tool reads its input into the input class`() =
        runTest {
            val tool = Tool<Order>("order", "Places an order.") { it.toString() }

            val result =
                tool.execute(
                    json(
                        """
                        {"customer": "Ana", "size": "LARGE", "quantity": 2, "price": 9.5, "delivery": true,
                         "toppings": [{"name": "olives"}], "coupon": "FREE", "unknown": 1}
                        """,
                    ),
                )

            assertEquals(
                Order("Ana", Size.LARGE, 2, 9.5, true, listOf(Topping("olives")), coupon = Coupon("FREE")).toString(),
                result,
            )
        }

    @Test
    fun `a tool without input takes Unit`() =
        runTest {
            val tool = Tool<Unit>("opening_hours", "Returns the opening hours.") { "12 to 23" }

            assertEquals(
                json("""{"type": "object", "properties": {}, "required": [], "additionalProperties": false}"""),
                tool.spec.inputSchema,
            )
            assertEquals("12 to 23", tool.execute(noInput))
        }

    @Test
    fun `an input that is not a class is rejected`() {
        assertFailsWith<GraphValidationException> { Tool<String>("echo", "Echoes.") { it } }
        assertFailsWith<GraphValidationException> { Tool<Coupon>("redeem", "Redeems a coupon.") { it.code } }
        assertFailsWith<GraphValidationException> { Tool<List<Lookup>>("prices", "Returns prices.") { "" } }
    }

    @Test
    fun `an input class that contains itself is rejected`() {
        val failure = assertFailsWith<GraphValidationException> { Tool<Folder>("mkdir", "Creates folders.") { "" } }

        assertTrue("contains itself" in failure.message.orEmpty())
    }

    @Test
    fun `an input property without a schema is rejected`() {
        val failure = assertFailsWith<GraphValidationException> { Tool<Raw>("raw", "Takes anything.") { "" } }

        assertTrue("JsonElement" in failure.message.orEmpty())
    }

    @Test
    fun `a tool name with other characters or the wrong length is rejected`() {
        val schema = JsonObject(emptyMap())

        assertFailsWith<GraphValidationException> { ToolSpec("", "Nothing.", schema) }
        assertFailsWith<GraphValidationException> { ToolSpec("menu price", "Has a space.", schema) }
        assertFailsWith<GraphValidationException> { ToolSpec("x".repeat(65), "Too long.", schema) }
        assertEquals("menu-price_2", ToolSpec("menu-price_2", "Fine.", schema).name)
    }

    @Test
    fun `execute returns one result for each call in the order of the calls`() =
        runTest {
            val results =
                listOf(menuPrice).execute(
                    listOf(
                        call("a", "menu_price", "item" to "cola"),
                        call(
                            "b",
                            "menu_price",
                            "item" to "cola",
                        ),
                    ),
                )

            assertEquals(
                listOf(ChatMessage.ToolResult("a", "menu_price", "2 euros"), ChatMessage.ToolResult("b", "menu_price", "2 euros")),
                results,
            )
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `execute runs the calls in parallel`() =
        runTest {
            val slow =
                Tool<Unit>("slow", "Takes a while.") {
                    delay(100)
                    "done"
                }

            val results = listOf(slow).execute(listOf(ToolCall("a", "slow", noInput), ToolCall("b", "slow", noInput)))

            assertEquals(2, results.size)
            assertEquals(100, currentTime)
        }

    @Test
    fun `a tool that throws gives an error result with the message of the exception`() =
        runTest {
            val result = listOf(menuPrice).execute(listOf(call("a", "menu_price", "item" to "salad"))).single()

            assertEquals(ChatMessage.ToolResult("a", "menu_price", "We do not sell salad.", isError = true), result)
        }

    @Test
    fun `a tool that throws without a message gives a general error result`() =
        runTest {
            val broken = Tool(ToolSpec("broken", "Fails.", noInput)) { throw IllegalStateException() }

            val result = listOf(broken).execute(listOf(ToolCall("a", "broken", noInput))).single()

            assertEquals(ChatMessage.ToolResult("a", "broken", "The tool failed.", isError = true), result)
        }

    @Test
    fun `a tool that fails with a plain error gives an error result`() =
        runTest {
            // Ktor's engine for the browser throws this, an Error and not an exception, when a
            // request gets no response.
            val offline = Tool(ToolSpec("order_status", "Looks up an order.", noInput)) { throw Error("Fail to fetch") }

            val result = listOf(offline).execute(listOf(ToolCall("a", "order_status", noInput))).single()

            assertEquals(ChatMessage.ToolResult("a", "order_status", "Fail to fetch", isError = true), result)
        }

    @Test
    fun `an error of the program in a tool is not turned into a result`() =
        runTest {
            val broken = Tool(ToolSpec("broken", "Fails.", noInput)) { throw AssertionError("Expected 2, was 3") }

            val failure = runCatching { listOf(broken).execute(listOf(ToolCall("a", "broken", noInput))) }.exceptionOrNull()

            assertEquals("Expected 2, was 3", assertIs<AssertionError>(failure).message)
        }

    @Test
    fun `an input that does not match the schema gives an error result`() =
        runTest {
            val result = listOf(menuPrice).execute(listOf(call("a", "menu_price", "product" to "cola"))).single()

            assertTrue(result.isError)
            assertTrue("item" in result.text)
        }

    @Test
    fun `an input that is not JSON gives an error result that quotes it and does not run the tool`() =
        runTest {
            var ran = false
            val lookup =
                Tool<Unit>("menu_price", "Returns the price of an item.") {
                    ran = true
                    "2 euros"
                }

            val call = ToolCall("a", "menu_price", noInput, invalidInput = "{item: cola")
            val result = listOf(lookup).execute(listOf(call)).single()

            assertEquals(ChatMessage.ToolResult("a", "menu_price", "The input is not a JSON object: {item: cola", isError = true), result)
            assertFalse(ran)
        }

    @Test
    fun `a call to a tool that does not exist gives an error result`() =
        runTest {
            val result = listOf(menuPrice).execute(listOf(ToolCall("a", "order_status", noInput))).single()

            assertEquals(ChatMessage.ToolResult("a", "order_status", "There is no tool named 'order_status'.", isError = true), result)
        }

    @Test
    fun `a tool that times out gives an error result and the other calls finish`() =
        runTest {
            val slow = Tool<Unit>("slow", "Takes too long.") { withTimeout(10) { delay(100).let { "done" } } }
            val quick = Tool<Unit>("quick", "Answers at once.") { "done" }

            val results = listOf(slow, quick).execute(listOf(ToolCall("a", "slow", noInput), ToolCall("b", "quick", noInput)))

            assertTrue(results[0].isError)
            assertFalse(results[1].isError)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a cancellation of the caller is not turned into a result`() =
        runTest {
            val forever = Tool<Unit>("forever", "Never returns.") { delay(Long.MAX_VALUE).let { "done" } }
            var results: List<ChatMessage.ToolResult>? = null
            var cancelled = false

            val job =
                launch {
                    try {
                        results = listOf(forever).execute(listOf(ToolCall("a", "forever", noInput)))
                    } catch (e: CancellationException) {
                        cancelled = true
                        throw e
                    }
                }
            testScheduler.advanceTimeBy(50)
            job.cancel()
            job.join()

            assertTrue(cancelled)
            assertEquals(null, results)
        }

    @Test
    fun `a tool with a schema written by hand gets the raw input`() =
        runTest {
            val schema =
                buildJsonObject {
                    put("type", "object")
                }
            val echo = Tool(ToolSpec("echo", "Returns its input.", schema)) { it.toString() }

            assertEquals("""{"text":"hi"}""", echo.execute(json("""{"text": "hi"}""")))
        }
}
