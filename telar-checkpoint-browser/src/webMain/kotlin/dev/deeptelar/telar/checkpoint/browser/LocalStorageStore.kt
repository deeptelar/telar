@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.deeptelar.telar.checkpoint.browser

import dev.deeptelar.telar.Store
import dev.deeptelar.telar.StoreCorruptedException
import dev.deeptelar.telar.StoreItem
import dev.deeptelar.telar.TelarException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.time.Clock
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Instant

/**
 * A persistent [Store] for apps that run in a browser. It keeps each item in an entry of the page's
 * `localStorage`, so what an agent remembers is still there after the page is reloaded or the
 * browser is closed.
 *
 * ```kotlin
 * val config = GraphConfig(
 *     threadId = "plan-7",
 *     checkpointer = LocalStorageCheckpointer(KotlinxStateSerializer<Trip>()),
 *     store = LocalStorageStore().within("traveller"),
 * )
 * ```
 *
 * What `LocalStorageCheckpointer` says about `localStorage` holds here too: it belongs to the
 * origin of the page, so give [keyPrefix] a name of your own; a browser keeps about 5 MB for an
 * origin, and a [put] into a full storage throws a [LocalStorageStoreException] and leaves the
 * items as they were; the person using the browser can read and change it; and it is not available
 * in a web worker, on Node.js, or when the browser blocks storage for the page.
 *
 * The key of an entry is [keyPrefix], then each part of the namespace followed by `/`, then the
 * key, with `%` and `/` in a part or the key written as `%25` and `%2F`. Its value is JSON with
 * the value of the item and its time. [list] reads the keys of every entry of the page, which is
 * quick for the size `localStorage` has.
 *
 * @param keyPrefix what the key of an entry starts with. It must not be the start of the
 * [keyPrefix] of a checkpointer or another store of the page, so that [list] finds only its own.
 * @param clock gives [StoreItem.updatedAt]. Two writes of one store never get the same time, and a
 * store starts after the last write it finds, so `list` keeps their order also with the clock of a
 * browser, which counts milliseconds.
 */
