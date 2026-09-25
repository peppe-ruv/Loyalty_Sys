import type { ActionField } from "./types";

// Editor "a righe" dei tipi custom di BO-09 (docs/08 §BO-09: "editor campi a righe → genera il JSON Schema").
// Puro e testato: righe ↔ JSON Schema 2020-12 e `sampleData` d'esempio coerente con lo schema.

export type FieldKind = "string" | "number" | "integer" | "boolean" | "date" | "enum";

export interface FieldRow {
  name: string;
  kind: FieldKind;
  required: boolean;
  /** Solo per `enum`: valori separati da virgola. */
  options: string;
}

export const KIND_LABEL: Record<FieldKind, string> = {
  string: "Testo",
  number: "Numero",
  integer: "Intero",
  boolean: "Sì / no",
  date: "Data",
  enum: "Elenco di valori",
};

const NAME = /^[a-z][a-zA-Z0-9]{0,39}$/;

export function emptyRow(): FieldRow {
  return { name: "", kind: "string", required: false, options: "" };
}

export function optionsOf(row: FieldRow): string[] {
  return row.options
    .split(",")
    .map((o) => o.trim())
    .filter(Boolean);
}

/** Errori per riga (indice → messaggio) e generali. Vuoto = righe valide. */
export function rowErrors(rows: FieldRow[]): Record<number, string> {
  const errors: Record<number, string> = {};
  const seen = new Set<string>();
  rows.forEach((r, i) => {
    if (!NAME.test(r.name)) errors[i] = "Nome in camelCase, lettera iniziale minuscola";
    else if (seen.has(r.name)) errors[i] = "Nome duplicato";
    else if (r.kind === "enum" && optionsOf(r).length === 0) errors[i] = "Indica almeno un valore";
    seen.add(r.name);
  });
  return errors;
}

export function rowsToSchema(rows: FieldRow[]): Record<string, unknown> {
  const properties: Record<string, unknown> = {};
  for (const r of rows) {
    properties[r.name] =
      r.kind === "date"
        ? { type: "string", format: "date" }
        : r.kind === "enum"
          ? { type: "string", enum: optionsOf(r) }
          : { type: r.kind };
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
  if (!schema || schema.type !== "object") return null;
  const props = (schema.properties ?? {}) as Record<string, Record<string, unknown>>;
  const required = new Set((schema.required as string[] | undefined) ?? []);
  const rows: FieldRow[] = [];
  for (const [name, p] of Object.entries(props)) {
    let kind: FieldKind;
    if (Array.isArray(p.enum)) kind = "enum";
    else if (p.type === "string" && p.format === "date") kind = "date";
    else if (p.type === "string" || p.type === "number" || p.type === "integer" || p.type === "boolean") kind = p.type;
    else return null;
    rows.push({ name, kind, required: required.has(name), options: kind === "enum" ? (p.enum as string[]).join(", ") : "" });
  }
  return rows;
}

/** `data` d'esempio per il simulatore (BO-28): un valore plausibile per ogni riga. */
export function sampleFromRows(rows: FieldRow[], today = new Date()): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const r of rows) {
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
