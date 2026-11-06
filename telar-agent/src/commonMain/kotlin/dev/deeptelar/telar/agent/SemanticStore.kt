package dev.deeptelar.telar.agent

import dev.deeptelar.telar.GraphConfig
import dev.deeptelar.telar.GraphValidationException
import dev.deeptelar.telar.Store
import dev.deeptelar.telar.StoreItem
import dev.deeptelar.telar.within
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.math.sqrt

/**
 * A [Store] whose [search] finds the items that a query means, with the vectors of an
 * [EmbeddingModel]: "what does she eat?" finds "Piscivegetarian: fish, but no meat", which has
 * none of its words. Everything else goes to [store] as it is.
 *
 * ```kotlin
 * val embeddings = OpenAiEmbeddingModel(client, apiKey = key)
 * val store = SemanticStore(SqlDelightStore(driver), embeddings)
 * graph.invoke(input, GraphConfig(threadId = "plan-7", store = store.within("users", userId)))
 *
 * // In a node or a tool:
 * val found = store().search(listOf("memories"), "what does the traveller eat?", limit = 5)
 * ```
 *
 * How it works:
 * - **A vector is made when a search first needs it**, not when an item is put: a [put] calls no
 *   model, and an item that other code put into [store] is found too. A search makes the vectors of
 *   the items that have none, in calls of at most 100 texts to [embeddings], and one for the query.
 * - **The vectors are kept in [store]**, in the namespace [VECTORS] followed by the namespace of
 *   the item, with the item's key. So a store that keeps its items keeps their vectors, and the next
 *   search only makes the vector of the query. Do not put items of your own under [VECTORS]: [list]
 *   leaves that namespace out.
 * - **A vector is made again** when the value of its item changed, or the [EmbeddingModel.id] did.
 *   A vector whose item was deleted is deleted by the next search that sees all the items of its
 *   namespace.
 * - **The vector of an item is made from its key and its value**, as `"diet: Piscivegetarian"`.
 * - **A search looks at the [scope] items that `list` returns first**, the ones changed last, and
 *   ranks them by the cosine of their vector and the vector of the query. An item below [minScore] is
 *   left out.
 *
 * Use it through [within] as any store. A run with this store gives `recall` of [memoryTools] a
 * search by meaning, and a [GraphConfig] takes it as its `store`.
 *
 * @param store where the items and their vectors are kept.
 * @param embeddings makes the vectors.
 * @param minScore the lowest cosine, from -1 to 1, of an item that a search returns. The default
 * returns every item, the best first. What a good limit is depends on the model.
 * @param scope how many items a search looks at.
 * @throws GraphValidationException if [minScore] is not between -1 and 1 or [scope] is not positive.
 */
