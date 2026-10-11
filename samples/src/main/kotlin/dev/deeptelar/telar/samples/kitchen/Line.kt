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
import dev.deeptelar.telar.agent.execute
import kotlinx.serialization.Serializable

/**
 * A shift in a kitchen with three stations that work at the same time.
 *
 * @property kitchen the kitchen at the minute the stations are asked next.
 * @property tickets what the reader made of each order: its toppings, by the number of the order.
 * The other two stations never see the words of a guest, only these.
 * @property notes the conversation of the reader with its model: the news it was given and the
 * tickets it wrote. It is short, and it is the memory that an order like "the same as table 2 had"
 * needs.
 * @property heardUntil the minute up to which the reader has been given the news.
 * @property log what happened, one line for each thing a station did.
 */
data class LineShift(
    override val kitchen: Kitchen,
    override val tokenBudget: Int = DEFAULT_TOKEN_BUDGET,
    val tickets: Map<Int, List<Topping>> = emptyMap(),
    val notes: List<ChatMessage> = emptyList(),
    val heardUntil: Int = kitchen.minute - 1,
    val reader: Desk = Desk(),
    val prep: Desk = Desk(),
    val oven: Desk = Desk(),
    val log: List<String> = emptyList(),
) : Service {
    override val desks: Map<String, Desk> get() = mapOf("reader" to reader, "prep" to prep, "oven" to oven)

    /** The open orders that have a ticket, the most urgent first, each with the pizza that is made for it, if any. */
    fun plan(): List<Pair<Order, Pizza?>> =
        kitchen.assign(kitchen.rail.filter { it.number in tickets }.sortedBy { it.dueAt }) { tickets.getValue(it.number) }

    /** The pizza the maker starts next, by the rules: the one of the most urgent order that has none and that the stock is enough for. */
    fun nextToMake(): List<Topping>? =
        plan().firstOrNull { (order, pizza) -> pizza == null && kitchen.canMake(tickets.getValue(order.number)) }?.let { (order, _) ->
            tickets.getValue(order.number)
        }

    /**
     * What the oven station does next, by the rules, or `null` when it can only wait: it serves a
     * pizza that is ready, takes a baked one out, or puts the most urgent raw one in.
     */
    fun nextAtOven(): ((Kitchen) -> Acted)? {
        val plan = plan()
        plan
            .firstOrNull { (_, pizza) ->
                pizza?.place == Place.PASS
            }?.let { (order, pizza) -> return { it.serve(order.number, pizza!!.name) } }
        kitchen.baked()?.let { pizza -> return { it.takeOut(pizza.name) } }
        if (kitchen.oven.size < Kitchen.OVEN_SLOTS) {
            plan.firstOrNull { (_, pizza) -> pizza?.place == Place.COUNTER }?.let { (_, pizza) -> return { it.bake(pizza!!.name) } }
        }
        return null
    }
}

@Serializable
data class Ticket(
    @Description("The number of the order, for example 3") val order: Int,
    @Description("Every topping the pizza must have. Name a topping twice for extra of it.") val toppings: List<Topping>,
)

/** What one station did in one turn, to be added to the shift. `null` stands for a station that had nothing to do. */
private class Turn(
    val from: Int,
    val counter: Counter,
    val usage: TokenUsage? = null,
    val asked: Boolean = false,
)

/** What the reader made of the news. */
private class Reading(
    val messages: List<ChatMessage>,
    val tickets: Map<Int, List<Topping>>,
    val usage: TokenUsage?,
    val heard: Int,
)

val READER: String =
    listOf(
        "You take the orders of a pizzeria and write a ticket for the kitchen. The kitchen makes exactly what the ticket says and never sees the guest's words.",
        "",
        MENU_RULES,
        "",
        "You are told when a guest orders and when a guest changes an order. Call write_ticket once for each new order and once for each " +
            "changed order, with every topping the pizza must have now. Remember what guests said earlier: later orders and changes build on it.",
        "Write no ticket for a cancelled order. Do not explain what you do.",
    ).joinToString("\n")

val PREP: String =
    listOf(
        "You make the pizzas of a pizzeria. Others bake and serve them. You are told which orders have no pizza yet, with the toppings each needs.",
        "Each of your answers costs ${Kitchen.THINK_MINUTES} minute and make_pizza costs ${Kitchen.MAKE_MINUTES} minutes. " +
            "While you work you hear no news, and you are asked again when you are done.",
        "Make one pizza for each order, the most urgent first. The stock is not refilled. Do not explain what you do.",
    ).joinToString("\n")

