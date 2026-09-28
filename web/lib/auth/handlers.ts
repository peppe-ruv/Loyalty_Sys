import { NextResponse, type NextRequest } from "next/server";
import { readCappedBody } from "@/lib/api/proxyBody";
import { problem, currentSession, type Bff } from "./bff";
import {
  AUTH_FLOW_COOKIE,
  AUTH_FLOW_MAX_AGE,
  CSRF_COOKIE,
  SESSION_COOKIE,
  authFlowCookieOptions,
  csrfCookieOptions,
  expiredCookie,
  sessionCookieOptions,
} from "./cookies";
import { checkCsrf, csrfTokenFor } from "./csrf";
import { open, randomId, seal } from "./crypto";
import { InvalidLogoutTokenError, LogoutKeysUnavailableError, verifyLogoutToken } from "./logoutToken";
import { LoginRejectedError, redirectUri, type LoginChecks } from "./oidc";
import { safeReturnTo } from "./returnTo";
import { effectiveRole, rolesFromClaim, sessionKind } from "./roles";
import type { SessionUser } from "./sessionStore";

// Endpoint di autenticazione del BFF (ADR-027, docs/18 §3.2) nel profilo enterprise. Funzioni pure rispetto al BFF
// passato: i route handler in app/api/auth/* le compongono con `resolveBff()`, i test con un IdP finto.

/** Motivi mostrati dalla pagina /auth/error (testi in app/auth/error/page.tsx). */
export type LoginFailure = "expired" | "denied" | "rejected" | "idp_unavailable" | "logout_failed";

const LOGOUT_TOKEN_MAX_BYTES = 16 * 1024;

interface AuthFlow extends LoginChecks {
  returnTo: string;
  /** Secondi epoch di inizio del login. */
  startedAt: number;
}

const nowSeconds = () => Math.floor(Date.now() / 1000);

/** `GET /api/auth/login?returnTo=`: avvia Authorization Code + PKCE, con state e nonce in un cookie cifrato. */
export async function handleLogin(req: NextRequest, bff: Bff): Promise<NextResponse> {
  const returnTo = safeReturnTo(req.nextUrl.searchParams.get("returnTo"));
  const flow: AuthFlow = { state: randomId(), nonce: randomId(), codeVerifier: randomId(), returnTo, startedAt: nowSeconds() };
  let authorizationUrl: URL;
  try {
    authorizationUrl = await bff.oidc.authorizationUrl(flow);
  } catch (err) {
    console.error("login: discovery dell'IdP non riuscita", errorName(err));
    return failure(bff, "idp_unavailable", returnTo);
  }
  const res = redirect(authorizationUrl);
  res.cookies.set(AUTH_FLOW_COOKIE, seal(JSON.stringify(flow), bff.flowKey, AUTH_FLOW_COOKIE), authFlowCookieOptions);
  return res;
}

/** `GET /api/auth/callback`: verifica state/iss, scambia il codice, valida l'ID token (nonce) e apre la sessione. */
export async function handleCallback(req: NextRequest, bff: Bff): Promise<NextResponse> {
  const flow = readFlow(req, bff);
  if (!flow) return clearFlow(failure(bff, "expired", "/"));

  const callbackUrl = new URL(redirectUri(bff.cfg));
  callbackUrl.search = req.nextUrl.search;
  let tokens;
  try {
    tokens = await bff.oidc.exchangeCode(callbackUrl, flow);
  } catch (err) {
    if (err instanceof LoginRejectedError) {
      return clearFlow(failure(bff, err.reason === "access_denied" ? "denied" : "rejected", flow.returnTo));
    }
    console.error("callback: scambio del codice non riuscito", errorName(err));
    return clearFlow(failure(bff, "idp_unavailable", flow.returnTo));
  }
  const user = userFromClaims(tokens.claims);
  if (!user || !tokens.idToken) return clearFlow(failure(bff, "rejected", flow.returnTo));

  // Mai riusare un id di sessione arrivato dal browser (session fixation): la vecchia sessione si chiude.
  const previous = req.cookies.get(SESSION_COOKIE)?.value;
  if (previous) await bff.store.delete(previous);
  const id = await bff.store.create(user, {
    accessToken: tokens.accessToken,
    accessExpiresAt: tokens.accessExpiresAt,
    refreshToken: tokens.refreshToken,
    idToken: tokens.idToken,
  });

  const res = redirect(new URL(safeReturnTo(flow.returnTo), bff.cfg.publicUrl));
  res.cookies.set(SESSION_COOKIE, id, sessionCookieOptions);
  res.cookies.set(CSRF_COOKIE, csrfTokenFor(id, bff.csrfKey), csrfCookieOptions);
  return clearFlow(res);
}

