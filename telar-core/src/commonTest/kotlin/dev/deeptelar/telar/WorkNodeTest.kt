package dev.deeptelar.telar

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

data class Research(
    val findings: List<String> = emptyList(),
    val sources: Int = 0,
    val summary: String = "",
)

class WorkNodeTest {
    /** Adds a node that takes [millis] to find [finding] and appends it to the findings. */
    private fun StateGraph<Research>.search(name: String, finding: String, millis: Long = 0): NodeRef =
        node(
            name,
            work = {
                delay(millis)
                finding
            },
        ) { state, found -> state.copy(findings = state.findings + found) }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `nodes with work and update run in parallel without a reducer`() =
        runTest {
            val app =
                StateGraph<Research> {
                    val web = search("web", "kotlin", millis = 100)
                    val docs = search("docs", "kotlin", millis = 100)

                    START then web then END
                    START then docs then END
                }.compile()

            val result = app.invoke(Research(findings = listOf("kotlin")))

            // Nothing is compared with the state before the step, so equal findings are all kept.
            assertEquals(listOf("kotlin", "kotlin", "kotlin"), result.state.findings)
            assertEquals(100, currentTime)
        }

    @Test
    fun `updates are applied in the order the nodes were added and the later node wins`() =
        runTest {
            val app =
                StateGraph<Research> {
                    val slow =
                        node(
                            "slow",
                            work = {
                                delay(100)
                                "slow"
                            },
                        ) { state, found -> state.copy(findings = state.findings + found, summary = found) }
                    val fast =
                        node("fast", work = { "fast" }) { state, found -> state.copy(findings = state.findings + found, summary = found) }

                    // The edges name "fast" first and it also finishes first, but "slow" was added first.
                    START then fast then END
                    START then slow then END
                }.compile()

            val result = app.invoke(Research())

            assertEquals(listOf("slow", "fast"), result.state.findings)
            assertEquals("fast", result.state.summary)
        }

    @Test
    fun `an update reads what the update of a node added earlier wrote in the same step`() =
        runTest {
            // "summarize" writes only the summary, but computes it from the sources that "count" writes.
            fun StateGraph<Research>.count(): NodeRef = node("count", work = { 3 }) { state, sources -> state.copy(sources = sources) }

            fun StateGraph<Research>.summarize(): NodeRef =
                node("summarize", work = { "sources" }) { state, word -> state.copy(summary = "${state.sources} $word") }

            val countFirst =
                StateGraph<Research> {
                    val count = count()
                    val summarize = summarize()

                    START then summarize then END
                    START then count then END
                }.compile()
            val summarizeFirst =
                StateGraph<Research> {
                    val summarize = summarize()
                    val count = count()

                    START then summarize then END
                    START then count then END
                }.compile()

            // The two nodes write different properties, and the order they were added in still decides the result.
            assertEquals(Research(sources = 3, summary = "3 sources"), countFirst.invoke(Research()).state)
            assertEquals(Research(sources = 3, summary = "0 sources"), summarizeFirst.invoke(Research()).state)
        }

    @Test
    fun `changes to different properties are all kept`() =
        runTest {
            val app =
                StateGraph<Research> {
                    val count = node("count", work = { 3 }) { state, sources -> state.copy(sources = sources) }
                    val summarize = node("summarize", work = { "done" }) { state, summary -> state.copy(summary = summary) }

                    START then count then END
                    START then summarize then END
                }.compile()

            assertEquals(Research(sources = 3, summary = "done"), app.invoke(Research()).state)
        }

    @Test
    fun `a node with work and update can also run alone`() =
        runTest {
            val app =
                StateGraph<Research> {
                    val web = search("web", "kotlin")

                    START then web then END
                }.compile()

            assertEquals(GraphResult.Completed(Research(findings = listOf("kotlin"))), app.invoke(Research()))
        }

    @Test
    fun `one node that returns a state can run next to nodes with work and update`() =
        runTest {
            val app =
                StateGraph<Research> {
                    val plan = node("plan") { it.copy(summary = "planned") }
                    val web = search("web", "kotlin")
                    val docs = search("docs", "coroutines")

                    START then plan then END
                    START then web then END
                    START then docs then END
                }.compile()

            assertEquals(Research(findings = listOf("kotlin", "coroutines"), summary = "planned"), app.invoke(Research()).state)
        }

    @Test
    fun `the reducer only merges the nodes that return a state`() =
        runTest {
            var merged = 0
            val app =
                StateGraph<Research> {
                    val one = node("one") { it.copy(sources = 1) }
                    val two = node("two") { it.copy(sources = 2) }
                    val web = search("web", "kotlin")

                    START then one then END
                    START then two then END
                    START then web then END
                }.compile { current, updates ->
                    merged = updates.size
                    current.copy(sources = updates.sumOf { it.sources })
                }

            assertEquals(Research(findings = listOf("kotlin"), sources = 3), app.invoke(Research()).state)
            assertEquals(2, merged)
        }