val OVEN: String =
    listOf(
        "You run the oven and the pass of a pizzeria. Another chef makes the pizzas and puts them on the counter. " +
            "You bake them, take them out and serve them. A pizza fits an order when it has exactly the toppings of the order.",
        "Each of your answers costs ${Kitchen.THINK_MINUTES} minute. bake, take_out and serve cost ${Kitchen.MOVE_MINUTES} minute each. " +
            "While you work or wait you hear no news, and you are asked again when you are done.",
        OVEN_RULES,
        POINTS_RULES,
        "You may call several tools in one answer. They run one after the other, in your order. Do not explain what you do.",
    ).joinToString("\n")

/**
 * A kitchen with three stations that work at the same time and share nothing but the state:
 *
 * - `reader` turns the words of the guests into tickets, a list of toppings for each order. It is
 *   always a model, [reader], because only a model can read "the same as table 2 had".
 * - `prep` makes the pizza of each ticket.
 * - `oven` bakes the pizzas and serves them.
 *
 * `prep` and `oven` are run by a model when one is given, and by a few rules in plain code when
 * not. With tickets to work from, neither has anything to read, so the rules do their job without a
 * token.
 *
 * The graph is a clock. `bell` starts a minute and the three stations run in parallel. A station
 * that is in the middle of something does nothing, a free one starts its next piece of work, and
 * `clock` moves the kitchen to the next minute. A station with a model gets no conversation: it is
 * asked with what it needs to know right now, so its answers do not get more expensive during the
 * shift, as they do for [soloChef]. Only the reader keeps its conversation.
 *
 * The stations are nodes with a `work` and an `update`. Each `work` acts on its own copy of the
 * kitchen, and each `update` does the same actions again on the kitchen in the state. That gives
 * the same kitchen in whatever order the updates run, because the stations do not touch the same
 * things: the maker takes from the stock and adds to the counter, and the oven station takes from
 * the counter.
 *
 * Run it with `GraphConfig(maxIterations = SHIFT_STEPS)`.
 */
fun line(reader: ChatModel, prep: ChatModel? = null, oven: ChatModel? = null): CompiledGraph<LineShift> =
    StateGraph<LineShift> {
        val bell = node("bell") { it }
        val clock = node("clock") { it.copy(kitchen = it.kitchen.after(1)) }

        val read =
            node("reader", work = { shift -> shift.read(reader) }) { shift, reading ->
                if (reading == null) shift else shift.with(reading)
            }
        val make =
            node("prep", work = { shift -> shift.prepTurn(prep) }) { shift, turn ->
                if (turn == null) shift else shift.with("prep", turn).copy(prep = shift.prep.after(turn))
            }
        val bake =
            node("oven", work = { shift -> shift.ovenTurn(oven) }) { shift, turn ->
                if (turn == null) shift else shift.with("oven", turn).copy(oven = shift.oven.after(turn))
            }

        START then bell
        listOf(read, make, bake).forEach { bell then it then clock }
        conditionalEdge(clock, targets = setOf(bell, NodeRef.END)) { shift -> if (shift.over) NodeRef.END else bell }
    }.compile()

private fun List<Topping>.words(): String = joinToString { it.word }

private fun LineShift.line(station: String, text: String, minute: Int = kitchen.minute): String =
    "${minute.toString().padStart(3)}  ${station.padEnd(6)}  $text"

/** This shift with what a station did in [turn] done to its kitchen too. The clock of the shift stays where it is. */
private fun LineShift.with(station: String, turn: Turn): LineShift =
    copy(
        kitchen =
            turn.counter.done
                .fold(kitchen.at(turn.from)) { kitchen, action -> action(kitchen).kitchen }
                .at(kitchen.minute),
        log = log + turn.counter.told.map { (minute, text) -> line(station, text, minute) },
    )

/** This shift with the tickets the reader wrote. They are there for the other stations from the next minute on. */
private fun LineShift.with(reading: Reading): LineShift {
    val done = kitchen.minute + Kitchen.THINK_MINUTES
    return copy(
        tickets = tickets + reading.tickets,
        notes = notes + reading.messages,
        heardUntil = reading.heard,
        reader = reader.answered(reading.usage).copy(busyUntil = done),
        log = log + reading.tickets.map { (order, toppings) -> line("reader", "Ticket #$order: ${toppings.words()}.", done) },
    )
}

private fun Desk.after(turn: Turn): Desk = (if (turn.asked) answered(turn.usage) else this).copy(busyUntil = turn.counter.kitchen.minute)

