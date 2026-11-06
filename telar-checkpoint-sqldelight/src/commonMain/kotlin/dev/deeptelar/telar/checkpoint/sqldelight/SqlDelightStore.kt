package dev.deeptelar.telar.checkpoint.sqldelight

import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import dev.deeptelar.telar.GraphValidationException
import dev.deeptelar.telar.Store
import dev.deeptelar.telar.StoreCorruptedException
import dev.deeptelar.telar.StoreItem
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A persistent [Store] that keeps its items in a table of a SQLite database, through a [SqlDriver]
 * of SQLDelight, on every platform that [SqlDelightCheckpointer] lists with its driver.
 *
 * ```kotlin
 * // Android: the checkpoints and the memories in one database.
 * val driver = AndroidSqliteDriver(SqlDelightCheckpointer.Schema, context, "runs.db")
 * val config = GraphConfig(
 *     threadId = "plan-7",
 *     checkpointer = SqlDelightCheckpointer(driver, KotlinxStateSerializer<Trip>()),
 *     store = SqlDelightStore(driver).within("users", userId),
 * )
 * ```
 *
 * The app creates the driver, and closes it. The store creates its [table] when it first needs it,
 * with `CREATE TABLE IF NOT EXISTS`, so the database may be the one of a [SqlDelightCheckpointer]
 * or of the app, whatever schema the driver was given. The table has one row for each item:
 *
 * ```sql
 * CREATE TABLE telar_store (
 *     namespace TEXT NOT NULL,   -- each part followed by '/', with '%' and '/' in a part written as %25 and %2F
 *     key TEXT NOT NULL,
 *     value TEXT NOT NULL,
 *     updated_at INTEGER NOT NULL, -- microseconds since 1970-01-01T00:00:00Z
 *     PRIMARY KEY (namespace, key)
 * )
 * ```
 *
 * Each write is one statement, so a crash leaves the items as they were or with the write done. In
 * the browser, the worker of SQLDelight keeps the database in memory, so the items are gone when the
 * page is reloaded; `LocalStorageStore` of `telar-checkpoint-browser` keeps them. One instance is
 * safe to share between coroutines.
 *
 * @param driver the database. Its dialect has to be SQLite.
 * @param table the name of the table, letters, digits and `_`, not starting with a digit.
 * @param clock gives [StoreItem.updatedAt], to the microsecond. Two writes of one store never get the same time, and a
 * store starts after the last write it finds in its table, so `list` keeps their order also with a
 * clock that is not precise or that went back.
 * @throws GraphValidationException if [table] is not a plain name.
 */
