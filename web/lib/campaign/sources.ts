// Fonti ammesse di una campagna (docs/08 §BO-06 «2 Quando», default tutte; Q-208 decisa). Non sono un campo della
// campagna: sono una regola del motore già prevista da docs/03 §3.3, `context.source` fra gli URN delle fonti
// (`urn:loyaltyhub:source:<codice>`, docs/05 §2), nel gruppo TUTTE alla radice delle condizioni. L'editor la mostra
// nella sezione «Quando» e la toglie dall'albero di «Se»; la frase generata la legge come «da ecommerce o app».
// Funzioni pure, senza chiamate: nessun campo nuovo nell'API di campaign, nessun contratto evento toccato.

import type { ConditionNode } from "./describe";

export const SOURCE_PREFIX = "urn:loyaltyhub:source:";
export const SOURCE_FIELD = "context.source";

/** Codice fonte → URN come lo vede il motore (`ecommerce` → `urn:loyaltyhub:source:ecommerce`). */
export function sourceUrn(code: string): string {
  return code.startsWith(SOURCE_PREFIX) ? code : SOURCE_PREFIX + code;
}

/** URN → codice fonte (un valore senza prefisso resta com'è). */
export function sourceCode(urn: string): string {
  return urn.startsWith(SOURCE_PREFIX) ? urn.slice(SOURCE_PREFIX.length) : urn;
}

function isSourceRule(n: ConditionNode): n is { field: string; cmp: string; value?: unknown } {
  if (!("field" in n) || n.field !== SOURCE_FIELD) return false;
  if (n.cmp === "in") return Array.isArray(n.value) && n.value.length > 0 && n.value.every((v) => typeof v === "string");
  return n.cmp === "eq" && typeof n.value === "string";
}

function codesOf(rule: { cmp: string; value?: unknown }): string[] {
  const values = rule.cmp === "in" ? (rule.value as string[]) : [rule.value as string];
  return values.map(sourceCode);
}

/**
 * Separa le fonti ammesse dal resto delle condizioni. Si riconosce solo la regola `context.source in [...]` (o `eq`)
 * figlia diretta del gruppo TUTTE radice, l'unica posizione in cui equivale a «l'azione arriva da queste fonti»;
 * altrove (dentro ALMENO UNA o NESSUNA, annidata) resta una condizione qualsiasi e rimane in `rest`.
 */
export function splitAllowedSources(conditions?: ConditionNode | null): { sources: string[]; rest: ConditionNode | null } {
  if (!conditions) return { sources: [], rest: null };
  if (isSourceRule(conditions)) return { sources: codesOf(conditions), rest: null };
  if (!("op" in conditions) || conditions.op !== "all") return { sources: [], rest: conditions };
  const idx = conditions.rules.findIndex(isSourceRule);
  if (idx < 0) return { sources: [], rest: conditions };
  const rule = conditions.rules[idx] as { cmp: string; value?: unknown };
  const rules = conditions.rules.filter((_, i) => i !== idx);
  return { sources: codesOf(rule), rest: rules.length === 0 ? null : { op: "all", rules } };
}

/**
 * Rimette le fonti ammesse nelle condizioni da salvare: nessuna fonte = tutte (nessuna regola). Una regola sulle fonti
 * già presente alla radice viene sostituita; una radice diversa da TUTTE viene avvolta in un gruppo TUTTE.
 */
export function withAllowedSources(conditions: ConditionNode | null | undefined, sources: string[]): ConditionNode | null {
  const { rest } = splitAllowedSources(conditions);
  const codes = [...new Set(sources.map(sourceCode).filter((s) => s.trim() !== ""))];
  if (codes.length === 0) return rest;
  const rule: ConditionNode = { field: SOURCE_FIELD, cmp: "in", value: codes.map(sourceUrn) };
  if (!rest) return { op: "all", rules: [rule] };
  if ("op" in rest && rest.op === "all") return { op: "all", rules: [rule, ...rest.rules] };
  return { op: "all", rules: [rule, rest] };
}
