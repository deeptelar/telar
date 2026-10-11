package dev.deeptelar.telar.samples.kitchen

import dev.deeptelar.telar.GraphConfig
import dev.deeptelar.telar.agent.ChatMessage
import dev.deeptelar.telar.agent.ChatModel
import dev.deeptelar.telar.agent.ChatRequest
import dev.deeptelar.telar.agent.ChatResponse
import dev.deeptelar.telar.agent.ToolCall
import dev.deeptelar.telar.samples.kitchen.Topping.MOZZARELLA
import dev.deeptelar.telar.samples.kitchen.Topping.MUSHROOMS
import dev.deeptelar.telar.samples.kitchen.Topping.ONIONS
import dev.deeptelar.telar.samples.kitchen.Topping.PEPPERONI
import dev.deeptelar.telar.samples.kitchen.Topping.PEPPERS
import dev.deeptelar.telar.samples.kitchen.Topping.TOMATO
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LineTest {
    private val config = GraphConfig<LineShift>(maxIterations = SHIFT_STEPS)

    private fun ticket(order: Int, vararg toppings: String) =
        ToolCall(
            "test-$order",
            "write_ticket",
            buildJsonObject {
                put("order", order)
                putJsonArray("toppings") { toppings.forEach(::add) }
            },
        )

    /** The scripted reader, with the tool calls of each answer passed through [change]. The first answer is number 0. */
    private fun reader(change: (answer: Int, calls: List<ToolCall>) -> List<ToolCall>): ChatModel {
        val scripted = scriptedReader()
        return ChatModel { request ->
            val response = scripted.chat(request)
            val answer = request.messages.count { it is ChatMessage.Assistant }
            ChatResponse(ChatMessage.Assistant(toolCalls = change(answer, response.message.toolCalls)), response.usage)
        }
    }

    /** [model], with every request it gets added to [requests]. */
    private fun recording(model: ChatModel, requests: MutableList<ChatRequest>): ChatModel =
        ChatModel { request ->
            requests += request
            model.chat(request)
        }

    @Test
    fun `the line serves the lunch rush with a model that only reads the orders`() =
        runTest {
            val end = line(scriptedReader()).invoke(LineShift(lunchRush()), config).state
            val solo = soloChef(scriptedChef()).invoke(shift(lunchRush()), GraphConfig(maxIterations = SHIFT_STEPS)).state

            assertEquals(
                """
                orders   11 of 11 on time
                points   1100 of 1100
                tokens   ${end.spent} (${end.tokens.inputTokens} read, ${end.tokens.outputTokens} written) in 10 answers
                stations reader ${end.spent} tokens in 10 answers, prep 0 tokens in 0 answers, oven 0 tokens in 0 answers
                kitchen  every order served at minute 78, 1 pizza wasted
                """.trimIndent(),
                end.report(),
            )
            // Two stations get the shift done sooner than one chef, and reading alone takes fewer tokens than doing everything.
            assertTrue(end.kitchen.minute < solo.kitchen.minute)
            assertTrue(end.spent < solo.spent / 2, "${end.spent} and ${solo.spent}")
        }

    @Test
    fun `the stations work in the same minutes`() =
        runTest {
            val log = line(scriptedReader()).invoke(LineShift(lunchRush()), config).state.log

            // The maker finishes the third pizza in the minute the oven station takes the first one out.
            assertTrue(" 11  prep    P3 is on the counter: tomato, mozzarella, mushrooms." in log, log.joinToString("\n"))
            assertTrue(" 11  oven    P1 is out of the oven: baked." in log)
            assertEquals("  1  reader  Ticket #1: tomato, mozzarella, basil.", log.first())
            assertEquals(" 78  oven    P12 was served to table 12 for order #12. On time.", log.last())
        }

    @Test
    fun `the reader remembers what the guests said, and the other stations get its tickets`() =
        runTest {
            val end = line(scriptedReader()).invoke(LineShift(lunchRush()), config).state

            assertEquals(listOf(MOZZARELLA, PEPPERONI, MUSHROOMS), end.tickets[5])
            assertEquals(listOf(TOMATO, MOZZARELLA, MUSHROOMS, PEPPERS), end.tickets[10])
            assertEquals((1..12).toSet(), end.tickets.keys)
            assertEquals(
                "Minute 0.\nNew order #1, table 1, due at minute 20: One margherita.\nNew order #2, table 2, due at minute 24: A pepperoni with extra cheese.",
                end.notes.first().text,
            )
            assertEquals(listOf("Noted.", "Noted."), end.notes.subList(2, 4).map { it.text })
            assertTrue(end.notes.any { it.text == "Minute 23.\nOrder #5 changed: Table 5 also wants mushrooms on it." })
            assertTrue(end.notes.any { it.text == "Minute 30.\nOrder #6 is cancelled: the guests of table 6 left." })
        }

    @Test
    fun `a model at a station is asked with what it needs to know now, and nothing before`() =
        runTest {
            val prep = mutableListOf<ChatRequest>()
            val oven = mutableListOf<ChatRequest>()

            val end =
                line(
                    scriptedReader(),
                    recording(scriptedMaker(), prep),
                    recording(scriptedOven(), oven),
                ).invoke(LineShift(lunchRush()), config).state

            assertEquals(
                """
                Minute 1.
                Orders without a pizza, the most urgent first:
                #1, due at minute 20: tomato, mozzarella, basil
                #2, due at minute 24: tomato, mozzarella, mozzarella, pepperoni
                Stock: 14 dough, 20 tomato, 20 mozzarella, 8 basil, 8 pepperoni, 7 mushrooms, 5 peppers, 4 onions.
                """.trimIndent(),
                prep
                    .first()
                    .messages
                    .single()
                    .text,
            )
            assertEquals(listOf("make_pizza"), prep.first().tools.map { it.name })
            assertEquals(PREP, prep.first().system)
            assertEquals(
                """
                Minute 4.
                Open orders, the most urgent first:
                #1, due at minute 20: tomato, mozzarella, basil
                #2, due at minute 24: tomato, mozzarella, mozzarella, pepperoni
                Counter, raw: P1 (tomato, mozzarella, basil).
                Oven: nothing.
                Pass, baked: nothing.
                """.trimIndent(),
                oven
                    .first()
                    .messages
                    .single()
                    .text,
            )
            assertEquals(listOf("bake", "take_out", "serve", "wait"), oven.first().tools.map { it.name })
            assertTrue((prep + oven).all { it.messages.size == 1 })
            assertTrue(oven.any { "Oven: P1 (tomato, mozzarella, basil), in for 6 minutes" in it.messages.single().text })

            assertEquals(prep.size, end.prep.answers)
            assertEquals(oven.size, end.oven.answers)
            assertEquals(end.spent, end.desks.values.sumOf { it.tokens.inputTokens + it.tokens.outputTokens })
            // An answer costs a minute, so chefs who do one thing with each answer are slower than the rules.
            assertTrue(end.kitchen.score().late > 0)
            assertEquals(0, end.kitchen.score().wrong + end.kitchen.score().allergy)
        }

    @Test
    fun `an order the reader left out is given to it again`() =
        runTest {
            val model =
                reader { answer, calls ->
                    if (answer ==
                        0
                    ) {
                        calls.filterNot { it.input.toString().contains("\"order\":2") }
                    } else {
                        calls
                    }
                }

            val end = line(model).invoke(LineShift(lunchRush()), config).state

            assertEquals(
                "Minute 1.\nOrder #2, table 2, due at minute 24: A pepperoni with extra cheese. has no ticket yet.",
                end.notes.filterIsInstance<ChatMessage.User>()[1].text,
            )
            assertEquals(11, end.reader.answers)
            assertEquals(1100, end.kitchen.score().points)
        }

    @Test
    fun `a ticket for an order or a topping that is not there is refused`() =
        runTest {
            val model = reader { answer, calls -> if (answer == 0) listOf(ticket(99, "tomato"), ticket(1, "pineapple")) + calls else calls }

            val end = line(model).invoke(LineShift(lunchRush()), config).state
            val refused = end.notes.filterIsInstance<ChatMessage.ToolResult>().filter { it.isError }

            assertEquals(2, refused.size)
            assertEquals("There is no open order #99.", refused.first().text)
            assertEquals(MENU.getValue("margherita"), end.tickets[1])
            assertEquals(1100, end.kitchen.score().points)
        }

    @Test
    fun `a wrong ticket is a wrong pizza`() =
        runTest {
            // The reader forgets what table 3 said half an hour before.
            val model =
                reader { _, calls ->
                    calls.map {
                        if (it.input.toString().contains(
                                "\"order\":10",
                            )
                        ) {
                            ticket(10, "tomato", "mozzarella", "mushrooms", "peppers", "onions")
                        } else {
                            it
                        }
                    }
                }

            val end = line(model).invoke(LineShift(lunchRush()), config).state

            assertEquals(MENU.getValue("veggie"), end.tickets[10])
            assertTrue(ONIONS in end.tickets.getValue(10))
            assertEquals(1, end.kitchen.score().allergy)
            assertEquals(900, end.kitchen.score().points)
            assertTrue("1 with an allergen" in end.report())
        }

    @Test
    fun `the line stops when its tokens are spent`() =
        runTest {
            val end = line(scriptedReader()).invoke(LineShift(lunchRush(), tokenBudget = 1_500), config).state

            assertTrue(end.outOfTokens)
            assertTrue(end.reader.answers < 10)
            assertTrue(
                end
                    .report()
                    .lines()
                    .last()
                    .startsWith("kitchen  out of tokens at minute ${end.kitchen.minute}, "),
                end.report(),
            )
        }
}
