import type { Comparator, CriteriaGroup, CriteriaLeaf, CriteriaNode, CriteriaValue } from "./types";

// Costruttore dei criteri dei segmenti dinamici (docs/08 §BO-04, docs/03 §10): campi del membro, comparatori ammessi
// per tipo di campo, conversione righe ⇄ JSON (stesso formato delle condizioni delle campagne, docs/03 §3.3) e frase
// in italiano. Logica pura, testata a parte.

export type FieldKind = "enum" | "number" | "text" | "labels" | "action" | "attribute";

export interface FieldDef {
  /** Chiave nel builder: il campo completo, oppure `actions` / `attributes` (richiedono un parametro). */
  id: string;
  label: string;
  kind: FieldKind;
  options?: string[];
  hint?: string;
}

export const TIERS = ["BASE", "SILVER", "GOLD", "PLATINUM"];
export const MEMBER_STATUSES = ["ACTIVE", "INACTIVE", "BLOCKED"];

/** Campi del membro offerti dal costruttore (docs/08 §BO-04: tier, stato, anzianità, attributi, etichette, saldo…). */
export const SEGMENT_FIELDS: FieldDef[] = [
  { id: "member.tier", label: "Livello", kind: "enum", options: TIERS },
  { id: "member.status", label: "Stato", kind: "enum", options: MEMBER_STATUSES },
  { id: "member.labels", label: "Etichette", kind: "labels", hint: "es. ebill, directdebit" },
  { id: "member.city", label: "Città", kind: "text" },
  { id: "member.registeredDaysAgo", label: "Anzianità (giorni dall'iscrizione)", kind: "number" },
  { id: "member.age", label: "Età (anni)", kind: "number" },
  { id: "member.balance.PTS", label: "Saldo PTS", kind: "number" },
  { id: "member.lifetimeEarned.PTS", label: "PTS guadagnati in totale", kind: "number" },
  { id: "member.lastActivityDaysAgo", label: "Giorni dall'ultima attività", kind: "number" },
  { id: "member.purchases.amount90d", label: "Spesa negli ultimi 90 giorni (€)", kind: "number" },
  { id: "actions", label: "Azioni di un tipo negli ultimi 30 giorni", kind: "action", hint: "es. purchase.completed" },
  { id: "attributes", label: "Attributo personalizzato", kind: "attribute", hint: "chiave, es. householdSize" },
];

const BY_ID = new Map(SEGMENT_FIELDS.map((f) => [f.id, f]));

export const COMPARATOR_LABEL: Record<Comparator, string> = {
  eq: "è",
  neq: "non è",
  gt: "maggiore di",
  gte: "almeno",
  lt: "minore di",
  lte: "al massimo",
  in: "è uno tra",
  nin: "non è tra",
  contains: "contiene",
  ncontains: "non contiene",
  exists: "è valorizzato",
  nexists: "non è valorizzato",
  between: "tra",
  startsWith: "inizia con",
};

const NUMBER_CMP: Comparator[] = ["gte", "gt", "lte", "lt", "eq", "neq", "between"];

/** Comparatori ammessi per tipo di campo. */
export const COMPARATORS: Record<FieldKind, Comparator[]> = {
  enum: ["eq", "neq", "in", "nin"],
  number: [...NUMBER_CMP, "exists", "nexists"],
  text: ["eq", "neq", "startsWith", "in", "exists", "nexists"],
  labels: ["contains", "ncontains", "exists", "nexists"],
  action: NUMBER_CMP,
  attribute: ["eq", "neq", "gt", "gte", "lt", "lte", "in", "exists", "nexists"],
};

/** Una riga del costruttore: campo (+ parametro per azioni/attributi), comparatore, valore come testo. */
export interface CriteriaRow {
  field: string;
  param: string;
  cmp: Comparator;
  value: string;
}

export interface BuilderState {
  op: "all" | "any";
  rows: CriteriaRow[];
}

export function fieldDef(id: string): FieldDef | undefined {
  return BY_ID.get(id);
}

