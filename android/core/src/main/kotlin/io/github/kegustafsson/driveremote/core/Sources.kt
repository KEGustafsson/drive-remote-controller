package io.github.kegustafsson.driveremote.core

/**
 * Port of `sk-plugin/src/pure/sources.ts`: what RX/HH report as the source
 * currently commanding a machine, and how to name it for the operator.
 *
 * The four ids mirror `control_core`'s arbitration source enum
 * (kLocal/kTx/kPlugin/none) as published by RX (ARCHITECTURE.md §9).
 */
enum class CommandSource(val wire: String) {
  LOCAL("local"),
  TX("tx"),
  PLUGIN("plugin"),
  NONE("none"),

  /** Not a wire value: what an unrecognised or absent reading maps to. */
  UNKNOWN("");

  /** How to name this source to the operator. */
  val label: String
    get() =
      when (this) {
        LOCAL -> "local switch"
        TX -> "TX remote"
        PLUGIN -> "this app"
        NONE -> "nobody"
        UNKNOWN -> "unknown"
      }

  /**
   * Does this source outrank -- or operate independently of -- this app, so a
   * press here is NOT what is moving the machine?
   *
   * Precedence is FIXED at local (unconditional) > TX > plugin
   * (ARCHITECTURE.md §5, arbitration.cpp), never recency-based: a recency
   * tie-break oscillates the output when two stations disagree (AGENTS.md
   * non-negotiable 3). So local and TX both override this app; PLUGIN is us,
   * and NONE/UNKNOWN are nobody.
   */
  val overridesThisApp: Boolean
    get() = this == LOCAL || this == TX
}

/** Parse a source reading from telemetry. Anything unrecognised is UNKNOWN. */
fun parseSource(raw: Any?): CommandSource =
  when (raw) {
    "local" -> CommandSource.LOCAL
    "tx" -> CommandSource.TX
    "plugin" -> CommandSource.PLUGIN
    "none" -> CommandSource.NONE
    else -> CommandSource.UNKNOWN
  }

/** Convenience wrappers mirroring the TypeScript module's free functions. */
fun sourceLabel(raw: Any?): String = parseSource(raw).label

fun overridesThisApp(raw: Any?): Boolean = parseSource(raw).overridesThisApp
