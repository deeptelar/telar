package dev.deeptelar.telar

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Keeps what an agent learned between conversations: a preference of a user, a fact, an earlier
 * decision. A [Checkpointer] saves the state of one thread; a store belongs to no thread, so every
 * run that is given the same store reads what the others wrote.
 *
 * A value is text under a namespace and a key. The namespace is a list of names, such as
 * `listOf("users", userId, "preferences")`, and [list] finds everything under a part of it, such as
 * `listOf("users", userId)`. The overloads with a [StateSerializer] keep other types, and with
 * `telar-serialization` a `@Serializable` type needs none.
 *
 * ```kotlin
 * val store = MemoryStore()
 * val graph = StateGraph<Trip> {
 *     val plan = node("plan") { trip ->
 *         val diet = store().get(listOf("preferences"), "diet")?.value
 *         trip.copy(diet = diet)
 *     }
 *     START then plan then END
 * }.compile()
 *
 * graph.invoke(trip, GraphConfig(threadId = "plan-7", store = store.within("users", userId)))
 * ```
 *
 * Give it to a run with [GraphConfig.store], and a node or a tool reaches it with [store].
 * [within] gives each user a part of the store of their own.
 *
 * A write goes straight to the store, and is not part of a checkpoint. When a step runs again, its
 * nodes write again: a [put] under the same key leaves the value of the last try, which is the try
 * whose state the run continues with. A node that reads a value and writes one made from it, such
 * as a counter, uses [effect], as it would with any other service. A step that fails and is never
 * resumed keeps what it wrote.
 *
 * [search] finds the items that a query is about. Here it looks for the words of the query; a store
 * that ranks by meaning, such as `SemanticStore` of `telar-agent`, replaces it.
 *
 * Every implementation checks what it is given with [checkAddress], [checkNamespace] and
 * [checkLimit], as [MemoryStore] does: a key and each part of a namespace are not empty, and a
 * limit is positive.
 */
public interface Store {
    /** Returns the item under [key] in [namespace], or `null` when there is none. */
    public suspend fun get(namespace: List<String>, key: String): StoreItem?

    /** Keeps [value] under [key] in [namespace], in place of the value that was there. */
    public suspend fun put(namespace: List<String>, key: String, value: String)

    /** Removes the item under [key] in [namespace]. Does nothing when there is none. */
    public suspend fun delete(namespace: List<String>, key: String)

    /**
     * Returns the items of [namespace] and of the namespaces that start with it, the last changed
     * first, at most [limit] of them. An empty [namespace] lists the whole store.
     */
    public suspend fun list(namespace: List<String>, limit: Int = DEFAULT_LIMIT): List<StoreItem>

    /**
     * Returns the items of [namespace] and of the namespaces under it that [query] is about, the
     * best first, at most [limit] of them.
     *
     * This one looks for the words of [query] in the key and the value of the [SEARCH_SCOPE] items
     * that [list] returns first, whatever their case: an item with more of the words comes first,
     * and of two with as many, the one changed last. An item without any of them is left out, and
     * so are the words of fewer than two letters. A store that finds items by meaning overrides it.
     */
    public suspend fun search(namespace: List<String>, query: String, limit: Int = DEFAULT_SEARCH_LIMIT): List<StoreItem> {
        checkNamespace(namespace)
        checkLimit(limit)
        val words = wordsOf(query)
        if (words.isEmpty()) return emptyList()
        return list(namespace, SEARCH_SCOPE)
            .map { item -> item to wordsOf(item.key + " " + item.value).let { found -> words.count { it in found } } }
            .filter { (_, matches) -> matches > 0 }
            // A stable sort: of two items with as many words, the one that list returned first stays first.
            .sortedByDescending { (_, matches) -> matches }
            .take(limit)
            .map { (item, _) -> item }
    }

    public companion object {
        /** The default for the limit of [list]. */
        public const val DEFAULT_LIMIT: Int = 100

        /** The default for the limit of [search]. */
        public const val DEFAULT_SEARCH_LIMIT: Int = 10

        /** How many items the [search] of a store that has none of its own looks at: the ones changed last. */
        public const val SEARCH_SCOPE: Int = 1_000

        /**
         * Checks a key and a namespace as every [Store] does: neither the key nor a part of the namespace
         * may be empty.
         *
         * @throws GraphValidationException if one is.
         */
        public fun checkAddress(namespace: List<String>, key: String) {
            checkNamespace(namespace)
            if (key.isEmpty()) throw GraphValidationException("A key of a store must not be empty.")
        }

        /**
         * Checks a namespace as every [Store] does: no part of it may be empty. An empty namespace is the
         * whole store.
         *
         * @throws GraphValidationException if a part is empty.
         */
        public fun checkNamespace(namespace: List<String>) {
            if (namespace.any { it.isEmpty() }) throw GraphValidationException("A part of a namespace must not be empty, was $namespace.")
        }

        /**
         * Checks the limit of [Store.list] as every [Store] does.
         *
         * @throws GraphValidationException if [limit] is not positive.
         */
        public fun checkLimit(limit: Int) {
            if (limit <= 0) throw GraphValidationException("The limit of a list must be positive, was $limit.")
        }
    }
}

/**
 * A value that a [Store] keeps.
 *
 * @property namespace where it is kept. From a store that [within] made, it leaves out the parts
 * that the view adds.
 * @property key its name in [namespace].
 * @property value the text that was put.
 * @property updatedAt when it was put last.
 */
public data class StoreItem(
    val namespace: List<String>,
    val key: String,
    val value: String,
    val updatedAt: Instant,
)

