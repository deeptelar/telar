package dev.deeptelar.telar.checkpoint.sqldelight

import app.cash.sqldelight.SuspendingTransacterImpl
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.db.SqlSchema
import dev.deeptelar.telar.Checkpoint
import dev.deeptelar.telar.Checkpointer
import dev.deeptelar.telar.GraphValidationException
import dev.deeptelar.telar.StateSerializer
import dev.deeptelar.telar.serialization.CheckpointCodec
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A persistent [Checkpointer] that saves the checkpoints of each thread in a table of a SQLite
 * database, through a [SqlDriver] of SQLDelight. One module for every platform an app runs on:
 *
 * | Platform | Driver, from SQLDelight |
 * |---|---|
 * | Android | `AndroidSqliteDriver` of `android-driver` |
 * | iOS, macOS, Linux, Windows | `NativeSqliteDriver` of `native-driver` |
 * | Desktop and servers (JVM) | `JdbcSqliteDriver` of `sqlite-driver` |
 * | Browser (JS and Wasm) | `WebWorkerDriver` of `web-worker-driver`, with SQLite in a web worker |
 *
 * ```kotlin
 * // Android, with a database file that only holds checkpoints.
 * val driver = AndroidSqliteDriver(SqlDelightCheckpointer.Schema, context, "runs.db")
 * val config = GraphConfig(
 *     threadId = "order-42",
 *     checkpointer = SqlDelightCheckpointer(driver, KotlinxStateSerializer<Order>()),
 *     interruptBefore = setOf("pay"),
 * )
 * ```
 *
 * The app creates the driver, and closes it. The checkpointer creates its [table] when it first
 * needs it, with `CREATE TABLE IF NOT EXISTS`, so the database may also be the one the app keeps
 * its own data in, with the schema of the app. [Schema] is a schema for a database that only holds
 * checkpoints, for the drivers that ask for one. The table has one row for each step of a thread:
 *
 * ```sql
 * CREATE TABLE telar_checkpoints (
 *     thread_id TEXT NOT NULL,
 *     step INTEGER NOT NULL,
 *     checkpoint TEXT NOT NULL, -- the checkpoint in the format of CheckpointCodec
 *     PRIMARY KEY (thread_id, step)
 * )
 * ```
 *
 * A save is one transaction: it replaces the checkpoints of the thread with the same step or a
 * later one, adds the new one, and removes the oldest beyond [maxHistory]. A crash in the middle
 * leaves the checkpoints as they were. SQLite keeps two processes that write the same database
 * from breaking it. One instance is safe to share between coroutines; do not run the same thread
 * in two processes at the same time.
 *
 * The calls of a driver that blocks, such as those of Android, run on `Dispatchers.IO`, so the
 * checkpointer can be used from the main thread. An asynchronous driver, such as the one of the
 * browser, is awaited.
 *
 * @param driver the database. Its dialect has to be SQLite, which every driver above is.
 * @param serializer converts the graph state to and from a string.
 * @param table the name of the table, letters, digits and `_`, not starting with a digit.
 * @param maxHistory how many checkpoints a thread keeps, counted from the latest, for
 * `CompiledGraph.history` and `fork`. The default keeps all of them; `1` keeps only the latest.
 * @throws GraphValidationException if [table] is not a plain name or [maxHistory] is not positive.
 */
