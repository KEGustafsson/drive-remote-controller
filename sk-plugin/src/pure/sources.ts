// Pure mapping of RX's reported command-source telemetry
// (control.remoteController.rx.<side>.source) to what it is and how to
// name it for the operator. Shared by the StatusPanel (which shows the
// authoritative source per drive) and App (which uses it to warn when
// THIS app's presses aren't the thing actually driving a drive) -- one
// definition so the two can't drift.
//
// These four ids mirror control_core's arbitration source enum
// (kLocal/kTx/kPlugin/none) as published by RX in src/rx (see ARCHITECTURE.md §9).

export type RemoteSourceId = 'local' | 'tx' | 'plugin' | 'none';
export type DisplaySourceId = RemoteSourceId | 'unknown';

export function parseSource(raw: unknown): DisplaySourceId {
  if (raw === 'local' || raw === 'tx' || raw === 'plugin' || raw === 'none') {
    return raw;
  }
  return 'unknown';
}

export function sourceLabel(raw: unknown): string {
  switch (parseSource(raw)) {
    case 'local':
      return 'local switch';
    case 'tx':
      return 'TX remote';
    case 'plugin':
      return 'this app';
    case 'none':
      return 'nobody';
    default:
      return 'unknown';
  }
}

// A source that outranks or is independent of this app -- i.e. one whose
// presence means a press here is NOT what's moving the drive. RX's fixed
// precedence is local (unconditional) > TX > plugin (ARCHITECTURE.md §5,
// arbitration.cpp), so 'local' and 'tx' both override this app.
export function overridesThisApp(raw: unknown): boolean {
  const s = parseSource(raw);
  return s === 'local' || s === 'tx';
}
