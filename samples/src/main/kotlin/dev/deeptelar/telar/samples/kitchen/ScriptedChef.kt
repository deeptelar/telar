package dev.deeptelar.telar.samples.kitchen

import dev.deeptelar.telar.agent.ChatMessage
import dev.deeptelar.telar.agent.ChatModel
import dev.deeptelar.telar.agent.ChatRequest
import dev.deeptelar.telar.agent.ChatResponse
import dev.deeptelar.telar.agent.TokenUsage
import dev.deeptelar.telar.agent.ToolCall
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Offline stand-in for a real model, so the sample runs without an API key: a chef who knows what
 * every order of [kitchen] means and plays the shift by simple rules. It serves what is ready,
 * empties the oven, fills it, and makes the pizza of the order that is due first.
 *
 * It reads nothing. It works its answers out once, by playing the shift on its own copy of the
 * kitchen, and then gives the answer of the turn it is asked for. Its token counts are a guess
 * from the length of the text, a quarter of a token for each character.
 *
 * @param patience the most minutes it waits before it answers again and hears the news. A chef who
 * waits longer needs fewer answers, and so fewer tokens, but starts new orders later.
 */
fun scriptedChef(kitchen: Kitchen = lunchRush(), patience: Int = 2): ChatModel {
    var script: List<List<ToolCall>>? = null
    return ChatModel { request ->
        val turns = script ?: rehearse(kitchen, patience).also { script = it }
        val calls = turns.getOrElse(request.messages.count { it is ChatMessage.Assistant }) { emptyList() }
        ChatResponse(ChatMessage.Assistant(toolCalls = calls), guessedUsage(request, calls))
    }
}

/**
 * Offline stand-in for the model that reads the orders in [line]: it knows what every order and
 * every change of [kitchen] means, and writes the ticket of each one it is told about.
 */
fun scriptedReader(kitchen: Kitchen = lunchRush()): ChatModel =
    ChatModel { request ->
        val news =
            request.messages
                .last { it is ChatMessage.User }
                .text
                .lines()
        val calls =
            news.mapNotNull { line ->
                val number =
                    NUMBERED
                        .find(line)
                        ?.groupValues
                        ?.get(1)
                        ?.toInt() ?: return@mapNotNull null
                // The last thing the guest said that the line tells, or else the order as it was placed.
                val wanted =
                    kitchen.changes.lastOrNull { it.order == number && it.text in line }?.wanted
                        ?: kitchen.orders
                            .first { it.number == number }
                            .wanted
                            .takeIf { "cancelled" !in line }
                        ?: return@mapNotNull null
                ToolCall(
                    "ticket-${request.messages.size}-$number",
                    "write_ticket",
                    buildJsonObject {
                        put("order", number)
                        putJsonArray("toppings") { wanted.forEach { add(it.word) } }
                    },
                )
            }
        ChatResponse(ChatMessage.Assistant(toolCalls = calls), guessedUsage(request, calls))
    }

private val NUMBERED = Regex("""^(?:New order|Order) #(\d+)[, ]""")

/**
 * Offline stand-in for a model at the `prep` station of [line]. It reads the brief of the station
 * and makes the pizza of the first order on it.
 */
fun scriptedMaker(): ChatModel =
    ChatModel { request ->
        val toppings =
            request.messages
                .last()
                .text
                .lines()
                .firstNotNullOfOrNull { TICKET.find(it) }
                ?.groupValues
                ?.get(2)
        val calls =
            listOfNotNull(
                toppings?.let {
                    ToolCall(
                        "make",
                        "make_pizza",
                        buildJsonObject { putJsonArray("toppings") { it.split(", ").forEach(::add) } },
                    )
                },
            )
        ChatResponse(ChatMessage.Assistant(toolCalls = calls), guessedUsage(request, calls))
    }

/**
 * Offline stand-in for a model at the `oven` station of [line]. It reads the brief of the station
 * and does one thing: it serves a pizza that fits an order, or takes out a baked one, or puts in a
 * raw one that fits an order.
 */
