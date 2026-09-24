// Attributi personalizzati ed etichette del membro (F-MBR-03, docs/08 §BO-03 scheda `segments`, M6.7).
// Puro e testato: dai valori del form alla PATCH di member (`attributes` chiave → valore, `null` rimuove; `labels`
// elenco completo), con gli stessi controlli del servizio.

export type AttributeType = "STRING" | "NUMBER" | "BOOLEAN" | "DATE";

export interface AttributeDefinition {
  key: string;
  label: string;
  type: AttributeType;
  options: string[];
}

export type AttributeValues = Record<string, unknown>;

/** Valore del form (sempre testo) da un valore salvato. */
export function toFormValue(def: AttributeDefinition, value: unknown): string {
  if (value === null || value === undefined) return "";
  if (def.type === "BOOLEAN") return value === true ? "true" : value === false ? "false" : "";
  return String(value);
}

/** Valore da salvare dal testo del form: `null` = vuoto (rimuove); `undefined` = non valido. */
export function fromFormValue(def: AttributeDefinition, text: string): unknown {
  const t = text.trim();
  if (t === "") return null;
  switch (def.type) {
    case "NUMBER": {
      const n = Number(t.replace(",", "."));
      return Number.isFinite(n) ? n : undefined;
    }
    case "BOOLEAN":
      return t === "true" ? true : t === "false" ? false : undefined;
    case "DATE":
      return /^\d{4}-\d{2}-\d{2}$/.test(t) && !Number.isNaN(Date.parse(t)) ? t : undefined;
    default:
      return def.options.length > 0 && !def.options.includes(t) ? undefined : t;
  }
}

export interface AttributePatch {
  attributes: Record<string, unknown>;
  errors: Record<string, string>;
}

/** Solo le chiavi cambiate rispetto a `current`; errori per chiave con messaggio in italiano. */
export function attributePatch(
  defs: AttributeDefinition[],
  current: AttributeValues,
  form: Record<string, string>,
): AttributePatch {
  const attributes: Record<string, unknown> = {};
  const errors: Record<string, string> = {};
  for (const d of defs) {
    const text = form[d.key] ?? toFormValue(d, current[d.key]);
    const value = fromFormValue(d, text);
    if (value === undefined) {
      errors[d.key] =
        d.type === "NUMBER" ? "Serve un numero" : d.type === "DATE" ? "Data AAAA-MM-GG" : d.type === "BOOLEAN" ? "Sì o no" : `Uno tra ${d.options.join(", ")}`;
      continue;
    }
    const before = current[d.key] ?? null;
    if (value !== before) attributes[d.key] = value;
  }
  return { attributes, errors };
}

const LABEL = /^[a-z0-9][a-z0-9_-]{0,29}$/;

/** Etichetta normalizzata (minuscolo, senza spazi) o `null` se non valida. */
export function normalizeLabel(raw: string): string | null {
  const v = raw.trim().toLowerCase();
  return LABEL.test(v) ? v : null;
}

export function addLabel(labels: string[], raw: string): string[] {
  const v = normalizeLabel(raw);
  return v == null || labels.includes(v) || labels.length >= 20 ? labels : [...labels, v];
}

export function sameLabels(a: string[], b: string[]): boolean {
  return a.length === b.length && a.every((x, i) => x === b[i]);
}
