package dev.deeptelar.telar.otel

import dev.deeptelar.telar.END
import dev.deeptelar.telar.GraphConfig
import dev.deeptelar.telar.START
import dev.deeptelar.telar.StateGraph
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FetchExportTest {
    @Test
    fun `a collector that cannot be reached is reported, and the run goes on`() =
        runTest {
            val failures = mutableListOf<OtlpExportException>()
            // Nothing listens on this port. In a browser, fetch fails with a plain Error of Ktor.
            val tracing =
                OtlpObserver(
                    HttpClient(Js),
                    backgroundScope,
                    endpoint = "http://127.0.0.1:59999/v1/traces",
                    onExportFailed = { failures += it },
                )
            val graph = StateGraph<Ticket> { START then node("read") { it.copy(answer = "done") } then END }.compile()

            val result = graph.invoke(Ticket(), GraphConfig(observers = listOf(tracing)))
            tracing.flush()

            assertEquals("done", result.state.answer)
            assertTrue(
                failures.single().message!!.startsWith("Could not send 2 spans to http://127.0.0.1:59999/v1/traces"),
                failures.single().message,
            )
        }
}
