import { REALM_COOKIES } from "./realm";

// Cookie del BFF (docs/18 §3.2, CLAUDE.md regola 20). Prefisso `__Host-`: il browser li accetta solo con `Secure`,
// `Path=/` e senza `Domain`, quindi nessun sottodominio può impostarli o sovrascriverli.
// - `__Host-lh_session`: id opaco della sessione; `HttpOnly` (mai leggibile dal JavaScript), `SameSite=Lax` (arriva
//   con la navigazione di ritorno dall'IdP, non con le POST cross-site), cookie di sessione del browser: la durata
//   vera la decide lo store (inattività e massimo, Q-354).
// - `__Host-lh_csrf`: token CSRF legato alla sessione; leggibile dal JavaScript della stessa origine, che lo rimanda
//   come `X-LH-CSRF`; `SameSite=Lax` come la sessione (la protezione viene dal fatto che un'altra origine non può
//   leggerlo, non dal SameSite). Non è un segreto verso il browser dell'utente, solo verso le altre origini.
// - `__Host-lh_auth`: stato del login in corso (state, nonce, code_verifier, ritorno), cifrato; `HttpOnly`,
//   `SameSite=Lax`, 10 minuti.

// Con il realm dei membri (ADR-051) il portale ha gli stessi tre cookie con il prefisso `lh_m` (lib/auth/realm.ts):
// le due sessioni non si toccano.

export const SESSION_COOKIE = REALM_COOKIES.operators.session;
export const CSRF_COOKIE = REALM_COOKIES.operators.csrf;
export const AUTH_FLOW_COOKIE = REALM_COOKIES.operators.flow;
export const AUTH_FLOW_MAX_AGE = 10 * 60;

export interface CookieOptions {
  secure: true;
  path: "/";
  httpOnly: boolean;
  sameSite: "lax";
  maxAge?: number;
  expires?: Date;
}

const HOST_PREFIX = { secure: true, path: "/" } as const;

export const sessionCookieOptions: CookieOptions = { ...HOST_PREFIX, httpOnly: true, sameSite: "lax" };
export const csrfCookieOptions: CookieOptions = { ...HOST_PREFIX, httpOnly: false, sameSite: "lax" };
export const authFlowCookieOptions: CookieOptions = { ...HOST_PREFIX, httpOnly: true, sameSite: "lax", maxAge: AUTH_FLOW_MAX_AGE };

/** Opzioni per cancellare un cookie `__Host-` (stessi attributi, scadenza nel passato). */
export function expiredCookie(options: CookieOptions): CookieOptions {
  return { ...options, maxAge: 0, expires: new Date(0) };
}
