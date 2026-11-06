package dev.deeptelar.telar.checkpoint.sqldelight

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement

/** Runs [sql], a statement that returns no rows, and awaits it. */
internal suspend fun SqlDriver.runStatement(sql: String, binders: (SqlPreparedStatement.() -> Unit)? = null) {
    execute(null, sql, sql.count { it == '?' }, binders).await()
}

/**
 * Returns the rows of [sql], each read with [column]. A driver that blocks has its rows read at
 * once, while its cursor is open; an asynchronous driver is awaited for each row.
 */
internal suspend fun <Value> SqlDriver.query(
    sql: String,
    column: SqlCursor.() -> Value,
    binders: SqlPreparedStatement.() -> Unit,
): List<Value> =
    executeQuery(
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
