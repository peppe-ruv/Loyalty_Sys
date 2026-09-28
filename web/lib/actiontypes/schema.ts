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
  personal: "I dati personali non viaggiano nelle azioni: usa l'identificativo del membro.",
} as const;

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

/** Righe da uno schema piatto (i tipi custom creati qui); campi annidati o non riconosciuti → `null`. */
export function schemaToRows(schema: Record<string, unknown> | null): FieldRow[] | null {
  const { rows, skipped } = flatRows(schema);
  return rows == null || skipped.length > 0 ? null : rows;
}

/**
 * Righe dei campi piatti di uno schema e nomi dei campi saltati (oggetti, elenchi): serve a *Duplica da un'azione
 * esistente*, che copia ciò che l'editor sa rappresentare (per esempio `purchase.completed` senza `items`).
 */
export function flatRows(schema: Record<string, unknown> | null): { rows: FieldRow[] | null; skipped: string[] } {
  if (!schema || schema.type !== "object") return { rows: null, skipped: [] };
  const props = (schema.properties ?? {}) as Record<string, Record<string, unknown>>;
  const required = new Set((schema.required as string[] | undefined) ?? []);
  const rows: FieldRow[] = [];
  const skipped: string[] = [];
  for (const [name, p] of Object.entries(props)) {
    let kind: FieldKind;
    if (Array.isArray(p.enum)) kind = "enum";
    else if (p.type === "string" && p.format === "date") kind = "date";
    else if (p.type === "string" || p.type === "number" || p.type === "integer" || p.type === "boolean") kind = p.type;
    else {
      skipped.push(name);
      continue;
    }
    rows.push({
      name,
      kind,
      required: required.has(name),
      options: kind === "enum" ? (p.enum as string[]).join(", ") : "",
      label: typeof p.title === "string" ? p.title : "",
      description: typeof p.description === "string" ? p.description : "",
    });
  }
  return { rows, skipped };
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

/** Codice di un tipo custom: minuscolo a punti, 2–4 parti (come i tipi di sistema). SPEC-GAP: Q-89. */
export function isValidCode(code: string): boolean {
  return code.length <= 60 && /^[a-z][a-z0-9]*(\.[a-z][a-z0-9]*){1,3}$/.test(code);
}

/** Campagne che usano il tipo come trigger ("usato da n campagne"). */
export function campaignsUsing(code: string, campaigns: { code: string; triggerActionTypes?: string[] | null }[]): string[] {
  return campaigns.filter((c) => (c.triggerActionTypes ?? []).includes(code)).map((c) => c.code);
}

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
