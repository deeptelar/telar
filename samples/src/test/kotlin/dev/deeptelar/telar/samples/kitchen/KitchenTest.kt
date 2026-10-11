package dev.deeptelar.telar.samples.kitchen

import dev.deeptelar.telar.GraphConfig
import dev.deeptelar.telar.agent.ChatMessage
import dev.deeptelar.telar.agent.ChatModel
import dev.deeptelar.telar.agent.ChatResponse
import dev.deeptelar.telar.agent.TokenUsage
import dev.deeptelar.telar.agent.ToolCall
import dev.deeptelar.telar.samples.kitchen.Topping.BASIL
import dev.deeptelar.telar.samples.kitchen.Topping.MOZZARELLA
import dev.deeptelar.telar.samples.kitchen.Topping.MUSHROOMS
import dev.deeptelar.telar.samples.kitchen.Topping.TOMATO
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KitchenTest {
    private val margherita = listOf(TOMATO, MOZZARELLA, BASIL)

    private fun kitchen(vararg orders: Order, changes: List<Change> = emptyList()): Kitchen =
        Kitchen(orders.toList(), changes, dough = 3, stock = mapOf(TOMATO to 3, MOZZARELLA to 3, BASIL to 3, MUSHROOMS to 1), closesAt = 60)

    private fun order(
        number: Int = 1,
        placedAt: Int = 0,
        dueAt: Int = 20,
        wanted: List<Topping> = margherita,
        never: Set<Topping> = emptySet(),
    ) = Order(number, table = number, placedAt, dueAt, "A pizza.", wanted, never)

    /** Makes a pizza, bakes it for [minutes] and takes it out. */
    private fun Kitchen.baked(toppings: List<Topping>, minutes: Int = Kitchen.BAKED_AFTER): Kitchen {
        val made = makePizza(toppings).kitchen
        val name = made.pizzas.last().name
        return made
            .bake(name)
            .kitchen
            .waitFor(minutes)
            .kitchen
            .takeOut(name)
            .kitchen
    }

    private fun config() = GraphConfig<Shift>(maxIterations = SHIFT_STEPS)

    @Test
    fun `a baked pizza with the toppings of its order is served on time`() {
        val made = kitchen(order()).makePizza(margherita)
        assertEquals("P1 is on the counter: tomato, mozzarella, basil.", made.told)
        assertEquals(2, made.kitchen.minute)
        assertEquals("2 dough, 2 tomato, 2 mozzarella, 2 basil, 0 pepperoni, 1 mushrooms, 0 peppers, 0 onions", made.kitchen.stockList)

        val inOven = made.kitchen.bake("P1")
        assertEquals("P1 is in the oven. It is baked from minute 9 and burnt after minute 15.", inOven.told)
        assertEquals(
            "P1 has been in the oven for 6 minutes.",
            inOven.kitchen
                .waitFor(6)
                .kitchen
                .checkOven(),
        )

        val out =
            inOven.kitchen
                .waitFor(6)
                .kitchen
                .takeOut("p1 ")
        assertEquals("P1 is out of the oven: baked.", out.told)
        assertEquals("The oven is empty.", out.kitchen.checkOven())

        val served = out.kitchen.serve(1, "P1")
        assertEquals("P1 was served to table 1 for order #1. On time.", served.told)
        assertEquals(11, served.kitchen.minute)
        assertTrue(served.kitchen.over)
        assertEquals(Score(onTime = 1, late = 0, wrong = 0, allergy = 0, missed = 0, cancelled = 0, wasted = 0), served.kitchen.score())
        assertEquals(100, served.kitchen.score().points)
    }

    @Test
    fun `a pizza that is late, raw, burnt or not what was ordered loses points`() {
        val late = kitchen(order(dueAt = 10)).baked(margherita).serve(1, "P1")
        assertEquals("P1 was served to table 1 for order #1. It was late.", late.told)
        assertEquals(50, late.kitchen.score().points)

        val raw =
            kitchen(order())
                .makePizza(margherita)
                .kitchen
                .bake("P1")
                .kitchen
                .waitFor(5)
                .kitchen
                .takeOut("P1")
        assertEquals("P1 is out of the oven: still raw after 5 minutes, and it cannot go back in.", raw.told)
        assertEquals(
            "P1 was served to table 1 for order #1. The guest sent it back: it was not baked right.",
            raw.kitchen.serve(1, "P1").told,
        )

        val burnt = kitchen(order(dueAt = 40)).baked(margherita, minutes = 13).serve(1, "P1")
        assertEquals(
            Outcome.WRONG,
            burnt.kitchen.orders
                .single()
                .outcome,
        )

        val other = kitchen(order()).baked(listOf(TOMATO, MOZZARELLA)).serve(1, "P1")
        assertEquals("P1 was served to table 1 for order #1. It is not what the guest ordered.", other.told)
        assertEquals(0, other.kitchen.score().points)
        assertEquals(1, other.kitchen.score().wasted)
    }

    @Test
    fun `a pizza with a topping the guest is allergic to costs points`() {
        val funghi = listOf(TOMATO, MOZZARELLA, MUSHROOMS)
        val served = kitchen(order(wanted = listOf(TOMATO, MOZZARELLA), never = setOf(MUSHROOMS))).baked(funghi).serve(1, "P1")

        assertEquals("P1 was served to table 1 for order #1. The guest is allergic to something on it.", served.told)
        assertEquals(-100, served.kitchen.score().points)
    }

    @Test
    fun `orders arrive, change and are cancelled while the chef works`() {
        val start =
            kitchen(
                order(1),
                order(2, placedAt = 2),
                order(3, placedAt = 3),
                changes =
                    listOf(
                        Change(at = 4, order = 2, text = "No basil.", wanted = listOf(TOMATO, MOZZARELLA)),
                        Change(at = 5, order = 3, text = "they left."),
                        Change(at = 50, order = 1, text = "No tomato.", wanted = listOf(MOZZARELLA, BASIL)),
                    ),
            ).opened()
        assertEquals(listOf("New order #1, table 1, due at minute 20: A pizza."), start.news)
        assertEquals("#1, table 1, due at minute 20: A pizza.", start.readOrders())

        val later = start.told().waitFor(5).kitchen
        assertEquals(
            listOf(
                "New order #2, table 2, due at minute 20: A pizza.",
                "New order #3, table 3, due at minute 20: A pizza.",
                "Order #2 changed: No basil.",
                "Order #3 is cancelled: they left.",
            ),
            later.news,
        )
        assertEquals("#1, table 1, due at minute 20: A pizza.\n#2, table 2, due at minute 20: No basil.", later.readOrders())
        assertEquals("Order #3 was cancelled.", assertFailsWith<KitchenException> { later.baked(margherita).serve(3, "P1") }.message)

        // An order that was served is not changed any more.
        val served = later.baked(margherita).serve(1, "P1").kitchen
        assertEquals(
            emptyList(),
            served
                .told()
                .waitFor(15)
                .kitchen
                .waitFor(15)
                .kitchen
                .waitFor(15)
                .kitchen.news,
        )
        assertEquals(Score(onTime = 1, late = 0, wrong = 0, allergy = 0, missed = 1, cancelled = 1, wasted = 0), served.score())
    }

    @Test
    fun `the kitchen refuses what it cannot do`() {
        val start = kitchen(order(), order(2, placedAt = 30))

        fun refused(action: () -> Any): String? = assertFailsWith<KitchenException> { action() }.message

        assertEquals("A pizza needs at least one topping.", refused { start.makePizza(emptyList()) })
        assertEquals("There is not enough mushrooms: 1 left.", refused { start.makePizza(listOf(MUSHROOMS, MUSHROOMS)) })
        assertEquals("There is no pizza P7.", refused { start.bake("P7") })
        assertEquals("Wait for 1 to 15 minutes.", refused { start.waitFor(16) })
        assertEquals("There is no order #2.", refused { start.baked(margherita).serve(2, "P1") })

        val three =
            start
                .makePizza(margherita)
                .kitchen
                .makePizza(margherita)
                .kitchen
                .makePizza(margherita)
                .kitchen
        assertEquals("There is no dough left.", refused { three.makePizza(margherita) })
        assertEquals("P1 was never baked.", refused { three.serve(1, "P1") })
        assertEquals("P1 is not in the oven.", refused { three.takeOut("P1") })

        val full =
            three
                .bake("P1")
                .kitchen
                .bake("P2")
                .kitchen
        assertEquals("The oven is full: P1, P2.", refused { full.bake("P3") })
        assertEquals("P1 is still in the oven.", refused { full.serve(1, "P1") })

        val out =
            full
                .waitFor(6)
                .kitchen
                .takeOut("P1")
                .kitchen
        assertEquals("P1 is not on the counter. Only a pizza that was never baked can go into the oven.", refused { out.bake("P1") })
        val served = out.serve(1, "P1").kitchen
        assertEquals("Order #1 was already served.", refused { served.serve(1, "P2") })
        assertEquals(
            "P1 was already served.",
            refused {
                served
                    .waitFor(15)
                    .kitchen
                    .waitFor(15)
                    .kitchen
                    .serve(2, "P1")
            },
        )

        val closed =
            served
                .waitFor(15)
                .kitchen
                .waitFor(15)
                .kitchen
                .waitFor(15)
                .kitchen
        assertTrue(closed.over)
        assertEquals("The kitchen is closed.", refused { closed.waitFor(1) })
        assertEquals(
            "Every order is served. The shift is over.",
            refused {
                kitchen(order())
                    .baked(margherita)
                    .serve(1, "P1")
                    .kitchen
                    .waitFor(1)
            },
        )
    }

    @Test
    fun `a guest who leaves while the pizza is on its way gets none`() {
        val start = kitchen(order(dueAt = 30), order(2), changes = listOf(Change(at = 11, order = 1, text = "they left.")))

        val served = start.baked(margherita).serve(1, "P1")

        assertEquals("Order #1 was cancelled on your way to the table.", served.told)
        assertEquals(
            Place.PASS,
            served.kitchen.pizzas
                .single()
                .place,
        )
        assertEquals("P1 was served to table 2 for order #2. On time.", served.kitchen.serve(2, "P1").told)
    }

    @Test
    fun `the lunch rush has just enough mushrooms and can be told from its orders`() {
        val rush = lunchRush()
        val end = rush.after(rush.closesAt)

        assertEquals(12, rush.orders.size)
        assertEquals(1, end.score().cancelled)
        assertEquals(
            rush.stock[MUSHROOMS],
            end.orders.filter { it.outcome == Outcome.OPEN }.sumOf { order ->
                order.wanted.count {
                    it ==
                        MUSHROOMS
                }
            },
        )
        assertTrue(rush.dough > end.score().orders)
        assertEquals(
            """
            The shift starts at minute 0. The kitchen closes at minute 95.
            Stock: 14 dough, 20 tomato, 20 mozzarella, 8 basil, 8 pepperoni, 6 mushrooms, 4 peppers, 4 onions.
            New order #1, table 1, due at minute 20: One margherita.
            New order #2, table 2, due at minute 24: A pepperoni with extra mozzarella.
            """.trimIndent(),
            shift(rush).messages.single().text,
        )
    }

    @Test
    fun `the tools of the kitchen tell the model which toppings there are`() {
        assertEquals(listOf("read_orders", "check_oven", "make_pizza", "bake", "take_out", "serve", "wait"), kitchenTools.map { it.name })
        val schema = kitchenTools.first { it.name == "make_pizza" }.inputSchema.toString()
        assertTrue("\"enum\":[\"tomato\",\"mozzarella\",\"basil\",\"pepperoni\",\"mushrooms\",\"peppers\",\"onions\"]" in schema, schema)
        assertTrue("- veggie: tomato, mozzarella, mushrooms, peppers, onions" in SOLO_CHEF, SOLO_CHEF)
    }

    @Test
    fun `the scripted chef serves the whole lunch rush`() =
        runTest {
            val end = soloChef(scriptedChef()).invoke(shift(lunchRush()), config()).state

            assertEquals(
                """
                orders   11 of 11 on time
                points   1100 of 1100
                tokens   ${end.spent} (${end.tokens.inputTokens} read, ${end.tokens.outputTokens} written) in 11 answers
                kitchen  every order served at minute 85, 0 pizzas wasted
                """.trimIndent(),
                end.report(),
            )
            assertTrue(end.spent in 10_000..20_000, "${end.spent}")
            assertFalse(end.outOfTokens)
        }

    @Test
    fun `a chef who waits longer needs fewer tokens and serves late`() =
        runTest {
            val quick = soloChef(scriptedChef()).invoke(shift(lunchRush()), config()).state
            val patient = soloChef(scriptedChef(patience = Kitchen.MAX_WAIT)).invoke(shift(lunchRush()), config()).state

            assertEquals(8, patient.answers)
            assertTrue(patient.spent < quick.spent)
            assertEquals("orders   7 of 11 on time, 4 late", patient.report().lines().first())
            assertEquals(900, patient.kitchen.score().points)
        }

    @Test
    fun `the shift ends when the token budget is spent`() =
        runTest {
            val end = soloChef(scriptedChef()).invoke(shift(lunchRush(), tokenBudget = 3_000), config()).state

            assertTrue(end.outOfTokens)
            assertTrue(end.answers < 11)
            assertTrue(
                end
                    .report()
                    .lines()
                    .last()
                    .startsWith("kitchen  out of tokens at minute ${end.kitchen.minute}, "),
                end.report(),
            )
            assertTrue("not served" in end.report())
        }

    @Test
    fun `a chef who only talks is told to work until the kitchen closes`() =
        runTest {
            val talker = ChatModel { ChatResponse(ChatMessage.Assistant("Let me think."), TokenUsage(10, 5)) }

            val end = soloChef(talker).invoke(shift(lunchRush()), config()).state

            assertEquals(95, end.answers)
            assertEquals(TokenUsage(950, 475), end.tokens)
            assertEquals("Minute 1.\nThe shift is not over. Use the tools, or wait.", end.messages[2].text)
            assertEquals(
                """
                orders   0 of 11 on time, 11 not served
                points   0 of 1100
                tokens   1425 (950 read, 475 written) in 95 answers
                kitchen  closed at minute 95, 0 pizzas wasted
                """.trimIndent(),
                end.report(),
            )
        }

    @Test
    fun `a call the kitchen cannot do reaches the chef as an error, and the calls of an answer run in order`() =
        runTest {
            val calls =
                listOf(
                    ToolCall("a", "bake", buildJsonObject { put("pizza", "P1") }),
                    ToolCall("b", "make_pizza", buildJsonObject { putJsonArray("toppings") { add("pineapple") } }),
                    ToolCall("c", "make_pizza", buildJsonObject { putJsonArray("toppings") { add("tomato") } }),
                    ToolCall("d", "bake", buildJsonObject { put("pizza", "P1") }),
                    ToolCall("e", "read_orders", buildJsonObject { }),
                )
            val asked = shift(lunchRush())

            val state = asked.copy(messages = asked.messages + ChatMessage.Assistant(toolCalls = calls)).afterAnswer()
            val results = state.messages.filterIsInstance<ChatMessage.ToolResult>()

            assertEquals(listOf(true, true, false, false, false), results.map { it.isError })
            assertEquals("There is no pizza P1.", results[0].text)
            assertEquals("P1 is on the counter: tomato.", results[2].text)
            assertEquals("P1 is in the oven. It is baked from minute 10 and burnt after minute 16.", results[3].text)
            assertEquals(
                "#1, table 1, due at minute 20: One margherita.\n#2, table 2, due at minute 24: A pepperoni with extra mozzarella.\nMinute 4.",
                results[4].text,
            )
            assertEquals(4, state.kitchen.minute)
            assertEquals(emptyList(), state.kitchen.news)
        }
}
