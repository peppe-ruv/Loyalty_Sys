import { describe, expect, it } from 'vitest';

import { ENTITY_KINDS, entityLabel, isEntityRef, localized, type EntityRef } from './common.js';

const premio: EntityRef = { id: 'rew_8821', label: 'Buono spesa 10 €', kind: 'reward' };

describe('EntityRef (RF-139)', () => {
  it('riconosce un riferimento completo', () => {
    expect(isEntityRef(premio)).toBe(true);
  });

  it('rifiuta gli oggetti incompleti o con un tipo non previsto', () => {
    expect(isEntityRef({ id: 'rew_8821', label: 'Buono' })).toBe(false);
    expect(isEntityRef({ id: 'x', label: 'y', kind: 'fattura' })).toBe(false);
    expect(isEntityRef(null)).toBe(false);
    expect(isEntityRef('rew_8821')).toBe(false);
  });

  it('mostra sempre la label, mai l’id', () => {
    expect(entityLabel(premio)).toBe(premio.label);
  });

  it('copre tutti i tipi di oggetto del backoffice', () => {
    expect(new Set(ENTITY_KINDS).size).toBe(ENTITY_KINDS.length);
    expect(ENTITY_KINDS).toContain('campaign');
  });
});

describe('testi localizzati (RF-79)', () => {
  it('ricade sull’italiano quando manca la traduzione', () => {
    const testo = { it: 'Punti attivi', en: 'Active points' };
    expect(localized(testo, 'en')).toBe('Active points');
    expect(localized(testo, 'de')).toBe('Punti attivi');
    expect(localized(testo)).toBe('Punti attivi');
  });
});
