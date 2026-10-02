import { it } from "@/lib/i18n/it";
import { actionLabel } from "@/lib/campaign/describe";
import { formatEuro, formatPoints } from "@/lib/format/points";
import { eventLabel, TECH_ONLY_TYPES } from "./eventLabels";
import type { Trace, TraceNode, TraceSummary } from "./trace";

// Racconto di un tracciato per chi non è tecnico (docs/08 §BO-25, issue #204): funzione pura da `Trace` a fasi e
// passi. Le fasi seguono la pipeline (azione → regole → effetti → fatti); i nodi uguali di una fase si raggruppano
// («Obiettivo avanzato ×3»); un'azione derivata (es. `badge.awarded` reimmessa in ingestion) e tutto ciò che ne
// discende si annidano sotto il passo che l'ha innescata, seguendo `parentEventId`.

export type PhaseId = "action" | "rules" | "effects" | "facts";
export const PHASES: readonly PhaseId[] = ["action", "rules", "effects", "facts"];

export interface StoryStep {
  key: string;
  family: string;
  shortType: string;
  label: string;
  /** Dato chiave leggibile («+100 Punti», «€ 24,90»), vuoto se non ricavabile o se il passo raggruppa più nodi. */
  detail: string;
  count: number;
  offsetMs: number;
  eventIds: string[];
  /** Passo non elaborato (voce DLQ). */
  failed: boolean;
  /** Passi innescati a loro volta da questo (azione derivata e discendenti), in ordine di tempo. */
  triggered: StoryStep[];
}

export interface StoryPhase {
  id: PhaseId;
  steps: StoryStep[];
}

export interface TraceStory {
  root: TraceNode | null;
  phases: StoryPhase[];
  /** Passi bloccati, ovunque si trovino (anche annidati). */
  failed: StoryStep[];
  /** Nodi azione di cui spiegare la valutazione («Perché»): la radice e le azioni derivate, in ordine di tempo. */
  actions: TraceNode[];
}

export interface StoryOptions {
  /** Nome della valuta (da Valute ed edizioni); ripiego: il codice. */
  currencyName?: (code: string) => string;
}

const POINTS_RE = /([+-]?\d+(?:[.,]\d+)?)\s+([A-Z][A-Z0-9_]{1,})$/;
const EURO_RE = /(\d+(?:[.,]\d+)?)\s*€/;

/** Importo in euro ricavato dalla sintesi del nodo («Acquisto · 24.9 €» → «€ 24,90»), o null. */
export function amountOf(node: TraceNode | null | undefined): string | null {
  const m = node?.summary?.match(EURO_RE);
  if (!m) return null;
  const value = Number(m[1].replace(",", "."));
  return Number.isFinite(value) ? formatEuro(value) : null;
}

/** Dato chiave leggibile dalla sintesi del servizio, senza codici: punti con il nome della valuta, euro, livello. */
export function detailOf(node: TraceNode, currencyName: (code: string) => string = (c) => c): string {
  const s = node.summary ?? "";
  if (!s || s === node.shortType) return "";
  const pts = s.match(POINTS_RE);
  if (pts) {
    const n = Number(pts[1].replace(",", "."));
    if (Number.isFinite(n)) return `${n > 0 ? "+" : ""}${formatPoints(n)} ${currencyName(pts[2])}`;
  }
  const euro = amountOf(node);
  if (euro) return euro;
  const arrow = s.indexOf("→");
  if (arrow >= 0) return `→ ${s.slice(arrow + 1).trim()}`;
  return "";
}

/** Stato della voce DLQ: ultimo token della sintesi «DLQ · <codice> · <stato>»; REPROCESSED = già gestita. */
function isReprocessed(node: TraceNode): boolean {
  const status = (node.summary ?? "").split("·").pop()?.trim() ?? "";
  return status === "REPROCESSED";
}

/** Vero se tra i passi (a qualunque profondità di annidamento) ce n'è uno bloccato. */
export function hasFailedStep(steps: StoryStep[]): boolean {
  return steps.some((s) => s.failed || hasFailedStep(s.triggered));
}

