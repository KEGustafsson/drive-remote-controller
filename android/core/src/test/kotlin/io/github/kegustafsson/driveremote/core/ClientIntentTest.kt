package io.github.kegustafsson.driveremote.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The intent wire format is a contract with `arbiter.cjs`. The arbiter
 * sanitises everything it receives (that is pinned exhaustively in
 * `test/arbiter.test.ts`), but it can only do so for fields it recognises --
 * so the field NAMES are pinned here as literals rather than derived, because
 * a rename would be silently accepted as "field absent" and read as the safe
 * default instead of the operator's actual command.
 */
class ClientIntentTest {

  private fun fields(intent: ClientIntent): JsonObject =
    Json.parseToJsonElement(intent.toJson()) as JsonObject

  private fun JsonObject.str(key: String) = (this[key] as JsonPrimitive).content

  private val armed =
    ClientIntent(
      session = 3,
      clientId = "ui-abc",
      seq = 7,
      armReq = 2,
      disarmReq = 1,
      port = DrivePosition.FORWARD,
      stbd = DrivePosition.REVERSE,
      thruster = ThrusterDirection.PORT,
      thrusterMode = ThrusterMode.MANUAL,
      trimDeg = 0.0,
    )

  @Test
  fun `carries exactly the fields the arbiter reads, under those names`() {
    val json = fields(armed)
    assertEquals(
      setOf(
        "clientId",
        // Per-launch, unlike clientId. The arbiter uses a change here to tell a
        // restart from a replayed packet -- see ClientIntent.session.
        "session",
        "seq",
        "armReq",
        "disarmReq",
        "port",
        "stbd",
        "thruster",
        "thrusterMode",
        "trimDeg",
      ),
      json.keys,
    )
  }

  @Test
  fun `serialises enums as their wire strings, not their Kotlin names`() {
    val json = fields(armed)
    assertEquals("forward", json.str("port"))
    assertEquals("reverse", json.str("stbd"))
    assertEquals("port", json.str("thruster"))
    assertEquals("manual", json.str("thrusterMode"))
  }

  @Test
  fun `carries the counters as numbers`() {
    val json = fields(armed)
    assertEquals("7", json.str("seq"))
    assertEquals("2", json.str("armReq"))
    assertEquals("1", json.str("disarmReq"))
  }

  @Test
  fun `the two-handed manoeuvre is representable -- opposite directions at once`() {
    // Port FORWARD and starboard REVERSE simultaneously is normal operation
    // (pivot-turning), not an error: the sides must never be coupled.
    val json = fields(armed)
    assertEquals("forward", json.str("port"))
    assertEquals("reverse", json.str("stbd"))
  }

  @Test
  fun `the safe intent commands nothing`() {
    val safe =
      ClientIntent.safe(clientId = "ui-abc", session = 3, seq = 1, armReq = 0, disarmReq = 0)
    assertEquals(DrivePosition.NEUTRAL, safe.port)
    assertEquals(DrivePosition.NEUTRAL, safe.stbd)
    assertEquals(ThrusterDirection.OFF, safe.thruster)
    assertEquals(0.0, safe.trimDeg)

    // MANUAL, and this is the field the others depend on. `thruster = OFF` does
    // NOT by itself stop the thruster: in HOLD, HH is not following a direction
    // at all -- it controls toward the trimmed setpoint, so HOLD plus a trim is
    // an ACTIVE command however 'off' the direction reads. MANUAL + OFF is the
    // only pairing that commands nothing under either mode's rules.
    //
    // Pinned because it was the gap that mattered: this test asserted the other
    // four fields and not this one, and `StationViewModel` was meanwhile sending
    // its own hand-built "safe" tuple that kept the live mode and trim.
    assertEquals(ThrusterMode.MANUAL, safe.thrusterMode)

    val json = fields(safe)
    assertEquals("neutral", json.str("port"))
    assertEquals("neutral", json.str("stbd"))
    assertEquals("off", json.str("thruster"))
    assertEquals("manual", json.str("thrusterMode"))
    assertEquals(0.0, json["trimDeg"]?.jsonPrimitive?.double)
  }

  @Test
  fun `a clientId needing JSON escaping does not corrupt the payload`() {
    // Generated ids never need this, but a hand-rolled serialiser would have
    // been a latent injection bug the day one did.
    val odd = armed.copy(clientId = "ui-\"quote\"\\and\nnewline")
    assertEquals("ui-\"quote\"\\and\nnewline", fields(odd).str("clientId"))
  }

  @Test
  fun `thruster wire strings match the firmware contract`() {
    assertEquals("port", ThrusterDirection.PORT.wire)
    assertEquals("off", ThrusterDirection.OFF.wire) // 'off', not 'neutral'
    assertEquals("stbd", ThrusterDirection.STBD.wire)
    assertEquals("manual", ThrusterMode.MANUAL.wire)
    assertEquals("hold", ThrusterMode.HOLD.wire)
  }

  @Test
  fun `acknowledgement parsing tolerates anything the server might send`() {
    assertTrue(parseIntentAck("""{"ok":true}"""))
    assertFalse(parseIntentAck("""{"ok":false}"""))
    for (body in listOf("", "not json", "[]", "{}", """{"ok":"yes"}""")) {
      assertFalse(parseIntentAck(body), "should not have read as ok: $body")
    }
  }
}
