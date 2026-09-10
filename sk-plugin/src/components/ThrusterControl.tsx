import { useEffect } from 'react';
import { HEADING_TRIM_COARSE_DEG, HEADING_TRIM_FINE_DEG } from '../config';
import { useMomentaryButton } from '../hooks/useMomentaryButton';
import { formatHeading, formatTrim } from '../pure/trimOffset';
import type { ThrusterDirection, ThrusterMode } from '../clientIntent';

interface ThrusterControlProps {
  /** Which gate the operator has selected. */
  mode: ThrusterMode;
  onModeChange: (mode: ThrusterMode) => void;

  /** Direction being asked for right now (manual mode). */
  onDirectionChange: (dir: ThrusterDirection) => void;

  /** This UI's commanded trim offset (deg, relative to the held heading). */
  trimDeg: number;
  onTrim: (stepDeg: number) => void;

  /** The heading the unit reports it is actually holding, for reference. */
  heldDeg: number | null;

  /**
   * True when a press here can actually reach the thruster: this app holds
   * control, the socket is up, AND the heading-hold (HH) unit is answering.
   * When false -- disarmed, offline, or HH switched off -- every control that
   * COMMANDS the thruster (the PORT/STBD contacts, the trim steps) is greyed
   * and made inert: a thruster control that cannot move the bow must not look
   * like it can. The kill switch remains the always-live stop, and
   * useMomentaryButton force-releases any in-flight press when this drops, so
   * nothing latches.
   *
   * The MANUAL/HOLD chooser is deliberately NOT gated on this. It commands
   * nothing on its own -- it only says which gate the next arm will open --
   * and the operator has to be able to pick that BEFORE arming rather than
   * arming into whichever mode happens to be showing. Disarmed, the widget
   * says which mode is selected and that it takes effect on arm, so a
   * selection is never mistaken for a live command.
   */
  armed: boolean;

  /**
   * True when this station holds the arm token, whatever else is wrong.
   *
   * Separate from [armed] only so the pre-arm note says something true: DISARMED
   * is the one case an arm would fix. Armed-but-not-commandable (socket down, HH
   * switched off) leaves the same controls inert for a completely different
   * reason, and telling the operator to "arm" there would send them at the one
   * control that is already doing its job. The kill switch and the status rows
   * name that reason instead.
   */
  holdsControl: boolean;

  /** Source label when a higher-precedence station owns the thruster. */
  overriddenBy?: string;

  /**
   * True while the unit is holding a reversal off to respect the thruster
   * control box's own interlock. Shown so a press that visibly does nothing
   * for a second has a stated reason rather than looking broken -- this is
   * the one delay in the manual path and the operator should see it.
   */
  reversalPending?: boolean;
}

