import { NextResponse, type NextRequest } from "next/server";
import { problem, type Bff } from "@/lib/auth/bff";
import {
  ACTION_SCOPE,
  ACTION_TYPES,
  ApiError,
  asItems,
  buildActionRow,
  describeError,
  findTestMember,
  STORY_SOURCE_CODE,
  UsageError,
  verifyAudit,
  type Api,
  type ProgramSeed,
} from "./programma-core.mjs";
import seedSnapshot from "./programma-seed.generated.json";
import { guarded, hubApi, json, type GuardPolicy } from "./programma";

// V11 (F2-ING-02, BO-32, ADR-051, Q-675, docs/18 M8.14) — «Invia un'azione» dalla fonte di test, sulla pagina Import.
// SOLO LATO SERVER (registrato in hub/serverOnly.test.ts). Stesse guardie e stesso trasporto di V10 (`guarded`, `hubApi`:
// enterprise → ambiente di test dichiarato → sessione → CSRF → token fresco, operatore), ma per i ruoli dell'import
// (ADMIN e CARE). Costruisce UNA riga NDJSON e la invia con POST /v1/imports (fonte vetrina-test); poi il client
// interroga `?import=<id>` finché l'import è elaborato, i punti sono arrivati e l'audit è verificato.
//
// Sicurezza (regole 18-22): il membro si sceglie per NOME UTENTE tra i tre di test del seed (mai un id del client) e si
// risolve sul servizio; l'id del membro vive solo in memoria e nella riga, mai nelle risposte; il token non esce mai dal
// server; la richiesta passa dalla lista bianca del nucleo (`assertActionImport`); l'attore dell'audit è l'operatore.

const SEED = seedSnapshot as unknown as ProgramSeed;
const STORIES = SEED.vetrinaTest.stories;
/** Tipi inviabili: quelli del nucleo che la fonte di test ammette davvero (mai un tipo fuori dall'elenco della fonte). */
const ALLOWED = new Set<string>((SEED.vetrinaTest.source.allowedTypes as string[]) ?? []);
const ACTIONS = ACTION_TYPES.filter((a) => ALLOWED.has(a.type));

export const ACTION_POLICY: GuardPolicy = {
  roles: ["ADMIN", "CARE"],
  notFound: "L'invio di un'azione esiste solo nell'ambiente di test dichiarato.",
  forbidden: "L'invio di un'azione è riservato agli operatori ADMIN e CARE.",
};

export interface ActionContext {
  /** La fonte `vetrina-test` esiste: senza, il modulo invita a caricare il programma di esempio. */
  ready: boolean;
  members: { key: string; name: string; registered: boolean; tier: string | null }[];
  actions: { type: string; label: string; valued: boolean }[];
}

export type Outcome = "accepted" | "duplicate" | "invalid" | "failed" | "pending";
export interface ActionResult {
  importId: string;
  status: "running" | "done" | "error";
  /** Niente altro da aspettare: import elaborato, punti arrivati (o finestra scaduta) e audit verificato (o dichiarato mancante). */
  final: boolean;
  importStatus: string;
  outcome: Outcome;
  member: { key: string; name: string };
  type: string;
  label: string;
  amount: number | null;
  before: WalletSnapshot | null;
  after: WalletSnapshot | null;
  /**
   * Variazione attribuita a QUESTA azione: somma dei movimenti del libro mastro con `actionId` = id della riga (mai la
   * differenza dei saldi, che conterrebbe anche altro). `null` = nessun movimento (ancora) o libro non leggibile.
   */
  pointsDelta: number | null;
  /** Come `pointsDelta` per i punti di livello (STS). */
  stsDelta: number | null;
  /** Parte dei punti accreditata «in attesa» (campagna con giorni di attesa): non è «nessuna variazione». */
  pendingPoints: number;
  /** `false` = il libro mastro non si è potuto leggere: i punti sono sconosciuti, non «invariati». */
  pointsKnown: boolean;
  /** Solo se il livello prima e dopo sono entrambi noti. */
  tierChanged: boolean;
  audit: { state: "verified" | "pending" | "missing"; actor: string; reason: string | null };
  problems: string[];
  startedAt: string;
}
interface WalletSnapshot {
  points: number;
  pending: number;
  tier: string | null;
}

// ---------------------------------------------------------------------------------------------------------------
// Invii recenti in memoria (come i lavori di V10: si perdono a un riavvio, Q-622)

