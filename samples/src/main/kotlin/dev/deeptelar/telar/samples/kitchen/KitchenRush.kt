package dev.deeptelar.telar.samples.kitchen

import dev.deeptelar.telar.GraphConfig
import dev.deeptelar.telar.GraphEvent
import dev.deeptelar.telar.TelarException
import dev.deeptelar.telar.agent.ChatMessage
import dev.deeptelar.telar.agent.ChatModel
import dev.deeptelar.telar.agent.pendingToolCalls
import dev.deeptelar.telar.samples.modelFor
import dev.deeptelar.telar.samples.scriptedModel
import io.ktor.client.HttpClient
import kotlin.time.TimeSource

/** Runs [shift]. When a model fails, the shift ends there and the result is that of the kitchen as it was. */
private suspend fun stopsWithTheModel(shift: suspend () -> Unit) {
    try {
        shift()
    } catch (e: TelarException) {
        println("The shift stopped: ${e.message}")
    }
}

/** Runs the lunch rush with one chef, prints what the chef did, and returns the shift as it ended. */
suspend fun runSoloChef(chef: ChatModel, tokenBudget: Int): Service {
    var shift = shift(lunchRush(), tokenBudget)
    println(shift.messages.single().text)
    stopsWithTheModel {
        soloChef(chef).stream(shift, GraphConfig(maxIterations = SHIFT_STEPS)).collect { event ->
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
    }
    return shift
}

/**
 * Runs the lunch rush in the kitchen with three stations, prints what each station did with the
 * minute it was done, and returns the shift as it ended.
 */
suspend fun runLine(reader: ChatModel, prep: ChatModel?, oven: ChatModel?, tokenBudget: Int): Service {
    var shift = LineShift(lunchRush(), tokenBudget)
    var printed = 0
    stopsWithTheModel {
        line(reader, prep, oven).stream(shift, GraphConfig(maxIterations = SHIFT_STEPS)).collect { event ->
            if (event !is GraphEvent.NodeCompleted || event.node != "clock") return@collect
            shift = event.state
            // Two stations work in the same minutes, so the lines of one minute are put in order here.
            shift.log
                .drop(printed)
                .sorted()
                .forEach(::println)
            printed = shift.log.size
        }
    }
    return shift
}

/**
 * Runs the lunch rush and prints how the shift went.
 *
 * KITCHEN names the kitchen: `solo` for one chef who does everything (the default), `line` for
 * three stations where a model reads the orders and rules make and bake the pizzas, and
 * `line-models` for three stations with a model at each.
 *
 * The model is the one that [modelFor] finds a key for in the environment. Without a key, scripted
 * stand-ins do the work. TOKEN_BUDGET sets another token budget than [DEFAULT_TOKEN_BUDGET].
 */
suspend fun main() {
    val client = HttpClient()
    val found = modelFor(client)
    val scripted = found === scriptedModel
    val budget = System.getenv("TOKEN_BUDGET")?.toIntOrNull() ?: DEFAULT_TOKEN_BUDGET
    val layout = System.getenv("KITCHEN")?.takeIf { it.isNotBlank() } ?: "solo"
    val started = TimeSource.Monotonic.markNow()

    val (title, shift) =
        when (layout) {
            "solo" -> "solo chef" to runSoloChef(if (scripted) scriptedChef() else found, budget)
            "line" ->
                "the line, a model reads the orders and rules do the rest" to
                    runLine(if (scripted) scriptedReader() else found, null, null, budget)
            "line-models" ->
                "the line, a model at every station" to
                    if (scripted) {
                        runLine(
                            scriptedReader(),
                            scriptedMaker(),
                            scriptedOven(),
                            budget,
                        )
                    } else {
                        runLine(found, found, found, budget)
                    }
            else -> error("KITCHEN is solo, line or line-models, not $layout.")
        }
    client.close()

    println()
    println("Lunch rush, $title" + if (scripted) " (scripted: no model was asked, and the tokens are a guess)" else "")
    println(shift.report())
    println("time     ${started.elapsedNow().inWholeSeconds} s")
}
