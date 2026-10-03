import { NextResponse, type NextRequest } from "next/server";
import { serviceBaseUrl, type ServiceCode } from "@/lib/api/services";
import { enterpriseOnly, problem, type Bff } from "@/lib/auth/bff";
import { checkCsrf } from "@/lib/auth/csrf";
import { csrfRejected } from "@/lib/auth/handlers";
import { UNAUTHENTICATED } from "@/lib/auth/proxyAuth";
import { freshSession, IdpUnavailableError } from "@/lib/auth/refresh";
import { testMode } from "@/lib/hub/testMode";
import {
  Api,
  applyPlan,
  applyStories,
  describeError,
  fetchTransport,
  PROGRAM_SCOPE,
  PROGRAM_SERVICES,
  type Scope as ApiScope,
  planProgram,
  summarizeProgram,
  verifyAudit,
  type PlanItem,
  type ProgramSeed,
  type ProgramSummary,
} from "./programma-core.mjs";
import seedSnapshot from "./programma-seed.generated.json";

// V10 (F2-DIST-09, ADR-051, Q-617, Q-722, Q-723, docs/18 M8.14) — «Carica il programma di esempio» dal backoffice.
// SOLO LATO SERVER (registrato in hub/serverOnly.test.ts). La logica del piano è del nucleo puro programma-core.mjs, lo
// stesso della riga di comando; qui ci sono le guardie della route, il token dell'operatore e il lavoro in memoria.
//
// Sicurezza (regole 18-22):
//  - la route esiste solo nel profilo enterprise e solo nell'ambiente di test dichiarato (404 altrimenti), per ADMIN;
//  - POST con CSRF come il proxy; il token dell'operatore si rilegge con `freshSession` a OGNI chiamata all'hub (l'access
//    token dura 5 minuti e il lavoro può durare di più) e non si tiene mai in una variabile del lavoro;
//  - nessun token, nessun identificativo di membro e nessuna e-mail nelle risposte né nei log;
//  - le scritture di configurazione passano dalla lista bianca del nucleo (corpi senza /MBR-\d/); le storie hanno la
//    loro (`assertStoryImport`); campagne e premi nascono DRAFT e non si inviano né approvano (regola 22);
//  - ogni scrittura è verificata su GET /v1/audit con l'attore reale = nome utente della sessione (regola 21).

const SEED = seedSnapshot as unknown as ProgramSeed;

export type Scope = "program" | "stories";
export type JobPhase = "planning" | "config" | "import" | "audit" | "done";

export interface JobView {
  id: string;
  scope: Scope;
  status: "running" | "done" | "error";
  phase: JobPhase;
  done: number;
  total: number;
  /** Cosa sta facendo, in italiano (es. «premi in bozza»). */
  message: string;
  actor: string;
  startedAt: string;
  finishedAt: string | null;
  result: JobResult | null;
}

export interface JobResult {
  created: number;
  already: number;
  failed: number;
  auditVerified: number;
  auditTotal: number;
  rewardsDraft: number;
  campaignsDraft: number;
  /** Righe dell'import delle storie (0 se non è stato caricato). */
  storyRows: number;
  /** Motivi leggibili di un errore: voci non create, audit mancante, righe non accettate. */
  problems: string[];
}

export type BoxState = "pending" | "stories-ready" | "stories-waiting" | "running" | "complete";

export interface PreviewView {
  summary: ProgramSummary;
  box: BoxState;
  job: JobView | null;
  lastRun: { finishedAt: string; by: string; scope: Scope } | null;
}

// ---------------------------------------------------------------------------------------------------------------
// Lavoro in memoria (un solo lavoro alla volta per processo, Q-622: niente SSE, il client interroga ogni 1,5 s)

interface Job extends JobView {
  /** Il lavoro non conserva token: solo l'id di sessione, con cui si rilegge un token fresco a ogni chiamata. */
  sessionId: string;
}
interface JobStore {
  current: Job | null;
  recent: Job[];
  lastRun: PreviewView["lastRun"];
}

const JOBS_KEY = Symbol.for("io.loyaltyhub.web.vetrina.programma");
type Holder = { [JOBS_KEY]?: JobStore };

