import { describe, expect, it } from 'vitest';

import type { EntityRef } from './common.js';
import {
  CONDITION_JOIN,
  EFFECT_KINDS,
  EFFECT_LABELS,
  RULE_JOIN,
  UnknownConditionTypeError,
  buildCondition,
  buildDisplayChips,
  describeCondition,
  findConditionType,
  type ConditionType,
} from './rules.js';

const elite: EntityRef = { id: 'tier_4b81', label: 'Elite', kind: 'tier' };
const gold: EntityRef = { id: 'tier_09c2', label: 'Gold', kind: 'tier' };

const tierType: ConditionType = {
  key: 'member.tier',
  label: 'Tier',
  category: 'member',
  operators: ['in', 'notIn', 'eq'],
  valueType: 'entity',
  options: [elite, gold],
};

const pointsType: ConditionType = {
  key: 'wallet.activeUnits',
  label: 'Punti attivi (Wallet premio)',
  category: 'popular',
  operators: ['gte', 'lt', 'exists'],
  valueType: 'number',
  unit: 'punti',
};

const types = [tierType, pointsType];

describe('semantica unica dei costruttori (RF-138)', () => {
  it('AND dentro la regola, OR tra regole', () => {
    expect(CONDITION_JOIN).toBe('AND');
    expect(RULE_JOIN).toBe('OR');
  });
});

describe('catalogo degli effetti', () => {
  it('ogni effetto ha un’etichetta leggibile', () => {
    for (const kind of EFFECT_KINDS) {
      expect(EFFECT_LABELS[kind]).toBeTruthy();
    }
  });
});

describe('findConditionType', () => {
  it('trova il tipo nel catalogo del modulo', () => {
    expect(findConditionType('member.tier', types)).toBe(tierType);
  });

  it('segnala i tipi sconosciuti invece di stampare la chiave in interfaccia', () => {
    expect(() => findConditionType('member.sconosciuto', types)).toThrow(UnknownConditionTypeError);
  });
});

describe('frasi delle condizioni (LG-13, RF-139)', () => {
  it('compone «Tier non è uno di Elite o Gold» senza id tecnici', () => {
    const condition = buildCondition(
      { id: 'c1', type: 'member.tier', operator: 'notIn', value: [elite, gold] },
      types,
    );
    const phrase = describeCondition(condition, types);
    expect(phrase).toBe('Tier non è uno di Elite o Gold');
    expect(phrase).not.toContain(elite.id);
    expect(phrase).not.toContain(gold.id);
  });

  it('tiene il riferimento all’entità nella chip per renderla navigabile', () => {
    const chips = buildDisplayChips({ id: 'c2', type: 'member.tier', operator: 'eq', value: elite }, tierType);
    expect(chips.map((chip) => chip.text)).toEqual(['Tier', 'uguale a', 'Elite']);
    expect(chips[2]?.ref).toEqual(elite);
  });

  it('applica l’unità ai valori numerici (LG-29)', () => {
    const condition = buildCondition(
      { id: 'c3', type: 'wallet.activeUnits', operator: 'gte', value: 500 },
      types,
    );
    expect(describeCondition(condition, types)).toBe('Punti attivi (Wallet premio) maggiore o uguale a 500 punti');
  });

  it('chiude la frase sull’operatore quando non c’è un valore', () => {
    const condition = buildCondition(
      { id: 'c4', type: 'wallet.activeUnits', operator: 'exists', value: undefined },
      types,
    );
    expect(condition.displayChips).toHaveLength(2);
    expect(describeCondition(condition, types)).toBe('Punti attivi (Wallet premio) valorizzato');
  });
});