    @Test
    fun `stream reports the own result of each node and the combined state of the step`() =
        runTest {
            val app =
                StateGraph<Research> {
                    val web = search("web", "kotlin")
                    val docs = search("docs", "coroutines", millis = 10)

                    START then web then END
                    START then docs then END
                }.compile()
            val both = Research(findings = listOf("kotlin", "coroutines"))

            assertEquals(
                listOf(
                    GraphEvent.NodeStarted(1, "web", Research()),
                    GraphEvent.NodeStarted(1, "docs", Research()),
                    GraphEvent.NodeCompleted(1, "web", Research(findings = listOf("kotlin"))),
                    GraphEvent.NodeCompleted(1, "docs", Research(findings = listOf("coroutines"))),
                    GraphEvent.StepCompleted(1, listOf("web", "docs"), both),
                    GraphEvent.Completed(both),
                ),
                app.stream(Research()).toList(),
            )
        }

    @Test
    fun `failing work is reported with its node`() =
        runTest {
            val app =
                StateGraph<Research> {
                    val web = node<String>("web", work = { error("offline") }) { state, found -> state.copy(summary = found) }
                    val docs = search("docs", "coroutines")

                    START then web then END
                    START then docs then END
                }.compile()

            val exception = assertFailsWith<NodeExecutionException> { app.invoke(Research()) }

            assertEquals("web", exception.nodeName)
            assertEquals("offline", exception.cause?.message)
        }

    @Test
    fun `an update that fails while the results are combined is reported with its node`() =
        runTest {
            val app =
                StateGraph<Research> {
                    val web = search("web", "kotlin")
                    // Works on the state the node received, and fails on the state that "web" has updated.
                    val docs =
                        node("docs", work = { "coroutines" }) { state, found ->
                            if (state.findings.isEmpty()) state.copy(findings = listOf(found)) else error("already has findings")
                        }

                    START then web then END
                    START then docs then END
                }.compile()

            val exception = assertFailsWith<NodeExecutionException> { app.invoke(Research()) }

            assertEquals("docs", exception.nodeName)
        }

    @Test
    fun `branches of nodes with work and update can join in a node that returns a state`() =
        runTest {
            var joins = 0
            val app =
                StateGraph<Research> {
                    val web = search("web", "kotlin")
                    val docs = search("docs", "coroutines")
                    val summarize =
                        node("summarize") {
                            joins++
                            it.copy(summary = it.findings.joinToString())
                        }

                    START then web then summarize
                    START then docs then summarize
                    summarize then END
                }.compile()

            assertEquals("kotlin, coroutines", app.invoke(Research()).state.summary)
            assertEquals(1, joins)
        }

    @Test
    fun `branches that reach two nodes that return a state in the same step need a reducer`() {
        val graph =
            StateGraph<Research> {
                val web = search("web", "kotlin")
                val docs = search("docs", "coroutines")
                val count = node("count") { it.copy(sources = it.findings.size) }
                val summarize = node("summarize") { it.copy(summary = it.findings.joinToString()) }

                START then web then count then END
                START then docs then summarize then END
            }

        val exception = assertFailsWith<GraphValidationException> { graph.compile() }

        assertEquals(
            "Nodes 'count' and 'summarize' can run in the same step and both return a whole state, " +
                "so compile() needs a Reducer, or those nodes need a work and an update",
            exception.message,
        )
        graph.compile { current, _ -> current }
    }

    @Test
    fun `branches whose nodes that return a state run in different steps need no reducer`() =
        runTest {
            val app =
                StateGraph<Research> {
                    val web = search("web", "kotlin")
                    val docs = search("docs", "coroutines")
                    val more = search("more", "flows")
                    val count = node("count") { it.copy(sources = it.findings.size) }
                    val summarize = node("summarize") { it.copy(summary = it.findings.joinToString()) }

                    // "count" runs in step 2 next to "more", and "summarize" runs alone in step 3.
                    START then web then count then END
                    START then docs then more then summarize then END
                }.compile()

            assertEquals(
                Research(findings = listOf("kotlin", "coroutines", "flows"), sources = 2, summary = "kotlin, coroutines, flows"),
                app.invoke(Research()).state,
            )
        }

    @Test
    fun `a conditional edge without targets may lead to any node`() {
        val graph =
            StateGraph<Research> {
                val web = search("web", "kotlin")
                val docs = search("docs", "coroutines")
                val count = node("count") { it.copy(sources = it.findings.size) }
                val summarize = node("summarize") { it.copy(summary = it.findings.joinToString()) }

                START then web
                START then docs then count then END
                // Nothing says where "web" leads, so it could be "summarize", in the same step as "count".
                conditionalEdge(web) { END }
                summarize then END
            }

        val exception = assertFailsWith<GraphValidationException> { graph.compile() }

        assertTrue("can run in the same step" in exception.message.orEmpty(), exception.message)
        graph.compile { current, _ -> current }
    }
}
