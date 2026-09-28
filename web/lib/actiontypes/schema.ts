import type { ActionField } from "./types";
import { isValidFieldName, looksPersonal } from "./fields";

// Editor "a righe" dei tipi custom di BO-09 (docs/08 §BO-09: "editor campi a righe → genera il JSON Schema").
// Puro e testato: righe ↔ JSON Schema 2020-12 e `sampleData` d'esempio coerente con lo schema.

export type FieldKind = "string" | "number" | "integer" | "boolean" | "date" | "enum";

export interface FieldRow {
  name: string;
  kind: FieldKind;
  required: boolean;
  /** Solo per `enum`: valori separati da virgola. */
  options: string;
  /** Etichetta in italiano, salvata come `title` del campo nello schema (Q-434). */
  label?: string;
  /** Spiegazione del campo, salvata come `description` nello schema (Q-434). */
  description?: string;
}

export const KIND_LABEL: Record<FieldKind, string> = {
  string: "Testo",
  number: "Numero",
  integer: "Intero",
  boolean: "Sì / no",
  date: "Data",
  enum: "Elenco di valori",
};

export function emptyRow(): FieldRow {
  return { name: "", kind: "string", required: false, options: "", label: "", description: "" };
}

export function optionsOf(row: FieldRow): string[] {
  return row.options
    .split(",")
    .map((o) => o.trim())
    .filter(Boolean);
}

export const ROW_ERROR = {
  name: "Il nome tecnico può contenere solo lettere e numeri, senza spazi, e deve iniziare con una minuscola (es. codiceNegozio).",
  duplicate: "C'è già un'informazione con questo nome tecnico.",
  options: "Indica almeno un valore ammesso, separando i valori con una virgola.",
  personal: "I dati personali non viaggiano nelle azioni: il membro è già identificato dall'azione.",
} as const;

/** Riga ancora da compilare: nome tecnico, etichetta e spiegazione vuoti. */
export function isBlankRow(row: FieldRow): boolean {
  return !row.name && !(row.label ?? "").trim() && !(row.description ?? "").trim();
}

/** Errori per riga (indice → messaggio). Vuoto = righe valide. */
export function rowErrors(rows: FieldRow[]): Record<number, string> {
  const errors: Record<number, string> = {};
  const seen = new Set<string>();
  rows.forEach((r, i) => {
    // SPEC-GAP: Q-435 — nessun dato personale nei campi di un'azione (regola 10, ADR-032).
    if (looksPersonal(r.name) || looksPersonal(r.label ?? "")) errors[i] = ROW_ERROR.personal;
    else if (!isValidFieldName(r.name)) errors[i] = ROW_ERROR.name;
    else if (seen.has(r.name)) errors[i] = ROW_ERROR.duplicate;
    else if (r.kind === "enum" && optionsOf(r).length === 0) errors[i] = ROW_ERROR.options;
    seen.add(r.name);
  });
  return errors;
}

export function rowsToSchema(rows: FieldRow[]): Record<string, unknown> {
  const properties: Record<string, unknown> = {};
  for (const r of rows) {
    const base =
      r.kind === "date"
        ? { type: "string", format: "date" }
        : r.kind === "enum"
          ? { type: "string", enum: optionsOf(r) }
          : { type: r.kind };
    // `title` e `description` sono annotazioni JSON Schema che il servizio accetta già; `x-lh-pii: false` come nei
    // seed (regola 10, ADR-032; SPEC-GAP: Q-435).
    properties[r.name] = {
      ...base,
      ...(r.label?.trim() ? { title: r.label.trim() } : {}),
      ...(r.description?.trim() ? { description: r.description.trim() } : {}),
      "x-lh-pii": false,
    };
  }
  const required = rows.filter((r) => r.required).map((r) => r.name);
  return {
    $schema: "https://json-schema.org/draft/2020-12/schema",
    type: "object",
    ...(required.length ? { required } : {}),
    properties,
  };
}

/**
 * Righe da uno schema piatto (i tipi custom creati qui). Campi annidati o con parole chiave che l'editor non sa
 * riscrivere (`minimum`, `pattern`, `x-lh-pii: true`…) → `null`: lo schema si conserva com'è, senza perdite.
 */
export function schemaToRows(schema: Record<string, unknown> | null): FieldRow[] | null {
  const { rows, skipped, lossy } = flatRows(schema);
  return rows == null || skipped.length > 0 || lossy.length > 0 ? null : rows;
}

