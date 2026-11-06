package dev.deeptelar.telar.agent

import dev.deeptelar.telar.GraphConfig
import dev.deeptelar.telar.GraphValidationException
import dev.deeptelar.telar.MemoryCheckpointer
import dev.deeptelar.telar.MemoryStore
import dev.deeptelar.telar.Store
import dev.deeptelar.telar.StoreItem
import dev.deeptelar.telar.within
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A store that counts its writes, and fails the first one when told to. */
private class CountingStore(
    private val store: Store = MemoryStore(),
) : Store by store {
    var puts = 0
    var crashOnce = false

    override suspend fun put(namespace: List<String>, key: String, value: String) {
        puts++
        store.put(namespace, key, value)
    }

    override suspend fun list(namespace: List<String>, limit: Int): List<StoreItem> {
        if (crashOnce) {
            crashOnce = false
            // An Error is no failure of the tool, so it fails the step, like a process that stops.
            throw AssertionError("The process stopped.")
        }
        return store.list(namespace, limit)
    }
}

private fun results(model: ScriptedModel, request: Int): List<String> =
    model.requests[request]
        .messages
        .filterIsInstance<ChatMessage.ToolResult>()
        .map { it.text }

class MemoryToolsTest {
    private val store = MemoryStore()

    @Test
    fun `an agent remembers in one conversation and recalls in the next`() =
        runTest {
            val first =
                ScriptedModel(
                    calls(call("1", "remember", "key" to "diet", "text" to "Piscivegetarian.")),
                    calls(call("2", "remember", "key" to "diet", "text" to "Piscivegetarian, and no shellfish.")),
                    says("Noted."),
                )
            toolAgent(first, memoryTools()).invoke(AgentState("I'm piscivegetarian."), GraphConfig("plan-1", store = store))

            assertEquals(listOf("Remembered diet."), results(first, 1))
            assertEquals("Piscivegetarian, and no shellfish.", store.get(listOf("memories"), "diet")?.value)
            assertEquals(1, store.list(emptyList()).size)

            val next = ScriptedModel(calls(call("1", "recall")), says("A fish lunch, then."))
            toolAgent(next, memoryTools()).invoke(AgentState("Plan my lunch."), GraphConfig("plan-2", store = store))

            assertEquals(listOf("diet: Piscivegetarian, and no shellfish."), results(next, 1))
        }

    @Test
    fun `an agent forgets what the user asks it to`() =
        runTest {
            store.put(listOf("memories"), "diet", "Piscivegetarian.")
            val model =
                ScriptedModel(
                    calls(call("1", "forget", "key" to "diet")),
                    calls(call("2", "forget", "key" to "diet"), call("3", "recall")),
                    says("Forgotten."),
                )
            toolAgent(model, memoryTools()).invoke(AgentState("Forget my diet."), GraphConfig(store = store))

            assertEquals(listOf("Forgot diet."), results(model, 1))
            assertEquals(listOf("Forgot diet.", "Nothing is remembered as diet.", "Nothing is remembered yet."), results(model, 2))
        }

    @Test
    fun `each user has memories of their own`() =
        runTest {
            val ana = ScriptedModel(calls(call("1", "remember", "key" to "diet", "text" to "Vegan.")), says("Noted."))
            toolAgent(ana, memoryTools()).invoke(AgentState("I'm vegan."), GraphConfig(store = store.within("users", "ana")))
            val joan = ScriptedModel(calls(call("1", "recall")), says("Nothing yet."))
            toolAgent(joan, memoryTools(listOf("trips"), limit = 5))
                .invoke(AgentState("What do you know?"), GraphConfig(store = store.within("users", "joan")))

            assertEquals("Vegan.", store.get(listOf("users", "ana", "memories"), "diet")?.value)
            assertEquals(listOf("Nothing is remembered yet."), results(joan, 1))
        }

    @Test
    fun `a run without a store gives the model an error`() =
        runTest {
            val model = ScriptedModel(calls(call("1", "recall")), says("I can't remember anything."))
            toolAgent(model, memoryTools()).invoke(AgentState("What do you know?"))

            val result =
                model.requests[1]
                    .messages
                    .filterIsInstance<ChatMessage.ToolResult>()
                    .single()
            assertTrue(result.isError)
            assertTrue("GraphConfig(store = ...)" in result.text, result.text)
        }

    @Test
    fun `a memory is not written again when its step runs again`() =
        runTest {
            val counting = CountingStore().apply { crashOnce = true }
            val model =
                ScriptedModel(
                    calls(call("1", "remember", "key" to "diet", "text" to "Vegan."), call("2", "recall")),
                    says("Noted."),
                )
            val agent = toolAgent(model, memoryTools(), inOrder = true)
            val config = GraphConfig(checkpointer = MemoryCheckpointer<AgentState>(), store = counting)

            assertFails { agent.invoke(AgentState("I'm vegan."), config) }
            agent.resume(config)

            assertEquals(1, counting.puts)
            assertEquals(listOf("Remembered diet.", "diet: Vegan."), results(model, 1))
        }

    @Test
    fun `a namespace and a limit are checked`() {
        assertFailsWith<GraphValidationException> { memoryTools(listOf("")) }
        assertFailsWith<GraphValidationException> { memoryTools(limit = 0) }
        assertFailsWith<GraphValidationException> { memoryTools(searchLimit = 0) }
        assertEquals(listOf("remember", "forget", "recall"), memoryTools().map { it.spec.name })
        assertNull(memoryTools().first().reconcile)
    }

    @Test
    fun `recall with a query finds the memories with its words`() =
        runTest {
            store.put(listOf("memories"), "diet", "Piscivegetarian: fish but no meat.")
            store.put(listOf("memories"), "lunch", "The big meal of the day.")
            val model =
                ScriptedModel(
                    calls(
                        call("1", "recall", "query" to "fish"),
                        call("2", "recall", "query" to "pizza"),
                        call(
                            "3",
                            "recall",
                            "query" to " ",
                        ),
                    ),
                    says("Fish, then."),
                )
            toolAgent(model, memoryTools()).invoke(AgentState("Plan my dinner."), GraphConfig(store = store))

            assertEquals(
                listOf(
                    "diet: Piscivegetarian: fish but no meat.",
                    "Nothing remembered is about pizza.",
                    "lunch: The big meal of the day.\ndiet: Piscivegetarian: fish but no meat.",
                ),
                results(model, 1),
            )
        }
}