export function newRow(field = "member.tier"): CriteriaRow {
  const def = fieldDef(field);
  return { field, param: "", cmp: COMPARATORS[def?.kind ?? "enum"][0], value: "" };
}

const needsValue = (cmp: Comparator) => cmp !== "exists" && cmp !== "nexists";

/** Campo completo della foglia: `member.actions.<type>.count30d`, `member.attributes.<k>` o il campo stesso. */
export function fieldPath(row: Pick<CriteriaRow, "field" | "param">): string {
  if (row.field === "actions") return `member.actions.${row.param.trim()}.count30d`;
  if (row.field === "attributes") return `member.attributes.${row.param.trim()}`;
  return row.field;
}

/** Inverso di {@link fieldPath}: il prefisso `member.` è facoltativo (docs/03 §10). */
export function parseFieldPath(path: string): { field: string; param: string } | null {
  const p = path.startsWith("member.") ? path : `member.${path}`;
  const action = /^member\.actions\.(.+)\.count30d$/.exec(p);
  if (action) return { field: "actions", param: action[1] };
  if (p.startsWith("member.attributes.") && p.length > "member.attributes.".length) {
    return { field: "attributes", param: p.slice("member.attributes.".length) };
  }
  return BY_ID.has(p) ? { field: p, param: "" } : null;
}

function isNumericKind(kind: FieldKind): boolean {
  return kind === "number" || kind === "action";
}

function toNumber(s: string): number | null {
  const t = s.trim().replace(",", ".");
  if (t === "") return null;
  const n = Number(t);
  return Number.isFinite(n) ? n : null;
}

function splitList(s: string): string[] {
  return s
    .split(/[,;\n]/)
    .map((x) => x.trim())
    .filter(Boolean);
}

/** Valore JSON della riga, secondo il tipo di campo e il comparatore. */
export function rowValue(row: CriteriaRow): CriteriaValue {
  const kind = fieldDef(row.field)?.kind ?? "text";
  if (!needsValue(row.cmp)) return undefined;
  if (row.cmp === "in" || row.cmp === "nin") {
    const items = splitList(row.value);
    return isNumericKind(kind) ? items.map((x) => toNumber(x) ?? x) : items;
  }
  if (row.cmp === "between") {
    return splitList(row.value).map((x) => toNumber(x) ?? x);
  }
  if (isNumericKind(kind) || (kind === "attribute" && ["gt", "gte", "lt", "lte"].includes(row.cmp))) {
    return toNumber(row.value) ?? row.value.trim();
  }
  if (kind === "attribute") {
    const t = row.value.trim();
    if (t === "true" || t === "false") return t === "true";
    return toNumber(t) ?? t;
  }
  return row.value.trim();
}

/** Righe → criteri JSON (`{op, rules}`), come li salva il servizio. */
export function toCriteria(state: BuilderState): CriteriaGroup {
  return {
    op: state.op,
    rules: state.rows.map((r) => {
      const leaf: CriteriaLeaf = { field: fieldPath(r), cmp: r.cmp };
      const v = rowValue(r);
      if (v !== undefined) leaf.value = v;
      return leaf;
    }),
  };
}

function valueText(v: CriteriaValue): string {
  if (v === undefined || v === null) return "";
  if (Array.isArray(v)) return v.join(", ");
  return String(v);
}

const isGroup = (n: CriteriaNode): n is CriteriaGroup => "rules" in n || "op" in n;

/**
 * Criteri JSON → righe del costruttore. `null` se non rappresentabili a righe (gruppi annidati, `not`, campi o
 * comparatori fuori catalogo): l'editor passa allora alla vista JSON.
 */
export function fromCriteria(node: CriteriaNode | null | undefined): BuilderState | null {
  if (!node) return { op: "all", rows: [] };
  const group: CriteriaGroup = isGroup(node) ? node : { op: "all", rules: [node] };
  if (group.op !== "all" && group.op !== "any") return null;
  const rows: CriteriaRow[] = [];
  for (const r of group.rules ?? []) {
    if (isGroup(r)) return null;
    const parsed = parseFieldPath(r.field);
    if (!parsed) return null;
    const kind = fieldDef(parsed.field)?.kind ?? "text";
    if (!COMPARATORS[kind].includes(r.cmp)) return null;
    rows.push({ field: parsed.field, param: parsed.param, cmp: r.cmp, value: valueText(r.value) });
  }
  return { op: group.op, rows };
}

