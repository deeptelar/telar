package dev.deeptelar.telar.otel

import dev.deeptelar.telar.GraphValidationException
import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.api.createClientPlugin
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * The ids of a span in the coroutine context. An [OtlpObserver] puts one in the context of each
 * node, with the span of the node. Read it with `currentCoroutineContext()[TraceContext]`, for
 * example to log the trace id next to a message, or let the [Traceparent] plugin send it with each
 * request.
 *
 * The app can put one around a run too, and the [OtlpObserver] records the run inside that span. A
 * server continues the trace of the request that started the run:
 *
 * ```kotlin
 * val trace = TraceContext.parse(call.request.headers["traceparent"])
 * withContext(trace ?: EmptyCoroutineContext) { graph.invoke(ticket, config) }
 * ```
 *
 * A run started inside a node traced by an [OtlpObserver], such as a graph that a tool runs, is in
 * the span of that node without anything more.
 *
 * @property traceId the id of the trace, 32 lowercase hex digits, not all zero.
 * @property spanId the id of the span, 16 lowercase hex digits, not all zero.
 * @throws GraphValidationException if [traceId] or [spanId] is not such an id.
 */
public class TraceContext(
    public val traceId: String,
    public val spanId: String,
) : AbstractCoroutineContextElement(TraceContext) {
    init {
        if (!traceId.isId(TRACE_ID_LENGTH) || !spanId.isId(SPAN_ID_LENGTH)) {
            throw GraphValidationException(
                "A TraceContext needs a trace id of $TRACE_ID_LENGTH and a span id of $SPAN_ID_LENGTH lowercase hex digits, " +
                    "not all zero, but got '$traceId' and '$spanId'",
            )
        }
    }

    /** The ids as the `traceparent` header of [W3C Trace Context](https://www.w3.org/TR/trace-context/). */
    public val traceparent: String get() = "00-$traceId-$spanId-01"

    override fun toString(): String = "TraceContext($traceparent)"

    /** The key of [TraceContext] in a coroutine context. */
    public companion object Key : CoroutineContext.Key<TraceContext> {
        /**
         * The span of a `traceparent` header of [W3C Trace Context](https://www.w3.org/TR/trace-context/),
         * such as `00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01`. `null` when [traceparent]
         * is `null` or not a valid header, which a server ignores, as the standard asks.
         */
        public fun parse(traceparent: String?): TraceContext? {
            val parts = traceparent?.trim()?.split('-') ?: return null
            if (parts.size < TRACEPARENT_PARTS) return null
            val (version, traceId, spanId, flags) = parts
            // A later version may add fields after the flags; version 00 has none, and ff is invalid.
            val validVersion = version.isHex(2) && version != "ff" && (version != "00" || parts.size == TRACEPARENT_PARTS)
            if (!validVersion || !flags.isHex(2) || !traceId.isId(TRACE_ID_LENGTH) || !spanId.isId(SPAN_ID_LENGTH)) return null
            return TraceContext(traceId, spanId)
        }
    }
}

/**
 * A plugin of Ktor's `HttpClient` that sends the `traceparent` header with each request made inside
 * a node traced by an [OtlpObserver], so that the server records its spans inside the span of the
 * node. A request outside a traced node, or with a `traceparent` of its own, is sent as it is.
 *
 * ```kotlin
 * val api = HttpClient(Js) { install(Traceparent) }
 * ```
 *
 * Install it in the clients that call servers of your own. A provider of models does not need the
 * header.
 */
public val Traceparent: ClientPlugin<Unit> =
    createClientPlugin("Traceparent") {
        onRequest { request, _ ->
            val trace = currentCoroutineContext()[TraceContext] ?: return@onRequest
            if (!request.headers.contains(TRACEPARENT)) request.headers.append(TRACEPARENT, trace.traceparent)
        }
    }

private const val TRACEPARENT = "traceparent"
private const val TRACEPARENT_PARTS = 4
private const val TRACE_ID_LENGTH = 32
private const val SPAN_ID_LENGTH = 16

private fun String.isHex(digits: Int): Boolean = length == digits && all { it in '0'..'9' || it in 'a'..'f' }

/** An id of OpenTelemetry: lowercase hex digits, and not all zero, which means no id. */
private fun String.isId(digits: Int): Boolean = isHex(digits) && any { it != '0' }
