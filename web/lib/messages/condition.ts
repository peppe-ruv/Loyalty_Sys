import type { ConditionGroup, ConditionLeaf, ConditionNode } from "./types";

// Condizione opzionale di una regola di notifica (BO-19 `rules`): stesso formato delle condizioni di campagna
// (docs/03 §3.3) sul solo spazio `data.*` (docs/servizi/engagement-service.md §2). Qui: il modello del costruttore
// semplice (righe in "tutte"/"almeno una"), la conversione da/verso JSON e la stessa validazione di forma del backend
// (DataCondition.problems), così gli errori compaiono sul campo prima del salvataggio.

export const COMPARATORS: { value: string; label: string; needsValue: boolean }[] = [
  { value: "eq", label: "è uguale a", needsValue: true },
  { value: "neq", label: "è diverso da", needsValue: true },
  { value: "gt", label: "è maggiore di", needsValue: true },
  { value: "gte", label: "è almeno", needsValue: true },
  { value: "lt", label: "è minore di", needsValue: true },
  { value: "lte", label: "è al massimo", needsValue: true },
  { value: "in", label: "è uno tra", needsValue: true },
  { value: "nin", label: "non è tra", needsValue: true },
  { value: "contains", label: "contiene", needsValue: true },
  { value: "ncontains", label: "non contiene", needsValue: true },
  { value: "startsWith", label: "inizia con", needsValue: true },
  { value: "between", label: "è compreso tra", needsValue: true },
  { value: "exists", label: "è presente", needsValue: false },
  { value: "nexists", label: "è assente", needsValue: false },
];

const CMP = new Set(COMPARATORS.map((c) => c.value));
const OPS = new Set(["all", "any", "not"]);

export function needsValue(cmp: string): boolean {
  return cmp !== "exists" && cmp !== "nexists";
}

/** Riga del costruttore: il valore è testo, interpretato come JSON se possibile (numeri, liste, booleani). */
export interface BuilderRow {
  field: string;
  cmp: string;
  value: string;
}

export interface BuilderModel {
  op: "all" | "any";
  rows: BuilderRow[];
}

function isGroup(n: ConditionNode): n is ConditionGroup {
  return typeof n === "object" && n !== null && "op" in n;
}

/** Testo del campo valore → valore JSON: `100` → 100, `["A","B"]` → lista, `true` → booleano, il resto stringa. */
export function parseValue(text: string): unknown {
  const t = text.trim();
  if (t === "") return "";
  try {
    return JSON.parse(t);
  } catch {
    return t;
  }
}

/** Valore JSON → testo del campo (inverso di {@link parseValue} per i valori semplici). */
export function formatValue(value: unknown): string {
  if (value === undefined || value === null) return "";
  return typeof value === "string" ? value : JSON.stringify(value);
}

/**
 * Condizione → modello del costruttore, se esprimibile: nessuna condizione, una foglia, oppure un gruppo `all`/`any`
 * di sole foglie. Altrimenti (gruppi annidati, `not`) → null: l'editor passa alla vista JSON.
 */
export function toBuilder(condition: ConditionNode | null | undefined): BuilderModel | null {
  if (condition == null || (typeof condition === "object" && Object.keys(condition).length === 0)) {
    return { op: "all", rows: [] };
  }
  const leafRow = (l: ConditionLeaf): BuilderRow => ({ field: l.field ?? "", cmp: l.cmp ?? "eq", value: formatValue(l.value) });
  if (!isGroup(condition)) return { op: "all", rows: [leafRow(condition)] };
  if (condition.op === "not" || !Array.isArray(condition.rules) || condition.rules.some(isGroup)) return null;
  return { op: condition.op, rows: (condition.rules as ConditionLeaf[]).map(leafRow) };
}

/** Modello del costruttore → condizione da salvare (`null` senza righe; una riga sola resta una foglia). */
export function fromBuilder(model: BuilderModel): ConditionNode | null {
  const leaves: ConditionLeaf[] = model.rows.map((r) =>
    needsValue(r.cmp) ? { field: r.field.trim(), cmp: r.cmp, value: parseValue(r.value) } : { field: r.field.trim(), cmp: r.cmp },
  );
  if (leaves.length === 0) return null;
  if (leaves.length === 1 && model.op === "all") return leaves[0];
  return { op: model.op, rules: leaves };
}

/** Testo JSON → condizione; testo vuoto = nessuna condizione. Errore di sintassi → messaggio per il campo. */
export function parseConditionJson(text: string): { condition: ConditionNode | null; error: string | null } {
  if (text.trim() === "") return { condition: null, error: null };
  try {
    const parsed = JSON.parse(text) as unknown;
    if (parsed === null) return { condition: null, error: null };
    if (typeof parsed !== "object" || Array.isArray(parsed)) {
      return { condition: null, error: "La condizione è un oggetto JSON, es. {\"field\":\"data.currency\",\"cmp\":\"eq\",\"value\":\"PTS\"}" };
    }
    return { condition: parsed as ConditionNode, error: null };
  } catch (e) {
    return { condition: null, error: `JSON non valido: ${(e as Error).message}` };
  }
}

/** Problemi di forma (stesse regole di DataCondition.problems in engagement). Vuoto = valida. */
export function conditionProblems(node: unknown): string[] {
  const out: string[] = [];
  check(node, out);
  return out;
}

function check(node: unknown, out: string[]): void {
  if (node == null) return;
  if (typeof node !== "object" || Array.isArray(node)) {
    out.push("ogni nodo della condizione è un oggetto");
    return;
  }
  const n = node as Record<string, unknown>;
  if (Object.keys(n).length === 0) return;
  if ("op" in n) {
    const op = String(n.op ?? "");
    if (!OPS.has(op)) out.push(`operatore di gruppo "${op}" (ammessi: all, any, not)`);
    if (!Array.isArray(n.rules)) {
      out.push(`il gruppo "${op}" richiede l'elenco rules`);
      return;
    }
    n.rules.forEach((r) => check(r, out));
    return;
  }
  const field = typeof n.field === "string" ? n.field : "";
  const cmp = typeof n.cmp === "string" ? n.cmp : "eq";
  if (!field.startsWith("data.") || field.length <= "data.".length) {
    out.push(`campo "${field}": le regole di notifica leggono solo data.*`);
  }
  if (!CMP.has(cmp)) out.push(`comparatore "${cmp}" sconosciuto`);
  if (needsValue(cmp) && !("value" in n)) out.push(`la foglia su "${field}" richiede value`);
}

/** Frase leggibile per l'elenco delle regole: `data.currency è uguale a PTS`. */
export function describeCondition(node: ConditionNode | null | undefined): string {
  if (node == null || (typeof node === "object" && Object.keys(node).length === 0)) return "sempre";
  if (isGroup(node)) {
    const parts = (node.rules ?? []).map((r) => describeCondition(r));
    if (node.op === "not") return `non (${parts.join(" e ")})`;
    return parts.join(node.op === "any" ? " oppure " : " e ");
  }
  const label = COMPARATORS.find((c) => c.value === node.cmp)?.label ?? node.cmp;
  return needsValue(node.cmp) ? `${node.field} ${label} ${formatValue(node.value)}` : `${node.field} ${label}`;
}
