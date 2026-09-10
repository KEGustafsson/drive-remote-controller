package io.github.kegustafsson.driveremote.core

/**
 * Which of the three arm states this station is in.
 *
 * Derived ENTIRELY from the arbiter's published `activeClient` -- never from a
 * local guess. This is the crux of the exclusive-arm design: exactly one
 * station can be [YOU] at a time, and every station agrees on who that is
 * because they all read the same authoritative value. A station that tracked
 * its own armed flag locally would disagree with the others the moment the
 * arbiter took the token away, and two stations both believing they held it is
 * precisely the oscillation this design exists to prevent.
 *
 * Port of the `ControlState` derivation in `sk-plugin/src/App.tsx`.
 */
enum class ControlState {
  /** We hold the arm token. */
  YOU,

  /** Another station holds it -- our kill switch shows IN USE and cannot arm. */
  OTHER,

  /** Nobody holds it. */
  NONE,
}

/**
 * @param activeClient the raw `plugin.activeClient` reading. Anything that is
 *   not a non-empty string means nobody holds the token: the arbiter publishes
 *   `''` for "released", and a missing or malformed value must degrade to
 *   [ControlState.NONE] rather than to a claim of control.
 */
fun controlStateOf(activeClient: Any?, myClientId: String): ControlState =
  when {
    activeClient !is String || activeClient.isEmpty() -> ControlState.NONE
    activeClient == myClientId -> ControlState.YOU
    else -> ControlState.OTHER
  }

/**
 * Can this station actually command a given machine right now?
 *
 * All three conditions, together: we hold the token, the read socket is up, and
 * that machine's own unit is answering. The two machines are judged
 * independently because the two boards are independent -- RX drives the gears,
 * HH drives the thruster -- so a dead thruster board must not grey out the
 * gears, and vice versa.
 *
 * A widget that is not commandable is greyed and made inert rather than left
 * looking live: a control that cannot move its machine must not invite a press.
 * The kill switch is deliberately NOT gated on this -- stopping must always
 * work, and its disarm travels over HTTP independently of the read socket.
 */
fun canCommand(
  controlState: ControlState,
  connectionState: ConnectionState,
  unitLiveness: UnitLiveness,
): Boolean =
  controlState == ControlState.YOU &&
    connectionState == ConnectionState.OPEN &&
    readyToArm(unitLiveness)

/**
 * May the operator be offered an ARM at all?
 *
 * One arm covers both machines and is offered when EITHER unit is reachable.
 * Refusing to arm the drives because the thruster board happens to be switched
 * off would take away the primary docking control at the worst possible moment:
 * the drives are what you need at the dock, and the thruster board is the
 * likelier of the two to be off. Per-machine gating ([canCommand]) still
 * applies, so arming with one unit present never lights up controls for the
 * absent one.
 *
 * The server enforces this too and is the real gate; this exists so the button
 * never invites a press the server would silently refuse, and so the operator
 * is told which unit is missing.
 */
fun canArm(rxLiveness: UnitLiveness, hhLiveness: UnitLiveness): Boolean =
  readyToArm(rxLiveness) || readyToArm(hhLiveness)