interface Submission {
  importId: string;
  actor: string;
  username: string;
  memberId: string; // solo in memoria
  type: string;
  amount: number | null;
  before: WalletSnapshot | null;
  startedAt: number;
  finishedAt: number | null;
  /** Id della riga (`vt-act-…`): lega i movimenti del portafoglio a questa azione. */
  rowId: string;
  /** Firma dell'ultima lettura e quante letture consecutive identiche (assestamento: due uguali = nient'altro in arrivo). */
  lastSig: string | null;
  stable: number;
  /** Esito definitivo già dato: libera l'invio successivo dello stesso operatore (O7). */
  final: boolean;
}
const KEY = Symbol.for("io.loyaltyhub.web.vetrina.azione");
type Holder = { [KEY]?: Map<string, Submission> };
const store = (): Map<string, Submission> => ((globalThis as Holder)[KEY] ??= new Map());
export function resetActionsForTests(): void {
  delete (globalThis as Holder)[KEY];
}

// SPEC-GAP: Q-725 (nessuna «Stima: circa N punti» prima dell'invio: servirebbe rieseguire nel web le regole delle campagne;
// l'esito mostra solo la variazione che il portafoglio dichiara dopo l'elaborazione)
/** Dopo l'import elaborato i punti arrivano dal bus: si aspetta al più questo, poi si dichiara «nessuna variazione». */
const SETTLE_MS = 20_000;
/** L'audit viaggia sul bus: dopo questo tempo senza voce è un errore esplicito (regola 21). */
const AUDIT_MS = 60_000;
/** Un invio non definitivo da più di questo tempo non blocca più l'operatore (client chiuso, server riavviato a metà). */
const INFLIGHT_MS = 90_000;
export const actionTiming = { settleMs: SETTLE_MS, auditMs: AUDIT_MS, inflightMs: INFLIGHT_MS };

// ---------------------------------------------------------------------------------------------------------------

async function readWallet(api: Api, memberId: string): Promise<WalletSnapshot | null> {
  try {
    const body = (await api.request("wallet", "GET", `/v1/wallets/${encodeURIComponent(memberId)}`)).body;
    const points = Number(body?.balances?.PTS?.active);
    const pending = Number(body?.balances?.PTS?.pending);
    return {
      points: Number.isFinite(points) ? points : 0,
      pending: Number.isFinite(pending) ? pending : 0,
      tier: typeof body?.tier?.name === "string" ? body.tier.name : null,
    };
  } catch {
    return null; // il portafoglio non è indispensabile: si mostra l'esito dell'import
  }
}

/**
 * Movimenti del libro mastro attribuiti a una riga (`actionId` = id della riga, GET /v1/wallets/{id}/ledger di wallet-service:
 * `amount` positivo e `direction` «+» o «-»). `null` se il libro non si legge.
 */
async function readLedger(api: Api, memberId: string, rowId: string): Promise<{ pts: number; sts: number; entries: number } | null> {
  try {
    const items = asItems((await api.request("wallet", "GET", `/v1/wallets/${encodeURIComponent(memberId)}/ledger`, { query: { limit: "100" } })).body);
    const mine = items.filter((e) => e?.actionId === rowId);
    const sum = (currency: string) =>
      mine.filter((e) => e.currency === currency).reduce((n, e) => n + (e.direction === "-" ? -1 : 1) * Number(e.amount ?? 0), 0);
    return { pts: sum("PTS"), sts: sum("STS"), entries: mine.length };
  } catch {
    return null;
  }
}

async function sourceExists(api: Api): Promise<boolean> {
  const items = asItems((await api.request("ingestion", "GET", "/v1/sources")).body);
  return items.some((s) => s.code === STORY_SOURCE_CODE);
}

const unavailable = () =>
  problem(503, "HUB_UNAVAILABLE", "Servizi non raggiungibili", "Qualche servizio non risponde (forse si sta svegliando): riprova tra poco.");

function hubFailure(e: unknown): NextResponse {
  if (e instanceof ApiError && e.status >= 400 && e.status < 500 && e.status !== 408 && e.status !== 429) {
    return problem(422, "ACTION_REJECTED", "Azione non accettata", `Il servizio ha rifiutato la richiesta (${e.code ?? e.status}).`);
  }
  console.error(`vetrina/azione: richiesta non riuscita (${e instanceof Error ? e.name : "errore"})`);
  return unavailable();
}

/** GET: contesto del modulo (membri di test con livello, azioni, programma presente) oppure `?import=<id>` per l'esito. */
export function handleGet(req: NextRequest, doFetch?: typeof fetch): Promise<NextResponse> {
  return guarded(
    req,
    async ({ bff, sessionId, actor }) => {
      const importId = req.nextUrl.searchParams.get("import");
      const api = hubApi(bff, sessionId, doFetch, ACTION_SCOPE);
      try {
        if (importId !== null) return await status(api, actor, importId);
        const ready = await sourceExists(api);
        const members = await Promise.all(
          STORIES.map(async (s) => {
            const id = await findTestMember(api, s);
            const wallet = id ? await readWallet(api, id) : null;
            return { key: s.username, name: s.name, registered: id !== null, tier: wallet?.tier ?? null };
          }),
        );
        const body: ActionContext = { ready, members, actions: ACTIONS.map(({ type, label, valued }) => ({ type, label, valued })) };
        return json(body);
      } catch (e) {
        return hubFailure(e);
      }
    },
    ACTION_POLICY,
  );
}