function jobStore(): JobStore {
  const holder = globalThis as Holder;
  return (holder[JOBS_KEY] ??= { current: null, recent: [], lastRun: null });
}

/** Vista pubblica del lavoro: nessun id di sessione, nessun token. */
const view = (job: Job): JobView => ({
  id: job.id, scope: job.scope, status: job.status, phase: job.phase, done: job.done, total: job.total, message: job.message,
  actor: job.actor, startedAt: job.startedAt, finishedAt: job.finishedAt, result: job.result,
});

/** Solo per i test: azzera il lavoro in memoria. */
export function resetJobsForTests(): void {
  const holder = globalThis as Holder;
  delete holder[JOBS_KEY];
  previewCache.clear();
}

// ---------------------------------------------------------------------------------------------------------------
// Chiamate all'hub con il token dell'operatore

/** L'IdP non risponde: nome proprio, così `describeError` del nucleo riporta il testo italiano e non un errore di rete. */
class IdpUnavailableNowError extends Error {
  constructor() {
    super("accesso momentaneamente non verificabile: riprova tra poco");
    this.name = "IdpUnavailableNowError";
  }
}

class SessionGoneError extends Error {
  constructor() {
    super("la sessione dell'operatore è scaduta: accedi di nuovo e riprova");
    this.name = "SessionGoneError";
  }
}

/** Client dell'hub con token fresco a ogni richiesta (single-flight del rinnovo in `freshSession`). */
export function hubApi(bff: Bff, sessionId: string, doFetch: typeof fetch = fetch, scope: ApiScope = PROGRAM_SCOPE): Api {
  const targets = Object.fromEntries(PROGRAM_SERVICES.map((s) => [s, serviceBaseUrl(s as ServiceCode)]));
  const transport = fetchTransport({
    targets,
    fetch: doFetch,
    getToken: async () => {
      let session;
      try {
        session = await freshSession(sessionId, { store: bff.store, oidc: bff.oidc, inflight: bff.inflight });
      } catch (err) {
        if (err instanceof IdpUnavailableError) throw new IdpUnavailableNowError();
        throw err;
      }
      if (!session) throw new SessionGoneError();
      return session.tokens.accessToken;
    },
  });
  return new Api({ transport, scope });
}

/** Attesa delle voci di audit (l'audit viaggia sul bus). Modificabile solo dai test. */
const auditTiming = { timeoutSec: 60, intervalSec: 2 };
export function setAuditTimingForTests(timeoutSec: number, intervalSec: number): void {
  auditTiming.timeoutSec = timeoutSec;
  auditTiming.intervalSec = intervalSec;
}

function setPhase(job: Job, phase: JobPhase, message: string, done = 0, total = 0): void {
  job.phase = phase;
  job.message = message;
  job.done = done;
  job.total = total;
}

const LABELS: Record<string, string> = {
  rewards: "premi in bozza",
  campaigns: "campagne in bozza",
  "event-types": "tipi di azione",
  sources: "fonte di test",
  segments: "segmenti",
  badges: "badge",
  achievements: "obiettivi",
  leaderboards: "classifiche",
  "reward-categories": "categorie premi",
  "reward-bands": "fasce premi",
  "coupon-pools": "pool coupon",
  "message-templates": "messaggi",
  "notification-rules": "regole di notifica",
  "attribute-definitions": "attributi",
  theme: "tema",
};

