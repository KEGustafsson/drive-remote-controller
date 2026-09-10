// The bow thruster widget. Two modes on one pair of controls, so the tests
// below are mostly about the mode boundary: a manual press must never leak
// into hold, a heading trim must never leak into manual, and neither must
// happen silently.

import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ThrusterControl } from './ThrusterControl';
import type { ThrusterMode } from '../clientIntent';

function renderControl(
  props: Partial<Parameters<typeof ThrusterControl>[0]> = {},
) {
  const onModeChange = vi.fn();
  const onDirectionChange = vi.fn();
  const onTrim = vi.fn();
  render(
    <ThrusterControl
      mode={'manual' as ThrusterMode}
      onModeChange={onModeChange}
      onDirectionChange={onDirectionChange}
      trimDeg={0}
      onTrim={onTrim}
      heldDeg={40}
      armed={true}
      holdsControl={true}
      {...props}
    />,
  );
  return { onModeChange, onDirectionChange, onTrim };
}

const press = (el: HTMLElement) =>
  fireEvent.pointerDown(el, { pointerId: 1, isPrimary: true });
const release = (el: HTMLElement) =>
  fireEvent.pointerUp(el, { pointerId: 1, isPrimary: true });

describe('ThrusterControl in MANUAL mode', () => {
  it('commands a direction while held and OFF on release', () => {
    const { onDirectionChange } = renderControl();
    const port = screen.getByLabelText('Thrust bow to port');

    press(port);
    expect(onDirectionChange).toHaveBeenLastCalledWith('port');

    release(port);
    expect(onDirectionChange).toHaveBeenLastCalledWith('off');
  });

  it('resolves both buttons pressed at once to OFF', () => {
    // Same fail-safe resolution as a shift switch reading two ways at once --
    // an ambiguous input must never pick a direction.
    const { onDirectionChange } = renderControl();
    fireEvent.pointerDown(screen.getByLabelText('Thrust bow to port'), {
      pointerId: 1,
      isPrimary: true,
    });
    fireEvent.pointerDown(screen.getByLabelText('Thrust bow to starboard'), {
      pointerId: 2,
      isPrimary: false,
    });
    expect(onDirectionChange).toHaveBeenLastCalledWith('off');
  });

  it('greys out and disables the manual buttons when not commandable', () => {
    // Disarmed, offline, or HH not answering (all folded into armed=false):
    // the thruster panel must not invite a press it can't honour. The buttons
    // are disabled and the widget carries the greyed is-disabled class; a press
    // produces no direction command.
    const { onDirectionChange } = renderControl({ armed: false });
    const port = screen.getByLabelText('Thrust bow to port');
    expect(port).toBeDisabled();
    expect(screen.getByLabelText('Thrust bow to starboard')).toBeDisabled();
    expect(document.querySelector('.thruster-control.is-disabled')).not.toBeNull();
    press(port);
    // Still OFF -- the disabled button accepts no press (last call stays 'off').
    expect(onDirectionChange).toHaveBeenLastCalledWith('off');
  });

  it('disables the trim when not commandable, but NOT the mode chooser', () => {
    // The trim commands the thruster (HOLD steers through it), so it goes
    // inert with everything else. The MANUAL/HOLD chooser commands nothing --
    // it only decides which gate the next arm opens -- and the operator picks
    // that before arming, so it stays live and pressable while disarmed.
    const { onModeChange } = renderControl({ armed: false, holdsControl: false, mode: 'hold' });
    expect(screen.getByLabelText(/Trim 10 degrees to starboard/)).toBeDisabled();
    expect(screen.getByText('MANUAL')).not.toBeDisabled();
    expect(screen.getByText('HOLD')).not.toBeDisabled();
    fireEvent.click(screen.getByText('MANUAL'));
    expect(onModeChange).toHaveBeenLastCalledWith('manual');
  });

  it('does not tell an ARMED operator to arm when HH is the thing missing', () => {
    // Armed, but the thruster unit is not answering: the same controls are
    // inert for a completely different reason, and "arm to thrust" would point
    // the operator at the one control already doing its job. The kill switch
    // and the status rows name the real reason.
    renderControl({ armed: false, holdsControl: true, mode: 'manual' });
    expect(screen.queryByText(/arm to thrust/)).not.toBeInTheDocument();
    expect(screen.queryByText(/starts holding when you arm/)).not.toBeInTheDocument();
  });

  it('says a disarmed MANUAL selection takes effect on arm', () => {
    // Disarmed, the panel must not read as though a press would thrust. It
    // states the selection and what will make it live.
    renderControl({ armed: false, holdsControl: false, mode: 'manual' });
    expect(screen.getByText(/MANUAL selected — arm to thrust/)).toBeInTheDocument();
  });

  it('shows OFF muted (the --off state class) while PORT/STBD stay default', () => {
    const { onDirectionChange } = renderControl();
    // Off by default -> the muted state class.
    expect(document.querySelector('.thruster-control__state--off')).not.toBeNull();
    press(screen.getByLabelText('Thrust bow to starboard'));
    expect(onDirectionChange).toHaveBeenLastCalledWith('stbd');
    expect(document.querySelector('.thruster-control__state--stbd')).not.toBeNull();
    expect(document.querySelector('.thruster-control__state--off')).toBeNull();
  });

  it('explains a withheld reversal instead of just looking unresponsive', () => {
    // The one delay in the manual path is the thruster control box's own
    // interlock; an operator who is not told will assume the app is broken.
    renderControl({ reversalPending: true });
    expect(screen.getByText(/waiting for the thruster/)).toBeInTheDocument();
  });

  it('names the station that outranks this app', () => {
    renderControl({ overriddenBy: 'TX remote' });
    expect(screen.getByText(/controlled by TX remote/)).toBeInTheDocument();
  });
});

