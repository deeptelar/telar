package dev.deeptelar.telar

import kotlin.jvm.JvmName
import kotlin.time.Duration

/**
 * Builder for a graph of nodes and edges over an immutable [State].
 *
 * ```kotlin
 * val graph = StateGraph<AgentState> {
 *     node("research") { state -> state.copy(notes = search(state.question)) }
 *     node("write") { state -> state.copy(answer = draft(state.notes)) }
 *
 *     edge(START, "research")
 *     conditionalEdge("research", targets = setOf("write", END)) { state ->
 *         if (state.notes.isEmpty()) END else "write"
 *     }
 *     edge("write", END)
 * }.compile()
 * ```
 *
 * Execution starts at [START] and ends when every active branch reaches [END]. A node with no
 * outgoing edge routes to [END].
 *
 * [node] returns a [NodeRef]. Using the references with [then] and [conditionalEdge] instead of
 * repeating node names lets the compiler catch typos:
 *
 * ```kotlin
 * val graph = StateGraph<AgentState> {
 *     val research = node("research") { ... }
 *     val write = node("write") { ... }
 *
 *     START then research
 *     conditionalEdge(research, targets = setOf(write, NodeRef.END)) { state ->
 *         if (state.notes.isEmpty()) NodeRef.END else write
 *     }
 *     write then END
 * }.compile()
 * ```
 */
public class StateGraph<State> {
    private val nodes = mutableMapOf<String, Node<State>>()
    private val edges = mutableListOf<Edge>()
    private val conditionalEdges = mutableListOf<ConditionalEdge<State>>()

    /**
     * Adds a node named [name] that runs [action] and returns a reference to it for use with [then].
     *
     * @throws GraphValidationException if the name is blank, reserved ([START], [END]), holds a `/` or is
     * already used.
     */
    public fun node(name: String, action: NodeAction<State>): NodeRef = add(stateNode(name, action = action))

    /**
     * Adds a node that is tried again when it fails, that has a time limit, or both.
     *
     * ```kotlin
     * val fetch = node("fetch", retry = Retry(attempts = 3), timeout = 30.seconds) { order ->
     *     order.copy(stock = warehouse.stock(order.item))
     * }
     * ```
     *
     * With a [retry], a node that fails runs again on the same state, in the same step: see [Retry]
     * for how often and with which pause. Only when its last try fails does the run fail, with a
     * [NodeExecutionException]. Nothing is saved between two tries, and the other nodes of the step
     * go on while this one waits for its next try. A stream gets a [GraphEvent.NodeRetry] for each
     * try that failed.
     *
     * With a [timeout], a try that takes longer is cancelled and fails with a
     * [NodeTimeoutException], which a [retry] treats like any other failure. The limit stops code
     * that suspends, such as a call with a Ktor client. Code that blocks its thread goes on until
     * it returns, and the node fails then.
     *
     * A node that is tried again starts from its first line, so make its side effects safe to
     * repeat.
     *
     * @param retry how the node is tried again after a failure. `null` runs it once.
     * @param timeout how long one try may take. `null` is no limit.
     * @throws GraphValidationException if the name is blank, reserved ([START], [END]), holds a `/` or
     * is already used, or [timeout] is not positive.
     */
    public fun node(name: String, retry: Retry? = null, timeout: Duration? = null, action: NodeAction<State>): NodeRef =
        add(stateNode(name, retry, checked(name, timeout), action))

    /**
     * Adds a node whose job is split in two, so that it can run next to other nodes without a
     * [Reducer]. [work] does the slow part, such as a call to a model or a service, and returns a
     * result. [update] writes that result into the state.
     *
     * ```kotlin
     * val web = node("web", work = { searchWeb(it.question) }) { state, found ->
     *     state.copy(findings = state.findings + found)
     * }
     * ```
     *
     * When several nodes run in the same step, their [work] runs in parallel on the same state.
     * Their updates are then applied one after another, in the order the nodes were added to the
     * graph, each to the state that the previous one produced. No change is lost, and if two nodes
     * write the same property, the node that was added later wins. A graph compiled with
     * [mergeRules], given as `rules` or as `reducer`, works differently: each update gets the state
     * from before the step, and the rules merge the results (see [compile]).
     *
     * [update] must only build the new state. The engine may call it more than once for one run of
     * the node.
     *
     * @throws GraphValidationException if the name is blank, reserved ([START], [END]), holds a `/` or is
     * already used.
     */
    public fun <Result> node(name: String, work: suspend (State) -> Result, update: suspend (State, Result) -> State): NodeRef =
        add(workNode(name, work, update = update))

