package dev.deeptelar.telar.agent

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * A [DecisionModel] that asks a [ChatModel], so that `choose`, `isYes`, `score` and `decisionEdge`
 * work with the model an app already has: Claude, OpenAI, Gemini, Ollama, or any other.
 *
 * ```kotlin
 * val decider = ChatDecisionModel(AnthropicChatModel(client, apiKey = key, model = "claude-haiku-5-5"))
 *
 * decisionEdge(from = START, model = decider, instructions = "What does the customer want?", routes = routes, minConfidence = 0.6, fallback = escalate) { it.body }
 * ```
 *
 * Each call to [decide] is one call to the chat model, however many questions the request has. The
 * model is asked how likely it finds each option, and the option it finds most likely is the answer.
 *
 * - **The probabilities are the model's own estimate.** A chat model is not built to measure them,
 *   so they are rougher than those of a decision model such as Jev (`telar-typesafe`), and a
 *   `confidence` of 0.9 is a guess, not a measurement. Try limits such as `minConfidence` on your
 *   own data before you rely on them.
 * - **`confidence` is computed as Jev computes it**: how far the probability of the most likely
 *   option is above an even split, `(highest - 1/n) / (1 - 1/n)` for `n` options. It is 0 when every
 *   option is as likely and 1 when the model is sure, so a limit means the same with both models.
 * - **A small, fast model is usually enough.** Picking an option is easier than writing an answer.
 *
 * A failed call to the chat model, an answer cut off at its output limit, and an answer that is not
 * the JSON it was asked for throw a [DecisionModelException]; for a failed call, the
 * [ChatModelException] is its `cause`.
 *
 * @param model the chat model that decides.
 */
public class ChatDecisionModel(
    private val model: ChatModel,
) : DecisionModel {
    override suspend fun decide(request: DecisionRequest): DecisionResponse {
        val labels = request.questions.mapValues { (_, question) -> question.labels() }
        val response =
            try {
                model.chat(ChatRequest(listOf(ChatMessage.User(prompt(request, labels))), SYSTEM))
            } catch (e: ChatModelException) {
                throw DecisionModelException("The chat model could not decide: ${e.message}", e)
            }
        if (response.message.truncated) {
            throw DecisionModelException("The chat model reached its output limit before it finished its decision.")
        }
        val reply = parse(response.message.text)
        val answers =
            request.questions.mapValues { (name, question) ->
                answer(question, probabilities(name, labels.getValue(name), reply[name]))
            }
        return DecisionResponse(answers, response.usage)
    }

    private fun prompt(request: DecisionRequest, labels: Map<String, Map<String, String?>>): String {
        val state = request.state
        val text = if (state is JsonPrimitive && state.isString) state.content else state.toString()
        val questions =
            buildJsonObject {
                request.questions.forEach { (name, question) ->
                    putJsonObject(name) {
                        put("instructions", question.instructions)
                        putJsonObject("options") {
                            labels.getValue(name).forEach { (label, description) -> put(label, description) }
                        }
                    }
                }
            }
        return "State:\n$text\n\nQuestions:\n$questions"
    }

    private fun parse(text: String): JsonObject =
        jsonObjectIn(text)
            ?: throw DecisionModelException("The chat model did not answer with a JSON object: ${text.take(REPLY_EXCERPT)}")

    /** The probability of each label of a question, adding up to 1, in the order of [labels]. */
    private fun probabilities(name: String, labels: Map<String, String?>, reply: JsonElement?): List<Double> {
        val given = reply as? JsonObject ?: throw DecisionModelException("The chat model did not answer the question '$name'.")
        val values =
            labels.keys.map { label ->
                val value = given[label] ?: return@map 0.0
                val number = (value as? JsonPrimitive)?.doubleOrNull
                if (number == null || !number.isFinite() || number < 0.0) {
                    throw DecisionModelException("The chat model gave '$value' as the probability of '$label' in '$name'.")
                }
                number
            }
        val total = values.sum()
        if (total <= 0.0) {
            throw DecisionModelException("The chat model gave no probability to any option of '$name' (${labels.keys}): $given")
        }
        return values.map { it / total }
    }

    private fun answer(question: Question, probabilities: List<Double>): Answer {
        val best = probabilities.indices.maxBy { probabilities[it] }
        val confidence = confidence(probabilities[best], probabilities.size)
        return when (question) {
            is Question.Choice -> {
                val options = question.options.keys.toList()
                Answer.Choice(options[best], options.zip(probabilities).toMap(), confidence)
            }
            is Question.YesNo -> Answer.YesNo(probabilities[0])
            is Question.Score -> Answer.Score(probabilities.withIndex().sumOf { (level, p) -> level * p }, probabilities, confidence)
        }
    }

    /** How far [highest], the probability of the most likely of [count] options, is above an even split, from 0 to 1. */
    private fun confidence(highest: Double, count: Int): Double {
        if (count == 1) return 1.0
        val even = 1.0 / count
        return ((highest - even) / (1 - even)).coerceIn(0.0, 1.0)
    }

    private fun Question.labels(): Map<String, String?> =
        when (this) {
            is Question.Choice -> options
            is Question.YesNo -> mapOf(YES to yes, NO to no)
            is Question.Score -> levels.withIndex().associate { (level, description) -> level.toString() to description }
        }
}

private const val YES = "yes"
private const val NO = "no"
private const val REPLY_EXCERPT = 200
private const val SYSTEM =
    "You make decisions about a state. For each question, judge the state as the instructions say, and estimate " +
        "how likely each of its options is to be the right one, from what the state says. An option may come with " +
        "a description of when it applies.\n\n" +
        "Reply with one JSON object and nothing else. It has the name of each question as a key, and as its value " +
        "an object with each option of that question as a key and its probability, a number from 0 to 1, as the " +
        "value. The probabilities of one question add up to 1. For example: " +
        "{\"team\": {\"refund\": 0.85, \"technical\": 0.15}, \"urgent\": {\"yes\": 0.2, \"no\": 0.8}}"