/**
 * Gives the reader the news it has not heard, and returns the tickets it wrote. Returns `null` when
 * the reader is busy or there is nothing to read.
 *
 * An open order that still has no ticket is given to the reader again, so one answer that leaves an
 * order out, or names a topping that does not exist, does not lose the order.
 */
private suspend fun LineShift.read(model: ChatModel): Reading? {
    if (reader.busyUntil > kitchen.minute) return null
    val news = kitchen.newsSince(heardUntil)
    val forgotten =
        kitchen.rail
            .filter {
                it.placedAt <= heardUntil && it.number !in tickets
            }.map { "Order ${it.ticket} has no ticket yet." }
    if (news.isEmpty() && forgotten.isEmpty()) return null

    val asked = ChatMessage.User((listOf("Minute ${kitchen.minute}.") + news + forgotten).joinToString("\n"))
    val written = mutableMapOf<Int, List<Topping>>()
    val pad =
        listOf(
            Tool<Ticket>("write_ticket", "Writes the ticket of an order, or replaces it: the toppings its pizza must have.") { ticket ->
                if (kitchen.rail.none { it.number == ticket.order }) throw KitchenException("There is no open order #${ticket.order}.")
                written[ticket.order] = ticket.toppings
                "Noted."
            },
        )
    val response = model.chat(ChatRequest(notes + asked, READER, pad.map { it.spec }))
    // One call after the other, so that the last ticket of an order is the one that counts.
    val results = response.message.toolCalls.map { pad.execute(listOf(it)).single() }
    return Reading(listOf(asked, response.message) + results, written, response.usage, kitchen.minute)
}

/** Lets the maker start its next piece of work. Returns `null` when the maker is busy or no order needs a pizza. */
private suspend fun LineShift.prepTurn(model: ChatModel?): Turn? {
    if (prep.busyUntil > kitchen.minute || kitchen.over) return null
    val toppings = nextToMake() ?: return null
    if (model == null) return Turn(kitchen.minute, Counter(kitchen).apply { act { it.makePizza(toppings) } })

    val waiting = plan().filter { (_, pizza) -> pizza == null }.map { (order, _) -> order.brief(tickets) }
    val brief =
        listOf("Minute ${kitchen.minute}.", "Orders without a pizza, the most urgent first:") + waiting + "Stock: ${kitchen.stockList}."
    return ask(model, PREP, brief, setOf("make_pizza"))
}

/** Lets the oven station start its next piece of work. Returns `null` when it is busy or can only wait. */
private suspend fun LineShift.ovenTurn(model: ChatModel?): Turn? {
    if (oven.busyUntil > kitchen.minute || kitchen.over) return null
    val move = nextAtOven() ?: return null
    if (model == null) return Turn(kitchen.minute, Counter(kitchen).apply { act(move) })

    fun pizzas(place: Place, describe: (Pizza) -> String = { "" }) =
        kitchen.made
            .filter {
                it.place == place && (it.baked == null || it.good)
            }.joinToString("; ") { "${it.name} (${it.toppings.words()})${describe(it)}" }
            .ifEmpty { "nothing" }
    val brief =
        listOf("Minute ${kitchen.minute}.", "Open orders, the most urgent first:") +
            plan().map { (order, _) -> order.brief(tickets) } +
            listOf(
                "Counter, raw: ${pizzas(Place.COUNTER)}.",
                "Oven: ${pizzas(Place.OVEN) { ", in for ${kitchen.minute - it.inOvenSince!!} minutes" }}.",
                "Pass, baked: ${pizzas(Place.PASS)}.",
            )
    return ask(model, OVEN, brief, setOf("bake", "take_out", "serve", "wait"))
}

private fun Order.brief(tickets: Map<Int, List<Topping>>): String = "#$number, due at minute $dueAt: ${tickets.getValue(number).words()}"

/** Asks a station's model once, with [brief] as all it knows, and does what it answers. */
private suspend fun LineShift.ask(model: ChatModel, system: String, brief: List<String>, tools: Set<String>): Turn {
    val counter = Counter(kitchen.after(Kitchen.THINK_MINUTES), only = tools)
    val response = model.chat(ChatRequest(listOf(ChatMessage.User(brief.joinToString("\n"))), system, counter.tools.map { it.spec }))
    response.message.toolCalls.forEach { counter.run(it) }
    return Turn(kitchen.minute + Kitchen.THINK_MINUTES, counter, response.usage, asked = true)
}
