package dev.deeptelar.telar.samples.kitchen

import dev.deeptelar.telar.CompiledGraph
import dev.deeptelar.telar.NodeRef
import dev.deeptelar.telar.START
import dev.deeptelar.telar.StateGraph
import dev.deeptelar.telar.agent.ChatMessage
import dev.deeptelar.telar.agent.ChatModel
import dev.deeptelar.telar.agent.ChatRequest
import dev.deeptelar.telar.agent.Description
import dev.deeptelar.telar.agent.TokenUsage
import dev.deeptelar.telar.agent.Tool
import dev.deeptelar.telar.agent.ToolCall
import dev.deeptelar.telar.agent.ToolSpec
import dev.deeptelar.telar.agent.execute
import dev.deeptelar.telar.agent.pendingToolCalls
import kotlinx.serialization.Serializable

@Serializable
data class NewPizza(
    @Description("Every topping of the pizza. Name a topping twice for extra of it.") val toppings: List<Topping>,
)

@Serializable
data class PizzaName(
    @Description("The name of the pizza, for example \"P1\"") val pizza: String,
)

@Serializable
data class Serving(
    @Description("The number of the order, for example 3") val order: Int,
    @Description("The name of the pizza, for example \"P1\"") val pizza: String,
)

@Serializable
data class Waiting(
    @Description("How long to wait, 1 to ${Kitchen.MAX_WAIT} minutes") val minutes: Int,
)

/**
 * The chef's hands: the tools of the kitchen, which act on [kitchen] and replace it with the kitchen
 * after the action.
 *
 * A counter lives for one answer of the chef. The kitchen itself is a value in the state of the
 * graph, so a checkpoint of the run holds the whole shift.
 */
class Counter(
    var kitchen: Kitchen,
) {
    val tools: List<Tool> =
        listOf(
            Tool<Unit>("read_orders", "Returns the open orders. Costs no time.") { kitchen.readOrders() },
            Tool<Unit>("check_oven", "Returns the pizzas in the oven and how long each has been there. Costs no time.") {
                kitchen.checkOven()
            },
            Tool<NewPizza>("make_pizza", "Makes a raw pizza from dough and toppings, puts it on the counter and returns its name.") {
                act(kitchen.makePizza(it.toppings))
            },
            Tool<PizzaName>("bake", "Puts a raw pizza from the counter into the oven.") { act(kitchen.bake(it.pizza)) },
            Tool<PizzaName>("take_out", "Takes a pizza out of the oven. Returns whether it is baked, raw or burnt.") {
                act(kitchen.takeOut(it.pizza))
            },
            Tool<Serving>("serve", "Serves a pizza that is out of the oven to the table of an order. This closes the order.") {
                act(kitchen.serve(it.order, it.pizza))
            },
            Tool<Waiting>("wait", "Does nothing for some minutes, for example until a pizza is baked.") {
                act(kitchen.waitFor(it.minutes))
            },
        )

    /** Runs one call. A call the kitchen cannot do comes back as an error result and changes nothing. */
    suspend fun run(call: ToolCall): ChatMessage.ToolResult = tools.execute(listOf(call)).single()

    private fun act(acted: Acted): String {
        kitchen = acted.kitchen
        return acted.told
    }
}

/** What the model is told about the tools of the kitchen. */
val kitchenTools: List<ToolSpec> = Counter(Kitchen(emptyList(), dough = 0, stock = emptyMap(), closesAt = 0)).tools.map { it.spec }

/**
 * A shift with a chef who is a model: the [kitchen], the conversation with the chef, and what the
 * conversation cost so far.
 *
 * @property tokenBudget the shift ends when the chef has used this many tokens.
 * @property tokens the tokens of all [answers] so far. An answer whose provider reports no usage counts as zero.
 */
data class Shift(
    val kitchen: Kitchen,
    val tokenBudget: Int,
    val messages: List<ChatMessage> = emptyList(),
    val tokens: TokenUsage = TokenUsage(0, 0),
    val answers: Int = 0,
) {
    val spent: Int get() = tokens.inputTokens + tokens.outputTokens

    val outOfTokens: Boolean get() = spent >= tokenBudget

    val over: Boolean get() = kitchen.over || outOfTokens
}

