package dev.deeptelar.telar.agent

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Serializable
data class Page(
    val url: String,
)

class AttachmentTest {
    private val png = Attachment.of(byteArrayOf(1, 2, 3), "image/png")
    private val screenshot =
        Tool<Page>("screenshot", "Takes a screenshot of a web page.") { page ->
            attach(png)
            "The screenshot of ${page.url}."
        }

    @Test
    fun `a file keeps its bytes in Base64 and its media type`() {
        assertEquals(Attachment.Data("AQID", "image/png"), png)
        assertContentEquals(byteArrayOf(1, 2, 3), png.bytes())
        assertEquals(
            Attachment.Url("https://example.com/a.pdf", "application/pdf"),
            Attachment.url("https://example.com/a.pdf", "application/pdf"),
        )
    }

    @Test
    fun `a file does not print its bytes`() {
        assertEquals("Data(mediaType=image/png, 4 characters of Base64)", png.toString())
    }

    @Test
    fun `messages with files survive serialization`() {
        val state =
            AgentState(
                listOf(
                    ChatMessage.User("What is on these?", listOf(png, Attachment.url("https://example.com/a.pdf", "application/pdf"))),
                    ChatMessage.ToolResult("call-1", "screenshot", "A page.", attachments = listOf(png)),
                ),
            )

        val json = Json.encodeToString(AgentState.serializer(), state)

        assertEquals(state, Json.decodeFromString(AgentState.serializer(), json))
    }

    @Test
    fun `a message saved before attachments existed still reads`() {
        val saved =
            """
            {"messages": [
              {"type": "user", "text": "Hi"},
              {"type": "tool_result", "toolCallId": "c", "toolName": "t", "text": "ok"}
            ]}
            """

        val state = Json.decodeFromString(AgentState.serializer(), saved)

        assertEquals(listOf(ChatMessage.User("Hi"), ChatMessage.ToolResult("c", "t", "ok")), state.messages)
    }

    @Test
    fun `a message without files saves as it did before`() {
        assertEquals("""{"type":"user","text":"Hi"}""", Json.encodeToString(ChatMessage.serializer(), ChatMessage.User("Hi")))
    }

    @Test
    fun `withUserMessage adds the files to the message`() {
        val state = AgentState().withUserMessage("And this one?", listOf(png))

        assertEquals(listOf(ChatMessage.User("And this one?", listOf(png))), state.messages)
    }

    @Test
    fun `chat and ask send their files with the prompt`() =
        runTest {
            @Serializable
            data class Receipt(
                val total: Int,
            )
            val model = ScriptedModel(says("A receipt."), says("A receipt."), says("""{"total": 12}"""))

            model.chat("What is this?", attachments = listOf(png))
            model.chatWithProgress("What is this?", attachments = listOf(png))
            val receipt = model.ask<Receipt>("Read the total.", attachments = listOf(png))

            assertEquals(Receipt(12), receipt)
            model.requests.forEach { assertEquals(listOf(png), (it.messages.first() as ChatMessage.User).attachments) }
        }

    @Test
    fun `what a tool attaches goes to the model with its result`() =
        runTest {
            val model = ScriptedModel(calls(call("call-1", "screenshot", "url" to "https://example.com")), says("A shop."))

            val state = toolAgent(model, listOf(screenshot)).invoke(AgentState("What is on the page?")).state

            assertEquals(
                ChatMessage.ToolResult("call-1", "screenshot", "The screenshot of https://example.com.", attachments = listOf(png)),
                model.requests
                    .last()
                    .messages
                    .last(),
            )
            assertEquals("A shop.", state.answer)
        }

    @Test
    fun `a state tool attaches too`() =
        runTest {
            val look =
                StateTool<AgentState, Page>("look", "Looks at a page.") { state, _ ->
                    attach(png)
                    ToolOutcome(state, "Looked.")
                }

            val (_, result) =
                runOnState(
                    call("call-1", "look", "url" to "https://example.com"),
                    "call-1",
                    AgentState(),
                    emptyList(),
                    listOf(look),
                )

            assertEquals(listOf(png), result.attachments)
        }

    @Test
    fun `a tool that fails returns no files`() =
        runTest {
            val broken =
                Tool<Page>("screenshot", "Takes a screenshot of a web page.") {
                    attach(png)
                    error("The browser crashed.")
                }

            val result = listOf(broken).execute(listOf(call("call-1", "screenshot", "url" to "https://example.com"))).single()

            assertTrue(result.isError)
            assertEquals(emptyList(), result.attachments)
        }

    @Test
    fun `a tool that runs outside a tool call returns its text`() =
        runTest {
            assertEquals(
                "The screenshot of https://example.com.",
                screenshot.execute(Json.parseToJsonElement("""{"url": "https://example.com"}""") as JsonObject),
            )
        }
}
