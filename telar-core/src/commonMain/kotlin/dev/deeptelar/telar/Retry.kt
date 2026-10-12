package dev.deeptelar.telar

import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Says how often a node is tried again after it failed, and how long the engine waits between two
 * tries. Give it to a node as `retry`:
 *
 * ```kotlin
 * val fetch = node("fetch", retry = Retry(attempts = 3)) { order ->
 *     order.copy(stock = warehouse.stock(order.item))
 * }
 * ```
 *
 * A node that fails runs again on the same state, in the same step, until it succeeds or has run
 * [attempts] times. Then the run fails with a [NodeExecutionException], as it does without a
 * `retry`. The engine waits [pause] before the second try, and [factor] times longer before each
 * try after it, but never longer than [maxPause]: with the defaults 1 second, then 2, then 4.
 *
 * Every failure of the node is retried, also a time limit of the node that ran out, with one
 * exception: a [GraphValidationException] is not, because a second try would fail in the same way.
 * To retry only some failures, pass [retryOn]:
 *
 * ```kotlin
 * Retry(attempts = 3) { failure -> failure is IOException }
 * ```
 *
 * What is no failure of a node is never retried: a cancelled run, a pause with [interrupt], and an
 * error of the program such as an `AssertionError`.
 *
 * A node with a `retry` runs more than once, so make its side effects safe to repeat.
 *
 * @property attempts how often the node runs at most, with its first try.
 * @property pause how long the engine waits before the second try.
 * @property factor what the pause is multiplied by after each try.
 * @property maxPause the longest pause between two tries.
 * @property retryOn returns `true` for a failure that is worth another try. It gets what the node
 * threw.
 * @throws GraphValidationException if [attempts] is less than 1, [pause] or [maxPause] is negative,
 * or [factor] is less than 1.
 */
public class Retry(
    public val attempts: Int = 3,
    public val pause: Duration = 1.seconds,
    public val factor: Double = 2.0,
    public val maxPause: Duration = 30.seconds,
    public val retryOn: (Throwable) -> Boolean = { true },
) {
    init {
        if (attempts < 1) throw GraphValidationException("A node must run at least once, but attempts was $attempts.")
        if (pause.isNegative()) throw GraphValidationException("The pause between two tries must not be negative, but was $pause.")
        if (maxPause.isNegative()) {
            throw GraphValidationException(
                "The longest pause between two tries must not be negative, but was $maxPause.",
            )
        }
        // The comparison is written this way round so that it also rejects NaN.
        if (!(factor >= 1.0)) throw GraphValidationException("The factor of the pause must be at least 1, but was $factor.")
    }

    /**
     * Returns how long to wait before the next try, after the try number [attempt] failed with
     * [failure], or `null` when the node must not run again.
     */
    internal fun pauseAfter(attempt: Int, failure: Throwable): Duration? {
        if (attempt >= attempts || failure is GraphValidationException || !retryOn(failure)) return null
        // Nothing times anything is nothing, also when the factor has grown beyond what a Double holds.
        return if (pause == Duration.ZERO) pause else minOf(pause * factor.pow(attempt - 1), maxPause)
    }

    override fun toString(): String = "Retry(attempts=$attempts, pause=$pause, factor=$factor, maxPause=$maxPause)"
}