    /**
     * Adds a node with a [work] and an [update] that is tried again when it fails, that has a time
     * limit, or both. See the overload of a node that returns a whole state for what [retry] and
     * [timeout] do.
     *
     * ```kotlin
     * val classify = node(
     *     "classify",
     *     work = { email -> model.ask<Verdict>("Classify this email: ${email.body}") },
     *     retry = Retry(attempts = 3),
     * ) { email, verdict -> email.copy(category = verdict.category) }
     * ```
     *
     * A try is the [work] of the node. In a step with several nodes, the [update] runs once, after
     * the work of every node of the step has succeeded.
     *
     * @param retry how the node is tried again after a failure. `null` runs it once.
     * @param timeout how long one try may take. `null` is no limit.
     * @throws GraphValidationException if the name is blank, reserved ([START], [END]), holds a `/` or
     * is already used, or [timeout] is not positive.
     */
    public fun <Result> node(
        name: String,
        work: suspend (State) -> Result,
        retry: Retry? = null,
        timeout: Duration? = null,
        update: suspend (State, Result) -> State,
    ): NodeRef = add(workNode(name, work, retry, checked(name, timeout), update))

    /**
     * Adds a node that runs its [work] once for each item of a list, all at the same time, when the
     * number of items is only known at run time: summarize every document a search found, or ask a
     * model about each line of an order. [forEach] reads the list from the state, and [update]
     * writes the results into the state, in the order of the items.
     *
     * ```kotlin
     * val summarize = node(
     *     "summarize",
     *     forEach = { search -> search.documents },
     *     work = { search, document -> model.ask<Summary>("Summarize: ${document.text}") },
     *     serializer = KotlinxStateSerializer(),
     * ) { search, summaries -> search.copy(summaries = summaries) }
     * ```
     *
     * Every item gets the state from before the step, and [update] runs once, after the work of
     * every item has succeeded, with an empty list when [forEach] returns none. To the rest of the
     * graph it is a node with a `work` and an `update`: it can run next to other nodes without a
     * [Reducer], and [GraphConfig.interruptBefore] and [GraphConfig.interruptAfter] name the whole
     * node.
     *
     * What to know:
     * - **Each item is tried on its own.** [retry] and [timeout] hold for one item: an item that
     *   fails is tried again while the others go on. When its last try fails, the run fails with a
     *   [NodeExecutionException] whose [item][NodeExecutionException.item] is the position of the
     *   item, and the items still running are cancelled.
     * - **With a [serializer], a finished item is not run again.** Its result is saved in the
     *   checkpoint as soon as it is there, as an [effect] is. When the step runs again, after a
     *   failure, a crash or an [interrupt] from the work of an item, only the items without a result
     *   run. The results belong to the positions in the list, so a [CompiledGraph.resume] that
     *   changes the list should not keep them: change the list in a later step instead. Without a
     *   serializer, every item runs again, as any node does. With `telar-serialization`,
     *   `KotlinxStateSerializer()` stores a `@Serializable` result.
     * - **An [effect] in [work] belongs to its item**, so `effect("charge") { ... }` runs once for
     *   each item without a key of its own for each.
     * - **A stream shows the items.** The node has one [GraphEvent.NodeStarted] and one
     *   [GraphEvent.NodeCompleted], and a [GraphEvent.ItemCompleted] for each item, in between.
     *   What an item passes to [reportProgress] and the retries of an item say which item it is.
     * - **[concurrency] limits how many items run at the same time.** There is no limit by default,
     *   so 500 items are 500 calls to a model at once: set one for a service with a rate limit.
     *
     * [forEach], [work] and [update] must only read and build. The engine may call [update] more
     * than once for one run of the node.
     *
     * @param forEach reads the items from the state.
     * @param work does the work of one item, with the state of the node.
     * @param serializer stores the result of an item as a string and reads it back. `null` saves no
     * results.
     * @param concurrency how many items may run at the same time.
     * @param retry how an item is tried again after a failure. `null` runs it once.
     * @param timeout how long one try of an item may take. `null` is no limit.
     * @throws GraphValidationException if the name is blank, reserved ([START], [END]), holds a `/` or
     * is already used, [concurrency] is not positive, or [timeout] is not positive.
     */
    public fun <Item, Result> node(
        name: String,
        forEach: suspend (State) -> List<Item>,
        work: suspend (State, Item) -> Result,
        serializer: StateSerializer<Result>? = null,
        concurrency: Int = Int.MAX_VALUE,
        retry: Retry? = null,
        timeout: Duration? = null,
        update: suspend (State, List<Result>) -> State,
    ): NodeRef {
        if (concurrency <= 0) throw GraphValidationException("The concurrency of node '$name' must be positive, but was $concurrency.")
        return add(forEachNode(name, forEach, work, serializer, concurrency, retry, checked(name, timeout), update))
    }

