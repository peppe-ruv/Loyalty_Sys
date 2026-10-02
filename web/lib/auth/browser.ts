import { REALM_COOKIES, type Realm } from "./realm";

// Lato browser del BFF (profilo enterprise). Nel profilo demo il cookie CSRF non esiste e nulla cambia.
// Il browser non vede mai token: solo il token CSRF (non un segreto verso l'utente) e la sessione opaca HttpOnly.

export const CSRF_HEADER_NAME = "X-LH-CSRF";

/**
 * Token CSRF della sessione del realm (`__Host-lh_csrf` per gli operatori, `__Host-lh_mcsrf` per i membri, ADR-051), o
 * `null` (profilo demo, nessuna sessione, lato server). Con un solo realm il portale usa la sessione degli operatori:
 * se il cookie dei membri manca vale quello degli operatori (il server lo verifica comunque contro la sua sessione).
 */
export function readCsrfToken(
  cookieHeader: string | undefined = typeof document === "undefined" ? undefined : document.cookie,
  realm: Realm = "operators",
): string | null {
  if (!cookieHeader) return null;
  const found = new Map<string, string>();
  for (const part of cookieHeader.split(";")) {
    const [name, ...rest] = part.trim().split("=");
    found.set(name, rest.join("="));
  }
  const raw = (realm === "members" ? found.get(REALM_COOKIES.members.csrf) : undefined) ?? found.get(REALM_COOKIES.operators.csrf);
  return raw ? decodeURIComponent(raw) || null : null;
}

/** Header da aggiungere a una richiesta che cambia stato (vuoto se non c'è il cookie). */
export function csrfHeaders(method: string | undefined, cookieHeader?: string, realm: Realm = "operators"): Record<string, string> {
  const upper = (method ?? "GET").toUpperCase();
  if (upper === "GET" || upper === "HEAD" || upper === "OPTIONS") return {};
  const token = readCsrfToken(cookieHeader, realm);
  return token ? { [CSRF_HEADER_NAME]: token } : {};
}

/** Percorso di login che riporta alla pagina corrente. */
export function loginHref(location: { pathname: string; search: string }): string {
  return `/api/auth/login?returnTo=${encodeURIComponent(location.pathname + location.search)}`;
}

let redirecting = false;

/** Porta al login a pagina intera, una volta sola anche se più richieste falliscono insieme. */
export function redirectToLogin(): void {
  if (redirecting || typeof window === "undefined") return;
  redirecting = true;
  window.location.assign(loginHref(window.location));
}
