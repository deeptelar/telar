package dev.deeptelar.telar

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

private data class Findings(
    val notes: List<String> = emptyList(),
    val status: String = "",
    val score: Int = 0,
    val topic: String = "wasm",
)

class MergeRulesTest {
    private val rules =
        mergeRules<Findings> {
            append(Findings::notes) { copy(notes = it) }
            replace(Findings::status) { copy(status = it) }
            merge(Findings::score, combine = { _, changed -> changed.max() }) { copy(score = it) }
        }

    private val before = Findings(notes = listOf("start"), status = "new", score = 1)

    @Test
    fun `a graph with rules merges nodes that each return a whole state`() =
        runTest {
            val graph =
                StateGraph<Findings> {
                    val web = node("web") { it.copy(notes = it.notes + "web", score = 3) }
                    val docs = node("docs") { it.copy(notes = it.notes + "docs", status = "found", score = 2) }
                    val sum = node("sum") { it.copy(status = "done") }

                    START then web then sum then END
                    START then docs then sum
                }.compile(reducer = rules)

            assertEquals(
                Findings(notes = listOf("start", "web", "docs"), status = "done", score = 3),
                graph.invoke(before).state,
            )
        }

    @Test
    fun `append adds what each node added in the order of the nodes`() =
        runTest {
            val merged =
                rules.reduce(
                    before,
                    listOf(before.copy(notes = before.notes + "a" + "b"), before, before.copy(notes = before.notes + "c")),
                )

            assertEquals(listOf("start", "a", "b", "c"), merged.notes)
        }

    @Test
    fun `append takes the list of the only node that rewrote it`() =
        runTest {
            assertEquals(listOf("short"), rules.reduce(before, listOf(before.copy(notes = listOf("short")), before)).notes)
            assertEquals(emptyList(), rules.reduce(before, listOf(before, before.copy(notes = emptyList()))).notes)
        }

    @Test
    fun `append cannot combine a rewritten list with another change`() =
        runTest {
            val failure =
                assertFailsWith<MergeRuleException> {
                    rules.reduce(before, listOf(before.copy(notes = listOf("short")), before.copy(notes = before.notes + "a")))
                }

            assertEquals(
                "Property 'notes': a node rewrote the list while another node changed it too. " +
                    "append can only combine what nodes add to the end; use merge to say how they combine.",
                failure.message,
            )
        }

    @Test
    fun `replace takes the value of the node that changed it`() =
        runTest {
            assertEquals("found", rules.reduce(before, listOf(before, before.copy(status = "found"))).status)
            assertEquals("found", rules.reduce(before, listOf(before.copy(status = "found"), before.copy(status = "found"))).status)
            assertEquals("new", rules.reduce(before, listOf(before, before)).status)
        }

    @Test
    fun `replace fails when nodes set different values`() =
        runTest {
            val failure =
                assertFailsWith<MergeRuleException> {
                    rules.reduce(before, listOf(before.copy(status = "found"), before.copy(status = "lost")))
                }

            assertEquals(
                "Property 'status' was set to different values by nodes of the same step: [found, lost]. Use merge to say how they combine.",
                failure.message,
            )
        }

    @Test
    fun `merge combines the changed values and is not asked when nothing changed`() =
        runTest {
            var asked = 0
            val lastWins =
                mergeRules<Findings> {
                    merge(Findings::status, combine = { current, changed ->
                        asked++
                        "$current>${changed.last()}"
                    }) { copy(status = it) }
                }

            assertEquals("new>b", lastWins.reduce(before, listOf(before.copy(status = "a"), before, before.copy(status = "b"))).status)
            assertEquals(before, lastWins.reduce(before, listOf(before, before)))
            assertEquals(1, asked)
        }

    @Test
    fun `a rule gets the values in the order the edges reach the nodes, not the order the nodes were added`() =
        runTest {
            val lastWins =
                mergeRules<Findings> {
                    merge(Findings::status, combine = { _, changed -> changed.last() }) { copy(status = it) }
                    append(Findings::notes) { copy(notes = it) }
                }
            val graph =
                StateGraph<Findings> {
                    val web = node("web") { it.copy(status = "web", notes = it.notes + "web") }
                    val docs = node("docs") { it.copy(status = "docs", notes = it.notes + "docs") }

                    // "web" was added first, but the edge to "docs" was written first.
                    START then docs then END
                    START then web then END
                }.compile(reducer = lastWins)

            assertEquals(Findings(notes = listOf("start", "docs", "web"), status = "web", score = 1), graph.invoke(before).state)
        }

    @Test
    fun `a change to a property without a rule is an error and not lost`() =
        runTest {
            val failure =
                assertFailsWith<MergeRuleException> {
                    rules.reduce(before, listOf(before.copy(score = 5), before.copy(topic = "js", status = "found")))
                }

            assertEquals(
                "Node 2 of the 2 that are merged changed a property that has no merge rule. The rules cover: notes, status, score.",
                failure.message,
            )
        }

    @Test
    fun `a run that breaks a rule fails with the nodes and the cause`() =
        runTest {
            val graph =
                StateGraph<Findings> {
                    START then node("web") { it.copy(status = "found") }
                    START then node("docs") { it.copy(status = "lost") }
                }.compile(reducer = rules)

            val failure = assertFailsWith<ReducerException> { graph.invoke(before) }

            assertEquals(listOf("web", "docs"), failure.nodes)
            assertIs<MergeRuleException>(failure.cause)
        }

    @Test
    fun `rules are checked when they are built`() {
        val none = assertFailsWith<GraphValidationException> { mergeRules<Findings> { } }
        val twice =
            assertFailsWith<GraphValidationException> {
                mergeRules<Findings> {
                    replace(Findings::status) { copy(status = it) }
                    append(Findings::notes) { copy(notes = it) }
                    replace(Findings::status) { copy(status = it) }
                }
            }

        assertEquals("mergeRules needs at least one rule.", none.message)
        assertEquals("mergeRules has two rules for the property 'status'.", twice.message)
    }
}
