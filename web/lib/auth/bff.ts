import { NextResponse } from "next/server";
import { getAuthConfig, InsecureConfigError, type EnterpriseAuthConfig } from "./config";
import { deriveKey } from "./crypto";
import { createOidcClient, type OidcClient } from "./oidc";
import { ReplayCache } from "./logoutToken";
import { InMemorySessionStore, type Session, type SessionStore } from "./sessionStore";
import { CALLBACK_PATHS, REALM_COOKIES, type Realm, type RealmCookies } from "./realm";

// Composizione del BFF nel profilo enterprise (ADR-027). SOLO LATO SERVER.
// Lo stato (sessioni, rinnovi in corso, jti già visti, discovery) vive su `globalThis` con una chiave `Symbol.for`:
// Next impacchetta ogni route per conto suo e una variabile di modulo potrebbe esistere in più copie nello stesso
// processo (sessioni create dalla callback e invisibili al proxy).

export interface Bff {
  /**
   * Configurazione vista da questo BFF: per il realm dei membri emittente e client sono quelli di `cfg.members`
   * (ADR-051); origine pubblica, chiave e durate restano quelle del web.
   */
  cfg: EnterpriseAuthConfig;
  /** Realm effettivo: `members` solo se il realm dei membri è configurato. */
  realm: Realm;
  cookies: RealmCookies;
  callbackPath: string;
  store: SessionStore;
  oidc: OidcClient;
  inflight: Map<string, Promise<Session | null>>;
  replay: ReplayCache;
  csrfKey: Buffer;
  flowKey: Buffer;
}

const GLOBAL_KEY = Symbol.for("io.loyaltyhub.web.bff");
type Holder = { [GLOBAL_KEY]?: { fingerprint: string; bffs: Partial<Record<Realm, Bff>> } };

/** Il BFF di un realm; `members` senza realm dei membri configurato ricade su `operators` (un solo realm). */
export function createBff(
  base: EnterpriseAuthConfig,
  overrides: Partial<Omit<Bff, "cfg" | "realm" | "cookies" | "callbackPath">> = {},
  requested: Realm = "operators",
): Bff {
  const realm: Realm = requested === "members" && base.members ? "members" : "operators";
  const cfg: EnterpriseAuthConfig =
    realm === "members" && base.members
      ? {
          ...base,
          issuer: base.members.issuer,
          clientId: base.members.clientId,
          clientSecret: base.members.clientSecret,
          allowInsecureIssuer: base.members.allowInsecureIssuer,
        }
      : base;
  // Chiavi distinte per realm: un token CSRF o uno stato di login di una sessione non vale per l'altra.
  const scope = realm === "members" ? ":members" : "";
  return {
    cfg,
    realm,
    cookies: REALM_COOKIES[realm],
    callbackPath: CALLBACK_PATHS[realm],
    store:
      overrides.store ??
      new InMemorySessionStore({
        masterKey: cfg.sessionKey,
        idleSeconds: cfg.idleSeconds,
        maxSeconds: cfg.maxSeconds,
        maxSessions: cfg.maxSessions,
      }),
    oidc: overrides.oidc ?? createOidcClient(cfg, CALLBACK_PATHS[realm]),
    inflight: overrides.inflight ?? new Map(),
    replay: overrides.replay ?? new ReplayCache(),
    csrfKey: overrides.csrfKey ?? deriveKey(cfg.sessionKey, `csrf${scope}`),
    flowKey: overrides.flowKey ?? deriveKey(cfg.sessionKey, `auth-flow${scope}`),
  };
}

/**
 * BFF del processo per la configurazione corrente, ricreato solo se la configurazione cambia DAVVERO: il confronto è
 * sull'impronta dei valori, mai sull'identità dell'oggetto. Next carica config.ts in più istanze (route handler e
 * layout RSC ricevono oggetti diversi con gli stessi valori): confrontare gli oggetti sostituiva il BFF, e con lui lo
 * store in memoria, a ogni render di pagina, chiudendo tutte le sessioni.
 */
export function bffFor(cfg: EnterpriseAuthConfig, requested: Realm = "operators"): Bff {
  const holder = globalThis as Holder;
  let current = holder[GLOBAL_KEY];
  if (!current || current.fingerprint !== cfg.fingerprint) {
    current = { fingerprint: cfg.fingerprint, bffs: {} };
    holder[GLOBAL_KEY] = current;
  }
  // Un solo realm: anche il portale usa il BFF (e lo store) degli operatori, come prima di ADR-051.
  const realm: Realm = requested === "members" && cfg.members ? "members" : "operators";
  return (current.bffs[realm] ??= createBff(cfg, {}, realm));
}

export type Resolved = { mode: "demo" } | { mode: "enterprise"; bff: Bff } | { mode: "error"; response: NextResponse };

/**
 * Profilo della richiesta: demo, enterprise con il suo BFF, oppure una risposta d'errore già pronta quando il profilo
 * enterprise è mal configurato (mai un ripiego sul demo, regola 22).
 */
export function resolveBff(realm: Realm = "operators"): Resolved {
  try {
    const cfg = getAuthConfig();
    return cfg.mode === "demo" ? { mode: "demo" } : { mode: "enterprise", bff: bffFor(cfg, realm) };
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
export async function enterpriseOnly(
  run: (bff: Bff) => Promise<NextResponse>,
  realm: Realm = "operators",
): Promise<NextResponse> {
  const resolved = resolveBff(realm);
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
  const id = cookies.get(bff.cookies.session)?.value;
  if (!id) return null;
  const session = await bff.store.get(id);
  return session ? { id, session } : null;
}
