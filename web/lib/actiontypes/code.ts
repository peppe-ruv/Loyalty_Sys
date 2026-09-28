// Codice tecnico di un'azione personalizzata (BO-09, docs/08 §BO-09 sezione 2).
// La regola resta quella del servizio (EventTypeService: minuscolo a punti, 2–4 parti, ≤ 60 caratteri, unico,
// immutabile): la UI la può rendere più severa, mai più permissiva. Qui il codice si **propone** dal nome.
// SPEC-GAP: Q-429 (codice dal nome), Q-430 (prefissi riservati).

export const CODE_MAX_LENGTH = 60;
export const CODE_MAX_PARTS = 4;
export const ACTION_TYPE_PREFIX = "io.loyaltyhub.action.";

const CODE_PATTERN = /^[a-z][a-z0-9]*(\.[a-z][a-z0-9]*){1,3}$/;

/** Prime parti vietate dalla UI: un codice `io.loyaltyhub.effect.x` finirebbe fuori dalle azioni (Q-430). */
export const RESERVED_FIRST_PARTS = ["io", "loyaltyhub"] as const;

/** Articoli e preposizioni italiane scartati dal nome (anche le forme articolate). */
export const ITALIAN_STOPWORDS = new Set([
  "il", "lo", "la", "i", "gli", "le", "l", "un", "uno", "una",
  "di", "d", "a", "da", "in", "con", "su", "per", "tra", "fra",
  "del", "dello", "della", "dei", "degli", "delle", "dell",
  "al", "allo", "alla", "ai", "agli", "alle", "all",
  "dal", "dallo", "dalla", "dai", "dagli", "dalle", "dall",
  "nel", "nello", "nella", "nei", "negli", "nelle", "nell",
  "col", "coi", "sul", "sullo", "sulla", "sui", "sugli", "sulle", "sull",
  "e", "ed", "o", "od",
]);

/**
 * Menu «Che cosa è successo?» per i nomi di una sola parola: etichetta italiana, participio inglese come i codici di
 * sistema (`purchase.completed`, `review.submitted`).
 */
export const VERB_CHOICES: { label: string; participle: string }[] = [
  { label: "Completamento", participle: "completed" },
  { label: "Invio", participle: "submitted" },
  { label: "Attivazione", participle: "activated" },
  { label: "Conferma", participle: "confirmed" },
  { label: "Registrazione", participle: "registered" },
  { label: "Prenotazione", participle: "booked" },
  { label: "Condivisione", participle: "shared" },
  { label: "Scansione", participle: "scanned" },
  { label: "Visita", participle: "visited" },
  { label: "Ricezione", participle: "received" },
];

/** Parole del nome utili al codice: minuscole, senza accenti, senza articoli, preposizioni e cifre iniziali. */
export function codeWords(name: string): string[] {
  return name
    .toLowerCase()
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .replace(/[^a-z0-9]+/g, " ")
    .split(" ")
    .filter((w) => w && !ITALIAN_STOPWORDS.has(w))
    .map((w) => w.replace(/^[0-9]+/, ""))
    .filter(Boolean);
}

/** Unisce al massimo `max` parole entro 60 caratteri, tagliando solo tra una parola e l'altra. */
function joinWithin(words: string[], max = CODE_MAX_PARTS): string[] {
  const out: string[] = [];
  for (const w of words) {
    if (out.length === max) break;
    const next = [...out, w].join(".");
    if (next.length > CODE_MAX_LENGTH) {
      // Una prima parola lunghissima si accorcia a 40 caratteri (l'unico taglio dentro una parola), così resta
      // spazio per la seconda parte.
      if (out.length === 0) {
        out.push(w.slice(0, 40));
        continue;
      }
      break;
    }
    out.push(w);
  }
  return out;
}

export interface CodeSuggestion {
  /** Codice proposto, o `null` se servono altre informazioni. */
  code: string | null;
  /** Il nome ha una sola parola utile: chiedi «Che cosa è successo?» (`VERB_CHOICES`). */
  needsVerb: boolean;
}

