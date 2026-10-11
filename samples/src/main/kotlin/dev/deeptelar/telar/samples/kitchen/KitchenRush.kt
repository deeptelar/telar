package dev.deeptelar.telar.samples.kitchen

import dev.deeptelar.telar.GraphConfig
import dev.deeptelar.telar.GraphEvent
import dev.deeptelar.telar.TelarException
import dev.deeptelar.telar.agent.ChatMessage
import dev.deeptelar.telar.agent.pendingToolCalls
import dev.deeptelar.telar.samples.modelFor
import dev.deeptelar.telar.samples.scriptedModel
import io.ktor.client.HttpClient
import kotlin.time.TimeSource

/**
 * How a shift went, in the numbers that one chef is compared with another on: first the points,
 * then the tokens.
 */
fun Shift.report(): String {
    val score = kitchen.score()
    val orders =
        listOfNotNull(
            "${score.onTime} of ${score.orders} on time",
            "${score.late} late".takeIf { score.late > 0 },
            "${score.wrong} wrong".takeIf { score.wrong > 0 },
            "${score.allergy} with an allergen".takeIf { score.allergy > 0 },
            "${score.missed} not served".takeIf { score.missed > 0 },
        )
    val end =
        when {
            outOfTokens -> "out of tokens at minute ${kitchen.minute}"
            kitchen.minute >= kitchen.closesAt -> "closed at minute ${kitchen.minute}"
            else -> "every order served at minute ${kitchen.minute}"
        }
    return listOf(
        "orders   ${orders.joinToString()}",
        "points   ${score.points} of ${score.orders * Score.ON_TIME_POINTS}",
        "tokens   $spent (${tokens.inputTokens} read, ${tokens.outputTokens} written) in $answers answers",
        "kitchen  $end, ${score.wasted} ${if (score.wasted == 1) "pizza" else "pizzas"} wasted",
    ).joinToString("\n")
}

/**
 * Runs the lunch rush with one chef and prints what the chef did and how the shift went.
 *
 * The chef is the model that [modelFor] finds a key for in the environment, or [scriptedChef]
 * without one. TOKEN_BUDGET sets another token budget than [DEFAULT_TOKEN_BUDGET].
 */
suspend fun main() {
    val client = HttpClient()
    val found = modelFor(client)
    val scripted = found === scriptedModel
    val budget = System.getenv("TOKEN_BUDGET")?.toIntOrNull() ?: DEFAULT_TOKEN_BUDGET
    var shift = shift(lunchRush(), budget)
    val started = TimeSource.Monotonic.markNow()

    println(shift.messages.single().text)
    try {
        soloChef(if (scripted) scriptedChef() else found).stream(shift, GraphConfig(maxIterations = SHIFT_STEPS)).collect { event ->
            if (event !is GraphEvent.NodeCompleted) return@collect
            shift = event.state
            if (event.node == "chef") {
                val answer = shift.messages.last()
                if (answer.text.isNotBlank()) println("chef: ${answer.text.trim()}")
                shift.messages.pendingToolCalls().forEach { println("> ${it.name} ${if (it.input.isEmpty()) "" else it.input}") }
            } else {
                shift.messages.takeLastWhile { it !is ChatMessage.Assistant }.forEach { println(it.text.prependIndent("  ")) }
            }
        }
    } catch (e: TelarException) {
        println("The shift stopped: ${e.message}")
    }
    client.close()

    println()
    println("Lunch rush, solo chef" + if (scripted) " (scripted: no model was asked, and the tokens are a guess)" else "")
    println(shift.report())
    println("time     ${started.elapsedNow().inWholeSeconds} s")
}
