package dev.deeptelar.telar.test

import dev.deeptelar.telar.START
import dev.deeptelar.telar.StateGraph
import dev.deeptelar.telar.agent.Answer
import dev.deeptelar.telar.agent.DecisionModelException
import dev.deeptelar.telar.agent.DecisionRequest
import dev.deeptelar.telar.agent.DecisionResponse
import dev.deeptelar.telar.agent.Question
import dev.deeptelar.telar.agent.TokenUsage
import dev.deeptelar.telar.agent.choose
import dev.deeptelar.telar.agent.decisionEdge
import dev.deeptelar.telar.agent.isYes
import dev.deeptelar.telar.agent.score
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

data class Mail(
    val body: String,
    val team: String = "",
)

class ScriptedDecisionModelTest {
    private val teams = mapOf("refund" to "Money back", "technical" to "Something does not work")

    private fun support(model: ScriptedDecisionModel) =
        StateGraph<Mail> {
            val read = node("read") { it }
            val refund = node("refund") { it.copy(team = "refund") }
            val technical = node("technical") { it.copy(team = "technical") }
            val person = node("person") { it.copy(team = "person") }
            START then read
            decisionEdge(
                read,
                model,
                "What does the customer want?",
                routes = mapOf(refund to "Money back", technical to "Something does not work"),
                minConfidence = 0.6,
                fallback = person,
            ) { it.body }
        }.compile()

    @Test
    fun `a scripted model routes a graph and keeps what it was asked`() =
        runTest {
            val model =
                ScriptedDecisionModel {
                    chooses("refund")
                    chooses("technical", confidence = 0.3)
                }
            val graph = support(model)

            assertEquals("refund", graph.invoke(Mail("I want my money back.")).state.team)
            // The model was not sure, so the graph took the fallback.
            assertEquals("person", graph.invoke(Mail("Hm.")).state.team)

            assertEquals(2, model.requests.size)
            val question =
                model.requests
                    .first()
                    .questions.values
                    .single()
            assertEquals("What does the customer want?", question.instructions)
            assertEquals(0, model.remaining)
        }

    @Test
    fun `a scripted model answers each kind of question`() =
        runTest {
            val model =
                ScriptedDecisionModel {
                    chooses("technical", usage = TokenUsage(20, 1))
                    yes()
                    no()
                    yes(probability = 0.7)
                    scores(2.0, confidence = 0.4)
                }

            assertEquals(Answer.Choice("technical"), model.choose("The app crashes.", "Which team?", teams))
            assertTrue(model.isYes("Help!", "Is this urgent?").isYes)
            assertFalse(model.isYes("Thanks.", "Is this urgent?").isYes)
            assertEquals(0.7, model.isYes("Soon, please.", "Is this urgent?").probability)
            assertEquals(
                Answer.Score(2.0, confidence = 0.4),
                model.score("WHERE IS IT", "How angry?", listOf("Calm", "Annoyed", "Furious")),
            )
            assertEquals(5, model.requests.size)
        }

    @Test
    fun `a decision has the tokens the script gave it`() =
        runTest {
            val model = ScriptedDecisionModel { yes(usage = TokenUsage(20, 1)) }

            val response = model.decide(DecisionRequest("Help!", mapOf("urgent" to Question.YesNo("Is this urgent?"))))

            assertEquals(DecisionResponse(mapOf("urgent" to Answer.YesNo(1.0)), TokenUsage(20, 1)), response)
        }

    @Test
    fun `a decision of the script answers every question of its request`() =
        runTest {
            val model = ScriptedDecisionModel { no() }
            val questions = mapOf("urgent" to Question.YesNo("Is this urgent?"), "rude" to Question.YesNo("Is this rude?"))

            assertEquals(setOf("urgent", "rude"), model.decide(DecisionRequest("Thanks.", questions)).answers.keys)
        }

    @Test
    fun `a script can pick something that is not an option`() =
        runTest {
            val model = ScriptedDecisionModel { chooses("billing") }

            // The script stands for the model, and a model can be wrong: the library reports it.
            assertFailsWith<DecisionModelException> { model.choose("Hello", "Which team?", teams) }
        }

    @Test
    fun `a decision of another kind than the question fails the test`() =
        runTest {
            val model = ScriptedDecisionModel { yes() }

            val error = assertFailsWith<AssertionError> { model.choose("Hello", "Which team?", teams) }

            assertEquals(
                "The script answers with yes or no, but the model was asked a question of another kind: Choice(Which team?)",
                error.message,
            )
        }

    @Test
    fun `a model that is asked for more than its script fails the test`() =
        runTest {
            val model = ScriptedDecisionModel { chooses("refund") }
            val graph = support(model)
            graph.invoke(Mail("I want my money back."))

            // Not an EdgeConditionException: the engine passes an AssertionError on as it is.
            val error = assertFailsWith<AssertionError> { graph.invoke(Mail("And again.")) }

            assertEquals(
                "The script of this model has 1 decisions, but the model was asked 2 times. " +
                    "The questions it was asked last: [What does the customer want?]",
                error.message,
            )
            assertEquals(0, model.remaining)
        }

    @Test
    fun `a call that the script fails throws`() =
        runTest {
            val broken = IllegalStateException("No connection.")
            val model =
                ScriptedDecisionModel {
                    fails("API error 429: slow down")
                    fails(broken)
                }

            assertEquals("API error 429: slow down", assertFailsWith<DecisionModelException> { model.isYes("Help!", "Urgent?") }.message)
            assertSame(broken, assertFailsWith<IllegalStateException> { model.isYes("Help!", "Urgent?") })
        }

    @Test
    fun `a decision of the script can depend on the request`() =
        runTest {
            val model =
                ScriptedDecisionModel {
                    decides { request ->
                        DecisionResponse(request.questions.mapValues { (name, _) -> Answer.YesNo(if (name == "urgent") 1.0 else 0.0) })
                    }
                }
            val questions = mapOf("urgent" to Question.YesNo("Is this urgent?"), "rude" to Question.YesNo("Is this rude?"))

            val answers = model.decide(DecisionRequest("Help!", questions)).answers

            assertEquals(mapOf("urgent" to Answer.YesNo(1.0), "rude" to Answer.YesNo(0.0)), answers)
        }
}
