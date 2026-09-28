import { ITALIAN_STOPWORDS } from "./code";
import type { FieldKind, FieldRow } from "./schema";

// Guida ai campi di un'azione personalizzata (BO-09 sezione 3, docs/08 §BO-09). Puro e testato.
// - Campi standard con gli stessi nomi tecnici dei tipi di sistema: la condizione `data.amount ≥ 1` e l'effetto
//   `PER_AMOUNT` continuano a funzionare, e con più trigger il costruttore di condizioni trova i campi comuni.
// - Etichetta italiana → nome tecnico proposto.
// - Blocco dei nomi che fanno pensare a dati personali (regola 10, ADR-032). SPEC-GAP: Q-435.
// SPEC-GAP: Q-434 (guida ai campi, etichette e descrizioni salvate come `title` e `description` nello schema).

export interface StandardField {
  name: string;
  label: string;
  kind: FieldKind;
  options?: string;
  description: string;
}

/** Campi standard: nome tecnico identico a quello dei tipi di sistema (`purchase.completed`, `review.submitted`). */
export const STANDARD_FIELDS: readonly StandardField[] = [
  { name: "amount", label: "Importo", kind: "number", description: "Importo su cui calcolare i punti (es. 25,50)." },
  { name: "currency", label: "Valuta", kind: "string", description: "Codice della valuta, per esempio EUR." },
  { name: "channel", label: "Canale", kind: "enum", options: "ONLINE, STORE, APP", description: "Dove è avvenuta l'azione." },
  { name: "orderId", label: "Codice ordine", kind: "string", description: "Identificativo dell'ordine nel tuo sistema." },
  { name: "productId", label: "Codice prodotto", kind: "string", description: "Identificativo del prodotto." },
  { name: "storeId", label: "Codice negozio", kind: "string", description: "Identificativo del punto vendita (es. NEG-001)." },
];

/** Sinonimi italiani che portano a un campo standard (chiave già normalizzata con `foldWords`). */
const STANDARD_SYNONYMS: Record<string, string> = {
  importo: "amount",
  "importo speso": "amount",
  spesa: "amount",
  valuta: "currency",
  canale: "channel",
  "codice ordine": "orderId",
  "numero ordine": "orderId",
  ordine: "orderId",
  "codice prodotto": "productId",
  prodotto: "productId",
  "codice negozio": "storeId",
  "punto vendita": "storeId",
  negozio: "storeId",
};

export function standardField(name: string): StandardField | undefined {
  return STANDARD_FIELDS.find((f) => f.name === name);
}

/** Minuscolo, senza accenti, senza articoli e preposizioni, parole separate da uno spazio. */
export function foldWords(text: string): string[] {
  return text
    .normalize("NFD")
    .replace(/[̀-ͯ]/g, "")
    .replace(/([a-z0-9])([A-Z])/g, "$1 $2")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, " ")
    .split(" ")
    .filter((w) => w && !ITALIAN_STOPWORDS.has(w));
}

export const FIELD_NAME_MAX = 40;
const FIELD_NAME = /^[a-z][a-zA-Z0-9]{0,39}$/;

export function isValidFieldName(name: string): boolean {
  return FIELD_NAME.test(name);
}

/**
 * Nome tecnico proposto da un'etichetta italiana: il nome standard se l'etichetta corrisponde a un campo standard,
 * altrimenti camelCase senza accenti, articoli e cifre iniziali, al massimo 40 caratteri. «Codice del negozio» →
 * `storeId`; «Data della visita» → `dataVisita`.
 */
export function technicalName(label: string): string {
  const words = foldWords(label);
  if (words.length === 0) return "";
  const key = words.join(" ");
  if (STANDARD_SYNONYMS[key]) return STANDARD_SYNONYMS[key];
  const cleaned = words.map((w, i) => (i === 0 ? w.replace(/^[0-9]+/, "") : w)).filter(Boolean);
  let out = "";
  for (const [i, w] of cleaned.entries()) {
    const part = i === 0 || out === "" ? w : w.charAt(0).toUpperCase() + w.slice(1);
    if ((out + part).length > FIELD_NAME_MAX) break;
    out += part;
  }
  return isValidFieldName(out) ? out : "";
}

/** Parole che da sole indicano un dato personale (nome o etichetta del campo). */
const PERSONAL_TOKENS = new Set([
  "email",
  "mail",
  "pec",
  "telefono",
  "tel",
  "phone",
  "cellulare",
  "indirizzo",
  "address",
  "cognome",
  "surname",
  "lastname",
  "firstname",
  "fullname",
  "codicefiscale",
  "fiscalcode",
  "taxcode",
  "iban",
  "passaporto",
  "passport",
  "nascita",
  "birthdate",
  "birthday",
  "dob",
  "ssn",
]);

/** Coppie di parole che insieme indicano un dato personale. */
const PERSONAL_PAIRS: [string, string][] = [
  ["codice", "fiscale"],
  ["fiscal", "code"],
  ["tax", "code"],
  ["first", "name"],
  ["last", "name"],
  ["full", "name"],
  ["birth", "date"],
  ["date", "birth"],
  ["numero", "telefono"],
  ["carta", "identita"],
  ["documento", "identita"],
];

/** Parole che, accanto a «nome», indicano una persona (non «nome prodotto» o «nome premio»). */
const PERSON_WORDS = new Set(["membro", "cliente", "utente", "persona", "socio", "member", "customer", "user"]);

/**
 * Il testo (nome tecnico o etichetta) fa pensare a un dato personale? I dati personali non viaggiano nelle azioni:
 * il membro è già identificato dall'evento (regola 10, ADR-032). SPEC-GAP: Q-435.
 */
export function looksPersonal(text: string): boolean {
  const words = foldWords(text);
  if (words.length === 0) return false;
  if (words.some((w) => PERSONAL_TOKENS.has(w))) return true;
  const joined = words.join("");
  if (PERSONAL_TOKENS.has(joined)) return true;
  for (const [a, b] of PERSONAL_PAIRS) {
    const i = words.indexOf(a);
    if (i >= 0 && words.slice(i + 1).includes(b)) return true;
  }
  const hasName = words.includes("nome") || words.includes("name");
  if (hasName && (words.length === 1 || words.some((w) => PERSON_WORDS.has(w)))) return true;
  return false;
}

/** Riga pronta per un campo standard. */
export function standardRow(name: string, required = false): FieldRow {
  const f = standardField(name);
  if (!f) throw new Error(`campo standard sconosciuto: ${name}`);
  return { name: f.name, label: f.label, description: f.description, kind: f.kind, required, options: f.options ?? "" };
}
