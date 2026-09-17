/**
 * Pattern 2 — FilterBuilder (LG-05) e vocabolario degli operatori condiviso con i costruttori
 * di condizioni (LG-13, RF-138). Le chip mostrano una frase leggibile, mai un id (RF-139).
 */

import type { EntityRef, OptionSource, Unit } from './common.js';
import { isEntityRef } from './common.js';
import { formatValue } from './format.js';

export const FILTER_OPERATORS = [
  'eq',
  'neq',
  'in',
  'notIn',
  'gt',
  'gte',
  'lt',
  'lte',
  'between',
  'contains',
  'startsWith',
  'exists',
  'notExists',
  'daysAgoBetween',
] as const;

export type FilterOperator = (typeof FILTER_OPERATORS)[number];

/** Frase dell'operatore usata nelle chip e nelle righe condizione: «Wallet: uguale a Premio». */
export const OPERATOR_LABELS: Record<FilterOperator, string> = {
  eq: 'uguale a',
  neq: 'diverso da',
  in: 'è uno di',
  notIn: 'non è uno di',
  gt: 'maggiore di',
  gte: 'maggiore o uguale a',
  lt: 'minore di',
  lte: 'minore o uguale a',
  between: 'compreso tra',
  contains: 'contiene',
  startsWith: 'inizia per',
  exists: 'valorizzato',
  notExists: 'non valorizzato',
  daysAgoBetween: 'negli ultimi giorni tra',
};

/** Operatori che non hanno un valore a destra: la chip finisce con l'operatore. */
export const UNARY_OPERATORS: readonly FilterOperator[] = ['exists', 'notExists'];

export interface FilterAttribute {
  key: string;
  label: string;
  type: 'text' | 'number' | 'date' | 'enum' | 'entity';
  unit?: Unit;
  operators: readonly FilterOperator[];
  /** Per type enum/entity: opzioni con ricerca. */
  options?: OptionSource;
}

export interface FilterChip {
  attribute: string;
  operator: FilterOperator;
  value: unknown;
  /** Frase mostrata nella chip: «Wallet: uguale a Premio». Vedi {@link describeFilterChip}. */
  display: string;
}

export interface FilterBuilderProps {
  attributes: FilterAttribute[];
  chips: FilterChip[];
  /** Nessun bottone «Applica»: ogni modifica ricalcola subito. */
  onChange: (chips: FilterChip[]) => void;
}

/**
 * Rende leggibile un valore di filtro o di condizione (RF-139):
 * gli {@link EntityRef} diventano la loro label, gli elenchi si uniscono con «o»,
 * i numeri seguono il formato italiano con l'eventuale unità.
 */
export function describeValue(value: unknown, unit?: Unit): string {
  if (value === null || value === undefined) return '—';
  if (isEntityRef(value)) return value.label;
  if (Array.isArray(value)) {
    const parts = value.map((item) => describeValue(item, unit));
    return parts.length <= 1 ? (parts[0] ?? '—') : `${parts.slice(0, -1).join(', ')} o ${parts[parts.length - 1] ?? ''}`;
  }
  if (typeof value === 'number') return formatValue(value, unit === undefined ? {} : { unit });
  if (typeof value === 'boolean') return value ? 'sì' : 'no';
  if (typeof value === 'string') return value;
  if (value instanceof Date) return value.toISOString().slice(0, 10);
  // Oggetto senza label: mostrarne la serializzazione violerebbe RF-139.
  return '—';
}

/** Frase della chip di filtro: «Wallet: uguale a Premio» (LG-05). */
export function describeFilterChip(
  attribute: Pick<FilterAttribute, 'label' | 'unit'>,
  operator: FilterOperator,
  value: unknown,
): string {
  const head = `${attribute.label}: ${OPERATOR_LABELS[operator]}`;
  if (UNARY_OPERATORS.includes(operator)) return head;
  if (operator === 'between' || operator === 'daysAgoBetween') {
    const range: unknown[] = Array.isArray(value) ? (value as unknown[]) : [];
    return `${head} ${describeValue(range[0], attribute.unit)} e ${describeValue(range[1], attribute.unit)}`;
  }
  return `${head} ${describeValue(value, attribute.unit)}`;
}

/** Costruisce la chip completa, con la frase già calcolata: unico modo previsto per crearne una. */
export function buildFilterChip(
  attribute: FilterAttribute,
  operator: FilterOperator,
  value: unknown,
): FilterChip {
  return {
    attribute: attribute.key,
    operator,
    value,
    display: describeFilterChip(attribute, operator, value),
  };
}

/** Riferimenti alle entità citate da una chip, per evidenziarle o renderle navigabili. */
export function chipEntityRefs(chip: Pick<FilterChip, 'value'>): EntityRef[] {
  const values = Array.isArray(chip.value) ? chip.value : [chip.value];
  return values.filter(isEntityRef);
}
