import {
  EMPTY,
  formatDecimal,
  formatDuration,
  formatNumber,
  formatPercent,
  millisBetween,
  toneOf,
} from './format';

describe('format', () => {
  it('formats numbers in pt-BR with a true minus sign', () => {
    expect(formatNumber(65432.1)).toBe('65.432,10');
    expect(formatNumber(-0.4, 2)).toBe('−0,40');
    expect(formatNumber(1.25, 2, true)).toBe('+1,25');
    expect(formatNumber(0, 2, true)).toBe('0,00');
  });

  it('renders missing values as a dash', () => {
    expect(formatNumber(null)).toBe(EMPTY);
    expect(formatNumber(undefined)).toBe(EMPTY);
    expect(formatNumber(Number.NaN)).toBe(EMPTY);
    expect(formatPercent(null)).toBe(EMPTY);
  });

  it('scales fractions to percent only when asked', () => {
    expect(formatPercent(0.62, 1, { fraction: true })).toBe('62,0%');
    expect(formatPercent(1.5)).toBe('1,50%');
    expect(formatPercent(-0.25, 2, { signed: true })).toBe('−0,25%');
  });

  it('trims trailing zeros but never the zeros of an integer', () => {
    expect(formatDecimal(0.35, 6)).toBe('0,35');
    expect(formatDecimal(20, 0)).toBe('20');
    expect(formatDecimal(100, 6)).toBe('100');
    expect(formatDecimal(0.00075, 6)).toBe('0,00075');
  });

  it('classifies the sign', () => {
    expect(toneOf(2)).toBe('positive');
    expect(toneOf(-2)).toBe('negative');
    expect(toneOf(0)).toBe('neutral');
    expect(toneOf(null)).toBe('neutral');
  });

  it('measures ISO instants', () => {
    expect(millisBetween('2026-09-18T12:00:00.100Z', '2026-09-18T12:00:00.350Z')).toBe(250);
    expect(millisBetween(null, '2026-09-18T12:00:00Z')).toBeNull();
  });

  it('formats durations', () => {
    expect(formatDuration(45_000)).toBe('45 s');
    expect(formatDuration(125_000)).toBe('2 min 05 s');
  });
});