/**
 * Returns the value under [key] in [namespace], read with [serializer], or `null` when there is none.
 *
 * @throws StoreCorruptedException when [serializer] cannot read the value, for example because its
 * type changed.
 */
public suspend fun <T> Store.get(namespace: List<String>, key: String, serializer: StateSerializer<T>): T? {
    val item = get(namespace, key) ?: return null
    return try {
        serializer.deserialize(item.value)
    } catch (e: IllegalArgumentException) {
        // SerializationException is an IllegalArgumentException, as are most parsing failures.
        throw StoreCorruptedException(namespace, key, "it does not match its type", e)
    }
}

/** Keeps [value] under [key] in [namespace], written with [serializer]. */
public suspend fun <T> Store.put(namespace: List<String>, key: String, value: T, serializer: StateSerializer<T>) {
    put(namespace, key, serializer.serialize(value))
}

/**
 * Returns a view of this store whose namespaces all start with [namespace]. Give each user a view
 * of their own, `store.within("users", userId)`, so that a graph compiled once serves every user,
 * and nothing in it can read what another user's runs wrote.
 *
 * The items that the view returns leave out the parts it adds. A view of a view adds both.
 *
 * @throws GraphValidationException if [namespace] is empty or has an empty part.
 */
public fun Store.within(vararg namespace: String): Store {
    val prefix = namespace.toList()
    if (prefix.isEmpty()) throw GraphValidationException("within() needs at least one part of a namespace.")
    Store.checkNamespace(prefix)
    return if (this is NamespacedStore) NamespacedStore(store, this.prefix + prefix) else NamespacedStore(this, prefix)
}

/** The view of [within]. */
private class NamespacedStore(
    val store: Store,
    val prefix: List<String>,
) : Store {
    override suspend fun get(namespace: List<String>, key: String): StoreItem? = store.get(prefix + namespace, key)?.inside()

    override suspend fun put(namespace: List<String>, key: String, value: String) {
        store.put(prefix + namespace, key, value)
    }

    override suspend fun delete(namespace: List<String>, key: String) {
        store.delete(prefix + namespace, key)
    }

    override suspend fun list(namespace: List<String>, limit: Int): List<StoreItem> =
        store.list(prefix + namespace, limit).map { it.inside() }

    override suspend fun search(namespace: List<String>, query: String, limit: Int): List<StoreItem> =
        store.search(prefix + namespace, query, limit).map { it.inside() }

    private fun StoreItem.inside(): StoreItem = copy(namespace = namespace.drop(prefix.size))
}

/**
 * A [Store] that keeps its items in memory, for tests and for apps that remember only while they
 * run. Safe to share between coroutines and threads.
 *
 * @param clock gives [StoreItem.updatedAt].
 */
public class MemoryStore(
    private val clock: Clock = Clock.System,
) : Store {
    private val mutex = Mutex()
    private val items = mutableMapOf<Pair<List<String>, String>, Kept>()
    private var writes = 0L

    /** An item, and the number of the write that put it, which orders items put at the same instant. */
    private class Kept(
        val item: StoreItem,
        val write: Long,
    )

    override suspend fun get(namespace: List<String>, key: String): StoreItem? {
        Store.checkAddress(namespace, key)
        return mutex.withLock { items[namespace to key]?.item }
    }

    override suspend fun put(namespace: List<String>, key: String, value: String) {
        Store.checkAddress(namespace, key)
        val namespace = namespace.toList()
        mutex.withLock { items[namespace to key] = Kept(StoreItem(namespace, key, value, clock.now()), ++writes) }
    }

    override suspend fun delete(namespace: List<String>, key: String) {
        Store.checkAddress(namespace, key)
        mutex.withLock { items.remove(namespace to key) }
    }

    override suspend fun list(namespace: List<String>, limit: Int): List<StoreItem> {
        Store.checkNamespace(namespace)
        Store.checkLimit(limit)
        return mutex.withLock {
            items.values
                .filter { it.item.namespace.startsWith(namespace) }
                .sortedByDescending { it.write }
                .take(limit)
                .map { it.item }
        }
    }
}

/**
 * Returns the [Store] of the running graph, which [GraphConfig.store] gave it. Call it from the
 * action of a node, the `work` of a node, or a tool that a node runs. The nodes of a subgraph, and
 * so the agents of a team, reach the store of the graph around them.
 *
 * @throws GraphValidationException when the run has no store, or when it is called outside a
 * running node.
 */
public suspend fun store(): Store {
    val node =
        currentCoroutineContext()[RunningNode]
            ?: throw GraphValidationException("store() was called outside a running node. Call it from a node or from a tool a node runs.")
    return node.store
        ?: throw GraphValidationException(
            "Node '${node.name}' called store(), but the run has no store. Give one to GraphConfig(store = ...).",
        )
}

private fun List<String>.startsWith(prefix: List<String>): Boolean = size >= prefix.size && subList(0, prefix.size) == prefix

/** The words of [text] in lower case, of two letters or more. A letter or a digit is part of a word, anything else ends it. */
private fun wordsOf(text: String): Set<String> =
    text
        .lowercase()
        .splitAt { !it.isLetterOrDigit() }
        .filter { it.length >= 2 }
        .toSet()

private inline fun String.splitAt(isSeparator: (Char) -> Boolean): List<String> {
    val words = mutableListOf<String>()
    val word = StringBuilder()
    for (char in this) {
        if (isSeparator(char)) {
            if (word.isNotEmpty()) words += word.toString()
            word.clear()
        } else {
            word.append(char)
        }
    }
    if (word.isNotEmpty()) words += word.toString()
    return words
}
