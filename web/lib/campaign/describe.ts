// Frase generata che rilegge una campagna in italiano (docs/08 §BO-06). Funzione pura, testabile,
// usata dall'editor e da BO-21. Non lancia mai: su dati incompleti produce una frase parziale sensata.

export interface CampaignDraft {
  triggerActionTypes?: string[];
  /** Nomi dei tipi azione noti al chiamante (es. i tipi custom da ingestion), prioritari sulla mappa interna. */
  actionLabels?: Record<string, string>;
  /** Fonti ammesse (BO-06 §2 Quando, default tutte): elencate dopo il trigger ("da ecommerce o app"). */
  sources?: string[];
  /** Nomi dei concorsi per codice (gamification `GET /v1/contests`): "1 giocata a Ruota d'Autunno". */
  contestNames?: Record<string, string>;
  audience?: { all?: boolean; tiers?: string[]; segments?: string[] };
  conditions?: ConditionNode;
  effects?: EffectSpec[];
  limits?: { perMember?: { max: number; period: string }[] };
}

export type ConditionNode =
  | { op: "all" | "any" | "not"; rules: ConditionNode[] }
  | { field: string; cmp: string; value?: unknown };

export interface EffectSpec {
  type: string;
  currency?: string;
  mode?: string;
  value?: number;
  unitStep?: number;
  /** FROM_FIELD e LOOKUP (docs/03 §3.4): campo dell'azione da cui si legge il valore o la chiave della tabella. */
  amountField?: string;
  lookup?: Record<string, number>;
  factor?: number;
  count?: number;
  contestCode?: string;
  /** SEND_MESSAGE (docs/03 §3.4): template del messaggio in inbox. */
  templateCode?: string;
}

const ACTION_LABEL: Record<string, string> = {
  "purchase.completed": "Acquisto completato",
  "purchase.returned": "Reso di un acquisto",
  "ebill.activated": "Bolletta digitale attivata",
  "directdebit.activated": "Domiciliazione attivata",
  "selfreading.submitted": "Autolettura inviata",
  "app.login.daily": "Accesso giornaliero",
  "survey.completed": "Survey completata",
  "quiz.completed": "Quiz completato",
  "review.submitted": "Recensione inviata",
  "newsletter.subscribed": "Iscrizione newsletter",
  "member.registered": "Registrazione membro",
  "member.profile.completed": "Profilo completato",
  "member.birthday": "Compleanno",
  "tier.upgraded": "Passaggio di livello",
  "instantwin.won": "Vincita instant win",
  "badge.awarded": "Badge assegnato",
  "referral.completed": "Referral completato",
  "reward.redeemed": "Premio riscattato",
};

/** Nome leggibile di un tipo di azione (fallback: il codice). */
export function actionLabel(type: string): string {
  return ACTION_LABEL[type] ?? type;
}

const PERIOD_LABEL: Record<string, string> = {
  DAY: "al giorno",
  WEEK: "alla settimana",
  MONTH: "al mese",
  EDITION: "per edizione",
  ALWAYS: "in totale",
};

const CMP_LABEL: Record<string, string> = {
  eq: "=",
  neq: "≠",
  gt: ">",
  gte: "≥",
  lt: "<",
  lte: "≤",
  in: "tra",
  nin: "non tra",
  contains: "contiene",
  ncontains: "non contiene",
  exists: "presente",
  nexists: "assente",
  between: "tra",
  startsWith: "inizia con",
};

export function describeCampaign(c: CampaignDraft): string {
  const trigger = triggerPhrase(c.triggerActionTypes, c.actionLabels) + sourcesPhrase(c.sources);
  const audience = audiencePhrase(c.audience);
  const conditions = conditionsPhrase(c.conditions);
  const effects = effectsPhrase(c.effects, c.contestNames);
  const limits = limitsPhrase(c.limits);

  let sentence = `Quando arriva ${trigger}`;
  const ifs: string[] = [];
  if (conditions) ifs.push(conditions);
  if (audience) ifs.push(audience);
  if (ifs.length > 0) sentence += `, se ${ifs.join(" e ")}`;
  sentence += `, ${effects}`;
  if (limits) sentence += `, ${limits}`;
  return sentence + ".";
}

function triggerPhrase(types?: string[], labels?: Record<string, string>): string {
  if (!types || types.length === 0) return "un'azione";
  return bold(types.map((t) => labels?.[t] ?? ACTION_LABEL[t] ?? t).join(" o "));
}

