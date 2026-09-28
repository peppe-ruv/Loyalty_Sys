import { NextResponse } from "next/server";
import { getAuthConfig, InsecureConfigError, type EnterpriseAuthConfig } from "./config";
import { deriveKey } from "./crypto";
import { createOidcClient, type OidcClient } from "./oidc";
import { ReplayCache } from "./logoutToken";
import { InMemorySessionStore, type Session, type SessionStore } from "./sessionStore";
import { SESSION_COOKIE } from "./cookies";

// Composizione del BFF nel profilo enterprise (ADR-027). SOLO LATO SERVER.
// Lo stato (sessioni, rinnovi in corso, jti già visti, discovery) vive su `globalThis` con una chiave `Symbol.for`:
// Next impacchetta ogni route per conto suo e una variabile di modulo potrebbe esistere in più copie nello stesso
// processo (sessioni create dalla callback e invisibili al proxy).

export interface Bff {
  cfg: EnterpriseAuthConfig;
  store: SessionStore;
  oidc: OidcClient;
  inflight: Map<string, Promise<Session | null>>;
  replay: ReplayCache;
  csrfKey: Buffer;
  flowKey: Buffer;
}

const GLOBAL_KEY = Symbol.for("io.loyaltyhub.web.bff");
type Holder = { [GLOBAL_KEY]?: { cfg: EnterpriseAuthConfig; bff: Bff } };

export function createBff(cfg: EnterpriseAuthConfig, overrides: Partial<Omit<Bff, "cfg">> = {}): Bff {
  return {
    cfg,
    store:
      overrides.store ??
      new InMemorySessionStore({
        masterKey: cfg.sessionKey,
        idleSeconds: cfg.idleSeconds,
        maxSeconds: cfg.maxSeconds,
        maxSessions: cfg.maxSessions,
      }),
    oidc: overrides.oidc ?? createOidcClient(cfg),
    inflight: overrides.inflight ?? new Map(),
    replay: overrides.replay ?? new ReplayCache(),
    csrfKey: overrides.csrfKey ?? deriveKey(cfg.sessionKey, "csrf"),
    flowKey: overrides.flowKey ?? deriveKey(cfg.sessionKey, "auth-flow"),
  };
}

/** BFF del processo per la configurazione corrente (ricreato solo se la configurazione cambia). */
export function bffFor(cfg: EnterpriseAuthConfig): Bff {
  const holder = globalThis as Holder;
  const current = holder[GLOBAL_KEY];
  if (current && current.cfg === cfg) return current.bff;
  const bff = createBff(cfg);
  holder[GLOBAL_KEY] = { cfg, bff };
  return bff;
}

export type Resolved = { mode: "demo" } | { mode: "enterprise"; bff: Bff } | { mode: "error"; response: NextResponse };

/**
 * Profilo della richiesta: demo, enterprise con il suo BFF, oppure una risposta d'errore già pronta quando il profilo
 * enterprise è mal configurato (mai un ripiego sul demo, regola 22).
 */
export function resolveBff(): Resolved {
  try {
    const cfg = getAuthConfig();
    return cfg.mode === "demo" ? { mode: "demo" } : { mode: "enterprise", bff: bffFor(cfg) };
  } catch (err) {
    if (!(err instanceof InsecureConfigError)) throw err;
    // Il dettaglio (nomi delle variabili) va nel log del server, non al browser.
    console.error(err.message);
    return {
      mode: "error",
      response: problem(500, "INSECURE_CONFIG", "Configurazione non sicura", "Il server web non è configurato per l'accesso: contatta l'amministratore."),
    };
  }
}

/** Esegue un endpoint che esiste solo nel profilo enterprise; nel demo risponde 404 (non c'è login da fare). */
export async function enterpriseOnly(run: (bff: Bff) => Promise<NextResponse>): Promise<NextResponse> {
  const resolved = resolveBff();
  if (resolved.mode === "error") return resolved.response;
  if (resolved.mode === "demo") {
    return problem(404, "AUTH_DISABLED", "Accesso non previsto", "Nel profilo demo l'identità è simulata: non c'è login.");
  }
  return run(resolved.bff);
}

/** Problema RFC 9457 del BFF (`code` stabile, `detail` in italiano mostrabile). Mai in cache. */
export function problem(status: number, code: string, title: string, detail: string, extra: Record<string, unknown> = {}): NextResponse {
  const res = NextResponse.json({ type: code, code, title, status, detail, ...extra }, { status });
  res.headers.set("content-type", "application/problem+json");
  res.headers.set("cache-control", "no-store");
  return res;
}

/** Sessione corrente dal cookie, senza rinnovo (per layout e logout). */
export async function currentSession(
  cookies: { get(name: string): { value: string } | undefined },
  bff: Bff,
): Promise<{ id: string; session: Session } | null> {
  const id = cookies.get(SESSION_COOKIE)?.value;
  if (!id) return null;
  const session = await bff.store.get(id);
  return session ? { id, session } : null;
}
