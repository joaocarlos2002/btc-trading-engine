import { ALL_PARAMS, parseOverrides, validateDays, validateParam } from './param-catalog';

const def = (key: string) => ALL_PARAMS.find((param) => param.key === key)!;

describe('backtest parameters', () => {
  it('offers the overrides of the old backtest page', () => {
    expect(ALL_PARAMS).toHaveLength(27);
  });

  it('accepts blanks and comma decimals', () => {
    expect(validateParam(def('buyThreshold'), '')).toBeNull();
    expect(validateParam(def('buyThreshold'), '0,35')).toBeNull();
    expect(parseOverrides({ buyThreshold: '0,35', smaPeriod: ' ', rsiPeriod: '14' })).toEqual({
      buyThreshold: 0.35,
      rsiPeriod: 14,
    });
  });

  it('flags values the server would refuse', () => {
    expect(validateParam(def('sellThreshold'), '0.2')).toBe('Máximo 0');
    expect(validateParam(def('smaPeriod'), '0')).toBe('Mínimo 1');
    expect(validateParam(def('smaPeriod'), '2.5')).toBe('Use um número inteiro');
    expect(validateParam(def('targetPercent'), 'abc')).toBe('Informe um número');
  });

  it('limits the period to what the API accepts', () => {
    expect(validateDays(30)).toBeNull();
    expect(validateDays(0)).not.toBeNull();
    expect(validateDays(181)).not.toBeNull();
  });
});
