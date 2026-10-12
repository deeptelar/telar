package dev.deeptelar.telar.agent

import dev.deeptelar.telar.GraphValidationException
import dev.deeptelar.telar.START
import dev.deeptelar.telar.StateGraph
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

enum class Desk { REFUND, TECHNICAL }

@Serializable
data class Verdict(
    val desk: Desk,
    @Description("One sentence that says why") val reason: String,
    val urgent: Boolean = false,
)

@Serializable
data class Rating(
    val stars: Int,
) {
    init {
        require(stars in 1..5) { "stars must be from 1 to 5, but was $stars." }
    }
}

class TypedAnswerTest {
    private val refund = """{"desk": "REFUND", "reason": "The customer was charged twice."}"""

    @Test
    fun `ask returns the answer of the model as an instance of the class`() =
        runTest {
            val model = ScriptedModel(says("""{"desk": "TECHNICAL", "reason": "The app shows an error.", "urgent": true}"""))

            val verdict = model.ask<Verdict>("Classify: The app shows an error when I log in.")

            assertEquals(Verdict(Desk.TECHNICAL, "The app shows an error.", urgent = true), verdict)
            assertEquals(listOf(ChatMessage.User("Classify: The app shows an error when I log in.")), model.requests.single().messages)
        }

    @Test
    fun `the system prompt holds the instructions of the app and then the schema of the class`() =
        runTest {
            val model = ScriptedModel(says(refund))

            model.ask<Verdict>("Classify: I was charged twice.", system = "You sort the emails of a pizzeria.")

            val system = model.requests.single().system!!
            assertTrue(system.startsWith("You sort the emails of a pizzeria.\n\nReply with one JSON object"))
            val schema = Json.parseToJsonElement(system.substringAfter("JSON Schema:\n")) as JsonObject
            val properties = schema.getValue("properties").jsonObject
            assertEquals(setOf("desk", "reason", "urgent"), properties.keys)
            assertEquals(
                listOf("REFUND", "TECHNICAL"),
                properties.getValue("desk").jsonObject.getValue("enum").jsonArray.map {
                    it.jsonPrimitive.content
                },
            )
            assertEquals(
                "One sentence that says why",
                properties
                    .getValue("reason")
                    .jsonObject
                    .getValue("description")
                    .jsonPrimitive.content,
            )
            assertEquals(listOf("desk", "reason"), schema.getValue("required").jsonArray.map { it.jsonPrimitive.content })
        }

    @Test
    fun `without instructions of the app the system prompt is the schema alone`() =
        runTest {
            val model = ScriptedModel(says(refund))

            model.ask<Verdict>("Classify: I was charged twice.")

            assertTrue(
                model.requests
                    .single()
                    .system!!
                    .startsWith("Reply with one JSON object"),
            )
        }

    @Test
    fun `an answer in a code block or in a sentence is read`() =
        runTest {
            val inBlock = ScriptedModel(says("Here is the verdict:\n```json\n$refund\n```\nI hope it helps."))

            assertEquals(Desk.REFUND, inBlock.ask<Verdict>("Classify.").desk)
        }

    @Test
    fun `a property the model left out gets its default and one the class does not have is dropped`() =
        runTest {
            val model = ScriptedModel(says("""{"desk": "REFUND", "reason": "Charged twice.", "mood": "angry"}"""))

            assertEquals(Verdict(Desk.REFUND, "Charged twice.", urgent = false), model.ask<Verdict>("Classify."))
        }

    @Test
    fun `an answer that is not JSON goes back to the model and the model is asked again`() =
        runTest {
            val model = ScriptedModel(says("That is a refund."), says(refund))

            val verdict = model.ask<Verdict>("Classify: I was charged twice.")

            assertEquals(Desk.REFUND, verdict.desk)
            val (first, second) = model.requests
            assertEquals(first.system, second.system)
            assertEquals(3, second.messages.size)
            assertEquals(first.messages.single(), second.messages[0])
            assertEquals(ChatMessage.Assistant("That is a refund."), second.messages[1])
            val correction = assertIs<ChatMessage.User>(second.messages[2]).text
            assertContains(correction, "It is not a JSON object.")
            assertContains(correction, "Reply again with one JSON object")
        }

    @Test
    fun `an answer that does not fit the class goes back with what was wrong`() =
        runTest {
            val model = ScriptedModel(says("""{"desk": "BILLING", "reason": "Charged twice."}"""), says(refund))

            assertEquals(Desk.REFUND, model.ask<Verdict>("Classify.").desk)
            assertContains(
                model.requests[1]
                    .messages
                    .last()
                    .text,
                "BILLING",
            )
        }

    @Test
    fun `a require of the class that fails goes back with its message`() =
        runTest {
            val model = ScriptedModel(says("""{"stars": 9}"""), says("""{"stars": 5}"""))

            assertEquals(Rating(5), model.ask<Rating>("Rate this pizza."))
            assertContains(
                model.requests[1]
                    .messages
                    .last()
                    .text,
                "stars must be from 1 to 5, but was 9.",
            )
        }

