import { Pipe, PipeTransform } from '@angular/core';
import {
  formatCompact,
  formatDate,
  formatDateTime,
  formatNumber,
  formatPercent,
  formatShortTime,
  formatTime,
} from '../../core/format/format';

/** {{ price | num }} · {{ delta | num: 4 : true }} */
@Pipe({ name: 'num' })
export class NumPipe implements PipeTransform {
  transform(value: number | null | undefined, digits = 2, signed = false): string {
    return formatNumber(value, digits, signed);
  }
}

/** {{ openInterest | compact }} */
@Pipe({ name: 'compact' })
export class CompactPipe implements PipeTransform {
  transform(value: number | null | undefined, digits = 1): string {
    return formatCompact(value, digits);
  }
}

/** {{ 0.62 | pct: 1 : true }} → 62,0% · {{ 1.5 | pct }} → 1,50% */
@Pipe({ name: 'pct' })
export class PctPipe implements PipeTransform {
  transform(value: number | null | undefined, digits = 2, fraction = false, signed = false): string {
    return formatPercent(value, digits, { fraction, signed });
  }
}

/** {{ iso | clock }} → 14:05:09 · {{ iso | clock: 'short' }} → 14:05 */
@Pipe({ name: 'clock' })
export class ClockPipe implements PipeTransform {
  transform(value: string | number | Date | null | undefined, style: 'full' | 'short' = 'full'): string {
    return style === 'short' ? formatShortTime(value) : formatTime(value);
  }
}

/** {{ iso | day }} → 18/09/2026 · {{ iso | day: 'time' }} → 18/09 14:05 */
@Pipe({ name: 'day' })
export class DayPipe implements PipeTransform {
  transform(value: string | number | Date | null | undefined, style: 'date' | 'time' = 'date'): string {
    return style === 'time' ? formatDateTime(value) : formatDate(value);
  }
}
