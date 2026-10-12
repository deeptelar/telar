package dev.deeptelar.telar.samples

import dev.deeptelar.telar.CompiledGraph
import dev.deeptelar.telar.END
import dev.deeptelar.telar.GraphEvent
import dev.deeptelar.telar.START
import dev.deeptelar.telar.StateGraph

/** The three kinds of email the support agent tells apart. */
enum class Category { REFUND, TECHNICAL, ESCALATION }

/**
 * The state: everything the graph knows about one email.
 *
 * [sender] and [body] are the input. [category] and [reply] start empty and are filled in by nodes.
 * State is immutable (only `val`), so a node never changes it; it returns an updated copy.
 */
data class SupportEmail(
    val sender: String,
    val body: String,
    val category: Category? = null,
    val reply: String = "",
)

/**
 * Decides what an email is about. A real agent would ask an AI model here; plain Kotlin keeps the
 * sample runnable without an API key.
 */
fun categoryOf(body: String): Category {
    val text = body.lowercase()
    val angry = listOf("unacceptable", "furious", "worst", "!!").any { it in text }
    val wantsRefund = listOf("refund", "money back").any { it in text }
    val hasProblem = listOf("error", "crash", "not working").any { it in text }

    return when {
        angry -> Category.ESCALATION // an upset customer gets a person
        wantsRefund && hasProblem -> Category.ESCALATION // two requests in one email: too complex
        wantsRefund -> Category.REFUND
        hasProblem -> Category.TECHNICAL
        else -> Category.ESCALATION // not sure what this is, so a person reads it
    }
}

/** Reads an email, decides its category, and writes the reply for that category. */
fun emailSupportGraph(): CompiledGraph<SupportEmail> =
    StateGraph<SupportEmail> {
        // A node is a named step: it receives the state and returns an updated copy.
        // This one fills in `category` and leaves the rest of the email as it is.
        val classify = node("classify") { email -> email.copy(category = categoryOf(email.body)) }

        // One node per category. Each writes the reply; only one of them runs for a given email.
        val refund =
            node("refund") { email ->
                email.copy(reply = "Hi ${email.sender}, your refund is on its way. It takes 3 to 5 days.")
            }
        val technical =
            node("technical") { email ->
                email.copy(reply = "Hi ${email.sender}, please update the app and try again. Here is our guide.")
            }
        val escalate =
            node("escalate") { email ->
                email.copy(reply = "Hi ${email.sender}, a colleague from our team will reply to you personally today.")
            }

        // Every run begins at START. This edge says: run `classify` first.
        START then classify

        // A conditional edge picks the next node by looking at the state.
        // `targets` lists every node it may pick, so compile() can check that no node is left out.
        conditionalEdge(classify, targets = setOf(refund, technical, escalate)) { email ->
            when (email.category) {
                Category.REFUND -> refund
                Category.TECHNICAL -> technical
                else -> escalate
            }
        }

        // After the reply is written there is nothing left to do, so each path goes to END.
        refund then END
        technical then END
        escalate then END
    }.compile() // Checks the graph (unknown names, nodes nothing leads to) and makes it runnable.

suspend fun main() {
    val graph = emailSupportGraph()

    val emails =
        listOf(
            SupportEmail(sender = "Ana", body = "I was charged twice, I would like a refund."),
            SupportEmail(sender = "Ben", body = "The app shows an error when I log in."),
            SupportEmail(sender = "Cleo", body = "This is unacceptable, third time I write to you!!"),
        )

    // invoke() runs the graph from START to END and returns the final state.
    for (email in emails) {
        val result = graph.invoke(email)
        println("${result.state.category}: ${result.state.reply}")
    }

    // stream() runs the graph too, and reports what happens while it runs.
    println()
    graph.stream(emails.first()).collect { event ->
        when (event) {
            is GraphEvent.NodeStarted -> println("${event.node} started")
            is GraphEvent.NodeProgress -> println("${event.node} reports ${event.value}")
            is GraphEvent.SubgraphEvent -> println("inside ${event.node}: ${event.event}")
            is GraphEvent.NodeRetry -> println("${event.node} failed, next try in ${event.pause}")
            is GraphEvent.NodeCompleted -> println("${event.node} finished")
            is GraphEvent.StepCompleted -> println("step ${event.step} done, ran ${event.nodes}")
            is GraphEvent.Completed -> println("done: ${event.state.reply}")
            is GraphEvent.Interrupted -> println("paused before ${event.nextNodes}")
        }
    }
}
