package io.github.kegustafsson.driveremote.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/** Ported one-for-one from `sk-plugin/src/pure/sources.test.ts`. */
class SourcesTest {

  @ParameterizedTest
  @MethodSource("validIds")
  fun `accepts the exact id`(raw: String, expected: CommandSource) {
    assertEquals(expected, parseSource(raw))
  }

  @ParameterizedTest
  @MethodSource("garbage")
  fun `maps unrecognised readings to unknown`(raw: Any?) {
    assertEquals(CommandSource.UNKNOWN, parseSource(raw))
  }

  @Test
  fun `gives human labels for each id`() {
    assertEquals("local switch", sourceLabel("local"))
    assertEquals("TX remote", sourceLabel("tx"))
    assertEquals("this app", sourceLabel("plugin"))
    assertEquals("nobody", sourceLabel("none"))
    assertEquals("unknown", sourceLabel("garbage"))
  }

  @Test
  fun `local and tx outrank this app`() {
    // Fixed precedence, never recency-based (AGENTS.md non-negotiable 3).
    assertTrue(overridesThisApp("local"))
    assertTrue(overridesThisApp("tx"))
  }

  @Test
  fun `plugin, none and unknown do not outrank this app`() {
    assertFalse(overridesThisApp("plugin")) // that's us
    assertFalse(overridesThisApp("none"))
    assertFalse(overridesThisApp("whatever"))
  }

  @Test
  fun `wire strings match the telemetry contract exactly`() {
    // Pinned as literals: these come off the wire from RX and HH, so a rename
    // would silently stop the "controlled by ..." note from ever appearing.
    assertEquals("local", CommandSource.LOCAL.wire)
    assertEquals("tx", CommandSource.TX.wire)
    assertEquals("plugin", CommandSource.PLUGIN.wire)
    assertEquals("none", CommandSource.NONE.wire)
  }

  companion object {
    @JvmStatic
    fun validIds() =
      listOf(
        org.junit.jupiter.params.provider.Arguments.of("local", CommandSource.LOCAL),
        org.junit.jupiter.params.provider.Arguments.of("tx", CommandSource.TX),
        org.junit.jupiter.params.provider.Arguments.of("plugin", CommandSource.PLUGIN),
        org.junit.jupiter.params.provider.Arguments.of("none", CommandSource.NONE),
      )

    /** Mirrors the vitest list `[undefined, null, '', 'TX', 'server', 42, {}]`. */
    @JvmStatic
    fun garbage(): List<Any?> = listOf(null, "", "TX", "server", 42, emptyMap<String, Any>())
  }
}
