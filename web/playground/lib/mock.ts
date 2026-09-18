/**
 * Dati finti del playground.
 *
 * Tutto inventato e nessun dato personale: i membri sono identificatori opachi come in
 * produzione (ADR-012), i nomi sono etichette di comodo e gli importi sono plausibili per una
 * utility. Nessuna chiamata di rete: il playground funziona anche senza connessione.
 */

import type { ConditionType, Rule } from '@loyalty-hub/backoffice-design-system';
import { buildCondition, buildFilterChip } from '@loyalty-hub/backoffice-design-system';
import type { FilterAttribute } from '@loyalty-hub/backoffice-design-system';
import type { Candidate, MemberProfile, Policy } from './decision.js';

// ---------------------------------------------------------------- Liste

export interface MemberRow {
  id: string;
  membro: string;
  livello: string;
  punti: number;
  spesa: number;
  ultimaAzione: string;
  inactive?: boolean;
}

export const MEMBER_ROWS: MemberRow[] = [
  { id: 'm-01', membro: 'sub-8f2a…', livello: 'TOP', punti: 18_420, spesa: 2140.5, ultimaAzione: 'Bolletta pagata' },
  { id: 'm-02', membro: 'sub-41bd…', livello: 'PLUS', punti: 7_310, spesa: 980.0, ultimaAzione: 'Autolettura' },
  { id: 'm-03', membro: 'sub-2c90…', livello: 'PLUS', punti: 6_050, spesa: 1210.75, ultimaAzione: 'Riscatto premio' },
  { id: 'm-04', membro: 'sub-77e1…', livello: 'BASE', punti: 940, spesa: 210.3, ultimaAzione: 'Adesione' },
  { id: 'm-05', membro: 'sub-b304…', livello: 'BASE', punti: 120, spesa: 64.9, ultimaAzione: 'Check-in negozio', inactive: true },
  { id: 'm-06', membro: 'sub-19aa…', livello: 'TOP', punti: 22_780, spesa: 3310.0, ultimaAzione: 'Domiciliazione attivata' },
  { id: 'm-07', membro: 'sub-5de2…', livello: 'PLUS', punti: 4_215, spesa: 540.2, ultimaAzione: 'Questionario' },
];

const LIVELLO: FilterAttribute = { key: 'livello', label: 'Livello', type: 'entity', operators: ['eq', 'in', 'notIn'] };
const PUNTI: FilterAttribute = { key: 'punti', label: 'Punti attivi', type: 'number', unit: 'punti', operators: ['gte', 'lte', 'between'] };
const SPESA: FilterAttribute = { key: 'spesa', label: 'Spesa 365 giorni', type: 'number', unit: 'EUR', operators: ['gte', 'lte'] };

export const FILTER_ATTRIBUTES: FilterAttribute[] = [LIVELLO, PUNTI, SPESA];

export const DEFAULT_CHIPS = [
  buildFilterChip(LIVELLO, 'in', [
    { id: 'plus', label: 'Plus', kind: 'tier' as const },
    { id: 'top', label: 'Top', kind: 'tier' as const },
  ]),
  buildFilterChip(PUNTI, 'gte', 1000),
];

// ---------------------------------------------------------------- Regole

export const CONDITION_TYPES: ConditionType[] = [
  { key: 'tier', label: 'Livello', category: 'member', operators: ['in', 'notIn'], valueType: 'entity' },
  {
    key: 'punti',
    label: 'Punti attivi (Wallet premio)',
    category: 'popular',
    operators: ['gte', 'lte'],
    valueType: 'number',
    unit: 'punti',
    help: 'Saldo spendibile al momento della valutazione.',
  },
  { key: 'bolletta', label: 'Bolletta pagata entro la scadenza', category: 'trigger', operators: ['eq'], valueType: 'enum' },
  { key: 'canale', label: 'Canale dell’azione', category: 'trigger', operators: ['in'], valueType: 'enum' },
];

export const RULES: Rule[] = [
  {
    id: 'r-bolletta',
    name: 'Bolletta puntuale',
    description: 'Premia chi paga entro la scadenza, con un moltiplicatore per i livelli alti.',
    conditions: [
      buildCondition({ id: 'c-1', type: 'bolletta', operator: 'eq', value: 'sì' }, CONDITION_TYPES),
      buildCondition(
        { id: 'c-2', type: 'tier', operator: 'notIn', value: [{ id: 'base', label: 'Base', kind: 'tier' }] },
        CONDITION_TYPES,
      ),
    ],
    effects: [
      {
        id: 'e-1',
        kind: 'addUnits',
        wallet: { id: 'premio', label: 'Punti premio', kind: 'wallet' },
        formula: { expression: '#amountEur * 2', display: 'il doppio dell’importo in euro' },
      },
    ],
  },
  {
    id: 'r-autolettura',
    name: 'Autolettura da app',
    description: 'Un accredito fisso, una volta al mese, solo dall’app.',
    conditions: [
      buildCondition({ id: 'c-3', type: 'canale', operator: 'in', value: ['app'] }, CONDITION_TYPES),
      buildCondition({ id: 'c-4', type: 'punti', operator: 'lte', value: 50_000 }, CONDITION_TYPES),
    ],
    effects: [{ id: 'e-2', kind: 'addUnits', wallet: { id: 'premio', label: 'Punti premio', kind: 'wallet' } }],
  },
];

// ---------------------------------------------------------------- KPI

function serie(from: number, to: number, points = 14): Array<{ t: string; v: number }> {
  const start = Date.UTC(2027, 0, 1);
  return Array.from({ length: points }, (_, index) => {
    const share = index / Math.max(1, points - 1);
    // Andamento deterministico: il playground deve mostrare sempre lo stesso grafico.
    const wobble = Math.sin(index * 1.1) * (to - from) * 0.06;
    return {
      t: new Date(start + index * 86_400_000).toISOString(),
      v: Math.round(from + (to - from) * share + wobble),
    };
  });
}

