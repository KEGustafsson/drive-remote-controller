import { describe, expect, it } from 'vitest';
import {
  clampTrim,
  formatHeading,
  formatTrim,
  isPlausibleHeading,
  trimBy,
  wrapDeg180,
} from './trimOffset';
import { MAX_TRIM_DEG } from '../config';

describe('wrapDeg180', () => {
  it('leaves in-range values alone', () => {
    expect(wrapDeg180(0)).toBe(0);
    expect(wrapDeg180(180)).toBe(180);
    expect(wrapDeg180(-179)).toBe(-179);
  });
  it('wraps out-of-range values to (-180, 180]', () => {
    expect(wrapDeg180(181)).toBe(-179);
    expect(wrapDeg180(-180)).toBe(180);
    expect(wrapDeg180(370)).toBe(10);
  });
  // The reason this is modulo-based and not a subtract-360 loop: a loop never
  // terminates for +/-Infinity, and iterates ~1e28 times for a large finite
  // value. Either one hangs the browser tab -- including the kill switch.
  // Returning NaN for garbage is fine (callers screen it); hanging is not.
  it('returns promptly for non-finite and enormous inputs', () => {
    expect(wrapDeg180(Number.POSITIVE_INFINITY)).toBeNaN();
    expect(wrapDeg180(Number.NEGATIVE_INFINITY)).toBeNaN();
    expect(wrapDeg180(NaN)).toBeNaN();
    expect(Number.isFinite(wrapDeg180(1e30))).toBe(true);
  });
});

describe('isPlausibleHeading', () => {
  it('accepts finite headings within +/-360', () => {
    expect(isPlausibleHeading(0)).toBe(true);
    expect(isPlausibleHeading(-360)).toBe(true);
    expect(isPlausibleHeading(360)).toBe(true);
  });
  it('rejects out-of-range, non-finite, and non-number values', () => {
    expect(isPlausibleHeading(361)).toBe(false);
    expect(isPlausibleHeading(NaN)).toBe(false);
    expect(isPlausibleHeading(Infinity)).toBe(false);
    expect(isPlausibleHeading('40')).toBe(false);
    expect(isPlausibleHeading(null)).toBe(false);
    expect(isPlausibleHeading(undefined)).toBe(false);
  });
});

describe('clampTrim', () => {
  it('passes a value within range', () => {
    expect(clampTrim(15)).toBe(15);
    expect(clampTrim(-30)).toBe(-30);
    expect(clampTrim(0)).toBe(0);
  });
  it('bounds to +/-MAX_TRIM_DEG', () => {
    expect(clampTrim(90)).toBe(MAX_TRIM_DEG);
    expect(clampTrim(-90)).toBe(-MAX_TRIM_DEG);
    // An old 999 sentinel or corruption simply saturates -- it does not hang
    // or become an arbitrary heading.
    expect(clampTrim(999)).toBe(MAX_TRIM_DEG);
  });
  it('maps non-finite to 0 (no trim) -- the safe rest', () => {
    expect(clampTrim(NaN)).toBe(0);
    expect(clampTrim(Infinity)).toBe(0);
    expect(clampTrim(-Infinity)).toBe(0);
  });
});

describe('trimBy', () => {
  it('starts from 0 and needs no seed -- a press is always well-defined', () => {
    expect(trimBy(0, 1)).toBe(1);
    expect(trimBy(0, -10)).toBe(-10);
  });
  it('accumulates and clamps at the limit however many presses', () => {
    let t = 0;
    for (let i = 0; i < 100; i++) t = trimBy(t, 1);
    expect(t).toBe(MAX_TRIM_DEG);
    for (let i = 0; i < 200; i++) t = trimBy(t, -1);
    expect(t).toBe(-MAX_TRIM_DEG);
  });
  it('returns to 0 by trimming back', () => {
    expect(trimBy(trimBy(0, 10), -10)).toBe(0);
  });
});

describe('formatTrim', () => {
  it('shows sign and magnitude, 0 as plain 0', () => {
    expect(formatTrim(0)).toBe('0');
    expect(formatTrim(15)).toBe('+15');
    expect(formatTrim(-10)).toBe('−10');
  });
});

describe('formatHeading', () => {
  it('formats to three digits', () => {
    expect(formatHeading(0)).toBe('000');
    expect(formatHeading(7)).toBe('007');
    expect(formatHeading(41)).toBe('041');
    expect(formatHeading(180)).toBe('180');
  });
  it('normalises negatives into 0..359', () => {
    expect(formatHeading(-90)).toBe('270');
    expect(formatHeading(-1)).toBe('359');
  });
  it('wraps 360 to 000', () => {
    expect(formatHeading(360)).toBe('000');
    expect(formatHeading(-360)).toBe('000');
  });
  it('renders null as dashes', () => {
    expect(formatHeading(null)).toBe('---');
  });
});
