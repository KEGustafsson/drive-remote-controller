import { useEffect } from 'react';
import { fromSwitch, type DrivePosition } from '../pure/driveCommand';
import { useMomentaryButton } from '../hooks/useMomentaryButton';

interface DriveControlProps {
  side: 'port' | 'stbd';
  label: string;
  /**
   * Set to a human source label ("TX remote", "local switch") when RX
   * reports a higher-precedence source actually owns this drive right
   * now, so a press here is NOT what's moving it. Shown as an honest
   * annotation rather than left for the operator to infer from the far-off
   * status panel -- the button can otherwise read "FORWARD" while the
   * drive stays where TX put it. Undefined = this app isn't being
   * overridden (or isn't armed to command at all).
   */
  overriddenBy?: string;
  /**
   * True when a press here cannot reach the drive -- this app is disarmed,
   * the socket is down, or the RX unit is not answering. The buttons are then
   * greyed and made inert (not merely ignored): a control that cannot move the
   * machine must not look like it can. The kill switch remains the always-live
   * stop, and useMomentaryButton force-releases any in-flight press when this
   * flips true, so nothing can stay latched. Distinct from `overriddenBy`,
   * which means "you ARE commanding but a higher-precedence station wins" --
   * there the buttons stay live and merely dim.
   */
  disabled?: boolean;
  onPositionChange: (position: DrivePosition) => void;
}

export function DriveControl({
  side,
  label,
  overriddenBy,
  disabled = false,
  onPositionChange,
}: DriveControlProps) {
  const forward = useMomentaryButton(disabled);
  const reverse = useMomentaryButton(disabled);
  const position = fromSwitch(forward.pressed, reverse.pressed);

  useEffect(() => {
    onPositionChange(position);
  }, [position, onPositionChange]);

  return (
    <section
      className={`drive-control drive-control--${side}${overriddenBy ? ' is-overridden' : ''}${disabled ? ' is-disabled' : ''}`}
      aria-label={`${label} drive control`}
    >
      <h2 className="drive-control__label">{label}</h2>

      <button
        type="button"
        className={`drive-button drive-button--forward${forward.pressed ? ' is-pressed' : ''}`}
        aria-pressed={forward.pressed}
        aria-label={`${label} forward`}
        disabled={disabled}
        {...forward.handlers}
      >
        <span aria-hidden="true">▲</span>
        <span>FWD</span>
      </button>

      <div
        className={`drive-control__state drive-control__state--${position}`}
        aria-live="polite"
      >
        {position.toUpperCase()}
        {overriddenBy && (
          <span className="drive-control__override" role="status">
            {' '}
            · controlled by {overriddenBy}
          </span>
        )}
      </div>

      <button
        type="button"
        className={`drive-button drive-button--reverse${reverse.pressed ? ' is-pressed' : ''}`}
        aria-pressed={reverse.pressed}
        aria-label={`${label} reverse`}
        disabled={disabled}
        {...reverse.handlers}
      >
        <span aria-hidden="true">▼</span>
        <span>REV</span>
      </button>
    </section>
  );
}
