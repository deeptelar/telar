package dev.deeptelar.telar.otel

import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.api.createClientPlugin
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * The span of the node that runs this coroutine, which an [OtlpObserver] puts in the coroutine
 * context of each node. Read it with `currentCoroutineContext()[TraceContext]`, for example to log
 * the trace id next to a message, or let the [Traceparent] plugin send it with each request.
 *
 * @property traceId the id of the trace, 32 hex digits.
 * @property spanId the id of the span of the node, 16 hex digits.
 */
public class TraceContext(
    public val traceId: String,
    public val spanId: String,
) : AbstractCoroutineContextElement(TraceContext) {
    /** The ids as the `traceparent` header of [W3C Trace Context](https://www.w3.org/TR/trace-context/). */
    public val traceparent: String get() = "00-$traceId-$spanId-01"

    override fun toString(): String = "TraceContext($traceparent)"

    /** The key of [TraceContext] in a coroutine context. */
    public companion object Key : CoroutineContext.Key<TraceContext>
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
