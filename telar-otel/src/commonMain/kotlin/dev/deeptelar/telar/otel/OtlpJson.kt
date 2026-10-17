package dev.deeptelar.telar.otel

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The body of an OTLP/HTTP request with [spans], in the JSON encoding of OTLP: ids in hex, and
 * 64-bit numbers as strings.
 */
internal fun encodeTraces(resource: List<Attribute>, scope: String, spans: List<SpanRecord>): String =
    buildJsonObject {
        putJsonArray("resourceSpans") {
            addJsonObject {
                putJsonObject("resource") { put("attributes", resource.json()) }
                putJsonArray("scopeSpans") {
                    addJsonObject {
                        putJsonObject("scope") { put("name", scope) }
                        putJsonArray("spans") { spans.forEach { add(it.json()) } }
                    }
                }
            }
        }
    }.toString()

private fun SpanRecord.json(): JsonObject =
    buildJsonObject {
        put("traceId", traceId)
        put("spanId", spanId)
        parentSpanId?.let { put("parentSpanId", it) }
        put("name", name)
        put("kind", SPAN_KIND_INTERNAL)
        put("startTimeUnixNano", startNanos.toString())
        put("endTimeUnixNano", (endNanos ?: startNanos).toString())
        put("attributes", attributes.json())
        putJsonArray("events") {
            events.forEach { event ->
                addJsonObject {
                    put("timeUnixNano", event.timeNanos.toString())
                    put("name", event.name)
                    put("attributes", event.attributes.json())
                }
            }
        }
        error?.let { message ->
            putJsonObject("status") {
                put("code", STATUS_CODE_ERROR)
                put("message", message)
            }
        }
    }

private fun List<Attribute>.json(): JsonArray =
    buildJsonArray {
        forEach { attribute ->
            addJsonObject {
                put("key", attribute.key)
                put("value", attribute.value.json())
            }
        }
    }

private fun AttributeValue.json(): JsonObject =
    when (this) {
        is AttributeValue.Text -> buildJsonObject { put("stringValue", value) }
        is AttributeValue.Number -> buildJsonObject { put("intValue", value.toString()) }
        is AttributeValue.Texts ->
            buildJsonObject {
                putJsonObject("arrayValue") {
                    putJsonArray("values") { values.forEach { addJsonObject { put("stringValue", it) } } }
                }
            }
    }

private const val SPAN_KIND_INTERNAL = 1
private const val STATUS_CODE_ERROR = 2
