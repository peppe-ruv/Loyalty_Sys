import { dedupe } from "./code";
import { standardRow } from "./fields";
import { flatRows, type FieldRow } from "./schema";
import type { ActionType } from "./types";

// «Parti da un modello» e «Duplica da un'azione esistente» (BO-09 sezione 3, docs/08 §BO-09). Ogni modello controlla
// prima se l'azione esiste già: molti doppioni dei tipi di sistema nascerebbero da qui. I modelli propongono codici in
// inglese, come i tipi di sistema (Q-429). SPEC-GAP: Q-434.

export interface ActionTemplate {
  key: string;
  /** Nome proposto per l'azione. */
  name: string;
  /** Codice di un'azione di sistema che copre già il caso: il modello propone «Usa quella esistente». */
  systemCode?: string;
  /** L'azione esistente arriva dal programma (ponte interno) e non si crea da qui. */
  internal?: boolean;
  code?: string;
  category?: string;
  icon?: string;
  description?: string;
  rows?: () => FieldRow[];
}

function row(name: string, label: string, kind: FieldRow["kind"], required: boolean, description = "", options = ""): FieldRow {
  return { name, label, kind, required, options, description };
}

export const ACTION_TEMPLATES: readonly ActionTemplate[] = [
  { key: "purchase", name: "Acquisto", systemCode: "purchase.completed" },
  {
    key: "partnerPurchase",
    name: "Acquisto presso un partner",
    code: "partner.purchase.completed",
    category: "TRANSACTION",
    icon: "handshake",
    description: "Il membro ha fatto un acquisto presso un partner del programma.",
    rows: () => [
      row("partnerId", "Codice partner", "string", true, "Identificativo del partner nel tuo sistema."),
      standardRow("orderId", true),
      standardRow("amount", true),
      standardRow("currency", true),
      standardRow("channel", false),
    ],
  },
  {
    key: "storeVisit",
    name: "Visita in negozio",
    code: "store.visited",
    category: "ENGAGEMENT",
    icon: "map-pin",
    description: "Il membro ha visitato un punto vendita.",
    rows: () => [standardRow("storeId", true), row("visitDate", "Data della visita", "date", false, "Giorno della visita.")],
  },
  {
    key: "eventAttended",
    name: "Partecipazione a un evento",
    code: "event.attended",
    category: "ENGAGEMENT",
    icon: "calendar",
    description: "Il membro ha partecipato a un evento del programma.",
    rows: () => [
      row("eventId", "Codice evento", "string", true, "Identificativo dell'evento."),
      row("eventDate", "Data dell'evento", "date", false, "Giorno dell'evento."),
    ],
  },
  {
    key: "qrScan",
    name: "Scansione di un codice QR",
    code: "qrcode.scanned",
    category: "ENGAGEMENT",
    icon: "qr-code",
    description: "Il membro ha scansionato un codice QR del programma.",
    rows: () => [
      row("codeId", "Codice QR", "string", true, "Identificativo del codice scansionato."),
      row("location", "Luogo", "string", false, "Dove si trovava il codice (es. vetrina, volantino)."),
    ],
  },
  { key: "review", name: "Recensione", systemCode: "review.submitted" },
  { key: "referral", name: "Presentazione di un amico", systemCode: "referral.completed", internal: true },
  { key: "survey", name: "Questionario", systemCode: "survey.completed" },
];

/** Che cosa offrire scegliendo un modello: l'azione esistente oppure la bozza da compilare. */
export type TemplateChoice =
  | { kind: "existing"; type: ActionType; internal: boolean }
  | {
      kind: "draft";
      name: string;
      code: string;
      category: string;
      icon: string;
      description: string;
      rows: FieldRow[];
    };

export function applyTemplate(template: ActionTemplate, types: ActionType[]): TemplateChoice {
  const byCode = new Map(types.map((t) => [t.code, t]));
  const existing = (template.systemCode && byCode.get(template.systemCode)) || (template.code && byCode.get(template.code));
  if (existing) return { kind: "existing", type: existing, internal: !!template.internal || existing.category === "INTERNAL" };
  if (!template.code || !template.rows) {
    // Il modello rimanda a un'azione di sistema che qui non c'è (dati demo ridotti): si parte da una bozza vuota.
    return { kind: "draft", name: template.name, code: "", category: "ENGAGEMENT", icon: "zap", description: "", rows: [] };
  }
  return {
    kind: "draft",
    name: template.name,
    code: dedupe(template.code.split("."), new Set(byCode.keys())),
    category: template.category ?? "ENGAGEMENT",
    icon: template.icon ?? "zap",
    description: template.description ?? "",
    rows: template.rows(),
  };
}

/**
 * *Duplica da un'azione esistente*: copia campi, categoria, icona e descrizione. Un tipo interno diventa
 * «Coinvolgimento» (una personalizzata non può essere interna); i campi che l'editor non rappresenta (oggetti,
 * elenchi) si saltano e si elencano.
 */
export function duplicateFrom(source: ActionType): {
  rows: FieldRow[];
  skipped: string[];
  category: string;
  icon: string;
  description: string;
} {
  const { rows, skipped } = flatRows(source.dataSchema);
  const category = source.category && source.category !== "INTERNAL" ? source.category : "ENGAGEMENT";
  return {
    rows: (rows ?? []).map((r) => ({ ...r })),
    skipped,
    category,
    icon: source.icon ?? "zap",
    description: source.description ?? "",
  };
}
