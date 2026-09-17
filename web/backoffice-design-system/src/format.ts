/**
 * Formattazione dei valori mostrati nelle liste, nei form e nei KPI (LG-04, LG-29, LG-40).
 *
 * Le stesse funzioni servono tabella, chip di filtro e grafico dei KPI: è ciò che tiene
 * allineati «618 (83,5%) di 740» in cima alla lista e il totale del cruscotto.
 */

import type { Unit } from './common.js';

/** Locale di default del backoffice. */
export const DEFAULT_LOCALE = 'it-IT';

/** Valuta di default quando `format: 'currency'` non specifica un'unità ISO. */
export const DEFAULT_CURRENCY = 'EUR';

export type ValueFormat = 'integer' | 'decimal' | 'currency' | 'percent';

export interface FormatOptions {
  format?: ValueFormat;
  /** Unità mostrata come suffisso; con `format: 'currency'` è il codice ISO della valuta. */
  unit?: Unit;
  locale?: string;
  /** Cifre decimali massime; se assente dipende dal formato (0 per integer, 2 altrimenti). */
  maximumFractionDigits?: number;
}

const CURRENCY_CODE = /^[A-Z]{3}$/;

function fractionDigits(options: FormatOptions): number {
  if (options.maximumFractionDigits !== undefined) return options.maximumFractionDigits;
  if (options.format === 'integer') return 0;
  if (options.format === 'percent') return 1;
  return 2;
}

/**
 * Formatta un numero secondo il formato del KPI o l'unità della colonna.
 *
 * `percent` accetta una frazione (`0.835` → `83,5%`), coerente con il contatore ad anello
 * delle liste, dove la percentuale nasce da `filtrate / totale`.
 */
export function formatValue(value: number, options: FormatOptions = {}): string {
  const locale = options.locale ?? DEFAULT_LOCALE;
  const maximumFractionDigits = fractionDigits(options);

  if (!Number.isFinite(value)) return '—';

  if (options.format === 'currency') {
    const currency = options.unit !== undefined && CURRENCY_CODE.test(options.unit) ? options.unit : DEFAULT_CURRENCY;
    return new Intl.NumberFormat(locale, { style: 'currency', currency, maximumFractionDigits }).format(value);
  }

  if (options.format === 'percent') {
    return new Intl.NumberFormat(locale, { style: 'percent', maximumFractionDigits }).format(value);
  }

  const number = new Intl.NumberFormat(locale, { maximumFractionDigits }).format(value);
  if (options.unit === undefined || options.unit === '') return number;
  // Il simbolo di percentuale resta attaccato al numero, le altre unità sono parole.
  return options.unit === '%' ? `${number}%` : `${number} ${options.unit}`;
}

/**
 * Contatore ad anello in cima alle liste (LG-04): «618 (83,5%) di 740».
 * Con `total` a zero la percentuale non ha senso e viene omessa.
 */
export function formatCount(filtered: number, total: number, locale: string = DEFAULT_LOCALE): string {
  const filteredText = formatValue(filtered, { format: 'integer', locale });
  const totalText = formatValue(total, { format: 'integer', locale });
  if (total <= 0) return `${filteredText} di ${totalText}`;
  const share = formatValue(filtered / total, { format: 'percent', locale });
  return `${filteredText} (${share}) di ${totalText}`;
}

/** Variazione rispetto al periodo precedente (LG-41): «+12,4%», «=» quando non cambia. */
export function formatDelta(current: number, previous: number, locale: string = DEFAULT_LOCALE): string {
  if (previous === 0) return current === 0 ? '=' : '—';
  const delta = (current - previous) / Math.abs(previous);
  if (delta === 0) return '=';
  const sign = delta > 0 ? '+' : '';
  return `${sign}${formatValue(delta, { format: 'percent', locale })}`;
}

const MS_PER_DAY = 86_400_000;

/**
 * Frase relativa per le date della scheda identità (LG-35): «tra 12 giorni», «3 giorni fa».
 * L'arrotondamento è per giorni interi: la scheda non mostra ore.
 */
export function formatRelativeDays(
  target: Date | string,
  now: Date = new Date(),
  locale: string = DEFAULT_LOCALE,
): string {
  const date = typeof target === 'string' ? new Date(target) : target;
  if (Number.isNaN(date.getTime())) return '—';
  const days = Math.round((startOfDay(date) - startOfDay(now)) / MS_PER_DAY);
  if (days === 0) return 'oggi';
  return new Intl.RelativeTimeFormat(locale, { numeric: 'auto' }).format(days, 'day');
}

function startOfDay(date: Date): number {
  return Date.UTC(date.getFullYear(), date.getMonth(), date.getDate());
}
