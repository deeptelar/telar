package dev.deeptelar.telar.agent

import dev.deeptelar.telar.GraphValidationException
import dev.deeptelar.telar.START
import dev.deeptelar.telar.StateGraph
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ShortenTest {
    private val lookup = Tool<Unit>("lookup", "Looks something up.") { "Found." }

    /** A question, then [rounds] rounds of one tool call and its result. */
    private fun conversation(rounds: Int): List<ChatMessage> =
        listOf(ChatMessage.User("Plan a week.")) +
            (1..rounds).flatMap { round ->
                listOf(
                    ChatMessage.Assistant(toolCalls = listOf(call("$round", "lookup"))),
                    ChatMessage.ToolResult("$round", "lookup", "Result $round"),
                )
            }

    /** Whether every tool result of [messages] follows the call it answers, and every call has its result. */
    private fun complete(messages: List<ChatMessage>): Boolean {
        val calls = messages.filterIsInstance<ChatMessage.Assistant>().flatMap { it.toolCalls }.map { it.id }
        val results = messages.filterIsInstance<ChatMessage.ToolResult>().map { it.toolCallId }
        return calls == results
    }

    @Test
    fun `a conversation within the limit stays as it is`() {
        val messages = conversation(rounds = 5)

        assertEquals(messages, keepLast(messages = 11)(messages))
    }

    @Test
    fun `a longer conversation keeps the first question and its last messages`() {
        val messages = conversation(rounds = 20)

        val kept = keepLast(messages = 10)(messages)

        assertEquals(listOf(messages.first(), ChatMessage.User(LEFT_OUT)), kept.take(2))
        assertEquals(messages.takeLast(kept.size - 2), kept.drop(2))
        assertTrue(kept.size in 6..10, "kept ${kept.size} messages")
    }

    @Test
    fun `a cut keeps every tool call with its result`() {
        val messages = conversation(rounds = 60)

        for (size in 1..messages.size) {
            for (limit in 3..30) {
                assertTrue(
                    complete(keepLast(limit)(messages.take(size).dropLastUnanswered())),
                    "$size messages, limit $limit",
                )
            }
        }
    }

    /** The conversation as a model reads it: it is not asked while a call has no result. */
    private fun List<ChatMessage>.dropLastUnanswered(): List<ChatMessage> = if (pendingToolCalls().isEmpty()) this else dropLast(1)

    @Test
    fun `the results of one answer stay together with their call`() {
        val calls = (1..6).map { call("$it", "lookup") }
        val results = calls.map { ChatMessage.ToolResult(it.id, it.name, "Found.") }
        val messages = conversation(rounds = 3) + ChatMessage.Assistant(toolCalls = calls) + results

        val kept = keepLast(messages = 4)(messages)

        assertEquals(listOf(messages.first(), ChatMessage.User(LEFT_OUT), ChatMessage.Assistant(toolCalls = calls)) + results, kept)
    }

    @Test
    fun `a user message between the results of a call does not become the start`() {
        val calls = listOf(call("a", "lookup"), call("b", "lookup"))
        val messages =
            listOf(
                ChatMessage.User("Plan a week."),
                ChatMessage.Assistant("Looking."),
                ChatMessage.User("Faster."),
                ChatMessage.Assistant(toolCalls = calls),
                ChatMessage.ToolResult("a", "lookup", "A"),
                ChatMessage.User("Please."),
                ChatMessage.ToolResult("b", "lookup", "B"),
                ChatMessage.Assistant("Done."),
                ChatMessage.User("Thanks."),
            )

        val kept = keepLast(messages = 4)(messages)

        assertEquals(listOf(messages.first(), ChatMessage.User(LEFT_OUT)) + messages.takeLast(2), kept)
    }

    @Test
    fun `messages before the first question stay as well`() {
        val greeting = ChatMessage.Assistant("How can I help?")

        val kept = keepLast(messages = 6)(listOf(greeting) + conversation(rounds = 10))

        assertEquals(listOf(greeting, ChatMessage.User("Plan a week."), ChatMessage.User(LEFT_OUT)), kept.take(3))
        assertIs<ChatMessage.Assistant>(kept[3])
    }

    @Test
    fun `the start stays the same between two cuts`() {
        val messages = conversation(rounds = 50)
        val shorten = keepLast(messages = 20)

        val starts = (2..messages.size step 2).map { size -> shorten(messages.take(size + 1)).drop(2).firstOrNull() }

        // Rounds of two messages, and a cut every nine messages: a new start about every five rounds.
        assertTrue(starts.distinct().size <= 12, "${starts.distinct().size} different starts")
    }

    @Test
    fun `nothing left out needs no note`() {
        val calls = (1..6).map { call("$it", "lookup") }
        val messages =
            listOf(ChatMessage.User("Plan a week."), ChatMessage.Assistant(toolCalls = calls)) +
                calls.map { ChatMessage.ToolResult(it.id, it.name, "Found.") }

        assertEquals(messages, keepLast(messages = 4)(messages))
    }

    @Test
    fun `keepLast needs room for the question and the note and a message`() {
        assertFailsWith<GraphValidationException> { keepLast(messages = 2) }
    }

    @Test
    fun `the model reads the short conversation and the state keeps all of it`() =
        runTest {
            val model = ScriptedModel(*Array(7) { calls(call("$it", "lookup")) }, says("Done."))

            val state = toolAgent(model, listOf(lookup), shorten = keepLast(messages = 7)).invoke(AgentState("Plan a week.")).state

            assertEquals(16, state.messages.size)
            assertEquals("Done.", state.answer)
            assertTrue(model.requests.all { it.messages.size <= 7 })
            assertEquals(
                ChatMessage.User("Plan a week."),
                model.requests
                    .last()
                    .messages
                    .first(),
            )
            assertEquals(listOf(false, false, false, false, true, true, true, true), model.requests.map { it.shortened })
        }

    @Test
    fun `a loop without shorten sends everything`() =
        runTest {
            val model = ScriptedModel(calls(call("1", "lookup")), says("Done."))

            toolAgent(model, listOf(lookup)).invoke(AgentState("Plan a week."))

            assertEquals(
                3,
                model.requests
                    .last()
                    .messages.size,
            )
            assertFalse(model.requests.last().shortened)
        }

    @Test
    fun `a first message goes through shorten`() =
        runTest {
            data class Order(
                val messages: List<ChatMessage> = emptyList(),
            )
            val model = ScriptedModel(says("Done."))
            val graph =
                StateGraph<Order> {
                    START then
                        toolLoop(
                            model = model,
                            messages = { it.messages },
                            append = { order, new -> order.copy(messages = order.messages + new) },
                            firstMessage = { "Plan a week." },
                            shorten = { messages -> messages + ChatMessage.User("Be brief.") },
                        )
                }.compile()

            graph.invoke(Order())

            assertEquals(
                listOf("Plan a week.", "Be brief."),
                model.requests
                    .single()
                    .messages
                    .map { it.text },
            )
            assertTrue(model.requests.single().shortened)
        }

    @Test
    fun `a summary in the conversation is where the model starts to read`() =
        runTest {
            data class Chat(
                val messages: List<ChatMessage>,
            )
            val summary = "Summary of the conversation so far: "
            val model = ScriptedModel(says("A week in Porto."), says("Done."))
            val graph =
                StateGraph<Chat> {
                    val summarize =
                        node("summarize", work = { chat ->
                            model.chat("Summarize this conversation: ${chat.messages.joinToString("\n") { it.text }}")
                        }) { chat, text -> chat.copy(messages = chat.messages + ChatMessage.User(summary + text)) }
                    START then summarize then
                        toolLoop(
                            model = model,
                            messages = { it.messages },
                            append = { chat, new -> chat.copy(messages = chat.messages + new) },
                            shorten = { messages -> messages.drop(messages.indexOfLast { it.text.startsWith(summary) }.coerceAtLeast(0)) },
                        )
                }.compile()
            val older = listOf(ChatMessage.User("Plan a week."), ChatMessage.Assistant("Porto, then Lisbon."))

            val state = graph.invoke(Chat(older)).state

            assertEquals(
                listOf("${summary}A week in Porto."),
                model.requests
                    .last()
                    .messages
                    .map { it.text },
            )
            assertEquals(4, state.messages.size)
        }
}
