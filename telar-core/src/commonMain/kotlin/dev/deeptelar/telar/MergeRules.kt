package dev.deeptelar.telar

import kotlin.reflect.KProperty1

/**
 * Builds [MergeRules] from one rule for each property that nodes of the same step may change, in
 * place of a reducer that merges whole states by hand:
 *
 * ```kotlin
 * data class Research(
 *     val notes: List<String> = emptyList(),
 *     val status: String = "",
 * )
 *
 * val graph = StateGraph<Research> {
 *     val web = node("web") { it.copy(notes = it.notes + "web") }
 *     val docs = node("docs") { it.copy(notes = it.notes + "docs") }
 *     START then web
 *     START then docs
 * }.compile(
 *     reducer = mergeRules {
 *         append(Research::notes) { copy(notes = it) }       // add what each node added
 *         replace(Research::status) { copy(status = it) }    // take the value of the node that changed it
 *     },
 * )
 * ```
 *
 * A rule names a property and says how to write its merged value back, which for a data class is
 * a `copy`. The rules compare what each node returned with the state before the step, so a node
 * is written as usual: it returns a whole state with `copy`.
 *
 * A node that changes a property without a rule fails the run with a [ReducerException] instead of
 * losing the change. For that check the state needs `equals`, which a data class has.
 *
 * Given as `reducer`, the rules merge the nodes that return a whole state. Given as `rules` to
 * [StateGraph.compile], they merge every node of a step, the nodes with a `work` and an `update`
 * too.
 *
 * @throws GraphValidationException if there is no rule, or two rules for the same property.
 */
public fun <State> mergeRules(block: MergeRulesBuilder<State>.() -> Unit): MergeRules<State> {
    val rules = MergeRulesBuilder<State>().apply(block).rules.toList()
    if (rules.isEmpty()) throw GraphValidationException("mergeRules needs at least one rule.")
    rules.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys.firstOrNull()?.let {
        throw GraphValidationException("mergeRules has two rules for the property '$it'.")
    }
    return MergeRules(rules)
}

/**
 * A [Reducer] built by [mergeRules] from one rule for each property. Give it to
 * [StateGraph.compile] as `reducer` to merge the nodes that return a whole state, or as `rules` to
 * merge every node of a step.
 */
public class MergeRules<State> internal constructor(
    private val rules: List<MergeRule<State, *>>,
) : Reducer<State> {
    override suspend fun reduce(currentState: State, updates: List<State>): State =
        merge(currentState, updates) { "Node ${it + 1} of the ${updates.size} that are merged" }

    /**
     * Merges [updates] into [current] by the rules.
     *
     * @param node names the update with the given index in an error.
     */
    internal fun merge(current: State, updates: List<State>, node: (Int) -> String): State {
        updates.forEachIndexed { index, update ->
            // The state before the step with only the properties that have a rule taken from the node.
            if (rules.fold(current) { state, rule -> rule.copy(from = update, into = state) } != update) {
                throw MergeRuleException(
                    "${node(index)} changed a property that has no merge rule. The rules cover: ${rules.joinToString { it.name }}.",
                )
            }
        }
        return rules.fold(current) { state, rule -> rule.merge(current, updates, into = state) }
    }
}

/** The rules of a [mergeRules] block. */
public class MergeRulesBuilder<State> internal constructor() {
    internal val rules = mutableListOf<MergeRule<State, *>>()

    /**
     * The list [property] gets what each node added to its end, in the order of the nodes in the
     * step. A single node may also rewrite the list. Two nodes that change it, one of which did
     * more than add to its end, fail the run.
     *
     * @param write returns the state with [property] set to the merged list.
     */
    public fun <Item> append(property: KProperty1<State, List<Item>>, write: State.(List<Item>) -> State) {
        add(property, write) { current, changed ->
            when {
                changed.all { it.size >= current.size && it.subList(0, current.size) == current } ->
                    current + changed.flatMap { it.subList(current.size, it.size) }
                changed.size == 1 -> changed.single()
                else ->
                    throw MergeRuleException(
                        "Property '${property.name}': a node rewrote the list while another node changed it too. " +
                            "append can only combine what nodes add to the end; use merge to say how they combine.",
                    )
            }
        }
    }

    /**
     * [property] gets the value of the node that changed it. Nodes that set the same value agree.
     * Nodes that set different values fail the run: use [merge] to say which one counts.
     *
     * @param write returns the state with [property] set to the new value.
     */
    public fun <Value> replace(property: KProperty1<State, Value>, write: State.(Value) -> State) {
        add(property, write) { _, changed ->
            changed.distinct().singleOrNull()
                ?: throw MergeRuleException(
                    "Property '${property.name}' was set to different values by nodes of the same step: ${changed.distinct()}. " +
                        "Use merge to say how they combine.",
                )
        }
    }

    /**
     * [property] gets what [combine] makes of the values of the nodes that changed it:
     *
     * ```kotlin
     * merge(Research::score, combine = { _, changed -> changed.max() }) { copy(score = it) }
     * merge(Research::status, combine = { _, changed -> changed.last() }) { copy(status = it) }   // the last node wins
     * ```
     *
     * @param combine receives the value before the step and the values that differ from it, in the
     * order of the nodes in the step. It is not called when no node changed the property.
     * @param write returns the state with [property] set to the combined value.
     */
    public fun <Value> merge(
        property: KProperty1<State, Value>,
        combine: (current: Value, changed: List<Value>) -> Value,
        write: State.(Value) -> State,
    ) {
        add(property, write, combine)
    }

    private fun <Value> add(property: KProperty1<State, Value>, write: State.(Value) -> State, combine: (Value, List<Value>) -> Value) {
        rules += MergeRule(property.name, property::get, write, combine)
    }
}

/** How the nodes of one step change the property [name] together. */
internal class MergeRule<State, Value>(
    val name: String,
    private val read: (State) -> Value,
    private val write: State.(Value) -> State,
    private val combine: (current: Value, changed: List<Value>) -> Value,
) {
    /** Returns [into] with this property as [from] has it. */
    fun copy(from: State, into: State): State = into.write(read(from))

    /** Returns [into] with this property merged from the [updates] that changed it since [current]. */
    fun merge(current: State, updates: List<State>, into: State): State {
        val before = read(current)
        val changed = updates.map(read).filter { it != before }
        return if (changed.isEmpty()) into else into.write(combine(before, changed))
    }
}
