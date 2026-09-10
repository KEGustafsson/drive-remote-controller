import { useCallback, useEffect, useRef, useState } from 'react';
import type { PointerEvent as ReactPointerEvent } from 'react';

export interface MomentaryButtonHandlers {
  onPointerDown: (e: ReactPointerEvent<HTMLElement>) => void;
  onPointerUp: (e: ReactPointerEvent<HTMLElement>) => void;
  onPointerCancel: (e: ReactPointerEvent<HTMLElement>) => void;
  onPointerLeave: (e: ReactPointerEvent<HTMLElement>) => void;
  onLostPointerCapture: (e: ReactPointerEvent<HTMLElement>) => void;
  onContextMenu: (e: React.MouseEvent) => void;
}

/**
 * One momentary, spring-return button -- this app's equivalent of one
 * contact on TX's physical 3-position switches. Tracks its OWN pressed
 * state independently of every other button, so pressing this one has no
 * effect on any other's state: that's what makes simultaneous multi-touch
 * presses across different buttons (e.g. port forward + stbd reverse at
 * once) work correctly by construction, with no shared "currently pressed
 * button" singleton anywhere to serialize them.
 *
 * Uses the Pointer Events API (not separate mouse/touch handlers) so the
 * exact same code path serves a laptop mouse and a phone/tablet finger.
 * setPointerCapture keeps this element receiving this pointer's up/cancel
 * events even if the finger drifts slightly off the visual button bounds
 * mid-press, while still correctly releasing on a genuine cancel (e.g. an
 * incoming call interrupting the touch) -- the same "as soon as contact
 * becomes invalid" intent as TX's spring-loaded switches physically
 * returning to center on their own.
 *
 * A touch UI has failure modes a physical switch doesn't: the tab going
 * to the background, or the window losing focus, mid-press with no
 * pointerup ever arriving. handlePanic() below force-releases for exactly
 * those cases. RX's own 1000ms staleness watchdog is the ultimate
 * backstop if even this somehow doesn't fire (fail to NEUTRAL, SAFETY.md
 * drive invariant 5) --
 * this exists so the normal case doesn't have to wait for that backstop.
 *
 * `disabled` makes the button inert -- it accepts no new press and, crucially,
 * FORCE-RELEASES any press already in flight (below) so a finger that was down
 * when control was lost (disarm, or the unit going offline) can never leave the
 * button logically latched, still commanding. That is the same "fail to the
 * safe value" contract as the panic/blur backstops, applied to the moment the
 * caller withdraws command authority rather than to a lost pointer event.
 */
export function useMomentaryButton(disabled = false): {
  pressed: boolean;
  handlers: MomentaryButtonHandlers;
  /**
   * Force-release from outside, idempotent. For the one case the pointer
   * events cannot cover: the element this hook drives being UNMOUNTED with the
   * pointer still down. React delivers nothing to an unmounted element, so its
   * pointerup/lostpointercapture never arrive here and `pressed` would stand
   * until the next remount -- see ThrusterControl's mode change.
   */
  release: () => void;
} {
  const [pressed, setPressed] = useState(false);
  const activePointerId = useRef<number | null>(null);

  const release = useCallback(() => {
    activePointerId.current = null;
    setPressed(false);
  }, []);

  // Withdrawing command authority mid-press must release, exactly like a lost
  // pointer would. Without this a press held across the disarm/offline edge
  // would keep reporting `pressed` (hence a command) even though the button is
  // now visually disabled -- the button would look dead while still asking a
  // machine to move. release() is idempotent, so this is a no-op when nothing
  // was pressed.
  useEffect(() => {
    if (disabled) release();
  }, [disabled, release]);

  useEffect(() => {
    function handlePanic() {
      if (document.hidden) release();
    }
    function handleBlurOrHide() {
      release();
    }
    document.addEventListener('visibilitychange', handlePanic);
    window.addEventListener('blur', handleBlurOrHide);
    window.addEventListener('pagehide', handleBlurOrHide);
    return () => {
      document.removeEventListener('visibilitychange', handlePanic);
      window.removeEventListener('blur', handleBlurOrHide);
      window.removeEventListener('pagehide', handleBlurOrHide);
    };
  }, [release]);

  const onPointerDown = useCallback(
    (e: ReactPointerEvent<HTMLElement>) => {
      e.preventDefault();
      // No new press while disabled. The disabled DOM attribute already stops a
      // <button> from firing this, but the guard keeps the hook correct for any
      // caller and makes the "inert while disabled" contract explicit here.
      if (disabled) return;
      // Ignore a second, different pointer landing on an already-pressed
      // button (e.g. a stray second finger) -- the first pointer down
      // remains authoritative until IT releases, rather than the button
      // trying to track two pointers as one boolean.
      if (activePointerId.current !== null) return;
      // Capture is a best-effort convenience (keeps up/cancel coming to this
      // element if the finger drifts) -- if the browser refuses it (e.g. the
      // pointer was already released in the same tick), that must not abort
      // the press: the press-tracking below, plus the panic/blur backstops,
      // are what actually matter for correctness.
      try {
        e.currentTarget.setPointerCapture(e.pointerId);
      } catch {
        // ignore -- proceed without capture
      }
      activePointerId.current = e.pointerId;
      setPressed(true);
    },
    [disabled],
  );

  const onPointerUpOrCancel = useCallback(
    (e: ReactPointerEvent<HTMLElement>) => {
      if (activePointerId.current === e.pointerId) release();
    },
    [release],
  );

  // Backstop for the no-capture case (setPointerCapture refused above, or a
  // browser that dropped the capture): without capture, a finger that drags
  // OFF the button before lifting delivers its pointerup to whatever element
  // it ends over -- never to this one -- and none of the panic handlers fire
  // (the tab stays visible and focused). The button would stay logically
  // pressed, commanding a drive, until RX's staleness watchdog... which
  // never triggers either, because the heartbeat keeps repeating the stuck
  // command. So: leaving the element WITHOUT holding its capture = release.
  // With capture held this handler is inert (leave/enter events are
  // suppressed for a captured pointer), so normal drift-while-pressed
  // behavior is unchanged.
  const onPointerLeave = useCallback(
    (e: ReactPointerEvent<HTMLElement>) => {
      if (activePointerId.current !== e.pointerId) return;
      let captured = false;
      try {
        captured = e.currentTarget.hasPointerCapture(e.pointerId);
      } catch {
        captured = false;
      }
      if (!captured) release();
    },
    [release],
  );

  // And if the capture itself is torn away mid-press (element removed,
  // another element claims it, some browsers on touch interruption),
  // up/cancel may never arrive here -- treat losing the capture as the end
  // of the press. Fires after a normal pointerup too, where release() is
  // simply idempotent.
  const onLostPointerCapture = useCallback(
    (e: ReactPointerEvent<HTMLElement>) => {
      if (activePointerId.current === e.pointerId) release();
    },
    [release],
  );

  const onContextMenu = useCallback((e: React.MouseEvent) => {
    // A long-press-triggered context menu would swallow the pointerup
    // that's supposed to release this button -- suppress it.
    e.preventDefault();
  }, []);

  return {
    pressed,
    handlers: {
      onPointerDown,
      onPointerUp: onPointerUpOrCancel,
      onPointerCancel: onPointerUpOrCancel,
      onPointerLeave,
      onLostPointerCapture,
      onContextMenu,
    },
    release,
  };
}
