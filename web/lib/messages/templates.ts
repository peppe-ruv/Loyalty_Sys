import type { LhFieldError } from "@/lib/api/client";
import type { MessageTemplate, NotificationRule } from "./types";
import type { PreviewSource } from "./facts";

// Regole pure dell'editor dei template (BO-19 `templates`): sintassi dei segnaposto (come TemplateEngine.problems in
// engagement), inserimento di un segnaposto nel punto del cursore, sorgenti che usano il template, errori per campo.

const PLACEHOLDER = /\{\{\s*([^{}|]*?)\s*(?:\|\s*([^{}|]*?)\s*)?}}/g;
const ROOTS = new Set(["data", "member", "event"]);
const FORMATTERS = new Set(["number", "date"]);

/** Problemi di sintassi di un testo con segnaposto. Vuoto = valido. */
export function templateProblems(template: string | null | undefined): string[] {
  if (!template) return [];
  const problems: string[] = [];
  for (const m of template.matchAll(PLACEHOLDER)) {
    const path = m[1];
    const formatter = m[2];
    if (!path || path.trim() === "") {
      problems.push("segnaposto vuoto");
      continue;
    }
    const root = path.includes(".") ? path.slice(0, path.indexOf(".")) : path;
    if (!ROOTS.has(root)) problems.push(`segnaposto {{${path}}}: il percorso inizia con data., member. o event.`);
    if (formatter !== undefined && !FORMATTERS.has(formatter)) {
      problems.push(`formattatore sconosciuto |${formatter} (ammessi: number, date)`);
    }
  }
  const rest = template.replace(PLACEHOLDER, "");
  if (rest.includes("{{") || rest.includes("}}")) problems.push("graffe {{ }} non bilanciate");
  return problems;
}

/** Inserisce `token` tra `start` e `end` (selezione del campo): ritorna il testo e la nuova posizione del cursore. */
export function insertAt(text: string, token: string, start: number | null, end: number | null): { text: string; caret: number } {
  const s = start == null ? text.length : Math.max(0, Math.min(start, text.length));
  const e = end == null ? s : Math.max(s, Math.min(end, text.length));
  return { text: text.slice(0, s) + token + text.slice(e), caret: s + token.length };
}

/** Campagna (dal campaign-service) con i soli campi che servono a trovare gli effetti SEND_MESSAGE. */
export interface CampaignLike {
  code: string;
  name: string;
  status: string;
  effects: { type: string; templateCode?: string; params?: Record<string, unknown> }[] | null;
}

/**
 * Chi usa un template ("Dove si vede", docs/08 §3.4): le regole che lo puntano e le campagne con SEND_MESSAGE. Ne
 * derivano i segnaposto suggeriti e le sorgenti selezionabili per l'anteprima.
 */
export function templateUsage(code: string, rules: NotificationRule[], campaigns: CampaignLike[]) {
  const usedByRules = rules.filter((r) => r.templateCode === code);
  const usedByCampaigns = campaigns
    .map((c) => ({ campaign: c, effect: (c.effects ?? []).find((e) => e.type === "SEND_MESSAGE" && e.templateCode === code) }))
    .filter((x): x is { campaign: CampaignLike; effect: NonNullable<typeof x.effect> } => x.effect != null);
  const sources: PreviewSource[] = [
    ...unique(usedByRules.map((r) => r.factType)).map((factType) => ({ kind: "fact" as const, factType })),
    ...usedByCampaigns.map(({ campaign, effect }) => ({ kind: "campaign" as const, campaignCode: campaign.code, params: effect.params ?? {} })),
  ];
  return { usedByRules, usedByCampaigns: usedByCampaigns.map((x) => x.campaign), sources };
}

function unique<T>(xs: T[]): T[] {
  return [...new Set(xs)];
}

/** Errori del backend (RFC 9457 `errors[]`) raggruppati per campo del form. */
export function errorsByField(errors: LhFieldError[] | undefined): Record<string, string[]> {
  const out: Record<string, string[]> = {};
  for (const e of errors ?? []) {
    (out[e.field] ??= []).push(e.message);
  }
  return out;
}

/** Modulo dell'editor → corpo della richiesta. */
export interface TemplateForm {
  code: string;
  name: string;
  channel: MessageTemplate["channel"];
  category: MessageTemplate["category"];
  icon: string;
  linkTarget: string;
  titleTpl: string;
  bodyTpl: string;
}

export function emptyTemplateForm(): TemplateForm {
  return { code: "MSG-", name: "", channel: "INAPP", category: "PROGRAM", icon: "bell", linkTarget: "/portal", titleTpl: "", bodyTpl: "" };
}

export function templateToForm(t: MessageTemplate): TemplateForm {
  return {
    code: t.code,
    name: t.name,
    channel: t.channel,
    category: t.category,
    icon: t.icon ?? "",
    linkTarget: t.linkTarget ?? "",
    titleTpl: t.titleTpl,
    bodyTpl: t.bodyTpl,
  };
}

const CODE = /^[A-Z][A-Z0-9-]{2,39}$/;

/** Validazione al salvataggio (stessa del backend, per mostrare gli errori subito sui campi). */
export function validateTemplateForm(f: TemplateForm, isNew: boolean): Record<string, string[]> {
  const out: Record<string, string[]> = {};
  const add = (field: string, msg: string) => (out[field] ??= []).push(msg);
  if (isNew && !CODE.test(f.code.trim().toUpperCase())) add("code", "formato ^[A-Z][A-Z0-9-]{2,39}$ (es. MSG-POINTS-EARNED)");
  if (!f.name.trim()) add("name", "obbligatorio");
  if (!f.titleTpl.trim()) add("titleTpl", "obbligatorio");
  if (!f.bodyTpl.trim()) add("bodyTpl", "obbligatorio");
  templateProblems(f.titleTpl).forEach((p) => add("titleTpl", p));
  templateProblems(f.bodyTpl).forEach((p) => add("bodyTpl", p));
  // SPEC-GAP: Q-81 — il servizio accetta qualsiasi link_target; PT-12 segue solo percorsi del portale (inboxHref), quindi
  // l'editor li chiede già così invece di salvare link che il membro non potrebbe aprire.
  if (f.linkTarget.trim() && !f.linkTarget.trim().startsWith("/portal")) add("linkTarget", "un percorso del portale, es. /portal/activity");
  return out;
}