function phaseOf(node: TraceNode, byId: Map<string, TraceNode>, rootId: string | null, depth = 0): PhaseId {
  if (node.eventId === rootId) return "action";
  if (node.family === "DLQ") {
    const parent = node.parentEventId ? byId.get(node.parentEventId) : undefined;
    return parent && depth < 50 ? phaseOf(parent, byId, rootId, depth + 1) : "facts";
  }
  if (node.family === "ACTION") return "action";
  if (node.shortType === "campaign.evaluated") return "rules";
  if (node.family === "EFFECT" || node.shortType.startsWith("wallet.")) return "effects";
  return "facts";
}

/** Trasforma il tracciato in racconto: quattro fasi sempre presenti (eventualmente vuote), passi raggruppati e annidati. */
export function buildStory(trace: Trace, options: StoryOptions = {}): TraceStory {
  const currencyName = options.currencyName ?? ((c: string) => c);
  const nodes = [...trace.nodes].sort((a, b) => a.offsetMs - b.offsetMs);
  const byId = new Map(nodes.map((n) => [n.eventId, n]));
  const inTrace = (id?: string | null) => (id ? byId.has(id) : false);

  const roots = nodes.filter((n) => !inTrace(n.parentEventId));
  const root = roots.find((n) => n.family === "ACTION") ?? roots[0] ?? null;
  const rootId = root?.eventId ?? null;

  // Azioni derivate: ACTION con un genitore nel tracciato (diverse dalla radice).
  const derived = new Set(nodes.filter((n) => n.family === "ACTION" && n.eventId !== rootId && inTrace(n.parentEventId)).map((n) => n.eventId));

  // Per ogni nodo, l'azione derivata PIÙ VICINA sopra di lui (o lui stesso): ogni azione derivata ha il suo sottoalbero,
  // appeso al passo che l'ha innescata; un'azione derivata dentro un elenco annidato ha a sua volta il proprio.
  const outerDerived = (node: TraceNode): string | null => {
    let cur: TraceNode | undefined = node;
    const seen = new Set<string>();
    while (cur && !seen.has(cur.eventId)) {
      seen.add(cur.eventId);
      if (derived.has(cur.eventId)) return cur.eventId;
      cur = cur.parentEventId ? byId.get(cur.parentEventId) : undefined;
    }
    return null;
  };

  const nestedBy = new Map<string, TraceNode[]>(); // azione derivata → nodi del suo sottoalbero (fino alla successiva azione derivata)
  const main: TraceNode[] = [];
  for (const n of nodes) {
    const outer = outerDerived(n);
    if (outer) {
      const list = nestedBy.get(outer) ?? [];
      list.push(n);
      nestedBy.set(outer, list);
    } else {
      main.push(n);
    }
  }
  // Nodo che ha innescato ciascuna azione derivata → le sue azioni.
  const triggersOf = new Map<string, string[]>();
  for (const actionId of nestedBy.keys()) {
    const parentId = byId.get(actionId)?.parentEventId;
    if (!parentId) continue;
    triggersOf.set(parentId, [...(triggersOf.get(parentId) ?? []), actionId]);
  }

  const failed: StoryStep[] = [];
  const toStep = (n: TraceNode): StoryStep => {
    const isDlq = n.family === "DLQ";
    // Una voce DLQ già riprocessata non blocca più nulla: resta come passo neutro.
    const isFailed = isDlq && !isReprocessed(n);
    const parent = n.parentEventId ? byId.get(n.parentEventId) : undefined;
    const step: StoryStep = {
      key: n.eventId,
      family: n.family,
      shortType: n.shortType,
      label: isDlq && !isFailed ? it.traces.events.dlqReprocessed : eventLabel(n.family, n.shortType),
      detail: isDlq ? (parent ? eventLabel(parent.family, parent.shortType) : "") : detailOf(n, currencyName),
      count: 1,
      offsetMs: n.offsetMs,
      eventIds: [n.eventId],
      failed: isFailed,
      triggered: [],
    };
    for (const actionId of triggersOf.get(n.eventId) ?? []) {
      step.triggered.push(...groupSteps((nestedBy.get(actionId) ?? []).map(toStep)));
    }
    if (isFailed) failed.push(step);
    return step;
  };

  const phases: StoryPhase[] = PHASES.map((id) => ({
    id,
    steps: groupSteps(main.filter((n) => phaseOf(n, byId, rootId) === id).map(toStep)),
  }));

  const actions = nodes.filter((n) => n.family === "ACTION" && (n.eventId === rootId || derived.has(n.eventId)));
  return { root, phases, failed, actions };
}