/** The start of a shift in [kitchen]: the chef is told the time, the stock and the first orders. */
fun shift(kitchen: Kitchen, tokenBudget: Int = DEFAULT_TOKEN_BUDGET): Shift {
    val start =
        listOf(
            "The shift starts at minute ${kitchen.minute}. The kitchen closes at minute ${kitchen.closesAt}.",
            "Stock: ${kitchen.stockList}.",
        ) +
            kitchen.newsSince(kitchen.minute - 1)
    return Shift(kitchen, tokenBudget, listOf(ChatMessage.User(start.joinToString("\n"))))
}

const val DEFAULT_TOKEN_BUDGET: Int = 300_000

/**
 * The steps a shift may take: `GraphConfig.maxIterations` for a run of [soloChef]. An answer and
 * the kitchen's turn after it are two steps, and an answer costs at least a minute of a shift.
 */
const val SHIFT_STEPS: Int = 400

val SOLO_CHEF: String =
    """
    You are the only chef of a pizzeria during one shift. Serve every order a pizza with exactly the toppings it asks for, before it is due.

    Menu:
    ${MENU.entries.joinToString("\n    ") { (pizza, toppings) -> "- $pizza: ${toppings.joinToString { it.word }}" }}

    Rules:
    - Each of your answers costs ${Kitchen.THINK_MINUTES} minute. make_pizza costs ${Kitchen.MAKE_MINUTES} minutes. bake, take_out and serve cost ${Kitchen.MOVE_MINUTES} minute each. read_orders and check_oven cost no time.
    - You may call several tools in one answer. They run one after the other, in your order.
    - The oven holds ${Kitchen.OVEN_SLOTS} pizzas. A pizza is baked after ${Kitchen.BAKED_AFTER} minutes in the oven and burnt after ${Kitchen.BURNT_AFTER}. A pizza that is out cannot go back in.
    - "Extra" of a topping means that topping twice. The stock is not refilled.
    - A served pizza cannot be taken back. An order is worth ${Score.ON_TIME_POINTS} points on time and ${Score.LATE_POINTS} late. A pizza with a topping the guest is allergic to costs ${Score.ALLERGY_PENALTY} points.
    - New orders and changes are announced after the result of your last tool, with the time.
    - Work until every order is served or the kitchen closes. Do not explain what you do.
    """.trimIndent()

/**
 * The simplest kitchen: one chef, [model], who has every tool and does everything.
 *
 * The graph is a loop of two nodes. `chef` asks the model what to do next. `kitchen` does it: it
 * runs the tool calls of the answer on the kitchen in the state, one after the other, and tells the
 * chef what happened. The loop ends when the kitchen closes, when every order is served, or when
 * the chef has used its token budget.
 *
 * This is the loop of `toolLoop`, written by hand for two reasons: the tools change the state of
 * the run, which a tool of `toolLoop` cannot reach, and they must run in order, where `toolLoop`
 * runs the calls of one answer in parallel.
 *
 * Run it with `GraphConfig(maxIterations = SHIFT_STEPS)`.
 */
fun soloChef(model: ChatModel): CompiledGraph<Shift> =
    StateGraph<Shift> {
        val chef =
            node("chef", work = { shift -> model.chat(ChatRequest(shift.messages, SOLO_CHEF, kitchenTools)) }) { shift, response ->
                shift.copy(
                    messages = shift.messages + response.message,
                    tokens =
                        TokenUsage(
                            shift.tokens.inputTokens + (response.usage?.inputTokens ?: 0),
                            shift.tokens.outputTokens + (response.usage?.outputTokens ?: 0),
                        ),
                    answers = shift.answers + 1,
                )
            }
        val kitchen = node("kitchen") { it.afterAnswer() }

        START then chef then kitchen
        conditionalEdge(kitchen, targets = setOf(chef, NodeRef.END)) { shift -> if (shift.over) NodeRef.END else chef }
    }.compile()

/** The shift after the kitchen did what the chef's last answer asked for. */
suspend fun Shift.afterAnswer(): Shift {
    val counter = Counter(kitchen.after(Kitchen.THINK_MINUTES))
    val results = messages.pendingToolCalls().map { counter.run(it) }
    val now = counter.kitchen
    val report = (listOf("Minute ${now.minute}.") + now.newsSince(kitchen.minute)).joinToString("\n")
    val told =
        if (results.isEmpty()) {
            listOf(ChatMessage.User("$report\nThe shift is not over. Use the tools, or wait."))
        } else {
            results.dropLast(1) + results.last().let { it.copy(text = "${it.text}\n$report") }
        }
    return copy(kitchen = now, messages = messages + told)
}