/** Il lavoro vero e proprio. Non lancia mai: ogni errore finisce nel lavoro (`status: "error"`, `problems`). */
export async function runJob(job: Job, api: Api, opts: { sleep?: (ms: number) => Promise<void>; now?: () => number; auditTimeoutSec?: number } = {}): Promise<void> {
  const now = opts.now ?? Date.now;
  const started = now();
  const result: JobResult = {
    created: 0, already: 0, failed: 0, auditVerified: 0, auditTotal: 0, rewardsDraft: 0, campaignsDraft: 0, storyRows: 0, problems: [],
  };
  const sleep = opts.sleep ?? ((ms: number) => new Promise<void>((r) => setTimeout(r, ms)));
  const audit = (items: PlanItem[]) =>
    verifyAudit({
      api, items, since: new Date(started - 120000), expectedActor: job.actor,
      timeoutSec: opts.auditTimeoutSec ?? auditTiming.timeoutSec, intervalSec: auditTiming.intervalSec, sleep,
    });
  try {
    setPhase(job, "planning", "Leggo lo stato dell'installazione");
    const planned = await planProgram({ seed: SEED, api, now: started });
    const readErrors = planned.plan.filter((i) => i.action === "error");
    if (readErrors.some((i) => /SessionGoneError/.test(String(i.reason)))) throw new SessionGoneError();
    if (readErrors.length) {
      const first = readErrors.slice(0, 3).map((i) => `${i.entity}: ${i.reason}`).join("; ");
      throw new Error(`lettura dello stato non riuscita (${first}${readErrors.length > 3 ? `; altre ${readErrors.length - 3}` : ""})`);
    }

    const toAudit: PlanItem[] = [];
    if (job.scope === "program") {
      const total = planned.plan.filter((i) => i.action === "create").length;
      setPhase(job, "config", "Preparo le scritture", 0, total);
      const results = await applyPlan({
        plan: planned.plan, ctx: planned.ctx, api,
        onProgress: ({ done, total: t, item }) => setPhase(job, "config", LABELS[item.entity] ?? item.entity, done, t),
      });
      const created = results.filter((r) => r.result === "created");
      const failed = results.filter((r) => r.result === "failed");
      result.created = created.length;
      result.already = results.filter((r) => r.result === "already").length;
      result.failed = failed.length;
      result.rewardsDraft = created.filter((r) => r.entity === "rewards").length;
      result.campaignsDraft = created.filter((r) => r.entity === "campaigns").length;
      for (const f of failed) result.problems.push(`${f.entity} ${f.key}: ${f.error}`);
      const skippedDeps = planned.plan.filter((i) => i.action === "skip" && i.reason?.startsWith("dipendenza non creata"));
      for (const s of skippedDeps) result.problems.push(`${s.entity} ${s.key}: ${s.reason}`);
      toAudit.push(...created);
    } else {
      if (planned.stories.state !== "ready" && planned.stories.state !== "running") {
        throw new Error(`le storie non si possono caricare ora: ${planned.stories.reasons.join("; ") || planned.stories.state}`);
      }
      setPhase(job, "import", "Carico le storie dalla fonte di test", 0, planned.stories.rows);
      const imported = await applyStories({
        seed: SEED, api, stories: planned.stories, now: started,
        onProgress: ({ done, total }) => setPhase(job, "import", "Carico le storie dalla fonte di test", done, total),
        sleep,
      });
      result.storyRows = imported.rows;
      result.problems.push(...imported.problems);
      if (!imported.reused) {
        toAudit.push({ entity: "import", service: "ingestion", key: imported.jobId, action: "create", audit: { type: "import", ids: [imported.jobId], actions: ["CREATE"] } });
        result.created = 1;
      } else {
        result.already = 1;
      }
    }

    if (toAudit.length) {
      setPhase(job, "audit", "Controllo l'audit", 0, toAudit.length);
      const checked = await audit(toAudit);
      result.auditVerified = checked.verified;
      result.auditTotal = toAudit.length;
      for (const p of checked.problems) result.problems.push(`${p.id}: ${p.reason ?? "voce di audit mancante"}`);
    }
    job.result = result;
    job.status = result.problems.length ? "error" : "done";
    setPhase(job, "done", job.status === "done" ? "Fatto" : failedMessage(job.scope, result), result.created, result.created + result.failed);
  } catch (e) {
    result.problems.push(messageOf(e));
    job.result = result;
    job.status = "error";
    setPhase(job, "done", failedMessage(job.scope, result), result.created, result.created + result.failed);
  }
  job.finishedAt = new Date(now()).toISOString();
}

/** Messaggio di un messaggio d'errore senza dettagli di rete: i nostri errori hanno già testo italiano. */
function messageOf(e: unknown): string {
  if (e instanceof SessionGoneError) return e.message;
  if (e instanceof Error && e.message) return e.message.slice(0, 300);
  return describeError(e);
}

function failedMessage(scope: Scope, r: JobResult): string {
  if (scope === "program" && r.failed > 0) return `Caricati ${r.created} di ${r.created + r.failed}. Riprova per completare.`;
  return "Non completato. Riprova: si crea solo ciò che manca.";
}

