package dev.deeptelar.telar

import kotlin.time.Duration

/**
 * Base class for every error raised by Telar itself, so callers can catch the whole family
 * with a single `catch (e: TelarException)`. Integration modules and custom [Checkpointer]s may
 * add their own subclasses.
 */
public abstract class TelarException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * The graph definition or its run configuration is invalid: for example an edge points at an unknown
 * node, or a fan-out has no [Reducer]. Thrown while building or compiling the graph, or when a run
 * starts. The only check that waits for a node to run is the one of [interrupt], which needs a
 * checkpointer.
 */
public class GraphValidationException(
    message: String,
) : TelarException(message)

/**
 * The graph ran more steps than [GraphConfig.maxIterations] allows, which usually means a cycle
 * never routes to [END].
 */
public class MaxIterationsExceededException(
    public val maxIterations: Int,
) : TelarException("Graph execution exceeded max iterations ($maxIterations). Possible infinite loop.")

/**
 * A conditional edge from [from] returned [target], which is not a node of the graph or not one of
 * the edge's declared targets.
 */
public class InvalidRouteException(
    public val from: String,
    public val target: String,
) : TelarException("Conditional edge from '$from' routed to '$target', which is not a valid target.")

/**
 * Whether this is a failure of the code that threw it, which the engine reports with the part of
 * the graph that failed.
 *
 * An error of the program or of its machine is not: a subclass of `Error`, such as
 * `OutOfMemoryError`, `AssertionError` or the `NotImplementedError` of `TODO()`. A retry would not
 * help, and a test that asserts inside a node should fail with its own error. The pause of
 * [interrupt] travels as such a subclass too.
 *
 * Everything else is a failure. That is every `Exception`, and what a browser throws in their
 * place: Ktor reports a request that got no response as a plain `Error`, and Kotlin/Wasm reports an
 * error of JavaScript as a `JsException`, which is a `Throwable` and nothing more.
 */
internal fun Throwable.isFailure(): Boolean = this !is Error || this::class == Error::class

/**
 * A node's action threw an exception. The original exception is available as [cause].
 *
 * Every exception is wrapped, also a [TelarException]: when a node runs another graph or calls a
 * model of an integration module, [cause] is the exception of that graph or model. So is what a
 * browser throws in place of an exception, such as the `Error` of a request that got no response.
 * An error of the program or of its machine is not wrapped: a subclass of `Error`, such as
 * `OutOfMemoryError`, `AssertionError` or the `NotImplementedError` of `TODO()`.
 *
 * Cancellation of the run itself is never wrapped: it propagates as a `CancellationException`. A
 * `CancellationException` that a node raises while the run is still active, such as an expired
 * `withTimeout` inside the node, is a failure of that node and is wrapped like any other.
 *
 * @property attempts how often the node ran before the run failed. More than 1 only for a node with
 * a [Retry]. For a node that runs for each item of a list, how often [item] ran.
 * @property item the position of the item that failed in the list of a node that runs for each item
 * of a list. `null` for every other failure.
 */
public class NodeExecutionException(
    public val nodeName: String,
    cause: Throwable,
    public val attempts: Int = 1,
    public val item: Int? = null,
) : TelarException(
        "Node '$nodeName' failed${item?.let { " for item $it" }.orEmpty()}" +
            "${if (attempts > 1) " after $attempts attempts" else ""}: ${cause.message}",
        cause,
    )

/**
 * A try of a node took longer than the [timeout] the node was added with, and was cancelled. It is
 * the `cause` of the [NodeExecutionException] the run fails with, and what the `retryOn` of a
 * [Retry] gets.
 */
public class NodeTimeoutException(
    public val timeout: Duration,
) : TelarException("The node did not finish within $timeout.")

/**
 * The condition of the conditional edge from [from] threw an exception. The original exception is
 * available as [cause]. Cancellation is handled as for [NodeExecutionException].
 */
public class EdgeConditionException(
    public val from: String,
    cause: Throwable,
) : TelarException("Conditional edge from '$from' failed: ${cause.message}", cause)

/**
 * The [Reducer] threw an exception while it merged the results of [nodes], the nodes of the failed
 * step that each returned a whole state. The original exception is available as [cause]. Cancellation is
 * handled as for [NodeExecutionException].
 */
public class ReducerException(
    public val nodes: List<String>,
    cause: Throwable,
) : TelarException("Reducer failed to merge the results of $nodes: ${cause.message}", cause)

/**
 * A reducer built with [mergeRules] could not merge the states of a step: a node changed a property
 * that has no rule, or two nodes changed a property in ways its rule cannot combine. It is the
 * `cause` of the [ReducerException] the run fails with.
 */
public class MergeRuleException(
    message: String,
) : TelarException(message)

/**
 * [CompiledGraph.resume] was called for [threadId], but the checkpointer has no checkpoint for it.
 */
public class CheckpointNotFoundException(
    public val threadId: String,
) : TelarException("No checkpoint found for thread '$threadId'. Start the run with invoke() first.")

/**
 * [CompiledGraph.resume] was called for [threadId], but its last run already completed. Start a new
 * run with [CompiledGraph.invoke].
 */
public class GraphAlreadyCompletedException(
    public val threadId: String,
) : TelarException("Thread '$threadId' already ran to completion, so there is nothing to resume.")

/**
 * [CompiledGraph.fork] was asked to start the thread [threadId], which already has a checkpoint. A
 * fork starts a thread of its own, so that it cannot overwrite a run. Choose another id, or delete
 * the thread first.
 */
public class ThreadAlreadyExistsException(
    public val threadId: String,
) : TelarException("Thread '$threadId' already has a checkpoint. fork() starts a new thread: choose another id, or delete this one first.")

/**
 * The stored checkpoint of [threadId] could not be read, for example because the file is damaged or
 * was written in a newer format. [Checkpointer] implementations throw this from [Checkpointer.load].
 */
public class CheckpointCorruptedException(
    public val threadId: String,
    message: String,
    cause: Throwable? = null,
) : TelarException("Checkpoint of thread '$threadId' cannot be read: $message", cause)
