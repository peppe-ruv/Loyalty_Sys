import { hmac, safeEqual } from "./crypto";

// Difesa CSRF del BFF nel profilo enterprise (docs/18 §3.2: «SameSite, controllo di Origin e header X-LH-CSRF su ogni
// richiesta non idempotente»). Tre controlli indipendenti, tutti obbligatori sui metodi che cambiano stato:
// 1. `Sec-Fetch-Site`, se il browser lo manda, deve valere `same-origin`;
// 2. `Origin` deve esserci e coincidere con l'origine pubblica del web (`LH_WEB_URL`);
// 3. `X-LH-CSRF` deve valere l'HMAC dell'id di sessione (signed double-submit, OWASP): il valore sta nel cookie
//    leggibile `__Host-lh_csrf` e solo una pagina della stessa origine può leggerlo e rimandarlo come header.
// Il cookie di sessione è comunque `SameSite=Lax`: non parte con POST cross-site.

export const CSRF_HEADER = "x-lh-csrf";
const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS"]);

export type CsrfFailure = "SEC_FETCH_SITE" | "ORIGIN" | "TOKEN";

export function isSafeMethod(method: string): boolean {
  return SAFE_METHODS.has(method.toUpperCase());
}

/** Token CSRF legato alla sessione: HMAC-SHA256 dell'id con la chiave derivata per lo scopo `csrf`. */
export function csrfTokenFor(sessionId: string, csrfKey: Buffer): string {
  return hmac(csrfKey, sessionId);
}

/** Controllo di provenienza (1 e 2): vale anche senza sessione, es. per il logout. `null` = superato. */
export function checkOrigin(headers: Headers, expectedOrigin: string): CsrfFailure | null {
  const site = headers.get("sec-fetch-site");
  if (site !== null && site !== "same-origin") return "SEC_FETCH_SITE";
  const origin = headers.get("origin");
  if (origin === null || origin !== expectedOrigin) return "ORIGIN";
  return null;
}

/**
 * Controllo completo per una richiesta che cambia stato. I metodi sicuri passano sempre (non devono cambiare stato).
 * `sessionId` assente ⇒ si verificano solo `Sec-Fetch-Site` e `Origin` (il token non ha niente a cui legarsi).
 */
export function checkCsrf(
  req: { method: string; headers: Headers },
  expectedOrigin: string,
  session: { id: string; csrfKey: Buffer } | null,
): CsrfFailure | null {
  if (isSafeMethod(req.method)) return null;
  const origin = checkOrigin(req.headers, expectedOrigin);
  if (origin) return origin;
  if (!session) return null;
  const sent = req.headers.get(CSRF_HEADER);
  if (!sent || !safeEqual(sent, csrfTokenFor(session.id, session.csrfKey))) return "TOKEN";
  return null;
}