/**
 * `POST /api/auth/logout`: invio di un modulo a pagina intera (campo `csrf` = token del cookie `__Host-lh_csrf`),
 * con `Origin` e `Sec-Fetch-Site` della stessa origine. Chiude la sessione del BFF e risponde 303 verso il logout
 * dell'IdP (RP-initiated logout con `id_token_hint`). L'ID token va dal server all'IdP nell'header `Location`: non
 * passa mai dal JavaScript della pagina (regola 20). Richiesta rifiutata ⇒ 303 verso /auth/error.
 */
export async function handleLogout(req: NextRequest, bff: Bff): Promise<NextResponse> {
  const id = req.cookies.get(SESSION_COOKIE)?.value ?? null;
  const formToken = await readFormField(req, "csrf");
  const csrf = checkCsrf(req, bff.cfg.publicUrl.origin, id ? { id, csrfKey: bff.csrfKey } : null, formToken);
  if (csrf) return failure(bff, "logout_failed", "/");

  const current = await currentSession(req.cookies, bff);
  let location = new URL("/", bff.cfg.publicUrl);
  if (current) {
    await bff.store.delete(current.id);
    // Ritorno alla home del web: il client `web` del realm ammette esattamente `${LH_WEB_URL}/` come
    // `post.logout.redirect.uris` (Q-412, M8.2d; verificato da scripts/check-realm.mjs).
    try {
      const endSession = await bff.oidc.endSessionUrl(current.session.tokens.idToken, new URL("/", bff.cfg.publicUrl).href);
      if (endSession) location = endSession;
    } catch (err) {
      // IdP irraggiungibile: la sessione del BFF è comunque chiusa; quella dell'IdP scade da sola.
      console.error("logout: end_session_endpoint non disponibile", errorName(err));
    }
  }
  const res = redirect(location);
  res.cookies.set(SESSION_COOKIE, "", expiredCookie(sessionCookieOptions));
  res.cookies.set(CSRF_COOKIE, "", expiredCookie(csrfCookieOptions));
  return res;
}

/** Un campo di un corpo `application/x-www-form-urlencoded` piccolo (4 KiB), oppure `null`. */
async function readFormField(req: NextRequest, name: string): Promise<string | null> {
  const contentType = (req.headers.get("content-type") ?? "").toLowerCase();
  if (!contentType.startsWith("application/x-www-form-urlencoded")) return null;
  try {
    const raw = await readCappedBody(req, 4 * 1024);
    return raw ? new URLSearchParams(new TextDecoder().decode(raw)).get(name) : null;
  } catch {
    return null;
  }
}

/**
 * `POST /api/auth/backchannel-logout` (OIDC Back-Channel Logout 1.0): chiamata server-to-server dall'IdP, senza
 * cookie. Il logout token firmato è l'unica credenziale: niente controllo CSRF (non c'è sessione del browser).
 * Risposte: 200 chiuso; 400 token non valido o già usato; 503 chiavi dell'IdP non disponibili.
 */