export function ThrusterControl({
  mode,
  onModeChange,
  onDirectionChange,
  trimDeg,
  onTrim,
  heldDeg,
  armed,
  holdsControl,
  overriddenBy,
  reversalPending,
}: ThrusterControlProps) {
  // `armed` here means "commandable right now" (see the prop doc). Not
  // commandable => every control that reaches the thruster is inert. The mode
  // chooser stays live: it selects the gate the next arm opens, not a command.
  const disabled = !armed;
  // Nothing armed here: the mode chosen now is the gate the next arm opens, and
  // the note below says so. See `holdsControl`.
  const preArm = !holdsControl;
  const port = useMomentaryButton(disabled);
  const stbd = useMomentaryButton(disabled);
  const manual = mode === 'manual';

  // Leaving MANUAL takes the PORT/STBD contacts off the screen. A pointer still
  // down on one of them at that moment -- the other hand tapping HOLD -- never
  // delivers its pointerup here: React dispatches nothing to an unmounted
  // element. Without this the hook would still read `pressed`, the contact
  // would come back on the next MANUAL selection already pressed, and the
  // effect below would re-command thrust with no finger on the glass. Same
  // rule as the disabled force-release: a contact that leaves the screen
  // releases.
  const releasePort = port.release;
  const releaseStbd = stbd.release;
  useEffect(() => {
    if (!manual) {
      releasePort();
      releaseStbd();
    }
  }, [manual, releasePort, releaseStbd]);

  // Both pressed resolves to no request, mirroring fromSwitch()'s fail-safe
  // truth table for a shift switch reading two ways at once.
  const direction: ThrusterDirection =
    port.pressed && !stbd.pressed
      ? 'port'
      : stbd.pressed && !port.pressed
        ? 'stbd'
        : 'off';

  useEffect(() => {
    onDirectionChange(manual ? direction : 'off');
  }, [direction, manual, onDirectionChange]);

  return (
    <section
      className={`thruster-control${overriddenBy ? ' is-overridden' : ''}${disabled ? ' is-disabled' : ''}`}
      aria-label="Bow thruster control"
    >
      <header className="thruster-control__head">
        <h2 className="thruster-control__label">Bow thruster</h2>
        <div
          className="thruster-control__mode"
          role="group"
          aria-label="Bow thruster mode"
        >
          <button
            type="button"
            className={`mode-button${manual ? ' is-active' : ''}`}
            aria-pressed={manual}
            onClick={() => onModeChange('manual')}
          >
            MANUAL
          </button>
          <button
            type="button"
            className={`mode-button${!manual ? ' is-active' : ''}`}
            aria-pressed={!manual}
            onClick={() => onModeChange('hold')}
          >
            HOLD
          </button>
        </div>
      </header>

      {manual ? (
        <>
          <div className="thruster-control__buttons">
            <button
              type="button"
              className={`thruster-button thruster-button--port${port.pressed ? ' is-pressed' : ''}`}
              aria-pressed={port.pressed}
              aria-label="Thrust bow to port"
              disabled={disabled}
              {...port.handlers}
            >
              <span aria-hidden="true">◀</span>
              <span>PORT</span>
            </button>
            <button
              type="button"
              className={`thruster-button thruster-button--stbd${stbd.pressed ? ' is-pressed' : ''}`}
              aria-pressed={stbd.pressed}
              aria-label="Thrust bow to starboard"
              disabled={disabled}
              {...stbd.handlers}
            >
              <span>STBD</span>
              <span aria-hidden="true">▶</span>
            </button>
          </div>
          <div
            className={`thruster-control__state thruster-control__state--${direction}`}
            aria-live="polite"
          >
            {direction === 'off' ? 'OFF' : direction.toUpperCase()}
            {preArm && (
              <span className="thruster-control__note" role="status">
                {' '}
                · MANUAL selected — arm to thrust
              </span>
            )}
            {reversalPending && (
              <span className="thruster-control__note" role="status">
                {' '}
                · reversing — waiting for the thruster
              </span>
            )}
            {overriddenBy && (
              <span className="thruster-control__note" role="status">
                {' '}
                · controlled by {overriddenBy}
              </span>
            )}
          </div>
        </>
      ) : (
        <>
          <div className="thruster-control__heading" aria-live="polite">
            <span className="thruster-control__heading-value">
              {formatHeading(heldDeg)}°
            </span>
            <span className="thruster-control__heading-label">
              {/* Disarmed, HH mirrors this path to the fused heading, so the
                  number is the boat's CURRENT heading, not a heading being
                  held. Label it for what it is -- with the mode chooser now
                  reachable while disarmed this panel is easy to open with
                  nothing engaged, and "holding" there would be a lie. */}
              {disabled
                ? 'current heading'
                : trimDeg !== 0
                  ? `holding · trim ${formatTrim(trimDeg)}°`
                  : 'holding'}
            </span>
          </div>
          <div
            className="thruster-control__trim"
            role="group"
            aria-label="Trim held heading"
          >
            <button
              type="button"
              className="trim-button"
              aria-label={`Trim ${HEADING_TRIM_COARSE_DEG} degrees to port`}
              disabled={disabled}
              onClick={() => onTrim(-HEADING_TRIM_COARSE_DEG)}
            >
              −{HEADING_TRIM_COARSE_DEG}
            </button>
            <button
              type="button"
              className="trim-button"
              aria-label={`Trim ${HEADING_TRIM_FINE_DEG} degree to port`}
              disabled={disabled}
              onClick={() => onTrim(-HEADING_TRIM_FINE_DEG)}
            >
              −{HEADING_TRIM_FINE_DEG}
            </button>
            <button
              type="button"
              className="trim-button"
              aria-label={`Trim ${HEADING_TRIM_FINE_DEG} degree to starboard`}
              disabled={disabled}
              onClick={() => onTrim(HEADING_TRIM_FINE_DEG)}
            >
              +{HEADING_TRIM_FINE_DEG}
            </button>
            <button
              type="button"
              className="trim-button"
              aria-label={`Trim ${HEADING_TRIM_COARSE_DEG} degrees to starboard`}
              disabled={disabled}
              onClick={() => onTrim(HEADING_TRIM_COARSE_DEG)}
            >
              +{HEADING_TRIM_COARSE_DEG}
            </button>
          </div>
          <div className="thruster-control__state" aria-live="polite">
            {armed && !overriddenBy && (
              <span className="thruster-control__note">
                holding heading — trim with the arrows
              </span>
            )}
            {preArm && (
              <span className="thruster-control__note" role="status">
                HOLD selected — starts holding when you arm
              </span>
            )}
            {overriddenBy && (
              <span className="thruster-control__note" role="status">
                controlled by {overriddenBy}
              </span>
            )}
          </div>
        </>
      )}
    </section>
  );
}
