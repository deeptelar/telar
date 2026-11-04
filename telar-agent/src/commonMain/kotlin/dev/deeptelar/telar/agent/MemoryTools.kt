package dev.deeptelar.telar.agent

import dev.deeptelar.telar.GraphConfig
import dev.deeptelar.telar.GraphValidationException
import dev.deeptelar.telar.Store
import dev.deeptelar.telar.store
import dev.deeptelar.telar.within
import kotlinx.serialization.Serializable

/**
 * Returns the tools with which an agent keeps what it learns for its next conversations, in
 * [namespace] of the [Store] of the run:
 * - `remember` keeps a text under a short name that the model chooses, such as `diet`. Remembering
 *   under a name that is already used replaces what was there, so a preference that changes stays
 *   one memory.
 * - `forget` removes what is kept under a name, for a user who asks the agent to forget it.
 * - `recall` lists what is kept, the last changed first.
 *
 * ```kotlin
 * val agent = toolAgent(model, tools = memoryTools(), system = "Remember what the traveller tells you about how they eat.")
 * agent.invoke(AgentState("I'm piscivegetarian."), GraphConfig(threadId = "plan-7", store = store.within("users", userId)))
 * ```
 *
 * The tools reach the store through [store], so the run needs a [GraphConfig.store]. With
 * [within], the same agent keeps the memories of each user apart. [toolLoop] runs each call as an
 * effect, so a call that finished is not made again when its step runs again.
 *
 * To give the model what it remembers without a call to `recall`, read it in a node before the
 * agent, with `store().list(namespace)`, and put it in the system prompt.
 *
 * @param namespace where the memories are kept in the store of the run.
 * @param limit how many memories `recall` lists at most.
 * @throws GraphValidationException if a part of [namespace] is empty or [limit] is not positive.
 */
public fun memoryTools(namespace: List<String> = listOf("memories"), limit: Int = Store.DEFAULT_LIMIT): List<Tool> {
    Store.checkNamespace(namespace)
    Store.checkLimit(limit)
    val remember =
        Tool<Remember>(
            "remember",
            "Keeps something you learned for your next conversations with this user. " +
                "Remembering under a name that is already used replaces what was there.",
        ) { input ->
            store().put(namespace, input.key, input.text)
            "Remembered ${input.key}."
        }
    val forget =
        Tool<Forget>("forget", "Forgets what is remembered under a name, when the user asks you to.") { input ->
            val store = store()
            if (store.get(namespace, input.key) == null) {
                "Nothing is remembered as ${input.key}."
            } else {
                store.delete(namespace, input.key)
                "Forgot ${input.key}."
            }
        }
    val recall =
        Tool<Unit>("recall", "Lists what you remember from earlier conversations with this user, the last changed first.") {
            val items = store().list(namespace, limit)
            if (items.isEmpty()) "Nothing is remembered yet." else items.joinToString("\n") { "${it.key}: ${it.value}" }
        }
    return listOf(remember, forget, recall)
}

/** The input of `remember`. */
@Serializable
internal data class Remember(
    @Description("A short name for what it is about, such as \"diet\"")
    val key: String,
    @Description("What to remember, in a sentence")
    val text: String,
)

/** The input of `forget`. */
@Serializable
internal data class Forget(
    @Description("The name it is remembered under")
    val key: String,
)
