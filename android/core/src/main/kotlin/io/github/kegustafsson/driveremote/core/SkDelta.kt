package io.github.kegustafsson.driveremote.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The Signal K delta wire format, parse side only. Port of the message handling
 * in `sk-plugin/src/skClient.ts`.
 *
 * STRICTLY READ-ONLY BY CONSTRUCTION, exactly as the browser client is: the
 * only message this app ever sends on the WebSocket is the initial `subscribe`
 * (built by [buildSubscribeMessage] below). There is deliberately no
 * delta-building function here at all. A working one, even unused, would be a
 * standing invitation for a future feature to bypass the arming authority
 * entirely; its absence makes the read-only invariant structural rather than
 * conventional.
 */
object SkDelta {

  private val json = Json { ignoreUnknownKeys = true }

  private const val SELF_CONTEXT = "vessels.self"

  /**
   * The single subscribe message sent on connect. Scoped to exactly the paths
   * this station displays -- the stream URL already carries `subscribe=none`,
   * so nothing else arrives.
   */
  fun buildSubscribeMessage(paths: List<String> = SkContract.SUBSCRIBE_PATHS): String =
    buildJsonObject {
        put("context", SELF_CONTEXT)
        putJsonArray("subscribe") {
          paths.forEach { path -> add(buildJsonObject { put("path", path) }) }
        }
      }
      .toString()

  /**
   * Extract the path/value pairs from one inbound frame, keeping only paths in
   * [accept].
   *
   * Returns an empty list for anything malformed rather than throwing: one bad
   * frame must never take down the connection that carries the kill switch's
   * view of the world. Unparseable input is indistinguishable from a frame that
   * simply had nothing for us.
   *
   * A JSON `null` value is preserved as a null entry rather than dropped --
   * "this path was explicitly cleared" is different information from "this path
   * was not in the frame", and the value store needs to be able to tell them
   * apart when deciding what it currently knows.
   */
  fun parseUpdates(raw: String, accept: Set<String>): List<Pair<String, Any?>> =
    runCatching {
        val root = json.parseToJsonElement(raw) as? JsonObject ?: return emptyList()
        val updates = root["updates"] as? JsonArray ?: return emptyList()

        buildList {
          for (update in updates) {
            val values = (update as? JsonObject)?.get("values") as? JsonArray ?: continue
            for (pv in values) {
              val entry = pv as? JsonObject ?: continue
              val path = (entry["path"] as? JsonPrimitive)?.takeIf { it.isString }?.content
              if (path == null || path !in accept) continue
              add(path to decodeValue(entry["value"]))
            }
          }
        }
      }
      .getOrDefault(emptyList())

  /**
   * Decode a Signal K value into a plain Kotlin type. Deliberately narrow: the
   * paths this station subscribes to carry only booleans, numbers and short
   * strings, so anything structured is passed through as its JSON text rather
   * than half-interpreted into something a display might misread.
   */
  private fun decodeValue(element: kotlinx.serialization.json.JsonElement?): Any? =
    when (element) {
      null,
      JsonNull -> null
      is JsonPrimitive ->
        when {
          element.isString -> element.content
          else ->
            element.booleanOrNull
              ?: element.doubleOrNull?.let { d ->
                // Keep integral values integral so a heading reads 41, not 41.0.
                if (d == d.toLong().toDouble()) d.toLong() else d
              }
              ?: element.content
        }
      is JsonObject -> element.jsonObject.toString()
      is JsonArray -> element.jsonArray.toString()
    }
}