    private fun checked(node: String, timeout: Duration?): Duration? {
        if (timeout != null && !timeout.isPositive()) {
            throw GraphValidationException("The timeout of node '$node' must be positive, but was $timeout.")
        }
        return timeout
    }

    /**
     * Adds a node that runs another graph, a *subgraph*, on a state of its own type.
     *
     * [state] reads the state of the subgraph out of the state of this graph, and [update] writes it
     * back:
     *
     * ```kotlin
     * val research = subgraph(
     *     "research", researchGraph,
     *     state = { order -> order.research },
     *     update = { order, research -> order.copy(research = research) },
     * )
     * START then intake then research then reply then END
     * ```
     *
     * The subgraph runs from its [START] to its [END] inside one step of this graph, and the node
     * behaves like one with a `work` and an `update`: it can run next to other nodes without a
     * [Reducer].
     *
     * When a node of the subgraph calls [interrupt], this graph pauses too. [update] then writes
     * the state of the subgraph into the state that is saved, so [GraphResult.Interrupted] shows
     * what the subgraph asks, and [CompiledGraph.resume] continues inside the subgraph, at the
     * node that paused, with what [state] reads from the saved state. For that to work, [state] has
     * to return what [update] stored: keep the whole state of the subgraph in a property of this
     * graph's state, as above. A subgraph that never pauses may also get a new state on every visit
     * and give back only its result:
     *
     * ```kotlin
     * state = { order -> Research(topic = order.topic) },
     * update = { order, research -> order.copy(findings = research.findings) },
     * ```
     *
     * Both functions must only read and build states. The engine may call them more than once for
     * one run of the node. A failure inside the subgraph is the `cause` of this node's
     * [NodeExecutionException], and [GraphConfig.maxIterations] limits the steps of the subgraph
     * on their own, for each visit.
     *
     * @throws GraphValidationException if the name is blank, reserved ([START], [END]), holds a `/` or is
     * already used.
     */
    public fun <Child> subgraph(
        name: String,
        graph: CompiledGraph<Child>,
        state: suspend (State) -> Child,
        update: suspend (State, Child) -> State,
    ): NodeRef = add(subgraphNode(name, graph, returnsState = false, state, update))

    /**
     * Adds a node that runs another graph with the same state type, a *subgraph*. It receives the
     * state of this graph and its result is the next state, like a node that returns a whole state.
     *
     * A node of the subgraph can pause the run with [interrupt], and [CompiledGraph.resume]
     * continues inside the subgraph. See the overload with a `state` and an `update`.
     *
     * @throws GraphValidationException if the name is blank, reserved ([START], [END]), holds a `/` or is
     * already used.
     */
    public fun subgraph(name: String, graph: CompiledGraph<State>): NodeRef =
        add(subgraphNode(name, graph, returnsState = true, state = { it }, update = { _, result -> result }))

    private fun add(node: Node<State>): NodeRef {
        val name = node.name
        if (name.isBlank()) throw GraphValidationException("Node name must not be blank.")
        if (name == START || name == END) throw GraphValidationException("'$name' is a reserved node name.")
        if (PATH_SEPARATOR in name) {
            throw GraphValidationException(
                "Node name '$name' must not contain '$PATH_SEPARATOR', which joins the names in the path to a node of a subgraph.",
            )
        }
        if (name in nodes) throw GraphValidationException("Node with name '$name' already exists.")
        nodes[name] = node
        return NodeRef(name)
    }

