import { describe, expect, it } from 'vitest';

import type { EntityRef } from './common.js';
import {
  FILTER_OPERATORS,
  OPERATOR_LABELS,
  buildFilterChip,
  chipEntityRefs,
  describeFilterChip,
  describeValue,
  type FilterAttribute,
} from './filters.js';

const walletPremio: EntityRef = { id: 'wal_7f3c19', label: 'Premio', kind: 'wallet' };
const walletStatus: EntityRef = { id: 'wal_91ab02', label: 'Status', kind: 'wallet' };

const wallet: FilterAttribute = {
  key: 'wallet',
  label: 'Wallet',
  type: 'entity',
  operators: ['eq', 'in', 'notIn'],
  options: [walletPremio, walletStatus],
};

const spesa: FilterAttribute = {
  key: 'avgSpend',
  label: 'Spesa media',
  type: 'number',
  unit: 'EUR',
  operators: ['gte', 'between'],
};

describe('vocabolario degli operatori', () => {
  it('ogni operatore ha una frase in italiano', () => {
    for (const operator of FILTER_OPERATORS) {
      expect(OPERATOR_LABELS[operator]).toBeTruthy();
    }
  });
});

describe('describeValue', () => {
  it('mostra la label delle entità, mai l’id (RF-139)', () => {
    expect(describeValue(walletPremio)).toBe('Premio');
  });

  it('unisce gli elenchi con «o»', () => {
    expect(describeValue([walletPremio, walletStatus])).toBe('Premio o Status');
  });

  it('gestisce vuoti, booleani e numeri con unità', () => {
    expect(describeValue(undefined)).toBe('—');
    expect(describeValue(true)).toBe('sì');
    expect(describeValue(30, 'giorni')).toBe('30 giorni');
  });
});

describe('describeFilterChip (LG-05)', () => {
  it('compone «Wallet: uguale a Premio»', () => {
    expect(describeFilterChip(wallet, 'eq', walletPremio)).toBe('Wallet: uguale a Premio');
  });

  it('non aggiunge valori agli operatori unari', () => {
    expect(describeFilterChip(wallet, 'exists', undefined)).toBe('Wallet: valorizzato');
  });

  it('scrive entrambi gli estremi degli intervalli', () => {
    expect(describeFilterChip(spesa, 'between', [10, 50]).replace(/[\u00a0\u202f]/g, ' ')).toBe(
      'Spesa media: compreso tra 10 EUR e 50 EUR',
    );
  });
});

describe('buildFilterChip', () => {
  it('calcola la frase e non espone mai l’id nel testo (RF-139)', () => {
    const chip = buildFilterChip(wallet, 'notIn', [walletPremio, walletStatus]);
    expect(chip.attribute).toBe('wallet');
    expect(chip.display).toBe('Wallet: non è uno di Premio o Status');
    expect(chip.display).not.toContain(walletPremio.id);
    expect(chip.display).not.toContain(walletStatus.id);
  });

  it('espone le entità citate per renderle navigabili', () => {
    const chip = buildFilterChip(wallet, 'in', [walletPremio, walletStatus]);
    expect(chipEntityRefs(chip).map((ref) => ref.id)).toEqual([walletPremio.id, walletStatus.id]);
  });
});