/** Parole chiave di un campo che le righe dell'editor riscrivono tali e quali. */
const ROW_KEYWORDS = new Set(["type", "format", "enum", "title", "description", "x-lh-pii"]);
/** Parole chiave dello schema radice che `rowsToSchema` riscrive. */
const ROOT_KEYWORDS = new Set(["$schema", "type", "required", "properties"]);

/** Il campo ha parole chiave che una riga perderebbe, oppure dichiara un dato personale. */
function isLossy(p: Record<string, unknown>): boolean {
  if (Object.keys(p).some((k) => !ROW_KEYWORDS.has(k))) return true;
  if ("x-lh-pii" in p && p["x-lh-pii"] !== false) return true;
  return "format" in p && p.format !== "date";
}

/**
 * Righe dei campi piatti di uno schema, campi saltati (oggetti, elenchi) e campi che una riga riscriverebbe perdendo
 * qualcosa (`lossy`). *Duplica da un'azione esistente* copia ciò che l'editor sa rappresentare (per esempio
 * `purchase.completed` senza `items` e senza `minimum`); la modifica di un'azione esistente invece no.
 */
export function flatRows(schema: Record<string, unknown> | null): { rows: FieldRow[] | null; skipped: string[]; lossy: string[] } {
  if (!schema || schema.type !== "object") return { rows: null, skipped: [], lossy: [] };
  const props = (schema.properties ?? {}) as Record<string, Record<string, unknown>>;
  const required = new Set((schema.required as string[] | undefined) ?? []);
  const rows: FieldRow[] = [];
  const skipped: string[] = [];
  const lossy: string[] = Object.keys(schema).some((k) => !ROOT_KEYWORDS.has(k)) ? ["(schema)"] : [];
  for (const [name, p] of Object.entries(props)) {
    let kind: FieldKind;
    if (Array.isArray(p.enum)) kind = "enum";
    else if (p.type === "string" && p.format === "date") kind = "date";
    else if (p.type === "string" || p.type === "number" || p.type === "integer" || p.type === "boolean") kind = p.type;
    else {
      skipped.push(name);
      continue;
    }
    if (isLossy(p)) lossy.push(name);
    rows.push({
      name,
      kind,
      required: required.has(name),
      options: kind === "enum" ? (p.enum as string[]).join(", ") : "",
      label: typeof p.title === "string" ? p.title : "",
      description: typeof p.description === "string" ? p.description : "",
    });
  }
  return { rows, skipped, lossy };
}

/** `data` d'esempio per il simulatore (BO-28): un valore plausibile per ogni riga. */
export function sampleFromRows(rows: FieldRow[], today = new Date()): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const r of rows) {
    if (r.name in STANDARD_SAMPLE && r.kind !== "enum") {
      out[r.name] = STANDARD_SAMPLE[r.name];
      continue;
    }
    out[r.name] =
      r.kind === "number" ? 10.5
      : r.kind === "integer" ? 1
      : r.kind === "boolean" ? true
      : r.kind === "date" ? today.toISOString().slice(0, 10)
      : r.kind === "enum" ? (optionsOf(r)[0] ?? "")
      : "esempio";
  }
  return out;
}

/** Esempi dei campi standard, come nei tipi di sistema. */
const STANDARD_SAMPLE: Record<string, unknown> = {
  amount: 25.5,
  currency: "EUR",
  orderId: "ORD-1001",
  productId: "PRD-001",
  storeId: "NEG-001",
};

/** Descrizione breve di un campo per la tabella del dettaglio: "numero · obbligatorio · APP, WEB". */
export function describeField(f: ActionField): string {
  const kind = f.format === "date" ? "data" : f.enum ? "elenco" : TYPE_IT[f.type] ?? f.type;
  return [kind, f.required ? "obbligatorio" : null, f.enum?.join(", ")].filter(Boolean).join(" · ");
}

const TYPE_IT: Record<string, string> = {
  string: "testo",
  number: "numero",
  integer: "intero",
  boolean: "sì/no",
  array: "elenco",
  object: "oggetto",
};

export interface CampaignRef {
  id: string;
  code: string;
  name: string;
  status: string;
  triggerActionTypes?: string[] | null;
}

/**
 * Impatto di una modifica: campagne che usano l'azione e quante sono attive (`LIVE`). Lo schema di un'azione vale
 * subito, anche per le campagne attive (Q-436).
 */
export function campaignImpact<C extends CampaignRef>(code: string, campaigns: C[]): { using: C[]; active: number } {
  const using = campaigns.filter((c) => (c.triggerActionTypes ?? []).includes(code));
  return { using, active: using.filter((c) => c.status === "LIVE").length };
}
