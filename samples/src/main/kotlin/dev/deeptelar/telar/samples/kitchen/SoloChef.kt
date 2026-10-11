package dev.deeptelar.telar.samples.kitchen

import dev.deeptelar.telar.CompiledGraph
import dev.deeptelar.telar.NodeRef
import dev.deeptelar.telar.START
import dev.deeptelar.telar.StateGraph
import dev.deeptelar.telar.agent.ChatMessage
import dev.deeptelar.telar.agent.ChatModel
import dev.deeptelar.telar.agent.ChatRequest
import dev.deeptelar.telar.agent.TokenUsage
import dev.deeptelar.telar.agent.pendingToolCalls

/**
 * A shift with one chef who is a model: the [kitchen], the conversation with the chef, and what the
 * conversation cost so far.
 *
 * @property tokens the tokens of all [answers] so far. An answer whose provider reports no usage counts as zero.
 */
data class Shift(
    override val kitchen: Kitchen,
    override val tokenBudget: Int,
    val messages: List<ChatMessage> = emptyList(),
    override val tokens: TokenUsage = TokenUsage(0, 0),
    override val answers: Int = 0,
) : Service {
    override val desks: Map<String, Desk> get() = mapOf("chef" to Desk(tokens, answers, kitchen.minute))
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

/** What every chef who reads orders is told about pizzas. */
val MENU_RULES: String =
    (
        listOf("Menu:") +
            MENU.map { (pizza, toppings) -> "- $pizza: ${toppings.joinToString { it.word }}" } +
            "\"Extra\" of a topping means that topping twice. A guest who is allergic to a topping gets the pizza without it."
    ).joinToString("\n")

/** What every chef who uses the oven is told about it. */
val OVEN_RULES: String =
    "The oven holds ${Kitchen.OVEN_SLOTS} pizzas. A pizza is baked after ${Kitchen.BAKED_AFTER} minutes in the oven and burnt after " +
        "${Kitchen.BURNT_AFTER}. A pizza that is out cannot go back in."

/** What every chef is told about the points. */
val POINTS_RULES: String =
    "A served pizza cannot be taken back. An order is worth ${Score.ON_TIME_POINTS} points on time and ${Score.LATE_POINTS} late. " +
        "A pizza with a topping the guest is allergic to costs ${Score.ALLERGY_PENALTY} points."

val SOLO_CHEF: String =
    listOf(
        "You are the only chef of a pizzeria during one shift. Serve every order a pizza with exactly the toppings it asks for, before it is due.",
        "",
        MENU_RULES,
        "",
        "Rules:",
        "- Each of your answers costs ${Kitchen.THINK_MINUTES} minute. make_pizza costs ${Kitchen.MAKE_MINUTES} minutes. " +
            "bake, take_out and serve cost ${Kitchen.MOVE_MINUTES} minute each. read_orders and check_oven cost no time.",
        "- You may call several tools in one answer. They run one after the other, in your order.",
        "- $OVEN_RULES",
        "- The stock is not refilled.",
        "- $POINTS_RULES",
        "- New orders and changes are announced after the result of your last tool, with the time.",
        "- Work until every order is served or the kitchen closes. Do not explain what you do.",
    ).joinToString("\n")

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
 * The chef gets the whole conversation with every answer, so each answer costs more than the one
 * before. [line] is a kitchen that does not.
 *
 * Run it with `GraphConfig(maxIterations = SHIFT_STEPS)`.
 */
fun soloChef(model: ChatModel): CompiledGraph<Shift> =
    StateGraph<Shift> {
        val chef =
            node("chef", work = { shift -> model.chat(ChatRequest(shift.messages, SOLO_CHEF, kitchenTools)) }) { shift, response ->
                shift.copy(
                    messages = shift.messages + response.message,
                    tokens = shift.tokens + response.usage,
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
