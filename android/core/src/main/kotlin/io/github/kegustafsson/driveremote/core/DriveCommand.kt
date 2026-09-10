package io.github.kegustafsson.driveremote.core

/**
 * Port of `sk-plugin/src/pure/driveCommand.ts`, which is itself a deliberate
 * mirror of the firmware's `lib/control_core/drive/drive_command.h`. Three
 * copies of this truth table now exist -- C++, TypeScript and Kotlin -- so the
 * vectors in `DriveCommandTest` are ported one-for-one from
 * `driveCommand.test.ts` and `test_drive_command.cpp`. Change one, change all
 * three, and the tests are what will tell you that you did.
 *
 * Unlike the TypeScript, Kotlin has no string-union type, so the wire
 * representation is carried explicitly on the enum. That is closer to the
 * firmware's own enum + `ToSkString`/`FromSkString` boundary conversion than
 * the TypeScript is.
 */
enum class DrivePosition(val wire: String) {
  FORWARD("forward"),
  NEUTRAL("neutral"),
  REVERSE("reverse"),
}

/**
 * What a *display* may show for a drive. Deliberately a separate type from
 * [DrivePosition], carrying a fourth [UNKNOWN] case: telemetry that cannot be
 * parsed must be shown as unknown, never silently relabelled as neutral.
 *
 * Commanding has no such case -- there the safe answer is always a real
 * position -- which is exactly why these are two types and not one nullable
 * one. AGENTS.md: never present unconfirmable data as live; degrade the
 * indication instead.
 */
enum class DisplayDrivePosition {
  FORWARD,
  NEUTRAL,
  REVERSE,
  UNKNOWN,
}

/**
 * Maps one side's two momentary button states to a commanded position.
 *
 * Both active at once fails to [DrivePosition.NEUTRAL], never to a direction.
 * A single physical switch cannot produce that input, but two independent
 * touch targets pressed by two different fingers can -- and on a phone that is
 * an ordinary thing to do by accident. SAFETY.md drive invariant 1: never
 * assert FORWARD and REVERSE together.
 */
fun fromSwitch(forwardActive: Boolean, reverseActive: Boolean): DrivePosition =
  when {
    forwardActive && reverseActive -> DrivePosition.NEUTRAL
    forwardActive -> DrivePosition.FORWARD
    reverseActive -> DrivePosition.REVERSE
    else -> DrivePosition.NEUTRAL
  }

/**
 * Defensive parse of a drive position arriving FROM Signal K (RX's telemetry,
 * read only for display). Anything unrecognised -- wrong case, wrong type,
 * absent -- reads as [DisplayDrivePosition.UNKNOWN] rather than a guess.
 */
fun parseDisplayPosition(raw: Any?): DisplayDrivePosition =
  when (raw) {
    "forward" -> DisplayDrivePosition.FORWARD
    "neutral" -> DisplayDrivePosition.NEUTRAL
    "reverse" -> DisplayDrivePosition.REVERSE
    else -> DisplayDrivePosition.UNKNOWN
  }