public class LocalStorageStore(
    private val keyPrefix: String = "telar.store.",
    private val clock: Clock = Clock.System,
) : Store {
    private val lock = Mutex()
    private var lastWrite: Instant? = null
    private var lastWriteRead = false

    override suspend fun get(namespace: List<String>, key: String): StoreItem? {
        Store.checkAddress(namespace, key)
        val entry = storage(namespace, key, "read the item") { getItem(entryKey(namespace, key)) } ?: return null
        val stored = decode(namespace, key, entry)
        return StoreItem(namespace.toList(), key, stored.value, stored.time())
    }

    override suspend fun put(namespace: List<String>, key: String, value: String) {
        Store.checkAddress(namespace, key)
        lock.withLock {
            val entry = json.encodeToString(StoredItem.serializer(), StoredItem(value, nextTime().toString()))
            storage(namespace, key, "store the item (${entry.length} characters). The storage of this page may be full") {
                setItem(entryKey(namespace, key), entry)
            }
        }
    }

    override suspend fun delete(namespace: List<String>, key: String) {
        Store.checkAddress(namespace, key)
        storage(namespace, key, "remove the item") { removeItem(entryKey(namespace, key)) }
    }

    override suspend fun list(namespace: List<String>, limit: Int): List<StoreItem> {
        Store.checkNamespace(namespace)
        Store.checkLimit(limit)
        return entries(namespace)
            .sortedByDescending { it.updatedAt }
            .take(limit)
    }

    /** Returns the items of [namespace] and of the namespaces under it, in no order. */
    private fun entries(namespace: List<String>): List<StoreItem> {
        val start = keyPrefix + encodeNamespace(namespace)
        val keys =
            storage(namespace, null, "read the keys of its entries") {
                (0 until storageLength()).mapNotNull { storageKey(it) }.filter { it.startsWith(start) }
            }
        return keys.mapNotNull { entryKey ->
            val (itemNamespace, key) = decodeEntryKey(entryKey.removePrefix(keyPrefix)) ?: return@mapNotNull null
            // An entry removed since its key was read is left out.
            val entry = storage(itemNamespace, key, "read the item") { getItem(entryKey) } ?: return@mapNotNull null
            val stored = decode(itemNamespace, key, entry)
            StoreItem(itemNamespace, key, stored.value, stored.time())
        }
    }

    private fun entryKey(namespace: List<String>, key: String): String = keyPrefix + encodeNamespace(namespace) + escape(key)

    private fun decode(namespace: List<String>, key: String, entry: String): StoredItem =
        try {
            json.decodeFromString(StoredItem.serializer(), entry).also { it.time() }
        } catch (e: IllegalArgumentException) {
            // SerializationException is an IllegalArgumentException, as is a time that cannot be parsed.
            throw StoreCorruptedException(namespace, key, "its entry in localStorage is not an item of a LocalStorageStore", e)
        }

    /** The time of a write: now, or just after the last write when the clock has not moved past it. Called under [lock]. */
    private fun nextTime(): Instant {
        if (!lastWriteRead) {
            lastWrite = entries(emptyList()).maxOfOrNull { it.updatedAt }
            lastWriteRead = true
        }
        val now = clock.now()
        val last = lastWrite
        return (if (last != null && now <= last) last + 1.nanoseconds else now).also { lastWrite = it }
    }

    /**
     * Runs [call] and reports an error of the browser as a [LocalStorageStoreException]. A call to
     * `localStorage` returns at once and does not suspend, so no cancellation can end up here.
     */
    private inline fun <Result> storage(namespace: List<String>, key: String?, action: String, call: () -> Result): Result =
        try {
            call()
        } catch (e: Throwable) {
            throw LocalStorageStoreException(namespace, key, "localStorage could not $action.", e)
        }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * The browser refused to read or write `localStorage` for the item under [key] in [namespace]: the
 * storage of the page is full, or the page may not use it. [key] is `null` when the store read the
 * keys of its entries. The error of the browser is the [cause].
 */
public class LocalStorageStoreException(
    public val namespace: List<String>,
    public val key: String?,
    message: String,
    cause: Throwable? = null,
) : TelarException(
        (
            if (key ==
                null
            ) {
                "Namespace $namespace"
            } else {
                "Item '$key' in the namespace $namespace"
            }
        ) + " of the store: $message",
        cause,
    )

/** The value of an entry. */
@Serializable
private class StoredItem(
    val value: String,
    val updatedAt: String,
) {
    fun time(): Instant = Instant.parse(updatedAt)
}

private fun escape(part: String): String = part.replace("%", "%25").replace("/", "%2F")

private fun unescape(part: String): String = part.replace("%2F", "/").replace("%25", "%")

/** Writes [namespace] as one string: each part followed by `/`. */
private fun encodeNamespace(namespace: List<String>): String = namespace.joinToString("") { escape(it) + "/" }

/** Reads the namespace and the key back from the key of an entry without its prefix, or returns `null` for a key the store does not write. */
private fun decodeEntryKey(entry: String): Pair<List<String>, String>? {
    val parts = entry.split("/")
    val namespace = parts.dropLast(1).map(::unescape)
    val key = unescape(parts.last())
    return (namespace to key).takeIf {
        key.isNotEmpty() &&
            namespace.none { it.isEmpty() } &&
            encodeNamespace(namespace) + escape(key) == entry
    }
}

private fun storageLength(): Int = js("globalThis.localStorage.length")

private fun storageKey(index: Int): String? = js("globalThis.localStorage.key(index)")

private fun getItem(key: String): String? = js("globalThis.localStorage.getItem(key)")

private fun setItem(key: String, value: String): Unit = js("globalThis.localStorage.setItem(key, value)")

private fun removeItem(key: String): Unit = js("globalThis.localStorage.removeItem(key)")
