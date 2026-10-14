package dev.deeptelar.telar.samples

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.deeptelar.telar.CompiledGraph
import dev.deeptelar.telar.END
import dev.deeptelar.telar.GraphConfig
import dev.deeptelar.telar.GraphSession
import dev.deeptelar.telar.GraphSnapshot
import dev.deeptelar.telar.START
import dev.deeptelar.telar.StateGraph
import dev.deeptelar.telar.checkpoint.sqldelight.SqlDelightCheckpointer
import dev.deeptelar.telar.serialization.KotlinxStateSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class ReplyDraft(
    val question: String,
    val reply: String = "",
    val approved: Boolean = false,
    val log: List<String> = emptyList(),
)

/** An agent drafts the reply to a customer, and a person approves it before it is sent. */
fun replyGraph(): CompiledGraph<ReplyDraft> =
    StateGraph<ReplyDraft> {
        val draft =
            node("draft") {
                it.copy(reply = "Your pizza left the oven 5 minutes ago and arrives in 20.", log = it.log + "drafted")
            }
        val send = node("send") { it.copy(log = it.log + if (it.approved) "sent" else "dropped") }
        START then draft then send then END
    }.compile()

/** The run lives in the SQLite database of [driver], so it outlives the process. */
fun replyConfig(driver: SqlDriver): GraphConfig<ReplyDraft> =
    GraphConfig(
        threadId = "ticket-7",
        checkpointer = SqlDelightCheckpointer(driver, KotlinxStateSerializer<ReplyDraft>()),
        interruptBefore = setOf("send"),
    )

/**
 * One launch of the app: it opens the screen where the last launch left it, and shows what the
 * session says until the run is over or the person quits. [answer] reads what the person types.
 */
suspend fun replyApp(driver: SqlDriver, answer: () -> String?, show: (String) -> Unit) {
    // What a ViewModel does with viewModelScope.
    val scope = CoroutineScope(Dispatchers.Default)
    val config = replyConfig(driver)
    val session = GraphSession(replyGraph(), config, scope)
    session.restore().join()
    try {
        while (true) {
            when (val snapshot = session.snapshot.value) {
                GraphSnapshot.Idle -> {
                    show("No saved run. A customer asks: where is my pizza?")
                    session.start(ReplyDraft("Where is my pizza?")).join()
                }
                is GraphSnapshot.Interrupted -> {
                    show("Draft: ${snapshot.state.reply}")
                    show("Send it? [y]es, [n]o, or [q]uit and come back later")
                    when (answer()?.trim()?.lowercase()) {
                        "y" -> session.resume { it.copy(approved = true) }.join()
                        "n" -> session.resume { it.copy(approved = false) }.join()
                        else -> {
                            show("Closed. Start the app again: the draft will be waiting.")
                            return
                        }
                    }
                }
                is GraphSnapshot.Completed -> {
                    show("Done: ${snapshot.state.log.joinToString(", ")}. The next launch starts a new run.")
                    config.checkpointer?.delete(config.threadId)
                    return
                }
                is GraphSnapshot.Stopped -> {
                    show("Stopped: ${snapshot.cause?.message}")
                    return
                }
                GraphSnapshot.Restoring, is GraphSnapshot.Running -> error("Each command was joined, so the session is never busy here")
            }
        }
    } finally {
        scope.cancel()
    }
}

suspend fun main() {
    val database = File(System.getProperty("java.io.tmpdir"), "telar-app-restart.db")
    JdbcSqliteDriver("jdbc:sqlite:$database").use { driver ->
        replyApp(driver, answer = ::readlnOrNull, show = ::println)
    }
}
