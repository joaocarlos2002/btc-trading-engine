import { FEATURE_GROUPS, readFeature } from './feature-catalog';

/** Every indicator the old index.html showed; the redesign must keep all of them (issue #134). */
const OLD_DASHBOARD_KEYS = [
  'returnPct1m', 'volatility5m', 'volatility20m', 'smaDistance', 'emaDistance', 'rsiValue', 'macdValue',
  'macdSignal', 'atrValue', 'volumeRatio', 'highLowRatio', 'closePosition', 'adx', 'atrPercent', 'bbWidth',
  'bbPercentB', 'mfi', 'vwapDistance', 'donchianPosition', 'bodyRatio', 'upperWickRatio', 'lowerWickRatio',
  'candleStreak', 'previousCandleBreak', 'recentHighDistance', 'recentLowDistance', 'swingHighTrend',
  'swingLowTrend', 'supportDistance', 'resistanceDistance', 'deltaRatio', 'cvd', 'cvdRatio', 'largeCvd',
  'largeVolumeShare', 'fundingRate', 'basisPercent', 'openInterest', 'openInterestChangePercent',
  'longShortRatio', 'vpin', 'absorption', 'absorptionSum', 'orderBookImbalance', 'hourOfDay', 'dayOfWeek',
];

describe('feature catalog', () => {
  const keys = FEATURE_GROUPS.flatMap((group) => group.items.map((item) => item.key));

  it('keeps every indicator of the old dashboard', () => {
    expect(OLD_DASHBOARD_KEYS.filter((key) => !keys.includes(key))).toEqual([]);
  });

  it('lists each indicator once and explains all of them', () => {
    expect(new Set(keys).size).toBe(keys.length);
    for (const group of FEATURE_GROUPS) {
      for (const item of group.items) {
        expect(item.tip.length, item.key).toBeGreaterThan(20);
      }
    }
  });

  it('reads values with sign, unit and words', () => {
    const find = (key: string) => FEATURE_GROUPS.flatMap((g) => g.items).find((item) => item.key === key)!;

    const rsi = readFeature(find('rsiValue'), 75.3);
    expect(rsi.value).toBe('75,3');
    expect(rsi.note).toBe('Sobrecomprado');

    const funding = readFeature(find('fundingRate'), 0.0001);
    expect(funding.value).toBe('+0,0100%');
    expect(funding.tone).toBe('positive');

    const missing = readFeature(find('openInterest'), null);
    expect(missing.value).toBe('—');
    expect(missing.note).toBe('');
  });
});
