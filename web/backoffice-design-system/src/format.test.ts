import { describe, expect, it } from 'vitest';

import { formatCount, formatDelta, formatRelativeDays, formatValue } from './format.js';

/** Intl usa spazi unificatori: i test confrontano il testo con spazi normali. */
const plain = (text: string): string => text.replace(/[\u00a0\u202f]/g, ' ');

describe('formatValue', () => {
  it('usa la punteggiatura italiana (migliaia con almeno due cifre, come da CLDR it)', () => {
    expect(plain(formatValue(1234.5))).toBe('1234,5');
    expect(plain(formatValue(12345.5))).toBe('12.345,5');
  });

  it('arrotonda gli interi', () => {
    expect(plain(formatValue(983.6, { format: 'integer' }))).toBe('984');
  });

  it('aggiunge l’unità come parola (LG-29)', () => {
    expect(plain(formatValue(984, { format: 'integer', unit: 'punti' }))).toBe('984 punti');
    expect(plain(formatValue(7, { format: 'integer', unit: 'giorni' }))).toBe('7 giorni');
  });

  it('attacca il simbolo di percentuale al numero', () => {
    expect(plain(formatValue(12.5, { unit: '%' }))).toBe('12,5%');
  });

  it('formatta la valuta con il codice ISO passato come unità', () => {
    expect(plain(formatValue(42, { format: 'currency', unit: 'EUR' }))).toContain('€');
    expect(plain(formatValue(42, { format: 'currency', unit: 'punti' }))).toContain('€');
  });

  it('tratta le percentuali come frazioni', () => {
    expect(plain(formatValue(0.835, { format: 'percent' }))).toBe('83,5%');
  });

  it('non inventa valori per i numeri non finiti', () => {
    expect(formatValue(Number.NaN)).toBe('—');
    expect(formatValue(Number.POSITIVE_INFINITY)).toBe('—');
  });
});

describe('formatCount (contatore ad anello, LG-04)', () => {
  it('compone «618 (83,5%) di 740»', () => {
    expect(plain(formatCount(618, 740))).toBe('618 (83,5%) di 740');
  });

  it('omette la percentuale quando la lista è vuota', () => {
    expect(plain(formatCount(0, 0))).toBe('0 di 0');
  });
});

describe('formatDelta (confronto con il periodo precedente, LG-41)', () => {
  it('mostra il segno della variazione', () => {
    expect(plain(formatDelta(110, 100))).toBe('+10%');
    expect(plain(formatDelta(90, 100))).toBe('-10%');
  });

  it('segnala l’assenza di variazione e il divisore nullo', () => {
    expect(formatDelta(100, 100)).toBe('=');
    expect(formatDelta(0, 0)).toBe('=');
    expect(formatDelta(5, 0)).toBe('—');
  });
});

describe('formatRelativeDays (scheda identità, LG-35)', () => {
  const now = new Date('2026-09-17T10:00:00Z');

  it('usa i giorni interi, non le ore', () => {
    expect(plain(formatRelativeDays('2026-09-29T23:00:00Z', now))).toBe('tra 12 giorni');
    expect(plain(formatRelativeDays('2026-09-14T01:00:00Z', now))).toBe('3 giorni fa');
  });

  it('riconosce la giornata corrente e le date non valide', () => {
    expect(formatRelativeDays('2026-09-17T23:59:00Z', now)).toBe('oggi');
    expect(formatRelativeDays('non-una-data', now)).toBe('—');
  });
});
