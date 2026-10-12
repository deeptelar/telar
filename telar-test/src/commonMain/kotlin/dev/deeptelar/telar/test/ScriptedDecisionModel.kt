package dev.deeptelar.telar.test

import dev.deeptelar.telar.agent.Answer
import dev.deeptelar.telar.agent.DecisionModel
import dev.deeptelar.telar.agent.DecisionModelException
import dev.deeptelar.telar.agent.DecisionRequest
import dev.deeptelar.telar.agent.DecisionResponse
import dev.deeptelar.telar.agent.Question
import dev.deeptelar.telar.agent.TokenUsage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A [DecisionModel] for tests: it gives the decisions of its script, in the order they were
 * written, and keeps every request it received. A graph that is routed by a model is tested with it
 * without an API key.
 *
 * ```kotlin
 * val model = ScriptedDecisionModel {
 *     chooses("refund")   // the answer to the first request
 *     no()                // the answer to the second one
 * }
 *
 * val state = routedSupport(model).invoke(SupportEmail(body = "I want my money back.")).state
 *
 * assertEquals("refund", state.team)
 * ```
 *
 * See [DecisionScript] for what a script can hold.
 *
 * A decision of the script answers every question of its request. A model that is asked for more
 * decisions than its script has, or for a decision of another kind than the script wrote, throws an
 * `AssertionError` that says what it was asked. The engine passes an `AssertionError` on as it is,
 * so the test fails with that message.
 *
 * @param script the decisions of the model, in order.
 */
public class ScriptedDecisionModel(
    script: DecisionScript.() -> Unit,
) : DecisionModel {
    private val steps: List<suspend (DecisionRequest) -> DecisionResponse> = DecisionScript().apply(script).steps.toList()
    private val received = mutableListOf<DecisionRequest>()
    private val lock = Mutex()

    /** Every request the model received so far, oldest first, also those of a call that failed. */
    public val requests: List<DecisionRequest> get() = received.toList()

    /** How many decisions of the script the model has not given yet. 0 when the whole script was used. */
    public val remaining: Int get() = (steps.size - received.size).coerceAtLeast(0)

    override suspend fun decide(request: DecisionRequest): DecisionResponse {
        val step =
            lock.withLock {
                received += request
                steps.getOrNull(received.size - 1)
                    ?: throw AssertionError(
                        "The script of this model has ${steps.size} decisions, but the model was asked ${received.size} times. " +
                            "The questions it was asked last: ${request.questions.values.map { it.instructions }}",
                    )
            }
        return step(request)
    }
}

/**
 * The script of a [ScriptedDecisionModel]: each call adds the decision for one request, in order.
 *
 * ```kotlin
 * ScriptedDecisionModel {
 *     chooses("refund")                       // picks an option
 *     chooses("technical", confidence = 0.3)  // picks one, and is not sure
 *     yes()
 *     no()
 *     scores(2.0)                             // rates on a scale
 *     fails("API error 429: slow down")       // a call that throws
 *     decides { request -> DecisionResponse(request.questions.mapValues { Answer.YesNo(1.0) }) }
 * }
 * ```
 */
public class DecisionScript internal constructor() {
    internal val steps = mutableListOf<suspend (DecisionRequest) -> DecisionResponse>()

    /**
     * The model picks [option].
     *
     * @param confidence how clearly the option won, from 0 to 1, to test what a graph does with a
     * decision the model is not sure of.
     * @param usage the tokens the provider reports for this decision.
     */
    public fun chooses(option: String, confidence: Double = 1.0, usage: TokenUsage? = null) {
        answers<Question.Choice>("a choice", Answer.Choice(option, confidence = confidence), usage)
    }

    /**
     * The model answers yes.
     *
     * @param probability how likely the model finds a yes, from 0 to 1.
     * @param usage the tokens the provider reports for this decision.
     */
    public fun yes(probability: Double = 1.0, usage: TokenUsage? = null) {
        answers<Question.YesNo>("yes or no", Answer.YesNo(probability), usage)
    }

    /**
     * The model answers no.
     *
     * @param probability how likely the model finds a yes, from 0 to 1.
     * @param usage the tokens the provider reports for this decision.
     */
    public fun no(probability: Double = 0.0, usage: TokenUsage? = null) {
        answers<Question.YesNo>("yes or no", Answer.YesNo(probability), usage)
    }

    /**
     * The model rates the state with [score]. Levels are counted from 0, the lowest.
     *
     * @param confidence how clearly one level won, from 0 to 1.
     * @param usage the tokens the provider reports for this decision.
     */
    public fun scores(score: Double, confidence: Double = 1.0, usage: TokenUsage? = null) {
        answers<Question.Score>("a score", Answer.Score(score, confidence = confidence), usage)
    }

    /** The call to the model fails with a [DecisionModelException] that has [message]. */
    public fun fails(message: String) {
        fails(DecisionModelException(message))
    }

    /** The call to the model throws [failure]. */
    public fun fails(failure: Throwable) {
        steps += { throw failure }
    }

    /**
     * The model answers with what [decision] returns for the request, for a request with several
     * questions or a decision that depends on what was asked.
     */
    public fun decides(decision: suspend (DecisionRequest) -> DecisionResponse) {
        steps += decision
    }

    /** Adds [answer] for every question of a request, which all have to be of the kind [Kind]. */
    private inline fun <reified Kind : Question> answers(kind: String, answer: Answer, usage: TokenUsage?) {
        steps += { request ->
            request.questions.values.firstOrNull { it !is Kind }?.let { other ->
                throw AssertionError(
                    "The script answers with $kind, but the model was asked a question of another kind: " +
                        "${other::class.simpleName}(${other.instructions})",
                )
            }
            DecisionResponse(request.questions.mapValues { answer }, usage)
        }
    }
}