fun scriptedOven(): ChatModel =
    ChatModel { request ->
        val brief =
            request.messages
                .last()
                .text
                .lines()
        val orders = brief.mapNotNull { TICKET.find(it) }.map { it.groupValues[1].toInt() to it.groupValues[2].split(", ").sorted() }

        fun pizzas(place: String) = PIZZA.findAll(brief.first { it.startsWith(place) }).map { it.groupValues }.toList()

        fun fits(pizza: List<String>, toppings: List<String>) = pizza[2].split(", ").sorted() == toppings

        fun pizza(name: String) = buildJsonObject { put("pizza", name) }

        val pass = pizzas("Pass")
        val oven = pizzas("Oven")
        val counter = pizzas("Counter")
        val served = orders.firstNotNullOfOrNull { (order, toppings) -> pass.firstOrNull { fits(it, toppings) }?.let { order to it[1] } }
        val baked = oven.filter { it[3].toInt() >= Kitchen.BAKED_AFTER }.maxByOrNull { it[3].toInt() }
        val raw = orders.firstNotNullOfOrNull { (_, toppings) -> counter.firstOrNull { fits(it, toppings) } }
        val call =
            when {
                served != null ->
                    ToolCall(
                        "serve",
                        "serve",
                        buildJsonObject {
                            put("order", served.first)
                            put("pizza", served.second)
                        },
                    )
                baked != null -> ToolCall("take-out", "take_out", pizza(baked[1]))
                raw != null && oven.size < Kitchen.OVEN_SLOTS -> ToolCall("bake", "bake", pizza(raw[1]))
                else -> null
            }
        ChatResponse(ChatMessage.Assistant(toolCalls = listOfNotNull(call)), guessedUsage(request, listOfNotNull(call)))
    }

private val TICKET = Regex("""^#(\d+), due at minute \d+: (.*)$""")
private val PIZZA = Regex("""(P\d+) \(([^)]*)\)(?:, in for (\d+) minutes)?""")

/** The answers of the scripted chef for a whole shift in [start], one list of tool calls for each answer. */
suspend fun rehearse(start: Kitchen, patience: Int = 2): List<List<ToolCall>> {
    var kitchen = start
    val turns = mutableListOf<List<ToolCall>>()
    while (!kitchen.over) {
        val counter = Counter(kitchen.after(Kitchen.THINK_MINUTES))
        val calls = mutableListOf<ToolCall>()

        suspend fun call(name: String, input: JsonObject) {
            calls += ToolCall("turn${turns.size + 1}-${calls.size + 1}", name, input).also { counter.run(it) }
        }
        while (!counter.kitchen.over) {
            val move = counter.kitchen.nextMove(known = kitchen) ?: break
            call(move.first, move.second)
        }
        // Nothing is left to do now. Wait until the next pizza is baked, less the minute the next answer costs.
        val now = counter.kitchen
        if (!now.over) {
            val ready = now.oven.minOfOrNull { it.inOvenSince!! + Kitchen.BAKED_AFTER }
            val gap = if (ready == null) patience else ready - now.minute - Kitchen.THINK_MINUTES
            if (gap >= 1 || calls.isEmpty()) call("wait", buildJsonObject { put("minutes", gap.coerceIn(1, patience)) })
        }
        turns += calls
        kitchen = counter.kitchen
    }
    return turns
}

/**
 * The next tool and its input, or `null` when the chef can only wait.
 *
 * The chef works from the orders as it was last told them, in [known]: an order that was placed or
 * changed since then is news it has not heard. It does see a table leave.
 */
private fun Kitchen.nextMove(known: Kitchen): Pair<String, JsonObject>? {
    val open = rail.map { it.number }
    val plan = assign(known.rail.filter { it.number in open }.sortedBy { it.dueAt }) { it.wanted }

    fun pizza(name: String) = buildJsonObject { put("pizza", name) }

    plan.firstOrNull { (_, pizza) -> pizza?.place == Place.PASS }?.let { (order, pizza) ->
        return "serve" to
            buildJsonObject {
                put("order", order.number)
                put("pizza", pizza!!.name)
            }
    }
    baked()?.let { return "take_out" to pizza(it.name) }
    if (oven.size < Kitchen.OVEN_SLOTS) {
        plan.firstOrNull { (_, pizza) -> pizza?.place == Place.COUNTER }?.let { (_, pizza) -> return "bake" to pizza(pizza!!.name) }
    }
    plan.firstOrNull { (order, pizza) -> pizza == null && canMake(order.wanted) }?.let { (order, _) ->
        return "make_pizza" to buildJsonObject { putJsonArray("toppings") { order.wanted.forEach { add(it.word) } } }
    }
    return null
}

private fun guessedUsage(request: ChatRequest, calls: List<ToolCall>): TokenUsage =
    TokenUsage(
        request.length() / 4,
        calls.sumOf { it.length() } / 4,
    )

private fun ChatRequest.length(): Int =
    system.orEmpty().length +
        tools.sumOf { it.name.length + it.description.length + it.inputSchema.toString().length } +
        messages.sumOf { message -> message.text.length + ((message as? ChatMessage.Assistant)?.toolCalls?.sumOf { it.length() } ?: 0) }

private fun ToolCall.length(): Int = name.length + input.toString().length
