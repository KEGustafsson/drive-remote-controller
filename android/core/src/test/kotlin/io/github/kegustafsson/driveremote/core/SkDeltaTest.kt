package io.github.kegustafsson.driveremote.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The inbound wire format. Mirrors what `sk-plugin/src/skClient.test.ts`
 * exercises against a real `ws` server, minus the transport -- the parsing
 * itself is pure and belongs here where every malformed shape can be tried.
 */
class SkDeltaTest {

  private val accept = SkContract.SUBSCRIBE_PATHS.toSet()

  private fun delta(path: String, valueJson: String) =
    """{"updates":[{"values":[{"path":"$path","value":$valueJson}]}]}"""

  @Test
  fun `subscribe message is scoped to self and lists every subscribed path`() {
    val msg = SkDelta.buildSubscribeMessage()
    assertTrue(msg.contains("\"context\":\"vessels.self\""))
    for (path in SkContract.SUBSCRIBE_PATHS) {
      assertTrue(msg.contains("\"$path\""), "subscribe message omitted $path")
    }
  }

  @Test
  fun `extracts a boolean value`() {
    assertEquals(
      listOf(SkContract.RX_LINK_UP to true),
      SkDelta.parseUpdates(delta(SkContract.RX_LINK_UP, "true"), accept),
    )
  }

  @Test
  fun `extracts a string value`() {
    assertEquals(
      listOf(SkContract.RX_PORT_STATE to "forward"),
      SkDelta.parseUpdates(delta(SkContract.RX_PORT_STATE, "\"forward\""), accept),
    )
  }

  @Test
  fun `keeps an integral number integral so a heading reads 041 not 041 point 0`() {
    assertEquals(
      listOf(SkContract.HH_SETPOINT to 41L),
      SkDelta.parseUpdates(delta(SkContract.HH_SETPOINT, "41"), accept),
    )
  }

  @Test
  fun `keeps a fractional number fractional`() {
    assertEquals(
      listOf(SkContract.HH_SETPOINT to 41.5),
      SkDelta.parseUpdates(delta(SkContract.HH_SETPOINT, "41.5"), accept),
    )
  }

  @Test
  fun `preserves an explicit null rather than dropping the entry`() {
    // "this path was cleared" is different information from "this path was not
    // in the frame" -- the value store needs to be able to tell them apart.
    assertEquals(
      listOf(SkContract.PLUGIN_ACTIVE_CLIENT to null),
      SkDelta.parseUpdates(delta(SkContract.PLUGIN_ACTIVE_CLIENT, "null"), accept),
    )
  }

  @Test
  fun `ignores paths outside the subscription`() {
    val raw = delta("navigation.speedOverGround", "3.4")
    assertEquals(emptyList<Pair<String, Any?>>(), SkDelta.parseUpdates(raw, accept))
  }

  @Test
  fun `collects several values across several updates in one frame`() {
    val raw =
      """
      {"updates":[
        {"values":[{"path":"${SkContract.RX_LINK_UP}","value":true},
                   {"path":"${SkContract.RX_PORT_STATE}","value":"neutral"}]},
        {"values":[{"path":"${SkContract.PLUGIN_RX_LIVE}","value":false}]}
      ]}
      """
    assertEquals(
      listOf(
        SkContract.RX_LINK_UP to true,
        SkContract.RX_PORT_STATE to "neutral",
        SkContract.PLUGIN_RX_LIVE to false,
      ),
      SkDelta.parseUpdates(raw, accept),
    )
  }

  @Test
  fun `one malformed frame yields nothing instead of throwing`() {
    // A bad frame must never take down the connection that carries the kill
    // switch's view of the world.
    val garbage =
      listOf(
        "",
        "not json at all",
        "{",
        "[]",
        "{\"updates\":\"not-an-array\"}",
        "{\"updates\":[{\"values\":\"not-an-array\"}]}",
        "{\"updates\":[{\"values\":[{\"novalue\":1}]}]}",
        "{\"updates\":[{\"values\":[{\"path\":42,\"value\":1}]}]}",
        "{\"nothing\":1}",
      )
    for (raw in garbage) {
      assertEquals(
        emptyList<Pair<String, Any?>>(),
        SkDelta.parseUpdates(raw, accept),
        "should have parsed to nothing: $raw",
      )
    }
  }

  @Test
  fun `a good value in a frame alongside a malformed sibling still arrives`() {
    val raw =
      """
      {"updates":[{"values":[
        {"novalue":1},
        {"path":"${SkContract.RX_LINK_UP}","value":true}
      ]}]}
      """
    assertEquals(listOf(SkContract.RX_LINK_UP to true), SkDelta.parseUpdates(raw, accept))
  }
}