export async function handleBackchannelLogout(req: NextRequest, bff: Bff): Promise<NextResponse> {
  const contentType = (req.headers.get("content-type") ?? "").toLowerCase();
  if (!contentType.startsWith("application/x-www-form-urlencoded")) return logoutError(400, "content-type non ammesso");
  let raw: Uint8Array | null;
  try {
    raw = await readCappedBody(req, LOGOUT_TOKEN_MAX_BYTES);
  } catch {
    return logoutError(400, "corpo non leggibile");
  }
  if (!raw) return logoutError(400, "corpo troppo grande");
  const token = new URLSearchParams(new TextDecoder().decode(raw)).get("logout_token");
  if (!token) return logoutError(400, "logout_token mancante");

  let verification;
  try {
    verification = await bff.oidc.logoutVerification();
  } catch (err) {
    console.error("back-channel logout: chiavi dell'IdP non disponibili", errorName(err));
    return logoutError(503, "chiavi dell'IdP non disponibili", "temporarily_unavailable");
  }
  let target;
  try {
    target = await verifyLogoutToken(token, { issuer: verification.issuer, clientId: bff.cfg.clientId, keys: verification.keys });
  } catch (err) {
    if (err instanceof LogoutKeysUnavailableError) {
      // Chiavi dell'IdP non scaricabili durante la verifica: condizione temporanea, l'IdP può riprovare.
      console.error("back-channel logout: JWKS non disponibile", errorName(err.cause));
      return logoutError(503, "chiavi dell'IdP non disponibili", "temporarily_unavailable");
    }
    if (err instanceof InvalidLogoutTokenError) return logoutError(400, "logout token non valido");
    throw err;
  }
  if (!bff.replay.remember(target.jti, target.exp, nowSeconds())) return logoutError(400, "logout token già usato");
  await bff.store.deleteMatching({ sid: target.sid, sub: target.sub });
  return new NextResponse(null, { status: 200, headers: { "cache-control": "no-store" } });
}

/** Identità della sessione dai claim dell'ID token validato; `null` se manca il `sub`. */
export function userFromClaims(claims: Record<string, unknown> | null): SessionUser | null {
  const sub = claims?.sub;
  if (!claims || typeof sub !== "string" || !sub) return null;
  const roles = rolesFromClaim(claims.lh_roles);
  const text = (value: unknown) => (typeof value === "string" && value.trim() ? value : null);
  return {
    sub,
    sid: text(claims.sid),
    username: text(claims.preferred_username) ?? sub,
    name: text(claims.name),
    roles,
    role: effectiveRole(roles),
    kind: sessionKind(roles),
  };
}

function readFlow(req: NextRequest, bff: Bff): AuthFlow | null {
  const sealed = req.cookies.get(AUTH_FLOW_COOKIE)?.value;
  if (!sealed) return null;
  const plain = open(sealed, bff.flowKey, AUTH_FLOW_COOKIE);
  if (!plain) return null;
  try {
    const flow = JSON.parse(plain) as Partial<AuthFlow>;
    const ok =
      typeof flow.state === "string" &&
      typeof flow.nonce === "string" &&
      typeof flow.codeVerifier === "string" &&
      typeof flow.returnTo === "string" &&
      typeof flow.startedAt === "number" &&
      nowSeconds() - flow.startedAt <= AUTH_FLOW_MAX_AGE;
    return ok ? (flow as AuthFlow) : null;
  } catch {
    return null;
  }
}

function redirect(url: URL): NextResponse {
  const res = NextResponse.redirect(url, 303);
  res.headers.set("cache-control", "no-store");
  return res;
}

function failure(bff: Bff, reason: LoginFailure, returnTo: string): NextResponse {
  const url = new URL("/auth/error", bff.cfg.publicUrl);
  url.searchParams.set("reason", reason);
  url.searchParams.set("returnTo", safeReturnTo(returnTo));
  return redirect(url);
}

function clearFlow(res: NextResponse): NextResponse {
  res.cookies.set(AUTH_FLOW_COOKIE, "", expiredCookie(authFlowCookieOptions));
  return res;
}

export function csrfRejected(): NextResponse {
  return problem(403, "CSRF_REJECTED", "Richiesta rifiutata", "La richiesta non arriva da una pagina di questo sito: ricarica la pagina e riprova.");
}

function logoutError(status: number, description: string, error = "invalid_request"): NextResponse {
  return NextResponse.json({ error, error_description: description }, { status, headers: { "cache-control": "no-store" } });
}

/** Solo il tipo dell'errore nei log: i messaggi delle librerie OIDC possono contenere parametri della richiesta. */
function errorName(err: unknown): string {
  return err instanceof Error ? err.name : typeof err;
}
