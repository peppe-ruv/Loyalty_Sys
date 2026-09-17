import { describe, expect, it } from 'vitest';

import type { EntityRef } from './common.js';
import type { ExpirationSetting } from './rules.js';
import {
  SECTION_LABELS,
  SECTION_ORDER,
  effectiveValue,
  isDeletionBlocked,
  sortSections,
  type SectionKind,
} from './forms.js';

const segmento: EntityRef = { id: 'seg_22f1', label: 'Clienti Elite', kind: 'segment' };

describe('ordine delle sezioni del form (LG-06)', () => {
  it('ogni sezione ha un titolo di default', () => {
    for (const kind of SECTION_ORDER) {
      expect(SECTION_LABELS[kind]).toBeTruthy();
    }
  });

  it('riordina le sezioni passate dal modulo, qualunque sia il loro ordine', () => {
    const disordinate: Array<{ kind: SectionKind }> = [
      { kind: 'state' },
      { kind: 'logic' },
      { kind: 'type' },
      { kind: 'limits' },
    ];
    expect(sortSections(disordinate).map((section) => section.kind)).toEqual(['type', 'logic', 'limits', 'state']);
  });

  it('non muta l’array di partenza', () => {
    const sezioni: Array<{ kind: SectionKind }> = [{ kind: 'state' }, { kind: 'type' }];
    sortSections(sezioni);
    expect(sezioni.map((section) => section.kind)).toEqual(['state', 'type']);
  });

  it('lo stato è sempre l’ultima sezione (RF-137)', () => {
    expect(SECTION_ORDER[SECTION_ORDER.length - 1]).toBe('state');
  });
});

describe('dipendenze «Usato da / Usa» (RF-141)', () => {
  it('blocca l’eliminazione finché l’oggetto è referenziato', () => {
    expect(isDeletionBlocked({ usedBy: [segmento] })).toBe(true);
    expect(isDeletionBlocked({ usedBy: [] })).toBe(false);
    expect(isDeletionBlocked(undefined)).toBe(false);
  });
});

describe('impostazioni ereditate (LG-17)', () => {
  it('usa il valore del wallet finché non c’è un override valorizzato', () => {
    const inherited: ExpirationSetting = { method: 'afterDays' };
    expect(
      effectiveValue({ override: false, inherited, inheritedDisplay: 'dal wallet Premio' }),
    ).toBe(inherited);
    expect(
      effectiveValue({ override: true, inherited, inheritedDisplay: 'dal wallet Premio' }),
    ).toBe(inherited);
    expect(
      effectiveValue({
        override: true,
        inherited,
        inheritedDisplay: 'dal wallet Premio',
        value: { method: 'none' },
      }),
    ).toEqual({ method: 'none' });
  });
});
