import { NextResponse, type NextRequest } from "next/server";
import { isEnterprise } from "@/lib/auth/config";
import { problem } from "@/lib/auth/bff";
import type { Checks } from "@/lib/hub/readiness";
import { forwardDomain, forwardedHosts, probeVetrina } from "@/lib/hub/vetrinaProbe";

// HUB-01 — accensione della vetrina dal browser (Q-674, ADR-051 decisione 9). SOLO LATO SERVER.
// Route del BFF nel profilo `demo` (Vercel) che legge e avvia il codespace della vetrina tramite l'API di GitHub.
// - Esiste solo con `LH_VETRINA_CODESPACE` e `LH_VETRINA_GITHUB_TOKEN` entrambe impostate (altrimenti 404).
// - Destinazioni in uscita: `https://api.github.com` (Q-674) e, solo a codespace `available`, i due indirizzi inoltrati
//   del codespace stesso (`<nome>-8000.<dominio>` e `<nome>-8001.<dominio>`, Q-728): vedi lib/hub/vetrinaProbe.ts.
//   Nessun dato dal browser oltre all'azione.
// - Il token (fine-grained, solo il repository Loyalty_Sys, «Codespaces» in lettura per leggere lo stato e «Codespaces
//   lifecycle admin» in scrittura per avviare, nient'altro, scadenza al massimo un anno; Q-674, Q-720) non lascia mai il
//   processo: né nelle risposte né nei log (regola 20); neppure il corpo grezzo di GitHub, solo `{state, url}`.
// - `POST` con controllo dell'origine e al più un avvio ogni 60 s per processo (Q-674); dentro quella finestra, se GitHub
//   ancora dice «spento», si risponde «in accensione» (l'avvio è partito, GitHub lo registra con qualche secondo di ritardo).
// - Lo stato letto da GitHub sta in una cache di processo di 5 s (`{state, url}`): più visitatori e il polling del client
//   non esauriscono il limite di richieste del token. Si svuota dopo un avvio riuscito.
// - Con il codespace `available` il `GET` aggiunge `checks`: le nove risorse della vetrina (`ok`/`pending`, Q-728) lette
//   dalla sonda di lib/hub/vetrinaProbe.ts, anch'esse in cache 5 s. Mai corpi grezzi, indirizzi o intestazioni.

export const GITHUB_API = "https://api.github.com";
export const GITHUB_TIMEOUT_MS = 10_000;
export const START_WINDOW_MS = 60_000;
export const CACHE_MS = 5_000;

export type CodespaceState = "available" | "starting" | "shutdown" | "unknown";

export interface CodespaceView {
  state: CodespaceState;
  /** Indirizzo web del codespace (`*.github.dev`) se GitHub lo dà e passa la validazione, altrimenti `null`. */
  url: string | null;
}

/** Risposta del `GET`: lo stato di GitHub più, a codespace `available`, le risorse della vetrina (Q-728). */
export interface CodespaceReading extends CodespaceView {
  checks?: Checks;
}

export type Env = Readonly<Record<string, string | undefined>>;

/** Nome di un codespace: lettere minuscole, cifre e trattini; mai un carattere che possa uscire dal percorso dell'URL. */
const NAME_RE = /^[a-z0-9][a-z0-9-]{1,98}[a-z0-9]$/;

let nameWarned = false;

/**
 * Configurazione della route: `null` nel profilo enterprise e quando manca la variabile o il token. Un nome non valido
 * vale come assente (un solo avviso per processo, senza il valore). Mai il token in log.
 */
export function codespaceConfig(env: Env): { name: string; token: string } | null {
  if (isEnterprise(env)) return null;
  const name = (env.LH_VETRINA_CODESPACE ?? "").trim();
  const token = (env.LH_VETRINA_GITHUB_TOKEN ?? "").trim();
  if (name === "" || token === "") return null;
  if (!NAME_RE.test(name)) {
    if (!nameWarned) {
      nameWarned = true;
      console.warn("LH_VETRINA_CODESPACE ignorata: serve il nome di un codespace (minuscole, cifre, trattini).");
    }
    return null;
  }
  return { name, token };
}

/** Vero se la route è attiva: serve al server component per scegliere tra il pulsante e la nota «su richiesta». */
export function codespaceConfigured(env: Env): boolean {
  return codespaceConfig(env) !== null;
}

/** Stati di GitHub (`Available`, `Starting`, `Provisioning`, `Queued`, `Rebuilding`, `Awaiting…`, `Shutdown`…) in 4 valori. */
export function mapState(raw: unknown): CodespaceState {
  if (typeof raw !== "string") return "unknown";
  const s = raw.toLowerCase();
  if (s === "available") return "available";
  if (["starting", "provisioning", "queued", "rebuilding", "created", "updating"].includes(s) || s.startsWith("awaiting")) {
    return "starting";
  }
  if (["shutdown", "shuttingdown", "archived", "unavailable"].includes(s)) return "shutdown";
  return "unknown";
}

class GithubError extends Error {
  constructor(readonly status: number | null) {
    super("richiesta a GitHub non riuscita");
    this.name = "GithubError";
  }
}

function webUrl(raw: unknown): string | null {
  if (typeof raw !== "string") return null;
  try {
    const u = new URL(raw);
    return u.protocol === "https:" && u.hostname.endsWith(".github.dev") && !u.username && !u.password ? u.origin : null;
  } catch {
    return null;
  }
}

async function github(
  fetchImpl: typeof fetch,
  token: string,
  method: "GET" | "POST",
  path: string,
): Promise<unknown> {
  let res: Response;
  try {
    res = await fetchImpl(`${GITHUB_API}${path}`, {
      method,
      headers: {
        Authorization: `Bearer ${token}`,
        Accept: "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
      },
      cache: "no-store",
      redirect: "error",
      signal: AbortSignal.timeout(GITHUB_TIMEOUT_MS),
    });
  } catch {
    throw new GithubError(null);
  }
  if (!res.ok) throw new GithubError(res.status);
  return res.json().catch(() => null);
}

