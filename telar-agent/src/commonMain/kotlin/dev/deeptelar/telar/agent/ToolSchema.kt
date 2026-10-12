package dev.deeptelar.telar.agent

import dev.deeptelar.telar.GraphValidationException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Returns the JSON Schema of a tool input class from its serial [descriptor].
 *
 * @throws GraphValidationException if [descriptor] is not a class, or a type in it has no schema.
 */
internal fun inputSchema(descriptor: SerialDescriptor): JsonObject =
    classSchemaOf(descriptor, SchemaOwner("A tool's input", otherWay = ", or write the schema by hand and pass a ToolSpec"))

/**
 * Returns the JSON Schema of the class a model is asked to answer with, from its serial [descriptor].
 *
 * @throws GraphValidationException if [descriptor] is not a class, or a type in it has no schema.
 */
internal fun answerSchema(descriptor: SerialDescriptor): JsonObject = classSchemaOf(descriptor, SchemaOwner("A typed answer"))

/**
 * What a schema is built for, to name it in an error.
 *
 * @property name starts the sentence of an error, for example "A tool's input".
 * @property otherWay ends the list of types that have a schema, when there is a way without one.
 */
private class SchemaOwner(
    val name: String,
    val otherWay: String = "",
)

private fun classSchemaOf(descriptor: SerialDescriptor, owner: SchemaOwner): JsonObject {
    if (descriptor.isInline || descriptor.kind !in setOf(StructureKind.CLASS, StructureKind.OBJECT)) {
        throw GraphValidationException(
            "${owner.name} must be a @Serializable class, so that it has named properties, but was ${descriptor.serialName}.",
        )
    }
    return schema(descriptor, emptyList(), owner)
}

/** [path] holds the classes that contain [descriptor], to stop at a class that contains itself. */
@OptIn(ExperimentalSerializationApi::class)
private fun schema(descriptor: SerialDescriptor, path: List<String>, owner: SchemaOwner): JsonObject {
    if (descriptor.isInline) return schema(descriptor.getElementDescriptor(0), path, owner)
    return when (descriptor.kind) {
        PrimitiveKind.STRING, PrimitiveKind.CHAR -> typed("string")
        PrimitiveKind.BOOLEAN -> typed("boolean")
        PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> typed("integer")
        PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> typed("number")
        SerialKind.ENUM ->
            buildJsonObject {
                put("type", "string")
                putJsonArray("enum") { descriptor.elementNames.forEach { add(it) } }
            }
        StructureKind.LIST ->
            buildJsonObject {
                put("type", "array")
                put("items", schema(descriptor.getElementDescriptor(0), path, owner))
            }
        StructureKind.MAP ->
            buildJsonObject {
                put("type", "object")
                put("additionalProperties", schema(descriptor.getElementDescriptor(1), path, owner))
            }
        StructureKind.CLASS, StructureKind.OBJECT -> classSchema(descriptor, path, owner)
        else -> throw GraphValidationException(
            "${owner.name} cannot have a property of type ${descriptor.serialName}. Use strings, numbers, booleans, enums, " +
                "lists, maps and @Serializable classes${owner.otherWay}.",
        )
    }
}

@OptIn(ExperimentalSerializationApi::class)
private fun classSchema(descriptor: SerialDescriptor, path: List<String>, owner: SchemaOwner): JsonObject {
    val name = descriptor.serialName.removeSuffix("?")
    if (name in path) {
        throw GraphValidationException("${owner.name} cannot have a class that contains itself, but $name does.")
    }
    val indices = 0 until descriptor.elementsCount
    return buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            for (index in indices) {
                val property = schema(descriptor.getElementDescriptor(index), path + name, owner)
                val description = descriptor.getElementAnnotations(index).filterIsInstance<Description>().firstOrNull()
                put(
                    descriptor.getElementName(index),
                    if (description == null) property else JsonObject(property + ("description" to JsonPrimitive(description.value))),
                )
            }
        }
        putJsonArray("required") {
            indices
                .filter { !descriptor.isElementOptional(it) && !descriptor.getElementDescriptor(it).isNullable }
                .forEach { add(descriptor.getElementName(it)) }
        }
        put("additionalProperties", false)
    }
}

private fun typed(type: String): JsonObject = buildJsonObject { put("type", type) }