    @Test
    fun `ask fails when the model was asked as often as allowed and gave no answer that can be read`() =
        runTest {
            val model = ScriptedModel(says("A refund."), says("""{"stars": 0}"""), says("""{"stars": 6}"""), says("""{"stars": 3}"""))

            val failure = assertFailsWith<ChatModelException> { model.ask<Rating>("Rate this pizza.", attempts = 3) }

            assertEquals(3, model.requests.size)
            assertContains(failure.message!!, "after 3 attempts")
            assertContains(failure.message!!, "stars must be from 1 to 5, but was 6.")
        }

    @Test
    fun `with one attempt the model is not asked again`() =
        runTest {
            val model = ScriptedModel(says("A refund."), says(refund))

            assertFailsWith<ChatModelException> { model.ask<Verdict>("Classify.", attempts = 1) }
            assertEquals(1, model.requests.size)
        }

    @Test
    fun `an answer that was cut off fails and the model is not asked again`() =
        runTest {
            val model = ScriptedModel(says("""{"desk": "REFUND", "reason": "The cust""").cutOff(), says(refund))

            val failure = assertFailsWith<ChatModelException> { model.ask<Verdict>("Classify.") }

            assertContains(failure.message!!, "output limit")
            assertEquals(1, model.requests.size)
        }

    @Test
    fun `a call that fails is not tried again`() =
        runTest {
            var calls = 0
            val model =
                ChatModel {
                    calls++
                    throw ChatModelException("The API is down.")
                }

            assertEquals("The API is down.", assertFailsWith<ChatModelException> { model.ask<Verdict>("Classify.") }.message)
            assertEquals(1, calls)
        }

    @Test
    fun `ask with a request keeps its conversation and returns the message of the answer`() =
        runTest {
            val answer = ChatResponse(ChatMessage.Assistant(refund), TokenUsage(inputTokens = 40, outputTokens = 12))
            val model = ScriptedModel(answer)
            val conversation =
                listOf(ChatMessage.User("I was charged twice."), ChatMessage.Assistant("I am sorry."), ChatMessage.User("Verdict?"))

            val response = model.ask<Verdict>(ChatRequest(conversation, system = "You sort emails."))

            assertEquals(Desk.REFUND, response.value.desk)
            assertSame(answer.message, response.message)
            assertEquals(TokenUsage(40, 12), response.usage)
            assertEquals(conversation, model.requests.single().messages)
        }

    @Test
    fun `the usage is the sum of every call`() =
        runTest {
            val model =
                ScriptedModel(
                    ChatResponse(ChatMessage.Assistant("A refund."), TokenUsage(inputTokens = 40, outputTokens = 3)),
                    ChatResponse(ChatMessage.Assistant(refund), TokenUsage(inputTokens = 60, outputTokens = 12)),
                )

            assertEquals(TokenUsage(100, 15), model.ask<Verdict>(ChatRequest(listOf(ChatMessage.User("Classify.")))).usage)
        }

    @Test
    fun `the usage is null when the model reports none`() =
        runTest {
            assertNull(ScriptedModel(says(refund)).ask<Verdict>(ChatRequest(listOf(ChatMessage.User("Classify.")))).usage)
        }

    @Test
    fun `a request with tools is rejected`() =
        runTest {
            val model = ScriptedModel(says(refund))
            val tool = Tool<Unit>("now", "Returns the time.") { "12:00" }
            val request = ChatRequest(listOf(ChatMessage.User("Classify.")), tools = listOf(tool.spec))

            val failure = assertFailsWith<GraphValidationException> { model.ask<Verdict>(request) }

            assertContains(failure.message!!, "cannot have tools")
            assertTrue(model.requests.isEmpty())
        }

    @Test
    fun `less than one attempt is rejected`() =
        runTest {
            assertFailsWith<GraphValidationException> { ScriptedModel(says(refund)).ask<Verdict>("Classify.", attempts = 0) }
        }

    @Test
    fun `a type that is not a class is rejected before the model is asked`() =
        runTest {
            val model = ScriptedModel(says("\"REFUND\""))

            val failure = assertFailsWith<GraphValidationException> { model.ask<String>("Classify.") }

            assertContains(failure.message!!, "A typed answer must be a @Serializable class")
            assertTrue(model.requests.isEmpty())
        }

    @Test
    fun `a class that contains itself is rejected and the error names the answer`() =
        runTest {
            val failure = assertFailsWith<GraphValidationException> { ScriptedModel().ask<Folder>("List the folders.") }

            assertContains(failure.message!!, "A typed answer cannot have a class that contains itself")
        }

    @Test
    fun `a node asks for a typed answer and writes it into the state`() =
        runTest {
            data class Email(
                val body: String,
                val desk: Desk? = null,
            )
            val model = ScriptedModel(says(refund))
            val graph =
                StateGraph<Email> {
                    val classify =
                        node("classify", work = { email -> model.ask<Verdict>("Classify: ${email.body}") }) { email, verdict ->
                            email.copy(desk = verdict.desk)
                        }
                    START then classify
                }.compile()

            assertEquals(Desk.REFUND, graph.invoke(Email("I was charged twice.")).state.desk)
        }
}
