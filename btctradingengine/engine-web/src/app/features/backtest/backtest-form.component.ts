import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { formatDecimal } from '../../core/format/format';
import { BadgeComponent } from '../../shared/ui/badge.component';
import { CardComponent } from '../../shared/ui/card.component';
import { SpinnerComponent } from '../../shared/ui/feedback.components';
import { IconComponent } from '../../shared/ui/icon.component';
import { BacktestStore } from './backtest.store';
import {
  ALL_PARAMS,
  DAY_PRESETS,
  MAX_DAYS,
  MIN_DAYS,
  PARAM_GROUPS,
  ParamDef,
  parseOverrides,
  validateDays,
  validateParam,
} from './param-catalog';

/**
 * Period and optional strategy overrides. Blank fields keep the configured value, shown as the placeholder
 * once a run has reported the parameters it used. Invalid values are flagged as they are typed; the run
 * button stays disabled until they are fixed.
 */
@Component({
  selector: 'bte-backtest-form',
  imports: [CardComponent, BadgeComponent, IconComponent, SpinnerComponent],
  template: `
    <bte-card heading="Configuração" subtitle="Histórico real da Binance mainnet" icon="sliders">
      <form novalidate (submit)="$event.preventDefault(); submit()">
        <fieldset class="period">
          <legend class="field-label">Período</legend>
          <div class="presets" role="group" aria-label="Períodos sugeridos">
            @for (preset of presets; track preset) {
              <button
                type="button"
                class="preset"
                [class.active]="days() === preset"
                [attr.aria-pressed]="days() === preset"
                (click)="setDays(preset)"
              >
                {{ preset }} dias
              </button>
            }
          </div>
          <div class="field">
            <label class="field-label" for="days">Dias de histórico</label>
            <input
              id="days"
              class="input num"
              type="number"
              inputmode="numeric"
              [min]="minDays"
              [max]="maxDays"
              [value]="days()"
              [attr.aria-invalid]="!!daysError()"
              aria-describedby="days-help"
              (input)="setDays(+$any($event.target).value)"
            />
            @if (daysError(); as error) {
              <p class="field-error" id="days-help" animate.enter="enter-fade">
                <bte-icon name="alert-circle" [size]="12" /> {{ error }}
              </p>
            } @else {
              <p class="field-hint" id="days-help">Até {{ maxDays }} dias por execução.</p>
            }
          </div>
        </fieldset>

        <div class="params">
          <div class="params-head">
            <p class="field-label">Parâmetros da estratégia</p>
            @if (overrideCount()) {
              <button type="button" class="btn btn-ghost btn-sm" (click)="clearOverrides()">
                <bte-icon name="rotate-ccw" [size]="14" /> Limpar ({{ overrideCount() }})
              </button>
            }
          </div>
          <p class="field-hint">
            Opcionais. Em branco, vale o <code>application.properties</code>. Nada aqui altera o bot ao vivo.
          </p>

          @for (group of groups; track group.id; let first = $first) {
            <details class="group" [open]="first">
              <summary>
                <bte-icon name="chevron-right" [size]="14" class="chevron" />
                <span class="group-title">{{ group.label }}</span>
                @if (groupCount(group.params); as count) {
                  <bte-badge tone="accent">{{ count }}</bte-badge>
                }
              </summary>
              <div class="group-body">
                <p class="group-description">{{ group.description }}</p>
                <div class="group-fields">
                  @for (param of group.params; track param.key) {
                    <div class="field">
                      <label class="field-label" [for]="'param-' + param.key">{{ param.label }}</label>
                      <input
                        class="input num"
                        type="text"
                        inputmode="decimal"
                        autocomplete="off"
                        [id]="'param-' + param.key"
                        [value]="overrides()[param.key] ?? ''"
                        [placeholder]="placeholder(param)"
                        [attr.aria-invalid]="!!errors()[param.key]"
                        [attr.aria-describedby]="'param-' + param.key + '-help'"
                        (input)="setOverride(param.key, $any($event.target).value)"
                      />
                      @if (errors()[param.key]; as error) {
                        <p class="field-error" [id]="'param-' + param.key + '-help'">{{ error }}</p>
                      } @else {
                        <p class="field-hint" [id]="'param-' + param.key + '-help'">{{ param.hint ?? '' }}</p>
                      }
                    </div>
                  }
                </div>
              </div>
            </details>
          }
        </div>

        <button
          type="submit"
          class="btn btn-primary btn-lg btn-block run"
          [disabled]="!canRun()"
          [attr.aria-busy]="store.busy()"
        >
          @if (store.busy()) {
            <bte-spinner [size]="14" /> Rodando…
          } @else {
            <bte-icon name="play" [size]="14" /> Rodar backtest
          }
        </button>
      </form>
    </bte-card>
  `,
  styleUrl: './backtest-form.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BacktestFormComponent {
  protected readonly store = inject(BacktestStore);

  /**
   * The configured values, as reported by the last run (minus what that run overrode): shown as placeholders,
   * so a blank field says which value it will use.
   */
  readonly configParams = input<Record<string, number | boolean | string> | null>(null);

  protected readonly presets = DAY_PRESETS;
  protected readonly groups = PARAM_GROUPS;
  protected readonly minDays = MIN_DAYS;
  protected readonly maxDays = MAX_DAYS;

  // The draft lives in the store, so it survives a visit to another screen
  protected readonly days = this.store.draftDays;
  protected readonly overrides = this.store.draftOverrides;

  protected readonly daysError = computed(() => validateDays(this.days()));
  protected readonly errors = computed(() => {
    const raw = this.overrides();
    const errors: Record<string, string> = {};
    for (const def of ALL_PARAMS) {
      const error = validateParam(def, raw[def.key] ?? '');
      if (error) {
        errors[def.key] = error;
      }
    }
    return errors;
  });
  protected readonly overrideCount = computed(() => Object.keys(parseOverrides(this.overrides())).length);
  protected readonly canRun = computed(
    () => !this.store.busy() && !this.daysError() && Object.keys(this.errors()).length === 0,
  );

  protected setDays(days: number): void {
    this.days.set(Number.isFinite(days) ? days : 0);
  }

  protected setOverride(key: string, value: string): void {
    this.overrides.update((current) => ({ ...current, [key]: value }));
  }

  protected clearOverrides(): void {
    this.overrides.set({});
  }

  protected groupCount(params: ParamDef[]): number {
    const parsed = parseOverrides(this.overrides());
    return params.filter((param) => param.key in parsed).length;
  }

  protected placeholder(param: ParamDef): string {
    const value = this.configParams()?.[param.key];
    if (typeof value !== 'number') {
      return 'config';
    }
    return formatDecimal(value, param.integer ? 0 : 6);
  }

  protected submit(): void {
    if (!this.canRun()) {
      return;
    }
    const params = parseOverrides(this.overrides());
    this.store.run({ days: this.days(), params: Object.keys(params).length ? params : undefined });
  }
}
