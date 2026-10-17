package dev.deeptelar.telar

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ConditionalFanOutTest {
    /** Adds a node that takes [millis] to find [finding] and appends it to the findings. */
    private fun StateGraph<Research>.search(name: String, finding: String = name, millis: Long = 0): NodeRef =
        node(
            name,
            work = {
                delay(millis)
                finding
            },
        ) { state, found -> state.copy(findings = state.findings + found) }

    /** `plan` fans out to the sources that [pick] returns; `locate` leads to both sources. */
    private fun research(pick: (Research) -> Set<String>): CompiledGraph<Research> =
        StateGraph<Research> {
            val plan = node("plan") { it }
            val locate = search("locate")
            val web = search("web", millis = 100)
            val docs = search("docs", millis = 100)
            val routes = mapOf("locate" to locate, "web" to web, "docs" to docs, END to NodeRef.END)

            START then plan
            conditionalFanOut(
                plan,
                targets = setOf(locate, web, docs, NodeRef.END),
            ) { state -> pick(state).mapTo(mutableSetOf(), routes::getValue) }
            locate then web
            locate then docs
            web then END
            docs then END
        }.compile()

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `the targets it picks run in parallel in the next step`() =
        runTest {
            val steps =
                research { setOf("web", "docs") }
                    .stream(Research())
                    .filterIsInstance<GraphEvent.StepCompleted<Research>>()
                    .toList()

            assertEquals(listOf(listOf("plan"), listOf("web", "docs")), steps.map { it.nodes })
            assertEquals(listOf("web", "docs"), steps.last().state.findings)
            assertEquals(100, currentTime)
        }

    @Test
    fun `one target is a branch like that of a conditional edge`() =
        runTest {
            val result = research { setOf("locate") }.invoke(Research())

            assertEquals(listOf("locate", "web", "docs"), result.state.findings)
        }

    @Test
    fun `END or no target ends the branch`() =
        runTest {
            assertEquals(listOf("web"), research { setOf("web", END) }.invoke(Research()).state.findings)
            assertIs<GraphResult.Completed<Research>>(research { emptySet() }.invoke(Research()))
        }

    @Test
    fun `a run paused before the fan-out resumes with every target`() =
        runTest {
            val config = GraphConfig(checkpointer = MemoryCheckpointer<Research>(), interruptBefore = setOf("web"))
            val graph = research { setOf("web", "docs") }

            val paused = assertIs<GraphResult.Interrupted<Research>>(graph.invoke(Research(), config))
            assertEquals(listOf("web", "docs"), paused.nextNodes)
            assertEquals(listOf("web", "docs"), graph.resume(config).state.findings)
        }

    @Test
    fun `a target that is not declared fails the run`() =
        runTest {
            val graph =
                StateGraph<Research> {
                    val plan = node("plan") { it }
                    val web = search("web")
                    val docs = search("docs")
                    // docs is reached from START, but not from plan.
                    START then plan
                    START then docs
                    conditionalFanOut(plan, targets = setOf(web)) { setOf(web, docs) }
                    docs then END
                }.compile()

            val error = assertFailsWith<InvalidRouteException> { graph.invoke(Research()) }
            assertEquals("docs", error.target)
        }

    @Test
    fun `two targets that return a whole state need a reducer`() {
        val graph =
            StateGraph<Research> {
                val plan = node("plan") { it }
                val web = node("web") { it }
                val docs = node("docs") { it }
                START then plan
                conditionalFanOut(plan, targets = setOf(web, docs)) { setOf(web) }
            }

        val message = assertFailsWith<GraphValidationException> { graph.compile() }.message.orEmpty()
        assertContains(message, "Nodes 'web' and 'docs' can run in the same step")
    }
}