    /**
     * Adds a static edge: after [from] finishes, [to] runs. Several static edges from the same node
     * fan out and run their targets in parallel. See [compile] for when that needs a [Reducer].
     * [conditionalFanOut] picks at run time which of them run.
     */
    public fun edge(from: String, to: String) {
        edges.add(Edge(from, to))
    }

    /**
     * Adds a static edge from this node to [to] and returns [to], so edges can be chained:
     * `START then research then write then END`.
     */
    public infix fun NodeRef.then(to: NodeRef): NodeRef = to.also { edge(name, it.name) }

    /** Adds a static edge from this node to the node named [to], usually [END]. */
    public infix fun NodeRef.then(to: String): String = to.also { edge(name, it) }

    /** Adds a static edge from the node named by this string, usually [START], to [to]. */
    public infix fun String.then(to: NodeRef): NodeRef = to.also { edge(this, it.name) }

    /**
     * Adds a conditional edge: after [from] finishes, [condition] picks the next node from the state.
     *
     * Declare the possible [targets] whenever you can. They are checked when the graph is compiled,
     * the value returned by [condition] is checked against them at run time, and they let [compile]
     * detect unreachable nodes. With `targets = null` the condition may return any node name.
     *
     * A node can have either one conditional edge or static edges, not both.
     *
     * [labels] say when the edge goes to some of its [targets], such as
     * `mapOf("write" to "too short")`, and are the labels of [CompiledGraph.toMermaid]. Each needs
     * its target in [targets].
     */
    public fun conditionalEdge(
        from: String,
        targets: Set<String>? = null,
        labels: Map<String, String> = emptyMap(),
        condition: EdgeCondition<State>,
    ) {
        conditionalEdges.add(ConditionalEdge(from, targets, { state -> setOf(condition(state)) }, labels))
    }

    /** Adds a conditional edge from the node referenced by [from]. See the overload taking a node name. */
    public fun conditionalEdge(
        from: NodeRef,
        targets: Set<String>? = null,
        labels: Map<String, String> = emptyMap(),
        condition: EdgeCondition<State>,
    ) {
        conditionalEdge(from.name, targets, labels, condition)
    }

    /**
     * Adds a conditional edge that works with references instead of names: [condition] returns one
     * of [targets], so a misspelled node is a compiler error.
     *
     * ```kotlin
     * conditionalEdge(check, targets = setOf(write, NodeRef.END)) { draft ->
     *     if (draft.isGood) NodeRef.END else write
     * }
     * ```
     *
     * To finish the branch, list [NodeRef.END] in [targets] and return it. [labels] say when the
     * edge goes to some of the targets, such as `mapOf(write to "too short")`, for
     * [CompiledGraph.toMermaid].
     */
    @JvmName("conditionalEdgeToRefs")
    public fun conditionalEdge(
        from: NodeRef,
        targets: Set<NodeRef>,
        labels: Map<NodeRef, String> = emptyMap(),
        condition: suspend (State) -> NodeRef,
    ) {
        conditionalEdge(from.name, targets, labels, condition)
    }

    /**
     * Adds a conditional edge that works with references, from the node named [from], usually
     * [START]. See the overload taking a node reference.
     */
    @JvmName("conditionalEdgeToRefs")
    public fun conditionalEdge(
        from: String,
        targets: Set<NodeRef>,
        labels: Map<NodeRef, String> = emptyMap(),
        condition: suspend (State) -> NodeRef,
    ) {
        conditionalEdge(from, targets.mapTo(mutableSetOf()) { it.name }, labels.mapKeys { it.key.name }) { state -> condition(state).name }
    }

    /**
     * Adds a conditional edge that can fan out: after [from] finishes, [condition] picks any of
     * [targets], and they run in parallel in the next step, like the targets of static edges from
     * one node. See [compile] for when that needs a [Reducer].
     *
     * ```kotlin
     * conditionalFanOut(brief, targets = setOf(locate, guide, map)) { trip ->
     *     if (trip.located) setOf(guide, map) else setOf(locate)
     * }
     * ```
     *
     * [NodeRef.END] in the result ends this branch while the other nodes run. An empty result ends
     * the branch too. [labels] are as for [conditionalEdge].
     */
    public fun conditionalFanOut(
        from: NodeRef,
        targets: Set<NodeRef>,
        labels: Map<NodeRef, String> = emptyMap(),
        condition: suspend (State) -> Set<NodeRef>,
    ) {
        conditionalFanOut(from.name, targets, labels, condition)
    }