/** Raggruppa i passi uguali (stessa famiglia e tipo) che non hanno innescato nulla e non sono bloccati. */
function groupSteps(steps: StoryStep[]): StoryStep[] {
  const out: StoryStep[] = [];
  const index = new Map<string, StoryStep>();
  for (const s of steps) {
    const groupable = !s.failed && s.triggered.length === 0;
    const k = `${s.family}|${s.shortType}`;
    const existing = groupable ? index.get(k) : undefined;
    if (existing) {
      existing.count += 1;
      existing.eventIds.push(...s.eventIds);
      existing.detail = "";
      continue;
    }
    out.push(s);
    if (groupable) index.set(k, s);
  }
  return out;
}

/** Numero di nodi FACT di un tipo (badge assegnati, obiettivi completati) per i riquadri KPI. */
export function countFacts(trace: Trace, shortType: string): number {
  return trace.nodes.filter((n) => n.family === "FACT" && n.shortType === shortType).length;
}

/** Stato in italiano: Completato / In corso / Bloccato. */
export function statusLabel(status: string): string {
  return it.traces.status[status] ?? it.traces.statusUnknown;
}

/** Frase di sintesi senza il soggetto: «ha completato un acquisto da € 24,90». */
export function storySentence(root: TraceNode | null): string {
  if (!root) return "";
  const verb = it.traces.sentence[root.shortType] ?? it.traces.sentenceFallback(eventLabel(root.family, root.shortType));
  const amount = amountOf(root);
  return amount ? `${verb}${it.traces.detail.amount(amount)}` : verb;
}

export type ChipTone = "good" | "neutral";
export interface OutcomeChip {
  text: string;
  tone: ChipTone;
}

/**
 * Esito sintetico dell'elenco in chip leggibili: «+124 PTS · +24 STS · tier GOLD» → «+124 punti», «+24 status»,
 * «livello GOLD». Le voci DLQ non diventano chip: lo stato «Bloccato» lo dice già.
 */
export function outcomeChips(summary: TraceSummary, currencyChip: (code: string) => string = (c) => c): OutcomeChip[] {
  const chips: OutcomeChip[] = [];
  for (const raw of (summary.outcomeSummary ?? "").split("·")) {
    const part = raw.trim();
    // Il server compone «+» + importo: un importo negativo arriva come «+-180 PTS».
    const pts = part.match(/^\+?(-?\d+)\s+(\S+)$/);
    if (pts && !/DLQ/.test(pts[2])) {
      const n = Number(pts[1]);
      chips.push({ text: `${n > 0 ? "+" : ""}${formatPoints(n)} ${currencyChip(pts[2])}`, tone: n > 0 ? "good" : "neutral" });
      continue;
    }
    const tier = part.match(/^tier\s+(.+)$/i);
    if (tier) chips.push({ text: it.traces.list.tier(tier[1]), tone: "neutral" });
  }
  if (chips.length > 0) return chips;
  if (isTechOnly(summary)) return [{ text: it.traces.list.techOnly, tone: "neutral" }];
  if (summary.status === "IN_PROGRESS") return [{ text: it.traces.list.working, tone: "neutral" }];
  return [{ text: it.traces.list.noPrize, tone: "neutral" }];
}

/** Un tracciato «solo tecnico»: parte da un tipo interno e non dà nulla al membro. */
export function isTechOnly(summary: TraceSummary): boolean {
  return TECH_ONLY_TYPES.has(summary.rootShortType) && !(summary.outcomeSummary ?? "").trim();
}

/** Azione dell'elenco in italiano con il nome leggibile del tipo radice. */
export function rootLabel(shortType: string): string {
  const label = actionLabel(shortType);
  return label !== shortType ? label : eventLabel("FACT", shortType);
}
