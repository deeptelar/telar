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
        ChatResponse(ChatMessage.Assistant(toolCalls = calls), TokenUsage(request.length() / 4, calls.sumOf { it.length() } / 4))
    }
}

/** The answers of the scripted chef for a whole shift in [start], one list of tool calls for each answer. */
suspend fun rehearse(start: Kitchen, patience: Int = 2): List<List<ToolCall>> {
    var kitchen = start.opened().told()
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
        kitchen = counter.kitchen.told()
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
    val open = orders.filter { it.outcome == Outcome.OPEN }.map { it.number }
    // Give each open order, the one due first before the others, a pizza that is already made.
    val free = pizzas.filter { it.place != Place.SERVED && (it.baked == null || it.good) }.toMutableList()
    val plan =
        known.rail.filter { it.number in open }.sortedBy { it.dueAt }.map { order ->
            val pizza = free.filter { it.has(order.wanted) }.minByOrNull { STAGE.indexOf(it.place) }
            free.remove(pizza)
            order to pizza
        }

    fun pizza(name: String) = buildJsonObject { put("pizza", name) }

    plan.firstOrNull { (_, pizza) -> pizza?.place == Place.PASS }?.let { (order, pizza) ->
        return "serve" to
            buildJsonObject {
                put("order", order.number)
                put("pizza", pizza!!.name)
            }
    }
    oven.firstOrNull { minute - it.inOvenSince!! >= Kitchen.BAKED_AFTER }?.let { return "take_out" to pizza(it.name) }
    if (oven.size < Kitchen.OVEN_SLOTS) {
        plan.firstOrNull { (_, pizza) -> pizza?.place == Place.COUNTER }?.let { (_, pizza) -> return "bake" to pizza(pizza!!.name) }
    }
    plan.firstOrNull { (order, pizza) -> pizza == null && canMake(order.wanted) }?.let { (order, _) ->
        return "make_pizza" to buildJsonObject { putJsonArray("toppings") { order.wanted.forEach { add(it.word) } } }
    }
    return null
}

/** The nearer a pizza is to the table, the sooner it is given to an order. */
private val STAGE = listOf(Place.PASS, Place.OVEN, Place.COUNTER)

private fun Kitchen.canMake(toppings: List<Topping>): Boolean =
    dough > 0 && toppings.groupingBy { it }.eachCount().all { (topping, count) -> (stock[topping] ?: 0) >= count }

private fun ChatRequest.length(): Int =
    system.orEmpty().length +
        tools.sumOf { it.name.length + it.description.length + it.inputSchema.toString().length } +
        messages.sumOf { message -> message.text.length + ((message as? ChatMessage.Assistant)?.toolCalls?.sumOf { it.length() } ?: 0) }

private fun ToolCall.length(): Int = name.length + input.toString().length