    /**
     * Adds a conditional edge that can fan out from the node named [from], usually [START]. See the
     * overload taking a node reference.
     */
    public fun conditionalFanOut(
        from: String,
        targets: Set<NodeRef>,
        labels: Map<NodeRef, String> = emptyMap(),
        condition: suspend (State) -> Set<NodeRef>,
    ) {
        val names = targets.mapTo(mutableSetOf()) { it.name }
        conditionalEdges.add(
            ConditionalEdge(from, names, { state ->
                condition(state).mapTo(mutableSetOf()) {
                    it.name
                }
            }, labels.mapKeys { it.key.name }, fansOut = true),
        )
    }

    /**
     * Validates the graph and returns an executable [CompiledGraph].
     *
     * Give [reducer] or [rules], not both. With [rules], each node of a step gets the state from
     * before the step, the [update][node] of a node with a `work` too, and the rules merge what all
     * of them return, in the order the nodes were added to the graph. Two nodes that set a property
     * to different values then fail the run as [replace][MergeRulesBuilder.replace] says, whatever
     * form the nodes have:
     *
     * ```kotlin
     * val graph = StateGraph<Job> {
     *     START then node("web", work = { searchWeb() }) { job, found -> job.copy(notes = job.notes + found) }
     *     START then node("docs", work = { searchDocs() }) { job, found -> job.copy(notes = job.notes + found) }
     * }.compile(
     *     rules = mergeRules {
     *         append(Job::notes) { copy(notes = it) }
     *         replace(Job::status) { copy(status = it) }
     *     },
     * )
     * ```
     *
     * [MergeRules] given as [reducer] work the same way as [rules], so that the rules hold for both
     * forms of node however they are given.
     *
     * Without rules, the updates are applied one after another, each to the state that the update
     * before it returned, and the node added later wins.
     *
     * @param rules merge the results of all nodes that ran in the same step, whatever form they
     * have.
     * @param reducer merges the states of nodes that ran in the same step and each returned a whole
     * state. Required when two such nodes can run in the same step, unless [rules] are given. Nodes
     * added with a `work` and an `update` do not need it, unless it is a [MergeRules], which then
     * merges them too, as [rules] do. It comes last so that it can be written as a trailing lambda;
     * give it by name otherwise.
     * @throws GraphValidationException listing every problem found in the graph definition.
     */
    public fun compile(rules: MergeRules<State>? = null, reducer: Reducer<State>? = null): CompiledGraph<State> {
        val problems = validate(reducer, rules)
        if (problems.isNotEmpty()) {
            throw GraphValidationException(
                if (problems.size == 1) problems.single() else "Invalid graph:\n" + problems.joinToString("\n") { "- $it" },
            )
        }

        // Rules given as the reducer hold for every node of a step, as if they were given as rules.
        val graphRules = rules ?: reducer as? MergeRules<State>
        return CompiledGraph(
            nodes = nodes.toMap(),
            edges = edges.groupBy({ it.from }, { it.to }),
            conditionalEdges = conditionalEdges.associateBy { it.from },
            reducer = reducer.takeIf { graphRules == null },
            rules = graphRules,
        )
    }

