package dev.deeptelar.telar.samples

import dev.deeptelar.telar.CompiledGraph
import dev.deeptelar.telar.NodeRef
import dev.deeptelar.telar.START
import dev.deeptelar.telar.StateGraph
import dev.deeptelar.telar.agent.Answer
import dev.deeptelar.telar.agent.ChatDecisionModel
import dev.deeptelar.telar.agent.DecisionModel
import dev.deeptelar.telar.agent.DecisionResponse
import dev.deeptelar.telar.agent.decisionEdge
import dev.deeptelar.telar.langchain4j.LangChain4jChatModel
import dev.deeptelar.telar.langchain4j.chatMessagesNode
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.ollama.OllamaChatModel
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration

data class ChatState(
    val messages: List<ChatMessage>,
    val needsEscalation: Boolean = false,
)

/**
 * A support agent: the model answers, and then [decisions] reads what the customer asked and decides
 * whether a person takes the conversation over.
 *
 * Pass any LangChain4j [ChatModel], for example `OpenAiChatModel` or `OllamaChatModel`. For the
 * decision, the same model works through `ChatDecisionModel(LangChain4jChatModel(model))`.
 */
fun supportAgent(model: ChatModel, decisions: DecisionModel): CompiledGraph<ChatState> =
    StateGraph<ChatState> {
        val assistant =
            node(
                "assistant",
                chatMessagesNode(
                    model = model,
                    messages = { it.messages },
                    update = { state, response -> state.copy(messages = state.messages + response.aiMessage()) },
                ),
            )
        val escalate = node("escalate") { it.copy(needsEscalation = true) }

        START then assistant
        // A model judges what the customer wants. Looking for a word in the reply instead would
        // also escalate "a colleague can help you further if this does not work".
        decisionEdge(
            from = assistant,
            model = decisions,
            instructions = "What does the customer who wrote this message need?",
            routes =
                mapOf(
                    escalate to "Money back, or to talk to a person",
                    NodeRef.END to "Help with the app, or an answer that the agent can give",
                ),
        ) { state ->
            state.messages
                .filterIsInstance<UserMessage>()
                .last()
                .singleText()
        }
    }.compile()

/** Offline stand-in for a real model so the sample runs without an API key. */
class CannedModel : ChatModel {
    override fun doChat(chatRequest: ChatRequest): ChatResponse {
        val question =
            chatRequest
                .messages()
                .filterIsInstance<UserMessage>()
                .last()
                .singleText()
        val reply = if ("refund" in question) "I need a human colleague to handle refunds." else "Restart the app and try again."
        return ChatResponse.builder().aiMessage(AiMessage.from(reply)).build()
    }
}

/** Offline stand-in for a real decision model: a customer who asks for a refund goes to a person. */
val cannedEscalation: DecisionModel =
    DecisionModel { request ->
        val message = request.state.jsonPrimitive.content
        val option = if ("refund" in message.lowercase()) "escalate" else NodeRef.END.name
        DecisionResponse(request.questions.mapValues { Answer.Choice(option) })
    }

/** The model of a local Ollama when OLLAMA_MODEL names one, such as `qwen3:8b`, and the canned one otherwise. */
suspend fun main() {
    val ollamaModel = System.getenv("OLLAMA_MODEL")?.takeIf { it.isNotBlank() }
    val agent =
        if (ollamaModel != null) {
            val model =
                OllamaChatModel
                    .builder()
                    .baseUrl("http://localhost:11434")
                    .modelName(ollamaModel)
                    .timeout(Duration.ofMinutes(5))
                    .build()
            // The same model decides, through the ChatModel of Telar that LangChain4jChatModel makes of it.
            supportAgent(model, ChatDecisionModel(LangChain4jChatModel(model)))
        } else {
            supportAgent(CannedModel(), cannedEscalation)
        }
    for (question in listOf("The app is frozen", "I want a refund")) {
        val result =
            agent.invoke(
                ChatState(
                    listOf(SystemMessage.from("You are a support agent. Answer in one or two sentences."), UserMessage.from(question)),
                ),
            )
        println("$question -> ${(result.state.messages.last() as AiMessage).text().trim()} (escalate: ${result.state.needsEscalation})")
    }
}