/** POST `{username, type, amount?}`: costruisce la riga e crea l'import. 202 con `{importId}`. */
export function handlePost(req: NextRequest, doFetch?: typeof fetch): Promise<NextResponse> {
  return guarded(
    req,
    async ({ bff, sessionId, actor }) => {
      let body: Record<string, unknown> | null;
      try {
        body = (await req.json()) as Record<string, unknown> | null;
      } catch {
        body = null;
      }
      // Codice per ogni causa: il client mostra il messaggio giusto (membro, azione, importo).
      const bad = (code: string, detail: string) => problem(400, code, "Richiesta non valida", detail);
      if (!body || typeof body !== "object" || Array.isArray(body)) return bad("BAD_REQUEST", "Indica membro, azione ed eventuale importo.");
      // Solo questi campi: un id di membro (o qualunque altro campo) dal client non è mai ammesso.
      if (Object.keys(body).some((k) => k !== "username" && k !== "type" && k !== "amount")) return bad("BAD_REQUEST", "Campi non ammessi nella richiesta.");
      const story = STORIES.find((s) => s.username === body.username);
      if (!story) return bad("INVALID_MEMBER", "Il membro non è tra quelli di test.");
      const def = ACTIONS.find((a) => a.type === body.type);
      if (!def) return bad("INVALID_TYPE", "Azione non inviabile dalla fonte di test.");
      // Un invio alla volta per operatore (O7): finché il precedente non ha l'esito definitivo, 409.
      const running = [...store().values()].find((x) => x.actor === actor && !x.final && Date.now() - x.startedAt < actionTiming.inflightMs);
      if (running) {
        return problem(409, "SEND_RUNNING", "Invio già in corso", "Un invio è ancora in elaborazione: aspetta il suo esito.", { importId: running.importId });
      }

      const api = hubApi(bff, sessionId, doFetch, ACTION_SCOPE);
      try {
        if (!(await sourceExists(api))) {
          return problem(409, "PROGRAM_MISSING", "Programma di esempio mancante", "Carica prima il programma di esempio dalla Dashboard.");
        }
        const memberId = await findTestMember(api, story);
        if (!memberId) return problem(422, "MEMBER_NOT_REGISTERED", "Membro non registrato", "Il membro di test non è ancora registrato: accedi al portale con il suo account.");
        const startedAt = Date.now();
        const uid = crypto.randomUUID().replaceAll("-", "");
        let built;
        try {
          built = buildActionRow({ type: def.type, memberId, username: story.username, amount: body.amount, now: startedAt, uid });
        } catch (e) {
          if (e instanceof UsageError) return bad("INVALID_AMOUNT", e.message);
          throw e;
        }
        api.storySubjects = new Set([memberId]);
        const before = await readWallet(api, memberId);
        const job = (
          await api.request("ingestion", "POST", "/v1/imports", {
            multipart: { file: built.file, fields: { kind: "EVENTS", source: STORY_SOURCE_CODE } },
            headers: { "Idempotency-Key": `vt-act-${crypto.randomUUID()}` },
          })
        ).body;
        if (typeof job?.id !== "string") throw new Error("risposta dell'import non riconosciuta");
        const sub = store();
        sub.set(job.id, {
          importId: job.id, actor, username: story.username, memberId, type: def.type,
          amount: def.valued ? (built.row.data.amount as number) : null, before, startedAt, finishedAt: null,
          rowId: built.row.id as string, lastSig: null, stable: 0, final: false,
        });
        for (const k of [...sub.keys()].slice(0, Math.max(0, sub.size - 20))) sub.delete(k);
        return json({ importId: job.id }, 202);
      } catch (e) {
        if (e instanceof Error && /non ammesso/.test(e.message)) return bad("BAD_REQUEST", e.message);
        return hubFailure(e);
      }
    },
    ACTION_POLICY,
  );
}