public class SemanticStore(
    private val store: Store,
    private val embeddings: EmbeddingModel,
    private val minScore: Double = -1.0,
    private val scope: Int = Store.SEARCH_SCOPE,
) : Store {
    init {
        if (minScore !in -1.0..1.0) throw GraphValidationException("minScore must be between -1 and 1, was $minScore.")
        Store.checkLimit(scope)
    }

    override suspend fun get(namespace: List<String>, key: String): StoreItem? = store.get(namespace, key)

    override suspend fun put(namespace: List<String>, key: String, value: String) {
        store.put(namespace, key, value)
    }

    override suspend fun delete(namespace: List<String>, key: String) {
        store.delete(namespace, key)
        store.delete(listOf(VECTORS) + namespace, key)
    }

    override suspend fun list(namespace: List<String>, limit: Int): List<StoreItem> =
        if (namespace.isEmpty()) {
            // Only a list of the whole store reaches the vectors, which may be anywhere in its order.
            Store.checkLimit(limit)
            store.list(namespace, Int.MAX_VALUE).filter { it.namespace.firstOrNull() != VECTORS }.take(limit)
        } else {
            store.list(namespace, limit)
        }

    override suspend fun search(namespace: List<String>, query: String, limit: Int): List<StoreItem> {
        Store.checkNamespace(namespace)
        Store.checkLimit(limit)
        if (query.isBlank()) return emptyList()
        val items = list(namespace, scope)
        if (items.isEmpty()) return emptyList()

        val kept = store.list(listOf(VECTORS) + namespace, scope).associateBy { it.namespace.drop(1) to it.key }
        val vectors = mutableMapOf<Pair<List<String>, String>, FloatArray>()
        val missing = mutableListOf<StoreItem>()
        for (item in items) {
            val vector = kept[item.namespace to item.key]?.let { decode(it.value, textOf(item)) }
            if (vector == null) missing += item else vectors[item.namespace to item.key] = vector
        }
        for (batch in missing.chunked(BATCH)) {
            embed(batch.map(::textOf)).forEachIndexed { index, vector ->
                val item = batch[index]
                vectors[item.namespace to item.key] = vector
                store.put(listOf(VECTORS) + item.namespace, item.key, encode(vector, textOf(item)))
            }
        }
        if (items.size < scope) removeOrphans(kept.keys - items.map { it.namespace to it.key }.toSet())

        val asked = embed(listOf(query)).single()
        return items
            .map { item -> item to cosine(asked, vectors.getValue(item.namespace to item.key)) }
            .filter { (_, score) -> score >= minScore }
            .sortedByDescending { (_, score) -> score }
            .take(limit)
            .map { (item, _) -> item }
    }

    private suspend fun embed(texts: List<String>): List<FloatArray> {
        val vectors = embeddings.embed(texts)
        if (vectors.size != texts.size) {
            throw EmbeddingModelException("The model ${embeddings.id} returned ${vectors.size} vectors for ${texts.size} texts.")
        }
        return vectors
    }

    /** Deletes the vectors of items that no longer exist. */
    private suspend fun removeOrphans(orphans: Set<Pair<List<String>, String>>) {
        orphans.forEach { (namespace, key) -> store.delete(listOf(VECTORS) + namespace, key) }
    }

    private fun encode(vector: FloatArray, text: String): String {
        val bytes = ByteArray(vector.size * 4)
        vector.forEachIndexed { index, value ->
            val bits = value.toRawBits()
            for (byte in 0 until 4) bytes[index * 4 + byte] = (bits ushr (8 * byte)).toByte()
        }
        return json.encodeToString(StoredVector.serializer(), StoredVector(embeddings.id, hash(text), Base64.encode(bytes)))
    }

    /** Returns the vector that [stored] holds, or `null` when it was made by another model or from another text. */
    private fun decode(stored: String, text: String): FloatArray? {
        val vector =
            try {
                json.decodeFromString(StoredVector.serializer(), stored)
            } catch (_: IllegalArgumentException) {
                // A vector that cannot be read is made again.
                return null
            }
        if (vector.model != embeddings.id || vector.text != hash(text)) return null
        val bytes =
            try {
                Base64.decode(vector.vector)
            } catch (_: IllegalArgumentException) {
                return null
            }
        if (bytes.size % 4 != 0) return null
        return FloatArray(bytes.size / 4) { index ->
            var bits = 0
            for (byte in 0 until 4) bits = bits or ((bytes[index * 4 + byte].toInt() and 0xFF) shl (8 * byte))
            Float.fromBits(bits)
        }
    }

    public companion object {
        /** The first part of the namespace under which the vectors of the items are kept. */
        public const val VECTORS: String = "telar.vectors"

        /** How many texts one call to the model gets at most. */
        private const val BATCH = 100

        private val json = Json { ignoreUnknownKeys = true }
    }
}

/** The text whose vector stands for [item]. */
private fun textOf(item: StoreItem): String = "${item.key}: ${item.value}"

/** The cosine of the angle between [a] and [b], 0 for vectors of different lengths or without length. */
private fun cosine(a: FloatArray, b: FloatArray): Double {
    if (a.size != b.size) return 0.0
    var dot = 0.0
    var normA = 0.0
    var normB = 0.0
    for (index in a.indices) {
        dot += a[index].toDouble() * b[index]
        normA += a[index].toDouble() * a[index]
        normB += b[index].toDouble() * b[index]
    }
    return if (normA == 0.0 || normB == 0.0) 0.0 else dot / (sqrt(normA) * sqrt(normB))
}

/** The FNV-1a hash of [text], in hexadecimal: it tells whether the text of a vector changed. */
private fun hash(text: String): String {
    var hash = -0x340d631b7bdddcdbL
    for (byte in text.encodeToByteArray()) {
        hash = hash xor (byte.toLong() and 0xFF)
        hash *= 0x100000001b3L
    }
    return hash.toULong().toString(16)
}

/** A vector as [SemanticStore] keeps it: the model that made it, the hash of its text, and its floats in Base64. */
@Serializable
private class StoredVector(
    val model: String,
    val text: String,
    val vector: String,
)
