/**
 * Pattern 5, 6, 7, 8 — ConditionRow e ConditionPicker (LG-13, LG-14, LG-28), RuleCard (LG-12),
 * FormulaInput (LG-16).
 *
 * Semantica unica dei costruttori di condizioni (RF-138): AND tra le condizioni di una regola,
 * OR tra regole, operatore logico sempre scritto tra due righe. Le frasi usano solo le label
 * degli oggetti referenziati (RF-139).
 */

import type { EntityRef, OptionSource, Unit } from './common.js';
import { isEntityRef } from './common.js';
import type { FilterOperator } from './filters.js';
import { OPERATOR_LABELS, UNARY_OPERATORS, describeValue } from './filters.js';
import type { InheritedSettingValue } from './forms.js';

export type LogicalOperator = 'AND' | 'OR';

/** Operatore che lega due condizioni della stessa regola (RF-138). */
export const CONDITION_JOIN: LogicalOperator = 'AND';

/** Operatore che lega due regole della stessa campagna (RF-138). */
export const RULE_JOIN: LogicalOperator = 'OR';

// ------------------------------------------------------------------ Condizioni

export interface ConditionType {
  key: string;
  /** Nome con contesto tra parentesi: «Punti attivi (Wallet premio)». */
  label: string;
  category: 'popular' | 'member' | 'trigger' | 'expression';
  operators: readonly FilterOperator[];
  valueType: 'text' | 'number' | 'date' | 'enum' | 'entity' | 'range' | 'expression';
  unit?: Unit;
  options?: OptionSource;
  help?: string;
}

/** Frammento di frase mostrato nella riga condizione; `ref` rende il frammento navigabile. */
export interface DisplayChip {
  text: string;
  ref?: EntityRef;
}

export interface Condition {
  id: string;
  type: string;
  operator: FilterOperator;
  value: unknown;
  /** Chip risolte: la frase «Tier non è uno di Elite» usa queste label, mai gli id (RF-139). */
  displayChips: DisplayChip[];
}

export interface ConditionRowProps {
  index: number;
  condition: Condition;
  /** Operatore mostrato tra questa riga e la successiva: sempre scritto (LG-28, RF-138). */
  joinWithNext?: LogicalOperator;
  editing: boolean;
  types: ConditionType[];
  onEdit: () => void;
  onSave: (condition: Condition) => void;
  onCancel: () => void;
  onDuplicate: () => void;
  onDelete: () => void;
}

export interface ConditionPickerProps {
  open: boolean;
  types: ConditionType[];
  /** Le «Popolari» sono calcolate dal team: le più usate negli ultimi 90 giorni. */
  onPick: (type: ConditionType) => void;
  onClose: () => void;
}

/** Errore sollevato quando una condizione cita un tipo non presente nel catalogo del modulo. */
export class UnknownConditionTypeError extends Error {
  readonly typeKey: string;

  constructor(typeKey: string) {
    super(`Tipo di condizione sconosciuto: ${typeKey}`);
    this.name = 'UnknownConditionTypeError';
    this.typeKey = typeKey;
  }
}

/** Cerca il tipo di una condizione nel catalogo, o solleva {@link UnknownConditionTypeError}. */
export function findConditionType(typeKey: string, types: readonly ConditionType[]): ConditionType {
  const type = types.find((candidate) => candidate.key === typeKey);
  if (type === undefined) throw new UnknownConditionTypeError(typeKey);
  return type;
}

/** Connettore tra i valori multipli: «Elite o Gold» negli elenchi, «10 e 50» negli intervalli. */
function valueJoin(operator: FilterOperator): string {
  return operator === 'between' || operator === 'daysAgoBetween' ? 'e' : 'o';
}

/**
 * Chip della riga condizione: nome del tipo, operatore e valori, tutti come testo leggibile.
 * Ogni valore è una chip a sé — così resta navigabile tramite `ref` senza mostrarne l'id (RF-139) —
 * e tra due valori viene inserito il connettore, perché la riga si legge come una frase (LG-13).
 */