/**
 * Codice proposto dal nome (Q-429). Esempio: «Visita in negozio» → `visita.negozio`. Se il codice è già usato si
 * aggiunge un numero all'ultima parte (`visita.negozio2`). Una prima parola riservata (`io`, `loyaltyhub`) si salta.
 */
export function suggestCode(name: string, taken: Iterable<string> = [], participle?: string | null): CodeSuggestion {
  let words = codeWords(name);
  while (words.length && (RESERVED_FIRST_PARTS as readonly string[]).includes(words[0])) words = words.slice(1);
  if (words.length === 0) return { code: null, needsVerb: false };
  if (words.length === 1 && !participle) return { code: null, needsVerb: true };
  const parts = words.length === 1 ? joinWithin([words[0], participle!]) : joinWithin(words);
  if (parts.length < 2) return { code: null, needsVerb: true };
  return { code: dedupe(parts, new Set(taken)), needsVerb: false };
}

/** Rende unico il codice con un numero in coda all'ultima parte, restando entro 60 caratteri. */
export function dedupe(parts: string[], taken: Set<string>): string {
  const base = parts.join(".");
  if (!taken.has(base)) return base;
  for (let n = 2; n < 1000; n++) {
    const suffix = String(n);
    const last = parts[parts.length - 1];
    const head = parts.slice(0, -1).join(".");
    const room = CODE_MAX_LENGTH - head.length - 1 - suffix.length;
    const candidate = `${head}.${last.slice(0, Math.max(1, room))}${suffix}`;
    if (!taken.has(candidate)) return candidate;
  }
  return base;
}

export type CodeCheckKey = "chars" | "parts" | "length" | "unique" | "reserved";

export interface CodeCheck {
  key: CodeCheckKey;
  ok: boolean;
}

/** Controlli mostrati come elenco dal vivo sotto il codice (sezione 2 dell'editor). */
export function codeChecks(code: string, taken: Iterable<string> = []): CodeCheck[] {
  const parts = code.split(".");
  const takenSet = new Set(taken);
  return [
    { key: "chars", ok: code.length > 0 && /^[a-z0-9.]+$/.test(code) && parts.every((p) => /^[a-z]/.test(p)) },
    { key: "parts", ok: parts.length >= 2 && parts.length <= CODE_MAX_PARTS && parts.every(Boolean) },
    { key: "length", ok: code.length > 0 && code.length <= CODE_MAX_LENGTH },
    { key: "unique", ok: code.length > 0 && !takenSet.has(code) },
    { key: "reserved", ok: !isReservedCode(code) },
  ];
}

/** Forma del servizio (EventTypeService, Q-89): minuscolo a punti, da 2 a 4 parti, al massimo 60 caratteri. */
export function isActionCode(code: string): boolean {
  return code.length <= CODE_MAX_LENGTH && CODE_PATTERN.test(code);
}

/** Il codice rispetta la regola del servizio e quelle, più severe, della UI. */
export function isAcceptableCode(code: string, taken: Iterable<string> = []): boolean {
  return isActionCode(code) && codeChecks(code, taken).every((c) => c.ok);
}

/** Prima parte `io` o `loyaltyhub` (Q-430). */
export function isReservedCode(code: string): boolean {
  const first = code.split(".")[0];
  return (RESERVED_FIRST_PARTS as readonly string[]).includes(first);
}

/** Tipo di sistema di cui il codice riusa lo spazio di nomi (`purchase.qualcosa` → `purchase.completed`), se c'è. */
export function systemNamespaceClash(code: string, types: { code: string; origin: string }[]): string | null {
  const first = code.split(".")[0];
  if (!first || !code.includes(".")) return null;
  const clash = types.find((t) => t.origin === "SYSTEM" && t.code.split(".")[0] === first && t.code !== code);
  return clash?.code ?? null;
}

/** Ciò che l'integratore mette nel campo `type` dell'evento: forma breve o completa. */
export function integratorTypes(code: string): { short: string; full: string } {
  return { short: code, full: ACTION_TYPE_PREFIX + code };
}
