package me.ri3d.dashcam.recorder

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** Outgoing serialisation: null fields are omitted, everything else is written. */
internal val RecorderJson = Json { explicitNulls = false; encodeDefaults = true }

internal val EMPTY_OBJECT = JsonObject(emptyMap())

internal fun parseObject(text: String): JsonObject? =
    try { Json.parseToJsonElement(text) as? JsonObject } catch (e: Exception) { null }

// Lenient readers in the spirit of org.json's optInt/optString: wrong types yield null, never an exception.
// Every parsed model also keeps its raw JsonObject, so nothing the recorder sends is lost.

private fun JsonObject.primitive(key: String) = (get(key) as? JsonPrimitive)?.takeUnless { it is JsonNull }

internal fun JsonObject.int(key: String): Int? = primitive(key)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
internal fun JsonObject.long(key: String): Long? = primitive(key)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
internal fun JsonObject.str(key: String): String? = primitive(key)?.content
internal fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject
internal fun JsonObject.ints(key: String): List<Int> =
    (get(key) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull } ?: emptyList()
internal fun JsonObject.objects(key: String): List<JsonObject> = (get(key) as? JsonArray).objects()
internal fun JsonArray?.objects(): List<JsonObject> = this?.filterIsInstance<JsonObject>() ?: emptyList()

/** `== 1` flag as the SDK reads it (`optInt(key) == 1`). */
internal fun JsonObject.flag(key: String) = int(key) == 1

internal fun JsonElement?.asObject(): JsonObject = this as? JsonObject ?: EMPTY_OBJECT