/** Esito di un invio: lo legge solo l'operatore che lo ha fatto. */
async function status(api: Api, actor: string, importId: string): Promise<NextResponse> {
  const sub = store().get(importId);
  if (!sub || sub.actor !== actor) {
    return problem(404, "IMPORT_NOT_FOUND", "Invio sconosciuto", "L'invio non esiste più (il server è stato riavviato): apri l'elenco degli import.");
  }
  const story = STORIES.find((s) => s.username === sub.username);
  const label = ACTIONS.find((a) => a.type === sub.type)?.label ?? sub.type;
  const base = {
    importId, member: { key: sub.username, name: story?.name ?? sub.username }, type: sub.type, label, amount: sub.amount,
    before: sub.before, startedAt: new Date(sub.startedAt).toISOString(),
  };
  let detail: Record<string, any> | null = null;
  try {
    detail = (await api.request("ingestion", "GET", `/v1/imports/${encodeURIComponent(importId)}`)).body?.job ?? null;
  } catch (e) {
    return hubFailure(e);
  }
  const importStatus = String(detail?.status ?? "QUEUED");
  if (importStatus !== "DONE" && importStatus !== "FAILED") {
    const out: ActionResult = {
      ...base, status: "running", final: false, importStatus, outcome: "pending", after: null, pointsDelta: null, stsDelta: null,
      pendingPoints: 0, pointsKnown: true, tierChanged: false,
      audit: { state: "pending", actor, reason: null }, problems: [],
    };
    return json(out);
  }
  sub.finishedAt ??= Date.now();
  const counts = (detail?.counts ?? {}) as Record<string, number>;
  const rejected = (counts.rejected ?? 0) + (counts.invalid ?? 0) + (counts.unmatched ?? 0);
  const outcome: Outcome = importStatus === "FAILED" ? "failed" : (counts.accepted ?? 0) > 0 ? "accepted" : (counts.duplicate ?? 0) > 0 ? "duplicate" : rejected > 0 ? "invalid" : "failed";
  const problems: string[] = [];
  if (outcome === "failed") problems.push(detail?.errorDetail ? String(detail.errorDetail).slice(0, 200) : "L'import non è andato a buon fine.");
  if (outcome === "invalid") problems.push("La riga non è stata accettata (rifiutata, non valida o con membro non abbinato): apri il dettaglio dell'import per il rapporto.");

  let after: WalletSnapshot | null = null;
  let pointsDelta: number | null = null;
  let stsDelta: number | null = null;
  let pendingPoints = 0;
  let pointsKnown = true;
  let tierChanged = false;
  let settled = true;
  if (outcome === "accepted") {
    // Letture fresche a ogni richiesta: livello dal portafoglio di adesso, movimenti dal libro mastro (per actionId).
    after = await readWallet(api, sub.memberId);
    const ledger = await readLedger(api, sub.memberId, sub.rowId);
    pointsKnown = ledger !== null;
    if (ledger && ledger.entries > 0) {
      pointsDelta = ledger.pts;
      stsDelta = ledger.sts;
    }
    if (pointsDelta !== null && pointsDelta > 0 && after && sub.before) pendingPoints = Math.max(0, Math.min(pointsDelta, after.pending - sub.before.pending));
    tierChanged = !!after?.tier && !!sub.before?.tier && after.tier !== sub.before.tier;
    // Assestamento: più effetti (punti, STS, livello) possono arrivare in momenti diversi. Si chiude solo con due letture
    // consecutive identiche (la prima non basta) o a finestra scaduta; un libro non leggibile non si aspetta.
    const sig = JSON.stringify([ledger?.entries ?? -1, ledger?.pts ?? 0, ledger?.sts ?? 0, after?.tier ?? null]);
    sub.stable = sig === sub.lastSig ? sub.stable + 1 : 0;
    sub.lastSig = sig;
    const expired = Date.now() - (sub.finishedAt ?? 0) > actionTiming.settleMs;
    settled = !pointsKnown || (ledger !== null && ledger.entries > 0 && sub.stable >= 1) || expired;
  }

  // Audit: la voce CREATE dell'import, a nome dell'operatore (regola 21). Una sola lettura per richiesta: ritenta il client.
  const checked = await verifyAudit({
    api, items: [{ entity: "import", service: "ingestion", key: importId, action: "create", audit: { type: "import", ids: [importId], actions: ["CREATE"] } }],
    since: new Date(sub.startedAt - 120000), expectedActor: actor, timeoutSec: 0, intervalSec: 1, sleep: async () => undefined,
  }).catch((e: unknown) => ({ verified: 0, problems: [{ id: "import", reason: describeError(e) }] }));
  const expired = Date.now() - sub.startedAt > actionTiming.auditMs;
  const audit: ActionResult["audit"] =
    checked.verified > 0 ? { state: "verified", actor, reason: null }
    : expired ? { state: "missing", actor, reason: checked.problems[0]?.reason ?? "voce di audit mancante" }
    : { state: "pending", actor, reason: null };
  if (audit.state === "missing") problems.push(`Audit: ${audit.reason}.`);

  const out: ActionResult = {
    ...base, status: problems.length ? "error" : "done", final: settled && audit.state !== "pending",
    importStatus, outcome, after, pointsDelta, stsDelta, pendingPoints, pointsKnown, tierChanged, audit, problems,
  };
  if (out.final) sub.final = true;
  return json(out);
}