function startJob(bff: Bff, sessionId: string, actor: string, scope: Scope, doFetch?: typeof fetch): Job | { running: Job } {
  const store = jobStore();
  if (store.current?.status === "running") return { running: store.current };
  const job: Job = {
    id: crypto.randomUUID(), scope, status: "running", phase: "planning", done: 0, total: 0, message: "Avvio", actor,
    startedAt: new Date().toISOString(), finishedAt: null, result: null, sessionId,
  };
  previewCache.clear();
  store.current = job;
  store.recent = [job, ...store.recent].slice(0, 5);
  void runJob(job, hubApi(bff, sessionId, doFetch)).then(() => {
    previewCache.clear();
    if (job.status === "done") store.lastRun = { finishedAt: job.finishedAt ?? new Date().toISOString(), by: job.actor, scope: job.scope };
  });
  return job;
}

// ---------------------------------------------------------------------------------------------------------------
// Anteprima

export function boxState(summary: ProgramSummary, job: JobView | null): BoxState {
  if (job?.status === "running" || summary.stories.state === "running") return "running";
  if (summary.create > 0) return "pending";
  if (summary.stories.state === "ready") return "stories-ready";
  if (summary.stories.state === "present") return "complete";
  return "stories-waiting";
}

// SPEC-GAP: Q-722 («caricato da / il» e l'elenco dei lavori vengono dalla memoria del processo e si perdono a ogni riavvio del web)
const PREVIEW_TTL_MS = 5000;
type PreviewEntry = { at: number; actor: string; value: Promise<PreviewView | null> };
const previewCache = new Map<string, PreviewEntry>();

/** Anteprima con cache di ~5 s per sessione e raggruppamento delle richieste concorrenti (leggere costa una decina di chiamate). */
function cachedPreview(bff: Bff, sessionId: string, actor: string, doFetch?: typeof fetch): Promise<PreviewView | null> {
  const hit = previewCache.get(sessionId);
  if (hit && hit.actor === actor && Date.now() - hit.at < PREVIEW_TTL_MS) return hit.value;
  const value = buildPreview(bff, sessionId, actor, doFetch);
  const entry: PreviewEntry = { at: Date.now(), actor, value };
  previewCache.set(sessionId, entry);
  if (previewCache.size > 50) for (const [k, v] of previewCache) if (Date.now() - v.at > PREVIEW_TTL_MS) previewCache.delete(k);
  // Un esito vuoto o fallito non si tiene: il prossimo tentativo rilegge.
  const drop = () => { if (previewCache.get(sessionId) === entry) previewCache.delete(sessionId); };
  value.then((v) => { if (!v) drop(); }, drop);
  return value;
}

export function clearPreviewCacheForTests(): void {
  previewCache.clear();
}

async function buildPreview(bff: Bff, sessionId: string, actor: string, doFetch?: typeof fetch): Promise<PreviewView | null> {
  const api = hubApi(bff, sessionId, doFetch);
  const planned = await planProgram({ seed: SEED, api, now: Date.now() });
  const summary = summarizeProgram({ plan: planned.plan as PlanItem[], stories: planned.stories, actor });
  if (summary.errors > 0 || planned.stories.state === "error") return null;
  const store = jobStore();
  const job = store.current ? view(store.current) : null;
  return { summary, box: boxState(summary, job), job: job?.status === "running" ? job : null, lastRun: store.lastRun };
}

// ---------------------------------------------------------------------------------------------------------------
// Route

export type Guarded = { bff: Bff; sessionId: string; actor: string };

/** Chi può usare la route e i testi dei rifiuti (V10: solo ADMIN; V11 «Invia un'azione»: ADMIN e CARE, i ruoli dell'import). */
export interface GuardPolicy {
  roles: readonly string[];
  notFound: string;
  forbidden: string;
}
const ADMIN_ONLY: GuardPolicy = {
  roles: ["ADMIN"],
  notFound: "Il programma di esempio esiste solo nell'ambiente di test dichiarato.",
  forbidden: "Il programma di esempio lo carica un operatore ADMIN.",
};

/**
 * Guardie in ordine: profilo enterprise → ambiente di test dichiarato (404) → sessione (401) → CSRF sul POST (403) →
 * token fresco, operatore ADMIN (403). Mai in cache.
 */