export const KPI_DEFINITIONS = [
  { key: 'attivi', label: 'Membri attivi', definition: 'Membri con almeno un evento premiante nel periodo.', format: 'integer' as const },
  { key: 'riscatti', label: 'Riscatti', definition: 'Premi riscattati e confermati nel periodo.', format: 'integer' as const },
  { key: 'accettazione', label: 'Offerte accettate', definition: 'Offerte accettate su offerte presentate.', format: 'percent' as const },
];

export const KPI_SERIES = {
  attivi: { key: 'attivi', current: serie(820, 1240), previous: serie(700, 980), currentTotal: 1240, previousTotal: 980 },
  riscatti: { key: 'riscatti', current: serie(40, 96), previous: serie(52, 88), currentTotal: 96, previousTotal: 88 },
  accettazione: {
    key: 'accettazione',
    current: serie(18, 27).map((point) => ({ ...point, v: point.v / 100 })),
    previous: serie(22, 24).map((point) => ({ ...point, v: point.v / 100 })),
    currentTotal: 0.27,
    previousTotal: 0.23,
  },
};

// ---------------------------------------------------------------- Decisioni

export const PROFILES: MemberProfile[] = [
  {
    id: 'sub-8f2a',
    label: 'Cliente Top, consensi completi',
    tier: 'TOP',
    riskLevel: 'LOW',
    consents: { marketing: true, profilazione: true },
    contacts7d: { app: 1, push: 0, email: 0, sms: 0 },
    preferredChannel: 'app',
    propensity: 0.62,
    churnRisk: 0.12,
  },
  {
    id: 'sub-41bd',
    label: 'Cliente Plus senza consenso marketing',
    tier: 'PLUS',
    riskLevel: 'LOW',
    consents: { marketing: false, profilazione: true },
    contacts7d: { app: 0, push: 0, email: 0, sms: 0 },
    preferredChannel: 'email',
    propensity: 0.44,
    churnRisk: 0.61,
  },
  {
    id: 'sub-77e1',
    label: 'Cliente Base molto contattato',
    tier: 'BASE',
    riskLevel: 'MEDIUM',
    consents: { marketing: true, profilazione: false },
    contacts7d: { app: 7, push: 3, email: 2, sms: 1 },
    preferredChannel: 'push',
    propensity: 0.3,
    churnRisk: 0.35,
  },
  {
    id: 'sub-b304',
    label: 'Cliente sotto verifica antifrode',
    tier: 'PLUS',
    riskLevel: 'CRITICAL',
    consents: { marketing: true, profilazione: true },
    contacts7d: { app: 0, push: 0, email: 0, sms: 0 },
    preferredChannel: 'app',
    propensity: 0.5,
    churnRisk: 0.2,
  },
];

export const CANDIDATES: Candidate[] = [
  {
    id: 'cand-punti',
    action: 'AWARD_POINTS',
    reference: 'Bolletta puntuale — 168 punti',
    units: 168,
    value: 0,
    cost: 0,
    channels: [],
    maxRiskLevel: 'CRITICAL',
    enabled: true,
    priority: 100,
    source: 'campagna',
  },
  {
    id: 'cand-premio',
    action: 'ISSUE_REWARD',
    reference: 'Buono manutenzione caldaia',
    units: 2_000,
    value: 12,
    cost: 6,
    channels: ['app', 'email'],
    requiredConsent: 'marketing',
    maxRiskLevel: 'MEDIUM',
    enabled: true,
    priority: 80,
    source: 'catalogo',
  },
  {
    id: 'cand-offerta',
    action: 'SHOW_OFFER',
    reference: 'Sconto 10% sul negozio',
    units: 0,
    value: 6,
    cost: 0.5,
    channels: ['app', 'web'],
    requiredConsent: 'marketing',
    // Unica azione discrezionale ammessa anche a rischio critico: serve a far vedere la differenza fra i
    // due codici. Senza, ogni candidato cade prima su RISK_LEVEL e RISK_BLOCK non si vede mai — cioè la
    // pagina prometterebbe un codice motivo irraggiungibile.
    maxRiskLevel: 'CRITICAL',
    enabled: true,
    priority: 60,
    source: 'offerte',
  },
  {
    id: 'cand-messaggio',
    action: 'SEND_MESSAGE',
    reference: 'Ricorda l’autolettura',
    units: 0,
    value: 3,
    cost: 0.2,
    channels: ['push', 'email', 'app'],
    requiredConsent: 'marketing',
    maxRiskLevel: 'HIGH',
    enabled: true,
    priority: 40,
    source: 'campagna',
  },
  {
    id: 'cand-feedback',
    action: 'ASK_FOR_FEEDBACK',
    reference: 'Come è andata in negozio?',
    units: 0,
    value: 2,
    cost: 0.1,
    channels: ['app', 'email'],
    requiredConsent: 'marketing',
    maxRiskLevel: 'HIGH',
    enabled: true,
    priority: 20,
    source: 'offerte',
  },
];

export const POLICY: Policy = {
  id: 'default',
  version: '1',
  maxArbitratedPerEvent: 1,
  dailyUnitsBudget: 50_000,
  contactCap7dByChannel: { app: 7, push: 3, email: 2, sms: 1 },
  quietHoursFrom: 21,
  quietHoursTo: 8,
  blockRiskLevel: 'CRITICAL',
  tierBoost: { BASE: 1, PLUS: 1.1, TOP: 1.25 },
  channelOrder: ['app', 'push', 'email', 'web', 'sms'],
};