export function buildDisplayChips(condition: Omit<Condition, 'displayChips'>, type: ConditionType): DisplayChip[] {
  const chips: DisplayChip[] = [{ text: type.label }, { text: OPERATOR_LABELS[condition.operator] }];
  if (UNARY_OPERATORS.includes(condition.operator)) return chips;

  const values = Array.isArray(condition.value) ? condition.value : [condition.value];
  values.forEach((value, index) => {
    if (index > 0) chips.push({ text: valueJoin(condition.operator) });
    const chip: DisplayChip = { text: describeValue(value, type.unit) };
    if (isEntityRef(value)) chip.ref = value;
    chips.push(chip);
  });
  return chips;
}

/** Frase completa della riga condizione: «Tier non è uno di Elite» (LG-13, RF-139). */
export function describeCondition(condition: Condition, types: readonly ConditionType[]): string {
  const type = findConditionType(condition.type, types);
  const chips = condition.displayChips.length > 0 ? condition.displayChips : buildDisplayChips(condition, type);
  return chips.map((chip) => chip.text).join(' ');
}

/** Crea una condizione con le chip già risolte: unico modo previsto per costruirne una. */
export function buildCondition(
  draft: Omit<Condition, 'displayChips'>,
  types: readonly ConditionType[],
): Condition {
  const type = findConditionType(draft.type, types);
  return { ...draft, displayChips: buildDisplayChips(draft, type) };
}

// ------------------------------------------------------------------ Formule ed effetti

export interface Formula {
  /** Espressione SpEL valida per il rules-engine (RF-84). */
  expression: string;
  /** Chip leggibile: «200» oppure «add_days_to_date(transaction.purchasedAt, 7)». */
  display: string;
}

export interface FormulaVariable {
  path: string;
  label: string;
  type: 'number' | 'string' | 'date' | 'boolean';
  example?: string;
}

export interface FormulaInputProps {
  value?: Formula;
  variables: FormulaVariable[];
  /** Validazione server-side prima del salvataggio: restituisce il messaggio d'errore o null. */
  validate: (expression: string) => Promise<string | null>;
  onChange: (formula: Formula) => void;
  examplesHref?: string;
}

export const EFFECT_KINDS = [
  'addUnits',
  'removeUnits',
  'grantReward',
  'setAttribute',
  'unsetAttribute',
  'grantBadge',
  'setTier',
  'emitEvent',
] as const;

export type EffectKind = (typeof EFFECT_KINDS)[number];

/** Etichette degli effetti, usate nel menu «Aggiungi effetto» e nel riepilogo della regola. */
export const EFFECT_LABELS: Record<EffectKind, string> = {
  addUnits: 'Accredita unità',
  removeUnits: 'Storna unità',
  grantReward: 'Assegna un premio',
  setAttribute: 'Imposta un attributo',
  unsetAttribute: 'Azzera un attributo',
  grantBadge: 'Assegna un badge',
  setTier: 'Forza un livello',
  emitEvent: 'Emetti un evento',
};

export type ExpirationMethod = 'none' | 'afterDays' | 'annualDate';

export interface ExpirationSetting {
  method: ExpirationMethod;
  formula?: Formula;
}

export interface ActivationSetting {
  delayDaysFormula?: Formula;
}

export interface Effect {
  id: string;
  kind: EffectKind;
  wallet?: EntityRef;
  reward?: EntityRef;
  attribute?: string;
  /** Formula SpEL o costante, resa da FormulaInput (LG-16). */
  formula?: Formula;
  /** Eccezione alle regole del wallet (LG-17). */
  expiration?: InheritedSettingValue<ExpirationSetting>;
  activation?: InheritedSettingValue<ActivationSetting>;
}

export interface Rule {
  id: string;
  name: string;
  description?: string;
  collapsed?: boolean;
  /** Dentro la regola le condizioni sono in AND (RF-138). */
  conditions: Condition[];
  effects: Effect[];
}

export interface RuleCardProps {
  rule: Rule;
  /** Operatore tra questa regola e la successiva: sempre OR (RF-138). */
  joinWithNext?: LogicalOperator;
  conditionTypes: ConditionType[];
  effectKinds: readonly EffectKind[];
  onChange: (rule: Rule) => void;
  onDuplicate: () => void;
  onDelete: () => void;
  dragHandleProps?: Record<string, unknown>;
}
