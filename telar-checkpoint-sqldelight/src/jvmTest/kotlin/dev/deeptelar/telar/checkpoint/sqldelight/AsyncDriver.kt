package dev.deeptelar.telar.checkpoint.sqldelight

import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement

/**
 * A [SqlDriver] that answers like the web worker driver of a browser: every result is a
 * [QueryResult.AsyncValue], also the rows of a cursor, which are read before the mapper sees them.
 * It runs on [delegate], and reads every value as a string. Nothing suspends, because the
 * transactions of [delegate] belong to one thread.
 */
class AsyncDriver(
    private val delegate: SqlDriver,
) : SqlDriver by delegate {
    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> =
        QueryResult.AsyncValue {
            val rows =
                delegate
                    .executeQuery(identifier, sql, { cursor ->
                        QueryResult.Value(buildList { while (cursor.next().value) add(cursor.getString(0)) })
                    }, parameters, binders)
                    .value
            mapper(RowsCursor(rows)).await()
        }

    override fun execute(identifier: Int?, sql: String, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long> =
        QueryResult.AsyncValue {
            delegate.execute(identifier, sql, parameters, binders).value
        }

    override fun newTransaction(): QueryResult<Transacter.Transaction> = QueryResult.AsyncValue { delegate.newTransaction().value }

    private class RowsCursor(
        private val rows: List<String?>,
    ) : SqlCursor {
        private var index = -1

        override fun next(): QueryResult<Boolean> =
            QueryResult.AsyncValue {
                ++index < rows.size
            }

        override fun getString(index: Int): String? = rows[this.index]

        override fun getLong(index: Int): Long? = rows[this.index]?.toLong()

        override fun getBytes(index: Int): ByteArray? = error("not used")

        override fun getDouble(index: Int): Double? = error("not used")

        override fun getBoolean(index: Int): Boolean? = error("not used")
    }
}