function sourcesPhrase(sources?: string[]): string {
  return sources && sources.length > 0 ? ` da ${sources.join(" o ")}` : "";
}

function audiencePhrase(a?: CampaignDraft["audience"]): string | null {
  if (!a || a.all || (!a.tiers?.length && !a.segments?.length)) return null;
  const parts: string[] = [];
  if (a.tiers?.length) parts.push(`il membro è ${bold(a.tiers.join(" o "))}`);
  if (a.segments?.length) parts.push(`è nel segmento ${bold(a.segments.join(" o "))}`);
  return parts.join(" e ");
}

function conditionsPhrase(node?: ConditionNode): string | null {
  if (!node) return null;
  return renderNode(node);
}

function renderNode(node: ConditionNode): string | null {
  if ("op" in node) {
    const parts = node.rules.map(renderNode).filter((p): p is string => Boolean(p));
    if (parts.length === 0) return null;
    if (node.op === "any") return `(${parts.join(" oppure ")})`;
    if (node.op === "not") return `non (${parts.join(" e ")})`;
    return parts.join(" e ");
  }
  const label = fieldLabel(node.field);
  const cmp = CMP_LABEL[node.cmp] ?? node.cmp;
  if (node.cmp === "exists" || node.cmp === "nexists") return bold(`${label} ${cmp}`);
  return bold(`${label} ${cmp} ${formatValue(node.value)}${isEuroField(node.field) ? " €" : ""}`);
}

/** `data.amount` è un importo in euro (esempio di BO-06: "importo ≥ 50 €"). */
function isEuroField(field: string): boolean {
  return field === "data.amount";
}

function fieldLabel(field: string): string {
  const short = field.replace(/^data\./, "").replace(/^context\./, "").replace(/^member\./, "").replace(/^history\./, "");
  const labels: Record<string, string> = {
    amount: "importo",
    dayOfWeek: "giorno",
    hour: "ora",
    role: "ruolo",
    rating: "voto",
    tier: "livello",
    status: "stato",
  };
  return labels[short] ?? short;
}

function formatValue(value: unknown): string {
  if (Array.isArray(value)) return value.join(", ");
  return String(value ?? "");
}

function effectsPhrase(effects?: EffectSpec[], contestNames?: Record<string, string>): string {
  if (!effects || effects.length === 0) return bold("nessun effetto");
  return "assegna " + effects.map((e) => effectLabel(e, contestNames)).join(" e ");
}

function effectLabel(e: EffectSpec, contestNames?: Record<string, string>): string {
  switch (e.type) {
    case "GRANT_POINTS": {
      const cur = e.currency ?? "PTS";
      if (e.mode === "PER_AMOUNT") return bold(`${e.value ?? 1} ${cur} ogni ${e.unitStep ?? 1} €`);
      if (e.mode === "FIXED") return bold(`${e.value ?? 0} ${cur}`);
      if (e.mode === "FROM_FIELD") return bold(`${cur} pari al campo ${fieldLabel(e.amountField ?? "?")}`);
      if (e.mode === "LOOKUP") return bold(`${cur} dalla tabella sul campo ${fieldLabel(e.amountField ?? "?")}`);
      return bold(`${cur} (${e.mode ?? "?"})`);
    }
    case "MULTIPLIER":
      return bold(`${e.currency ?? "PTS"} ×${e.factor ?? 1}`);
    case "GRANT_PLAYS": {
      const count = e.count ?? 1;
      const plays = `${count} ${count === 1 ? "giocata" : "giocate"}`;
      if (!e.contestCode) return bold(plays);
      return bold(`${plays} a ${contestNames?.[e.contestCode] ?? e.contestCode}`);
    }
    case "ISSUE_COUPON":
      return bold("un coupon");
    case "AWARD_BADGE":
      return bold("un badge");
    case "SEND_MESSAGE":
      return bold(e.templateCode ? `il messaggio ${e.templateCode}` : "un messaggio");
    default:
      return bold(e.type);
  }
}

function limitsPhrase(limits?: CampaignDraft["limits"]): string | null {
  const perMember = limits?.perMember;
  if (!perMember || perMember.length === 0) return null;
  const l = perMember[0];
  return `al massimo ${bold(`${l.max} ${l.max === 1 ? "volta" : "volte"} ${PERIOD_LABEL[l.period] ?? l.period}`)}`;
}

// Enfasi con marcatori **…**: il renderer li converte in <strong> (o li mostra così nei test).
function bold(text: string): string {
  return `**${text}**`;
}
