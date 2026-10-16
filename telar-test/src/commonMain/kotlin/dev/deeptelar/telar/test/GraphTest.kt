package dev.deeptelar.telar.test

import dev.deeptelar.telar.Checkpoint
import dev.deeptelar.telar.CompiledGraph
import dev.deeptelar.telar.GraphConfig
import dev.deeptelar.telar.GraphEvent
import dev.deeptelar.telar.MemoryCheckpointer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

/**
 * Runs this graph with [input] and checks, in [scenario], the path the run takes: which nodes
 * finish, in which order, where the run pauses and how it ends.
 *
 * ```kotlin
 * @Test
 * fun `high value refund pauses before pay and completes on approval`() = runTest {
 *     refunds.test(Refund("1001", amount = 5000)) {
 *         expectNode("check")
 *         expectInterruptBefore("pay") { state ->
 *             assertEquals("checked", state.status)
 *         }
 *         resumeWith { it.copy(approved = true) }
 *         expectNode("pay")
 *         expectFinished { state ->
 *             assertEquals("paid", state.status)
 *         }
 *     }
 * }
 * ```
 *
 * See [GraphTest] for the checks. They are strict: each one is about what happened right after the
 * one before it, and the test fails if the run did more than [scenario] checked.
 *
 * The run always has a checkpointer, so that a node can pause it and [GraphTest.resumeWith] can
 * continue it: when [config] has none, the test adds a [MemoryCheckpointer].
 *
 * @param config the settings of the run, such as the nodes it pauses before:
 * `GraphConfig(checkpointer = MemoryCheckpointer(), interruptBefore = setOf("pay"))`.
 * @throws AssertionError if the run does not do what [scenario] expects.
 */
public suspend fun <State> CompiledGraph<State>.test(
    input: State,
    config: GraphConfig<State> = GraphConfig(),
    scenario: suspend GraphTest<State>.() -> Unit,
) {
    val test = GraphTest(this, config.withCheckpointer())
    test.start(stream(input, test.config))
    test.scenario()
    test.finish()
}

/**
 * Runs this graph again from [checkpoint], a checkpoint of an earlier run, and checks in [scenario]
 * what it does from there. [scenario] holds the same checks as in [test].
 *
 * Use it to find out why a run went wrong: save a checkpoint of the run, for example the file a
 * `FileCheckpointer` wrote, read it with `CheckpointCodec`, and run this graph from it with a
 * scripted model or the node you fixed. The graph does not have to be the one that saved the
 * checkpoint, only to have its nodes.
 *
 * ```kotlin
 * val history = CheckpointCodec<AgentState>().decodeHistory("ticket-42", readResource("ticket-42.json"))
 * val model = ScriptedChatModel { says("Your pizza is 5 minutes away.") }
 *
 * // The run failed in its fourth step, which was not saved: the last checkpoint is the one after step 3.
 * helpDeskAgent(model).replay(history.last()) {
 *     expectNode("model")
 *     expectFinished { state -> assertEquals("Your pizza is 5 minutes away.", state.answer) }
 * }
 * ```
 *
 * The run continues like [CompiledGraph.fork] does: on the thread of [config], with the step
 * numbers of the run that saved [checkpoint]. When [config] has no checkpointer, the test adds a
 * [MemoryCheckpointer].
 *
 * @param update edits the state of [checkpoint] before the run continues.
 * @throws AssertionError if the run does not do what [scenario] expects.
 */
public suspend fun <State> CompiledGraph<State>.replay(
    checkpoint: Checkpoint<State>,
    config: GraphConfig<State> = GraphConfig(),
    update: suspend (State) -> State = { it },
    scenario: suspend GraphTest<State>.() -> Unit,
) {
    val test = GraphTest(this, config.withCheckpointer())
    test.start(streamFork(checkpoint, test.config, update))
    test.scenario()
    test.finish()
}

private fun <State> GraphConfig<State>.withCheckpointer(): GraphConfig<State> =
    if (checkpointer != null) this else copy(checkpointer = MemoryCheckpointer())