public class SqlDelightCheckpointer<State>(
    private val driver: SqlDriver,
    serializer: StateSerializer<State>,
    private val table: String = DEFAULT_TABLE,
    private val maxHistory: Int = Int.MAX_VALUE,
) : Checkpointer<State> {
    private val codec = CheckpointCodec(serializer)
    private val transacter = object : SuspendingTransacterImpl(driver) {}
    private val lock = Mutex()
    private var tableCreated = false

    init {
        if (!table.matches(TABLE_NAME)) {
            throw GraphValidationException("table must be letters, digits and '_', not starting with a digit, was '$table'.")
        }
        if (maxHistory <= 0) throw GraphValidationException("maxHistory must be positive, was $maxHistory.")
    }

    override suspend fun save(threadId: String, checkpoint: Checkpoint<State>) {
        val encoded = codec.encode(checkpoint)
        database {
            transacter.transaction {
                execute("DELETE FROM $table WHERE thread_id = ? AND step >= ?") {
                    bindString(0, threadId)
                    bindLong(1, checkpoint.step.toLong())
                }
                execute("INSERT INTO $table (thread_id, step, checkpoint) VALUES (?, ?, ?)") {
                    bindString(0, threadId)
                    bindLong(1, checkpoint.step.toLong())
                    bindString(2, encoded)
                }
                if (maxHistory != Int.MAX_VALUE) {
                    // The oldest step to keep, if the thread has more than maxHistory checkpoints.
                    val oldestKept =
                        query("SELECT step FROM $table WHERE thread_id = ? ORDER BY step DESC LIMIT 1 OFFSET ?", { getLong(0) }) {
                            bindString(0, threadId)
                            bindLong(1, maxHistory - 1L)
                        }.singleOrNull()
                    if (oldestKept != null) {
                        execute("DELETE FROM $table WHERE thread_id = ? AND step < ?") {
                            bindString(0, threadId)
                            bindLong(1, oldestKept)
                        }
                    }
                }
            }
        }
    }

    override suspend fun load(threadId: String): Checkpoint<State>? =
        database { checkpoints(threadId, "SELECT checkpoint FROM $table WHERE thread_id = ? ORDER BY step DESC LIMIT 1") }
            .singleOrNull()
            ?.let { codec.decode(threadId, it) }

    override suspend fun history(threadId: String): List<Checkpoint<State>> =
        database { checkpoints(threadId, "SELECT checkpoint FROM $table WHERE thread_id = ? ORDER BY step") }
            .map { codec.decode(threadId, it) }

    override suspend fun delete(threadId: String) {
        database {
            execute("DELETE FROM $table WHERE thread_id = ?") { bindString(0, threadId) }
        }
    }

    /** Runs [block] with the table in place, one call of this checkpointer at a time. */
    private suspend fun <Result> database(block: suspend () -> Result): Result =
        lock.withLock {
            withContext(ioDispatcher) {
                if (!tableCreated) {
                    driver.execute(null, createTable(table), 0).await()
                    tableCreated = true
                }
                block()
            }
        }

    private suspend fun checkpoints(threadId: String, sql: String): List<String> =
        // The column is NOT NULL.
        query(sql, { checkNotNull(getString(0)) }) { bindString(0, threadId) }

    private suspend fun execute(sql: String, binders: (SqlPreparedStatement.() -> Unit)? = null) {
        driver.execute(null, sql, sql.count { it == '?' }, binders).await()
    }

    /**
     * Returns the rows of [sql], each read with [column]. A driver that blocks has its rows read at
     * once, while its cursor is open; an asynchronous driver is awaited for each row.
     */
    private suspend fun <Value> query(sql: String, column: SqlCursor.() -> Value, binders: SqlPreparedStatement.() -> Unit): List<Value> =
        driver
            .executeQuery(
                null,
                sql,
                { cursor ->
                    val first = cursor.next()
                    if (first is QueryResult.Value) {
                        QueryResult.Value(
                            buildList {
                                var more = first.value
                                while (more) {
                                    add(cursor.column())
                                    more = cursor.next().value
                                }
                            },
                        )
                    } else {
                        QueryResult.AsyncValue {
                            buildList {
                                var more = first.await()
                                while (more) {
                                    add(cursor.column())
                                    more = cursor.next().await()
                                }
                            }
                        }
                    }
                },
                sql.count { it == '?' },
                binders,
            ).await()

    /**
     * The schema of a database that only holds checkpoints, in the table of the default name, for
     * a driver that asks for a schema:
     *
     * ```kotlin
     * val android = AndroidSqliteDriver(SqlDelightCheckpointer.Schema, context, "runs.db")
     * val ios = NativeSqliteDriver(SqlDelightCheckpointer.Schema, "runs.db")
     * ```
     *
     * A database with tables of the app has the schema of the app instead: the checkpointer adds its
     * table to it.
     */
    public object Schema : SqlSchema<QueryResult.Value<Unit>> {
        /** The version of the schema. It is `1`. */
        override val version: Long = 1

        /** Creates the table of the checkpoints. */
        override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
            driver.execute(null, createTable(DEFAULT_TABLE), 0)
            return QueryResult.Unit
        }

        /** Does nothing: the schema has had one version only. */
        override fun migrate(
            driver: SqlDriver,
            oldVersion: Long,
            newVersion: Long,
            vararg callbacks: AfterVersion,
        ): QueryResult.Value<Unit> = QueryResult.Unit
    }

    private companion object {
        const val DEFAULT_TABLE = "telar_checkpoints"
        val TABLE_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

        fun createTable(table: String): String =
            "CREATE TABLE IF NOT EXISTS $table (" +
                "thread_id TEXT NOT NULL, step INTEGER NOT NULL, checkpoint TEXT NOT NULL, " +
                "PRIMARY KEY (thread_id, step))"
    }
}