    private fun validate(given: Reducer<State>?, rules: MergeRules<State>?): List<String> {
        val problems = mutableListOf<String>()
        if (given != null && rules != null) {
            problems += "compile() takes a reducer or rules, not both: rules merge the nodes that return a whole state too"
        }
        val reducer = given ?: rules
        val sources = nodes.keys + START
        val destinations = nodes.keys + END

        if (edges.none { it.from == START } && conditionalEdges.none { it.from == START }) {
            problems += "Graph must have at least one edge originating from START"
        }

        for (edge in edges) {
            if (edge.from !in sources) problems += "Edge references unknown from-node: ${edge.from}"
            if (edge.to !in destinations) problems += "Edge references unknown to-node: ${edge.to}"
        }
        edges.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach {
            problems += "Duplicate edge from '${it.from}' to '${it.to}'"
        }

        for (edge in conditionalEdges) {
            if (edge.from !in sources) problems += "Conditional edge references unknown from-node: ${edge.from}"
            edge.targets?.filter { it !in destinations }?.forEach {
                problems += "Conditional edge from '${edge.from}' references unknown target: $it"
            }
            if (edge.targets?.isEmpty() == true) problems += "Conditional edge from '${edge.from}' declares no targets"
            edge.labels.keys.filter { edge.targets == null || it !in edge.targets }.forEach {
                problems += "Conditional edge from '${edge.from}' has a label for '$it', which is not one of its targets"
            }
        }
        val conditionalSources = conditionalEdges.groupingBy { it.from }.eachCount()
        conditionalSources.filterValues { it > 1 }.keys.forEach {
            problems += "Node '$it' has more than one conditional edge"
        }
        conditionalSources.keys.filter { from -> edges.any { it.from == from } }.forEach {
            problems += "Node '$it' has both a conditional edge and static edges"
        }

        if (reducer == null) {
            edges
                .groupBy({ it.from }, { it.to })
                .filterValues { targets -> targets.count { nodes[it]?.returnsState == true } > 1 }
                .keys
                .forEach {
                    problems +=
                        "Node '$it' fans out to several nodes that return a whole state, " +
                        "so compile() needs a Reducer, or those nodes need a work and an update"
                }
        }

        // Reachability is only decidable when every conditional edge declares its targets.
        if (problems.isEmpty() && conditionalEdges.all { it.targets != null }) {
            (nodes.keys - reachableNodes()).forEach { problems += "Node '$it' is not reachable from START" }
        }

        // Branches of a fan-out can also meet later in the graph, away from the node that fans out.
        if (problems.isEmpty() && reducer == null) {
            parallelStateNodes()?.let { (first, second) ->
                problems +=
                    "Nodes '$first' and '$second' can run in the same step and both return a whole state, " +
                    "so compile() needs a Reducer, or those nodes need a work and an update"
            }
        }
        return problems
    }

    /**
     * Returns two nodes that each return a whole state and can run in the same step, or `null` if
     * there are none. Two nodes can run in the same step when they are targets of the same fan-out,
     * static or conditional, or when they follow two nodes that can. A conditional edge without
     * declared targets may lead to any node.
     */
    private fun parallelStateNodes(): Pair<String, String>? {
        val routes = conditionalEdges.associateBy { it.from }
        val staticTargets = edges.groupBy({ it.from }, { it.to })

        fun successors(node: String): Collection<String> {
            val route = routes[node] ?: return staticTargets[node].orEmpty()
            return route.targets ?: nodes.keys
        }

        val together = mutableSetOf<Pair<String, String>>()
        val queue = ArrayDeque<Pair<String, String>>()

        fun add(first: String, second: String) {
            if (first != second && first != END && second != END && together.add(first to second)) queue.add(first to second)
        }

        for (targets in staticTargets.values + routes.values.filter { it.fansOut }.map { it.targets ?: nodes.keys }) {
            for (first in targets) for (second in targets) add(first, second)
        }
        while (queue.isNotEmpty()) {
            val (first, second) = queue.removeFirst()
            for (afterFirst in successors(first)) for (afterSecond in successors(second)) add(afterFirst, afterSecond)
        }
        return together.firstOrNull { (first, second) -> nodes.getValue(first).returnsState && nodes.getValue(second).returnsState }
    }

    private fun reachableNodes(): Set<String> {
        val successors: Map<String, List<String>> =
            (edges.map { it.from to it.to } + conditionalEdges.flatMap { edge -> edge.targets.orEmpty().map { edge.from to it } })
                .groupBy({ it.first }, { it.second })
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque(listOf(START))
        while (queue.isNotEmpty()) {
            successors[queue.removeFirst()].orEmpty().filter(visited::add).forEach(queue::add)
        }
        return visited
    }
}

/**
 * DSL entry point for building a [StateGraph].
 */
public fun <State> StateGraph(block: StateGraph<State>.() -> Unit): StateGraph<State> = StateGraph<State>().apply(block)