export async function guarded(req: NextRequest, run: (g: Guarded) => Promise<NextResponse>, policy: GuardPolicy = ADMIN_ONLY): Promise<NextResponse> {
  const res = await enterpriseOnly(async (bff) => {
    if (testMode() === null) {
      return problem(404, "NOT_FOUND", "Non disponibile", policy.notFound);
    }
    const sessionId = req.cookies.get(bff.cookies.session)?.value;
    if (!sessionId) return problem(401, UNAUTHENTICATED, "Accesso richiesto", "La sessione è scaduta o assente: accedi di nuovo.");
    if (checkCsrf(req, bff.cfg.publicUrl.origin, { id: sessionId, csrfKey: bff.csrfKey })) return csrfRejected();
    let session;
    try {
      session = await freshSession(sessionId, { store: bff.store, oidc: bff.oidc, inflight: bff.inflight });
    } catch (err) {
      if (!(err instanceof IdpUnavailableError)) throw err;
      return problem(503, "IDP_UNAVAILABLE", "Accesso momentaneamente non verificabile", "Il servizio di accesso non risponde: riprova tra poco.");
    }
    if (!session) return problem(401, UNAUTHENTICATED, "Accesso richiesto", "La sessione è scaduta o assente: accedi di nuovo.");
    if (session.user.kind !== "operator" || !policy.roles.includes(session.user.role)) {
      return problem(403, "FORBIDDEN_ROLE", "Operazione non consentita", policy.forbidden);
    }
    return run({ bff, sessionId, actor: session.user.username });
  });
  res.headers.set("cache-control", "no-store");
  return res;
}

export function json(body: unknown, status = 200): NextResponse {
  const res = NextResponse.json(body, { status });
  res.headers.set("cache-control", "no-store");
  return res;
}

/** GET: anteprima (piano: da creare, presenti, esclusi) oppure `?job=<id>` per lo stato di un lavoro. */
export function handleGet(req: NextRequest, doFetch?: typeof fetch): Promise<NextResponse> {
  return guarded(req, async ({ bff, sessionId, actor }) => {
    const jobId = req.nextUrl.searchParams.get("job");
    if (jobId !== null) {
      // Lo stato di un lavoro lo legge solo chi l'ha avviato (l'id restituito dal 409 non dà accesso al resto).
      const found = jobStore().recent.find((j) => j.id === jobId && j.actor === actor);
      return found ? json(view(found)) : problem(404, "JOB_NOT_FOUND", "Lavoro sconosciuto", "Il lavoro non esiste più (il server è stato riavviato): ricontrolla lo stato.");
    }
    try {
      const preview = await cachedPreview(bff, sessionId, actor, doFetch);
      if (!preview) {
        return problem(503, "HUB_UNAVAILABLE", "Servizi non raggiungibili", "Qualche servizio non risponde (forse si sta svegliando): riprova tra poco.");
      }
      return json(preview);
    } catch (e) {
      console.error(`vetrina/programma: anteprima non riuscita (${e instanceof Error ? e.name : "errore"})`);
      return problem(503, "HUB_UNAVAILABLE", "Servizi non raggiungibili", "Qualche servizio non risponde (forse si sta svegliando): riprova tra poco.");
    }
  });
}

/** POST `{scope}`: avvia il lavoro. 202 con l'id; con un lavoro già in corso 409 con l'id di quello in corso. */
export function handlePost(req: NextRequest, doFetch?: typeof fetch): Promise<NextResponse> {
  return guarded(req, async ({ bff, sessionId, actor }) => {
    let scope: unknown;
    try {
      scope = ((await req.json()) as { scope?: unknown } | null)?.scope;
    } catch {
      scope = undefined;
    }
    if (scope !== "program" && scope !== "stories") {
      return problem(400, "BAD_REQUEST", "Richiesta non valida", "Indica cosa caricare: scope «program» o «stories».");
    }
    const started = startJob(bff, sessionId, actor, scope, doFetch);
    if ("running" in started) {
      return problem(409, "JOB_RUNNING", "Caricamento già in corso", "Un caricamento è già in corso: aspetta che finisca.", { jobId: started.running.id });
    }
    return json({ jobId: started.id }, 202);
  });
}
