import { CSRF_COOKIE } from "./cookies";

// Lato browser del BFF (profilo enterprise). Nel profilo demo il cookie CSRF non esiste e nulla cambia.
// Il browser non vede mai token: solo il token CSRF (non un segreto verso l'utente) e la sessione opaca HttpOnly.

export const CSRF_HEADER_NAME = "X-LH-CSRF";

/** Token CSRF dal cookie `__Host-lh_csrf`, o `null` (profilo demo, nessuna sessione, lato server). */
export function readCsrfToken(cookieHeader: string | undefined = typeof document === "undefined" ? undefined : document.cookie): string | null {
  if (!cookieHeader) return null;
  for (const part of cookieHeader.split(";")) {
    const [name, ...rest] = part.trim().split("=");
    if (name === CSRF_COOKIE) return decodeURIComponent(rest.join("=")) || null;
  }
  return null;
}

/** Header da aggiungere a una richiesta che cambia stato (vuoto se non c'è il cookie). */
export function csrfHeaders(method: string | undefined, cookieHeader?: string): Record<string, string> {
  const upper = (method ?? "GET").toUpperCase();
  if (upper === "GET" || upper === "HEAD" || upper === "OPTIONS") return {};
  const token = readCsrfToken(cookieHeader);
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
