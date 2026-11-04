package dev.deeptelar.telar.serialization

import dev.deeptelar.telar.Store
import dev.deeptelar.telar.StoreCorruptedException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import dev.deeptelar.telar.get as getWith
import dev.deeptelar.telar.put as putWith

/**
 * Returns the value under [key] in [namespace] as a [T], which a [Json] of [json] reads, or `null`
 * when there is none. [T] is a `@Serializable` class or a basic type.
 *
 * ```kotlin
 * @Serializable
 * data class Diet(val name: String, val avoids: List<String>)
 *
 * val diet = store().get<Diet>(listOf("preferences"), "diet")
 * ```
 *
 * A `String` is read as it was put, without JSON, so that it matches `Store.put` with a `String`.
 *
 * @throws StoreCorruptedException when the value cannot be read as a [T].
 */
public suspend inline fun <reified T> Store.get(namespace: List<String>, key: String, json: Json = Json): T? =
    getJson(namespace, key, serializer<T>(), json)

/**
 * Keeps [value] under [key] in [namespace], which a [Json] of [json] writes. [T] is a
 * `@Serializable` class or a basic type. A `String` is kept as it is, by `Store.put`.
 */
public suspend inline fun <reified T> Store.put(namespace: List<String>, key: String, value: T, json: Json = Json) {
    putJson(namespace, key, value, serializer<T>(), json)
}

/** The work of the reified `get`, outside the inline function. */
@PublishedApi
internal suspend fun <T> Store.getJson(namespace: List<String>, key: String, serializer: KSerializer<T>, json: Json): T? {
    if (serializer == String.serializer()) {
        @Suppress("UNCHECKED_CAST")
        return get(namespace, key)?.value as T?
    }
    return getWith(namespace, key, KotlinxStateSerializer(serializer, json))
}

/** The work of the reified `put`, outside the inline function. */
@PublishedApi
internal suspend fun <T> Store.putJson(namespace: List<String>, key: String, value: T, serializer: KSerializer<T>, json: Json) {
    if (value is String && serializer == String.serializer()) {
        put(namespace, key, value)
    } else {
        putWith(namespace, key, value, KotlinxStateSerializer(serializer, json))
    }
}