public class SqlDelightStore(
    private val driver: SqlDriver,
    private val table: String = DEFAULT_TABLE,
    private val clock: Clock = Clock.System,
) : Store {
    private val lock = Mutex()
    private var tableCreated = false
    private var lastWrite: Long? = null

    init {
        if (!table.matches(TABLE_NAME)) {
            throw GraphValidationException("table must be letters, digits and '_', not starting with a digit, was '$table'.")
        }
    }

    override suspend fun get(namespace: List<String>, key: String): StoreItem? {
        Store.checkAddress(namespace, key)
        return database {
            driver.query("SELECT namespace, key, value, updated_at FROM $table WHERE namespace = ? AND key = ?", { row() }) {
                bindString(0, encodeNamespace(namespace))
                bindString(1, key)
            }
        }.singleOrNull()
    }

    override suspend fun put(namespace: List<String>, key: String, value: String) {
        Store.checkAddress(namespace, key)
        database {
            val time = nextTime()
            driver.runStatement("INSERT OR REPLACE INTO $table (namespace, key, value, updated_at) VALUES (?, ?, ?, ?)") {
                bindString(0, encodeNamespace(namespace))
                bindString(1, key)
                bindString(2, value)
                bindLong(3, time)
            }
        }
    }

    override suspend fun delete(namespace: List<String>, key: String) {
        Store.checkAddress(namespace, key)
        database {
            driver.runStatement("DELETE FROM $table WHERE namespace = ? AND key = ?") {
                bindString(0, encodeNamespace(namespace))
                bindString(1, key)
            }
        }
    }

    override suspend fun list(namespace: List<String>, limit: Int): List<StoreItem> {
        Store.checkNamespace(namespace)
        Store.checkLimit(limit)
        val columns = "SELECT namespace, key, value, updated_at FROM $table"
        val order = "ORDER BY updated_at DESC, namespace, key LIMIT ?"
        return database {
            if (namespace.isEmpty()) {
                driver.query("$columns $order", { row() }) { bindLong(0, limit.toLong()) }
            } else {
                // Every encoded namespace ends with '/', and '0' comes right after '/': the range holds exactly
                // the namespaces that start with this one, and the primary key finds them.
                val prefix = encodeNamespace(namespace)
                driver.query("$columns WHERE namespace >= ? AND namespace < ? $order", { row() }) {
                    bindString(0, prefix)
                    bindString(1, prefix.dropLast(1) + "0")
                    bindLong(2, limit.toLong())
                }
            }
        }
    }

    /** Runs [block] with the table in place, one call of this store at a time. */
    private suspend fun <Result> database(block: suspend () -> Result): Result =
        lock.withLock {
            withContext(ioDispatcher) {
                if (!tableCreated) {
                    driver.runStatement(createTable(table))
                    lastWrite = driver.query("SELECT MAX(updated_at) FROM $table", { getLong(0) }) {}.singleOrNull()
                    tableCreated = true
                }
                block()
            }
        }

    /**
     * The time of a write, in microseconds: now, or just after the last write when the clock has not moved past it.
     * Microseconds, because the worker of the browser reads an integer as a number of JavaScript, which is exact only
     * up to 2^53: nanoseconds since 1970 are not.
     */
    private fun nextTime(): Long {
        val now = clock.now().let { it.epochSeconds * MICROS_PER_SECOND + it.nanosecondsOfSecond / NANOS_PER_MICRO }
        val last = lastWrite
        return (if (last != null && now <= last) last + 1 else now).also { lastWrite = it }
    }

    private fun SqlCursor.row(): StoreItem {
        // The columns are NOT NULL.
        val encoded = checkNotNull(getString(0))
        val namespace =
            decodeNamespace(encoded)
                ?: throw StoreCorruptedException(listOf(encoded), null, "the table $table has a namespace it did not write")
        val micros = checkNotNull(getLong(3))
        val updatedAt = Instant.fromEpochSeconds(micros.floorDiv(MICROS_PER_SECOND), micros.mod(MICROS_PER_SECOND) * NANOS_PER_MICRO)
        return StoreItem(namespace, checkNotNull(getString(1)), checkNotNull(getString(2)), updatedAt)
    }

    private companion object {
        const val DEFAULT_TABLE = "telar_store"
        const val MICROS_PER_SECOND = 1_000_000L
        const val NANOS_PER_MICRO = 1_000L
        val TABLE_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

        fun createTable(table: String): String =
            "CREATE TABLE IF NOT EXISTS $table (" +
                "namespace TEXT NOT NULL, key TEXT NOT NULL, value TEXT NOT NULL, updated_at INTEGER NOT NULL, " +
                "PRIMARY KEY (namespace, key))"
    }
}

/** Writes [namespace] as one string: each part followed by `/`, with `%` and `/` in a part escaped. */
internal fun encodeNamespace(namespace: List<String>): String =
    namespace.joinToString("") { part -> part.replace("%", "%25").replace("/", "%2F") + "/" }

/** Reads back what [encodeNamespace] wrote, or returns `null` for a string it does not write. */
internal fun decodeNamespace(encoded: String): List<String>? {
    if (encoded.isEmpty()) return emptyList()
    if (!encoded.endsWith("/")) return null
    return encoded
        .dropLast(1)
        .split("/")
        .map { it.replace("%2F", "/").replace("%25", "%") }
        .takeIf { encodeNamespace(it) == encoded }
}