/** Problemi delle righe (stesse regole del servizio, per avvisare prima di salvare): indice → messaggio. */
export function validateRows(state: BuilderState): Record<number, string> {
  const out: Record<number, string> = {};
  if (state.rows.length === 0) out[-1] = "Aggiungi almeno una condizione";
  state.rows.forEach((row, i) => {
    const def = fieldDef(row.field);
    if (!def) {
      out[i] = "Campo non disponibile";
      return;
    }
    if ((def.kind === "action" || def.kind === "attribute") && !row.param.trim()) {
      out[i] = def.kind === "action" ? "Indica il tipo di azione" : "Indica la chiave dell'attributo";
      return;
    }
    if (!COMPARATORS[def.kind].includes(row.cmp)) {
      out[i] = "Comparatore non ammesso per questo campo";
      return;
    }
    if (!needsValue(row.cmp)) return;
    if (!row.value.trim()) {
      out[i] = "Valore mancante";
      return;
    }
    const v = rowValue(row);
    if (row.cmp === "between" && !(Array.isArray(v) && v.length === 2 && v.every((x) => typeof x === "number"))) {
      out[i] = "Servono due numeri: minimo, massimo";
    } else if (isNumericKind(def.kind) && row.cmp !== "in" && row.cmp !== "nin" && row.cmp !== "between" && typeof v !== "number") {
      out[i] = "Serve un numero";
    } else if (def.kind === "enum" && def.options) {
      const values = Array.isArray(v) ? v : [v];
      const bad = values.find((x) => !def.options!.includes(String(x)));
      if (bad !== undefined) out[i] = `Valore non valido: ${bad}`;
    }
  });
  return out;
}

function leafSentence(leaf: CriteriaLeaf): string {
  const parsed = parseFieldPath(leaf.field);
  const def = parsed ? fieldDef(parsed.field) : undefined;
  const name =
    parsed?.field === "actions"
      ? `azioni «${parsed.param}» in 30 giorni`
      : parsed?.field === "attributes"
        ? `attributo «${parsed.param}»`
        : (def?.label ?? leaf.field).toLowerCase();
  const verb = COMPARATOR_LABEL[leaf.cmp] ?? leaf.cmp;
  if (!needsValue(leaf.cmp)) return `${name} ${verb}`;
  const v = leaf.value;
  const shown =
    leaf.cmp === "between" && Array.isArray(v) && v.length === 2
      ? `${v[0]} e ${v[1]}`
      : Array.isArray(v)
        ? v.join(", ")
        : def?.kind === "labels" || def?.kind === "text"
          ? `«${String(v)}»`
          : String(v);
  return `${name} ${verb} ${shown}`;
}

/** Criteri in una frase italiana (elenco BO-04, riepiloghi). */
export function describeCriteria(node: CriteriaNode | null | undefined): string {
  if (!node) return "—";
  if (!isGroup(node)) return leafSentence(node);
  const parts = (node.rules ?? []).map((r) => (isGroup(r) ? `(${describeCriteria(r)})` : leafSentence(r)));
  if (parts.length === 0) return "—";
  if (node.op === "not") return `non (${parts.join(" e ")})`;
  return parts.join(node.op === "any" ? " oppure " : " e ");
}

/** Testo JSON dei criteri → nodo, o messaggio d'errore di sintassi. */
export function parseCriteriaJson(text: string): { criteria: CriteriaNode | null; error: string | null } {
  if (!text.trim()) return { criteria: null, error: "Criteri vuoti" };
  try {
    const parsed = JSON.parse(text) as unknown;
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
      return { criteria: null, error: "Serve un oggetto {op, rules} o {field, cmp, value}" };
    }
    return { criteria: parsed as CriteriaNode, error: null };
  } catch (e) {
    return { criteria: null, error: `JSON non valido: ${(e as Error).message}` };
  }
}