/**
 * The checks of a [test] or a [replay]. Each check is about what happened right after what the
 * check before it was about, so a test reads as the path of the run:
 *
 * | Check | What happened next |
 * |---|---|
 * | `expectNode("check")` | the node `check` finished |
 * | `expectNodes("model", "tools", "model")` | these nodes finished, one after another |
 * | `expectStep("search", "summarize")` | a step ran these nodes, at the same time and in any order |
 * | `expectInterruptBefore("pay")` | the run paused, and `pay` runs when it is resumed |
 * | `expectFinished()` | the run completed |
 * | `expectFailure()` | the run failed |
 *
 * After a pause, [resumeWith] continues the run, and the checks go on with what the run does next.
 * Each check takes a function that gets the state at that point, for the assertions of the test,
 * and also returns that state.
 *
 * The run until its next pause or its end happens before the first check, so a check sees what
 * the run did, and what a scripted model was asked, up to that pause or end. Retries, progress and
 * the events inside a subgraph are not checked here: collect them with [record].
 *
 * Every check that does not hold throws an `AssertionError` that says what happened instead and
 * the path of the run so far. After the last check, the test fails if the run did more: every node
 * that finished, and the pause or end of the run, has to be checked.
 */
public class GraphTest<State> internal constructor(
    private val graph: CompiledGraph<State>,
    internal val config: GraphConfig<State>,
) {
    /** What the current part of the run did: up to its pause or end, or its failure. */
    private var events: List<GraphEvent<State>> = emptyList()
    private var failure: Exception? = null

    /** The index in [events] of the next event to check. [events].size is the failure, if there is one. */
    private var next = 0

    /** What the parts of the run before the current one did, for the messages. */
    private val earlier = mutableListOf<String>()

    /**
     * Checks that [node] finished next, and passes the state it returned to [check]. In a step that
     * ran several nodes this is the node's own result: [expectStep] has the state of the step.
     *
     * @return the state [node] returned.
     */
    public fun expectNode(node: String, check: (State) -> Unit = {}): State {
        val event = peek("node \"$node\"")
        if (event !is GraphEvent.NodeCompleted || event.node != node) fail("node \"$node\"", event)
        next++
        check(event.state)
        return event.state
    }

    /**
     * Checks that [nodes] finished next, one after another in this order. For a graph whose nodes
     * run one at a time, this is the path of the run.
     */
    public fun expectNodes(vararg nodes: String) {
        nodes.forEach { expectNode(it) }
    }

    /**
     * Checks that the next step ran [nodes], all of them and no other, and passes the state after
     * the step, with the results of its nodes combined, to [check]. The nodes of a step run at the
     * same time and finish in any order, so this check, not [expectNode], is the one for parallel
     * branches.
     *
     * @return the state after the step.
     */
    public fun expectStep(vararg nodes: String, check: (State) -> Unit = {}): State {
        val expected = "a step with ${nodes.toList()}"
        // The end of a step whose nodes were checked with expectNode is behind us, not the step to check.
        while (next < events.size && events[next] is GraphEvent.StepCompleted) next++
        var index = next
        while (index < events.size) {
            when (val event = events[index]) {
                is GraphEvent.NodeCompleted -> index++
                is GraphEvent.StepCompleted -> {
                    if (event.nodes.toSet() != nodes.toSet()) fail(expected, event)
                    next = index + 1
                    check(event.state)
                    return event.state
                }
                else -> fail(expected, event)
            }
        }
        next = index
        fail(expected, peek(expected))
    }

    /**
     * Checks that the run paused next, and that [node] runs when it is resumed: the run paused
     * before [node] because of [GraphConfig.interruptBefore], after the node before it because of
     * [GraphConfig.interruptAfter], or in [node] because it called `interrupt`. [check] gets the
     * state of the paused run.
     *
     * @return the state of the paused run.
     */
    public fun expectInterruptBefore(node: String, check: (State) -> Unit = {}): State {
        val expected = "a pause before \"$node\""
        val event = peek(expected)
        if (event !is GraphEvent.Interrupted || node !in event.nextNodes) fail(expected, event)
        next++
        check(event.state)
        return event.state
    }

    /**
     * Checks that the run completed next, and passes its last state to [check].
     *
     * @return the state the run completed with.
     */
    public fun expectFinished(check: (State) -> Unit = {}): State {
        val event = peek("the end of the run")
        if (event !is GraphEvent.Completed) fail("the end of the run", event)
        next++
        check(event.state)
        return event.state
    }

    /**
     * Checks that the run failed next, and passes what it threw to [check]: usually a
     * `NodeExecutionException`, whose `cause` is what the node threw.
     *
     * An `AssertionError`, such as the one of a scripted model whose script is too short, is not a
     * failure of the run: it fails the test.
     *
     * @return what the run threw.
     */
    public fun expectFailure(check: (Exception) -> Unit = {}): Exception {
        val failure = failure
        if (failure == null || events.drop(next).any { it !is GraphEvent.StepCompleted }) fail("a failure", peek("a failure"))
        next = events.size + 1
        check(failure)
        return failure
    }

    /**
     * Continues the paused run, with the state that [update] returns, as [CompiledGraph.resume]
     * does: for example with the decision of the person the run waited for. The checks after this
     * one are about what the run does from there.
     *
     * @throws AssertionError if the run did not pause, or its pause was not checked with
     * [expectInterruptBefore].
     */
    public suspend fun resumeWith(update: suspend (State) -> State = { it }) {
        val unchecked = (next..events.size).firstOrNull { counts(it) }
        if (unchecked != null || events.lastOrNull() !is GraphEvent.Interrupted) {
            val instead = unchecked?.let { describe(it) } ?: "nothing: the run had stopped"
            throw AssertionError(
                "resumeWith continues a paused run after expectInterruptBefore, but the next was $instead. " +
                    "The run so far: ${path()}",
                failure,
            )
        }
        earlier += checked()
        start(graph.streamResume(config, update))
    }

    /** Runs [run] until it pauses, ends or fails, and makes it the part of the run the checks are about. */
    internal suspend fun start(run: Flow<GraphEvent<State>>) {
        val collected = mutableListOf<GraphEvent<State>>()
        failure =
            try {
                run.collect { event -> if (event.isChecked()) collected += event }
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e
            }
        events = collected
        next = 0
    }

    /** Fails if the run did something the test did not check. */
    internal fun finish() {
        val unchecked = (next..events.size).filter { counts(it) }
        if (unchecked.isNotEmpty()) {
            throw AssertionError(
                "The test did not check everything the run did. Not checked: ${unchecked.joinToString { describe(it) }}. " +
                    "The run so far: ${path()}",
                failure,
            )
        }
    }

    /**
     * Returns the next event to check, and moves [next] to it, past the ends of steps, which only
     * [expectStep] checks. Fails at a failure or after the end.
     */
    private fun peek(expected: String): GraphEvent<State> {
        while (next < events.size && events[next] is GraphEvent.StepCompleted) next++
        if (next >= events.size) {
            val instead = if (failure != null && next == events.size) describe(next) else "nothing: the run had stopped"
            throw AssertionError("Expected $expected next, but the next was $instead. The run so far: ${path()}", failure)
        }
        return events[next]
    }

    /** `true` when the event at [index], or the failure after the events, has to be checked. The ends of steps do not. */
    private fun counts(index: Int): Boolean =
        if (index < events.size) events[index] !is GraphEvent.StepCompleted else index == events.size && failure != null

    private fun fail(expected: String, event: GraphEvent<State>): Nothing =
        throw AssertionError("Expected $expected next, but the next was ${describe(event)}. The run so far: ${path()}", failure)

    private fun describe(index: Int): String =
        when {
            index < events.size -> describe(events[index])
            failure != null -> "a failure: $failure"
            else -> "nothing"
        }

    private fun describe(event: GraphEvent<State>): String =
        when (event) {
            is GraphEvent.NodeCompleted -> "node \"${event.node}\" in step ${event.step}"
            is GraphEvent.StepCompleted -> "step ${event.step} with ${event.nodes}"
            is GraphEvent.Interrupted -> "a pause before ${event.nextNodes}"
            is GraphEvent.Completed -> "the end of the run"
            else -> error("Only the events that are checked are kept, not $event")
        }

    /** What the checks of the current part of the run were about, without the ends of steps. */
    private fun checked(): List<String> = (0 until next).filter { counts(it) }.map { describe(it) }

    /** Everything the run did, the part not checked yet in brackets. */
    private fun path(): String {
        val rest =
            (next..events.size).filter { counts(it) }.map { "[${describe(it)}]" }
        return (earlier + checked() + rest).joinToString(", ").ifEmpty { "nothing" }
    }
}

/** `true` for the events that the checks of a [GraphTest] are about. */
private fun GraphEvent<*>.isChecked(): Boolean =
    this is GraphEvent.NodeCompleted || this is GraphEvent.StepCompleted || this is GraphEvent.Interrupted || this is GraphEvent.Completed