describe('ThrusterControl in HOLD mode', () => {
  it('offers no direction buttons at all', () => {
    // The manual buttons must not merely be ignored in hold mode -- they must
    // not be there to press.
    renderControl({ mode: 'hold' });
    expect(screen.queryByLabelText('Thrust bow to port')).not.toBeInTheDocument();
    expect(
      screen.queryByLabelText('Thrust bow to starboard'),
    ).not.toBeInTheDocument();
  });

  it('reports OFF as the direction so no manual command leaks into hold', () => {
    const { onDirectionChange } = renderControl({ mode: 'hold' });
    expect(onDirectionChange).toHaveBeenLastCalledWith('off');
  });

  it('trims by the fine and coarse steps in both directions', () => {
    const { onTrim } = renderControl({ mode: 'hold' });
    fireEvent.click(screen.getByLabelText(/Trim 1 degree to starboard/));
    expect(onTrim).toHaveBeenLastCalledWith(1);
    fireEvent.click(screen.getByLabelText(/Trim 1 degree to port/));
    expect(onTrim).toHaveBeenLastCalledWith(-1);
    fireEvent.click(screen.getByLabelText(/Trim 10 degrees to starboard/));
    expect(onTrim).toHaveBeenLastCalledWith(10);
    fireEvent.click(screen.getByLabelText(/Trim 10 degrees to port/));
    expect(onTrim).toHaveBeenLastCalledWith(-10);
  });

  it('shows the unit’s held heading, labelled holding, with no trim', () => {
    renderControl({ mode: 'hold', heldDeg: 40, trimDeg: 0 });
    expect(screen.getByText('040°')).toBeInTheDocument();
    expect(screen.getByText('holding')).toBeInTheDocument();
  });

  it('keeps showing the held heading and states the trim offset once trimmed', () => {
    // The big number stays the ACTUAL held heading (HH already folds the trim
    // into it as it slews); the trim is shown as a signed offset beside it.
    renderControl({ mode: 'hold', heldDeg: 40, trimDeg: 11 });
    expect(screen.getByText('040°')).toBeInTheDocument();
    expect(screen.getByText(/trim \+11°/)).toBeInTheDocument();
  });

  it('shows dashes rather than a made-up heading when none is known', () => {
    renderControl({ mode: 'hold', heldDeg: null, trimDeg: 0 });
    expect(screen.getByText('---°')).toBeInTheDocument();
  });

  it('selectable while disarmed, and says so rather than claiming a hold', () => {
    // HOLD can be armed INTO, so it must be selectable with nothing armed.
    // Two things then have to be true on screen: the number is labelled as the
    // current heading (disarmed, HH mirrors the setpoint to the fused heading
    // -- nothing is being held), and the operator is told that arming is what
    // engages it, since HOLD needs no further press to start working the
    // thruster.
    const { onModeChange } = renderControl({
      armed: false,
      holdsControl: false,
      mode: 'manual',
      heldDeg: 40,
    });
    fireEvent.click(screen.getByText('HOLD'));
    expect(onModeChange).toHaveBeenLastCalledWith('hold');

    renderControl({ armed: false, holdsControl: false, mode: 'hold', heldDeg: 40, trimDeg: 0 });
    expect(screen.getByText('current heading')).toBeInTheDocument();
    expect(screen.queryByText('holding')).not.toBeInTheDocument();
    expect(
      screen.getByText(/HOLD selected — starts holding when you arm/),
    ).toBeInTheDocument();
  });
});

describe('ThrusterControl mode switch', () => {
  it('releases a contact still held when the mode leaves MANUAL, so it cannot come back pressed', () => {
    // Two hands: PORT held, HOLD tapped. The PORT contact unmounts with the
    // pointer still down, so its pointerup never reaches the hook. Returning
    // to MANUAL must not find it still pressed and re-command port with no
    // finger on the glass -- and a new press on it must still work.
    const onDirectionChange = vi.fn();
    const props = {
      onModeChange: vi.fn(),
      onDirectionChange,
      trimDeg: 0,
      onTrim: vi.fn(),
      heldDeg: 40,
      armed: true,
      holdsControl: true,
    };
    const { rerender } = render(<ThrusterControl mode="manual" {...props} />);
    press(screen.getByLabelText('Thrust bow to port'));
    expect(onDirectionChange).toHaveBeenLastCalledWith('port');

    rerender(<ThrusterControl mode="hold" {...props} />);
    expect(onDirectionChange).toHaveBeenLastCalledWith('off');

    rerender(<ThrusterControl mode="manual" {...props} />);
    expect(onDirectionChange).toHaveBeenLastCalledWith('off');
    const port = screen.getByLabelText('Thrust bow to port');
    expect(port).toHaveAttribute('aria-pressed', 'false');

    press(port);
    expect(onDirectionChange).toHaveBeenLastCalledWith('port');
    release(port);
    expect(onDirectionChange).toHaveBeenLastCalledWith('off');
  });

  it('reports the selected mode', () => {
    const { onModeChange } = renderControl({ mode: 'hold' });
    fireEvent.click(screen.getByText('MANUAL'));
    expect(onModeChange).toHaveBeenLastCalledWith('manual');
  });

  it('marks the active mode for assistive tech, not just visually', () => {
    renderControl({ mode: 'hold' });
    expect(screen.getByText('HOLD')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByText('MANUAL')).toHaveAttribute('aria-pressed', 'false');
  });
});
