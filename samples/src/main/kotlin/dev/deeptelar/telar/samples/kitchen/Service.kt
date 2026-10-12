package dev.deeptelar.telar.samples.kitchen

import dev.deeptelar.telar.agent.TokenUsage

/**
 * What a station of a kitchen cost so far, and when its chef has time again.
 *
 * @property tokens the tokens of all [answers]. An answer whose provider reports no usage counts as zero.
 * @property answers how often the station asked its model.
 * @property busyUntil the minute its chef is done with what it is doing.
 */
data class Desk(
    val tokens: TokenUsage = TokenUsage(0, 0),
    val answers: Int = 0,
    val busyUntil: Int = 0,
) {
    /** This desk after one more answer of its model that used [usage]. */
    fun answered(usage: TokenUsage?): Desk = copy(tokens = tokens + usage, answers = answers + 1)
}

/**
 * A shift in a kitchen whose chefs are models, whatever the kitchen is built like: what every layout
 * is measured on.
 *
 * @property tokenBudget the shift ends when its chefs have used this many tokens together.
 * @property desks each station of the kitchen by its name.
 */
interface Service {
    val kitchen: Kitchen
    val tokenBudget: Int
    val desks: Map<String, Desk>

    val tokens: TokenUsage get() = desks.values.fold(TokenUsage(0, 0)) { sum, desk -> sum + desk.tokens }

    val answers: Int get() = desks.values.sumOf { it.answers }

    val spent: Int get() = tokens.inputTokens + tokens.outputTokens

    val outOfTokens: Boolean get() = spent >= tokenBudget

    val over: Boolean get() = kitchen.over || outOfTokens
}

const val DEFAULT_TOKEN_BUDGET: Int = 300_000

/**
 * The steps a shift may take: `GraphConfig.maxIterations` for a run of a kitchen. A minute of a
 * shift is at most three steps of its graph.
 */
const val SHIFT_STEPS: Int = 400

/**
 * How a shift went, in the numbers that one kitchen is compared with another on: first the points,
 * then the tokens.
 */
fun Service.report(): String {
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
            kitchen.standing.none { it.outcome == Outcome.OPEN } -> "every order served at minute ${kitchen.minute}"
            kitchen.minute >= kitchen.closesAt -> "closed at minute ${kitchen.minute}"
            outOfTokens -> "out of tokens at minute ${kitchen.minute}"
            else -> "stopped at minute ${kitchen.minute}"
        }
    return listOfNotNull(
        "orders   ${orders.joinToString()}",
        "points   ${score.points} of ${score.orders * Score.ON_TIME_POINTS}",
        "tokens   $spent (${tokens.inputTokens} read, ${tokens.outputTokens} written) in $answers answers",
        desks.entries
            .joinToString(prefix = "stations ") { (name, desk) ->
                "$name ${desk.tokens.inputTokens + desk.tokens.outputTokens} tokens in ${desk.answers} answers"
            }.takeIf { desks.size > 1 },
        "kitchen  $end, ${score.wasted} ${if (score.wasted == 1) "pizza" else "pizzas"} wasted",
    ).joinToString("\n")
}
