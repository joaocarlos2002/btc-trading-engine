// Number and time formatting for the whole app: one locale, one placeholder for missing values.

export const LOCALE = 'pt-BR';
export const EMPTY = '—';

export type Tone = 'positive' | 'negative' | 'neutral';

const numberFormats = new Map<string, Intl.NumberFormat>();

function numberFormat(digits: number, signed: boolean, compact = false, trim = false): Intl.NumberFormat {
  const key = `${digits}|${signed}|${compact}|${trim}`;
  let format = numberFormats.get(key);
  if (!format) {
    format = new Intl.NumberFormat(LOCALE, {
      minimumFractionDigits: compact || trim ? 0 : digits,
      maximumFractionDigits: digits,
      signDisplay: signed ? 'exceptZero' : 'auto',
      notation: compact ? 'compact' : 'standard',
    });
    numberFormats.set(key, format);
  }
  return format;
}

export function isNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value);
}

/** 65.432,10 · signed: +1,25 / −0,40 */
export function formatNumber(value: number | null | undefined, digits = 2, signed = false): string {
  return isNumber(value) ? numberFormat(digits, signed).format(value).replace('-', '−') : EMPTY;
}

/** Up to `maxDigits` decimals, without trailing zeros: 0,35 · 20 · 0,0015 */
export function formatDecimal(value: number | null | undefined, maxDigits = 4): string {
  return isNumber(value) ? numberFormat(maxDigits, false, false, true).format(value).replace('-', '−') : EMPTY;
}

/** 1,2 mil · 3,4 mi */
export function formatCompact(value: number | null | undefined, digits = 1): string {
  return isNumber(value) ? numberFormat(digits, false, true).format(value) : EMPTY;
}

/**
 * A percentage. {@code fraction} multiplies by 100 first (probabilities, confidence: 0.62 → 62,0%);
 * without it the value already is in percent (returns, distances: 1.5 → 1,50%).
 */
export function formatPercent(
  value: number | null | undefined,
  digits = 2,
  options: { fraction?: boolean; signed?: boolean } = {},
): string {
  if (!isNumber(value)) {
    return EMPTY;
  }
  const percent = options.fraction ? value * 100 : value;
  return `${formatNumber(percent, digits, options.signed ?? false)}%`;
}

export function toneOf(value: number | null | undefined): Tone {
  if (!isNumber(value) || value === 0) {
    return 'neutral';
  }
  return value > 0 ? 'positive' : 'negative';
}

const timeFormat = new Intl.DateTimeFormat(LOCALE, {
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hour12: false,
});
const shortTimeFormat = new Intl.DateTimeFormat(LOCALE, { hour: '2-digit', minute: '2-digit', hour12: false });
const dateFormat = new Intl.DateTimeFormat(LOCALE, { day: '2-digit', month: '2-digit', year: 'numeric' });
const dateTimeFormat = new Intl.DateTimeFormat(LOCALE, {
  day: '2-digit',
  month: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hour12: false,
});

function toDate(value: string | number | Date | null | undefined): Date | null {
  if (value === null || value === undefined || value === '') {
    return null;
  }
  const date = value instanceof Date ? value : new Date(value);
  return Number.isNaN(date.getTime()) ? null : date;
}

/** 14:05:09 in the browser's time zone */
export function formatTime(value: string | number | Date | null | undefined): string {
  const date = toDate(value);
  return date ? timeFormat.format(date) : EMPTY;
}

/** 14:05 */
export function formatShortTime(value: string | number | Date | null | undefined): string {
  const date = toDate(value);
  return date ? shortTimeFormat.format(date) : EMPTY;
}

/** 18/09/2026 */
export function formatDate(value: string | number | Date | null | undefined): string {
  const date = toDate(value);
  return date ? dateFormat.format(date) : EMPTY;
}

/** 18/09 14:05 */
export function formatDateTime(value: string | number | Date | null | undefined): string {
  const date = toDate(value);
  return date ? dateTimeFormat.format(date) : EMPTY;
}

/** Milliseconds between two instants, or null when either is missing. */
export function millisBetween(from: string | null | undefined, to: string | null | undefined): number | null {
  const start = toDate(from);
  const end = toDate(to);
  return start && end ? end.getTime() - start.getTime() : null;
}

/** 2 min 05 s · 45 s */
export function formatDuration(millis: number): string {
  const seconds = Math.max(0, Math.round(millis / 1000));
  const minutes = Math.floor(seconds / 60);
  const rest = seconds % 60;
  return minutes > 0 ? `${minutes} min ${String(rest).padStart(2, '0')} s` : `${rest} s`;
}
