package dev.deeptelar.telar.agent

import dev.deeptelar.telar.GraphValidationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

/**
 * Sends [prompt] as a single user message and returns the answer as an instance of the
 * `@Serializable` class [Answer], for a node that needs a value from the model and not a text.
 *
 * ```kotlin
 * @Serializable
 * data class Verdict(
 *     val category: Category,
 *     @Description("One sentence that says why") val reason: String,
 *     val urgent: Boolean = false,
 * )
 *
 * node("classify", work = { email -> model.ask<Verdict>("Classify this support email: ${email.body}") }) { email, verdict ->
 *     email.copy(category = verdict.category, urgent = verdict.urgent)
 * }
 * ```
 *
 * The model is told to reply with JSON that matches the schema of [Answer], which is built from the
 * class as the input schema of a [Tool] is: the text of a [Description] describes a property, and a
 * property is required unless it has a default value or is nullable. This works with every
 * [ChatModel], because it only needs the model to follow the instruction.
 *
 * An answer that cannot be read is sent back to the model with what was wrong, and the model is
 * asked again, until it was asked [attempts] times. That covers an answer that is not JSON, a
 * property that is missing or has the wrong type, and a `require` in the `init` of [Answer] that
 * fails, so a `require` is a way to check an answer: its message goes to the model.
 *
 * In a streamed run, the model's JSON is not reported as progress.
 *
 * @param prompt what the model is asked.
 * @param system instructions for the model.
 * @param attempts how often the model is asked at most.
 * @throws ChatModelException when a call fails, when the answer was cut off at the model's output
 * limit, or when the model gave no answer that could be read in [attempts] attempts.
 * @throws GraphValidationException if [Answer] is not a class or has a property of a type that
 * cannot be described, or [attempts] is less than 1.
 */
public suspend inline fun <reified Answer> ChatModel.ask(prompt: String, system: String? = null, attempts: Int = 2): Answer =
    ask(serializer<Answer>(), ChatRequest(listOf(ChatMessage.User(prompt)), system), attempts).value

/**
 * Returns the model's next message for [request] as an instance of the `@Serializable` class
 * [Answer], with the message it was read from. Use it to ask for a value at the end of a
 * conversation. See the overload with a prompt for how the model is asked.
 *
 * ```kotlin
 * val response = model.ask<Verdict>(ChatRequest(ticket.messages + ChatMessage.User("What is your verdict?")))
 * ```
 *
 * @throws ChatModelException when a call fails, when the answer was cut off at the model's output
 * limit, or when the model gave no answer that could be read in [attempts] attempts.
 * @throws GraphValidationException if [Answer] is not a class or has a property of a type that
 * cannot be described, [request] has tools, or [attempts] is less than 1.
 */
public suspend inline fun <reified Answer> ChatModel.ask(request: ChatRequest, attempts: Int = 2): TypedResponse<Answer> =
    ask(serializer<Answer>(), request, attempts)

/** Returns the model's next message for [request] as a value read with [serializer]. See the overload with a reified type. */
public suspend fun <Answer> ChatModel.ask(serializer: KSerializer<Answer>, request: ChatRequest, attempts: Int = 2): TypedResponse<Answer> {
    if (attempts < 1) throw GraphValidationException("A model must be asked at least once, but attempts was $attempts.")
    if (request.tools.isNotEmpty()) {
        throw GraphValidationException(
            "A request for a typed answer cannot have tools. Run the tools with toolLoop, and ask for the typed answer after it.",
        )
    }
    val schema = answerSchema(serializer.descriptor)
    val system = listOfNotNull(request.system, "$ANSWER_INSTRUCTIONS\n$schema").joinToString("\n\n")

    var messages = request.messages
    var usage: TokenUsage? = null
    var problem = ""
    repeat(attempts) {
        val response = chat(request.copy(messages = messages, system = system))
        usage = usage + response.usage
        if (response.message.truncated) {
            throw ChatModelException("The model reached its output limit before it finished its answer. Raise the limit.")
        }
        try {
            val json = jsonObjectIn(response.message.text) ?: throw SerializationException("It is not a JSON object.")
            return TypedResponse(modelJson.decodeFromJsonElement(serializer, json), response.message, usage)
        } catch (e: IllegalArgumentException) {
            // A SerializationException is one, and so is the failure of a `require` in the class.
            problem = e.message ?: "It does not match the schema."
        }
        messages = messages + response.message + ChatMessage.User("Your answer could not be read. $problem\n$ANSWER_AGAIN")
    }
    throw ChatModelException("The model gave no answer that could be read after $attempts attempts. The last one: $problem")
}

/**
 * What [ask] returns for a [ChatRequest].
 *
 * @property value the model's answer.
 * @property message the message [value] was read from. Add it to the conversation to continue it.
 * @property usage the tokens of every call it took, also of those whose answer could not be read,
 * when the provider reports them.
 */
public data class TypedResponse<out Answer>(
    val value: Answer,
    val message: ChatMessage.Assistant,
    val usage: TokenUsage? = null,
)

/**
 * Returns the JSON object in [text], also when the model wrapped it in a code block or in a
 * sentence, and `null` when there is none.
 */
internal fun jsonObjectIn(text: String): JsonObject? {
    val start = text.indexOf('{')
    val end = text.lastIndexOf('}')
    if (start !in 0..<end) return null
    return try {
        Json.parseToJsonElement(text.substring(start, end + 1)) as? JsonObject
    } catch (_: SerializationException) {
        null
    }
}

private operator fun TokenUsage?.plus(other: TokenUsage?): TokenUsage? =
    when {
        this == null -> other
        other == null -> this
        else -> TokenUsage(inputTokens + other.inputTokens, outputTokens + other.outputTokens)
    }

private const val ANSWER_INSTRUCTIONS =
    "Reply with one JSON object and nothing else: no text before or after it, and no code block. " +
        "The object must match this JSON Schema:"
private const val ANSWER_AGAIN = "Reply again with one JSON object that matches the schema, and nothing else."