function viewOf(body: unknown): CodespaceView {
  const o = body !== null && typeof body === "object" ? (body as { state?: unknown; web_url?: unknown }) : {};
  return { state: mapState(o.state), url: webUrl(o.web_url) };
}

export interface HandlerDeps {
  env?: Env;
  fetchImpl?: typeof fetch;
  now?: () => number;
}

let lastStartAt: number | null = null;
let cache: { name: string; at: number; view: CodespaceView } | null = null;

let checksCache: { key: string; at: number; checks: Checks } | null = null;

/** Solo per i test: azzera la finestra dei 60 s e le cache dello stato e delle risorse. */
export function resetStartWindow(): void {
  lastStartAt = null;
  cache = null;
  checksCache = null;
}

/** Risorse della vetrina, dalla cache se ha meno di 5 s (stesso criterio dello stato di GitHub). Non lancia mai. */
async function readChecks(fetchImpl: typeof fetch, cfg: { name: string }, env: Env, now: number): Promise<Checks> {
  const hosts = forwardedHosts(cfg.name, forwardDomain(env));
  const key = hosts === null ? `${cfg.name}|-` : `${hosts.web}|${hosts.idp}`;
  if (checksCache !== null && checksCache.key === key && now >= checksCache.at && now - checksCache.at < CACHE_MS) {
    return checksCache.checks;
  }
  const checks = await probeVetrina(fetchImpl, hosts);
  checksCache = { key, at: now, checks };
  return checks;
}

/** Stato di GitHub, dalla cache se ha meno di 5 s (mai una risposta d'errore in cache). */
async function readView(fetchImpl: typeof fetch, cfg: { name: string; token: string }, now: number): Promise<CodespaceView> {
  if (cache !== null && cache.name === cfg.name && now >= cache.at && now - cache.at < CACHE_MS) return cache.view;
  const view = viewOf(await github(fetchImpl, cfg.token, "GET", `/user/codespaces/${cfg.name}`));
  cache = { name: cfg.name, at: now, view };
  return view;
}

const inStartWindow = (now: number): boolean => lastStartAt !== null && now - lastStartAt < START_WINDOW_MS;

/** Dentro la finestra dopo un avvio riuscito «spento» vale «in accensione»: GitHub può restare indietro di qualche secondo. */
function effective(view: CodespaceView, now: number): CodespaceView {
  return view.state === "shutdown" && inStartWindow(now) ? { ...view, state: "starting" } : view;
}

function unavailable(): NextResponse {
  return problem(404, "VETRINA_NOT_AVAILABLE", "Non disponibile", "L'accensione della vetrina da questa pagina non è attiva.");
}

function upstream(err: unknown): NextResponse {
  // Solo lo stato HTTP (mai corpo, intestazioni o token) nel log del server.
  console.error("vetrina: richiesta a GitHub non riuscita", err instanceof GithubError ? (err.status ?? "rete") : "errore");
  return problem(502, "CODESPACE_UNAVAILABLE", "GitHub non risponde", "Non è stato possibile leggere o avviare la vetrina. Riprova tra poco.");
}

function ok(view: CodespaceReading): NextResponse {
  const res = NextResponse.json(view);
  res.headers.set("cache-control", "no-store");
  return res;
}

/** `GET /api/vetrina/codespace`: stato del codespace, `{state, url}`, più `checks` a codespace `available` (Q-728). */
export async function handleCodespaceGet(_req: NextRequest, deps: HandlerDeps = {}): Promise<NextResponse> {
  const env = deps.env ?? process.env;
  const cfg = codespaceConfig(env);
  if (!cfg) return unavailable();
  const fetchImpl = deps.fetchImpl ?? fetch;
  const now = (deps.now ?? Date.now)();
  try {
    const view = effective(await readView(fetchImpl, cfg, now), now);
    if (view.state !== "available") return ok(view);
    return ok({ ...view, checks: await readChecks(fetchImpl, cfg, env, now) });
  } catch (err) {
    return upstream(err);
  }
}

/**
 * `POST /api/vetrina/codespace`: avvia il codespace. `Origin` uguale all'origine della richiesta (altrimenti 403), al
 * più un avvio ogni 60 s per processo (dentro la finestra si risponde con lo stato corrente, «in accensione» se GitHub
 * dice ancora «spento»), già acceso ⇒ nessuna chiamata di avvio. La finestra parte solo da un avvio riuscito: un avvio
 * fallito non blocca il tentativo successivo.
 */
export async function handleCodespacePost(req: NextRequest, deps: HandlerDeps = {}): Promise<NextResponse> {
  const cfg = codespaceConfig(deps.env ?? process.env);
  if (!cfg) return unavailable();
  const origin = req.headers.get("origin");
  if (origin === null || origin !== req.nextUrl.origin) {
    return problem(403, "ORIGIN_MISMATCH", "Richiesta rifiutata", "La richiesta deve arrivare da questa stessa pagina.");
  }
  const fetchImpl = deps.fetchImpl ?? fetch;
  const now = (deps.now ?? Date.now)();
  try {
    const current = await readView(fetchImpl, cfg, now);
    if (current.state === "available") return ok(current);
    if (inStartWindow(now)) return ok(effective(current, now));
    await github(fetchImpl, cfg.token, "POST", `/user/codespaces/${cfg.name}/start`);
    lastStartAt = now;
    cache = null;
    return ok({ state: "starting", url: current.url });
  } catch (err) {
    return upstream(err);
  }
}
