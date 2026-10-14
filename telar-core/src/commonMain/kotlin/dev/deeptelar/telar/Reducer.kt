package dev.deeptelar.telar

/**
 * Merges the states of nodes that ran in the same step and each returned a whole state.
 *
 * Such nodes each produce their own copy of the state. The reducer takes the state before the step
 * and those copies, and returns the one state the run continues with. Whatever it does not carry
 * over from the copies is lost.
 *
 * [mergeRules] builds a reducer from one rule for each property, such as "add to this list", so
 * that nothing has to be carried over by hand.
 *
 * Nodes added with a `work` and an `update` do not go through the reducer, and a graph whose
 * parallel nodes are all of that kind needs none. A graph compiled with [MergeRules] as `rules`
 * merges those nodes too. See [StateGraph.node] and [StateGraph.compile].
 */
public fun interface Reducer<State> {
    public suspend fun reduce(currentState: State, updates: List<State>): State
}
