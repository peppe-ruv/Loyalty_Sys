import { csrfHeaders, redirectToLogin } from "@/lib/auth/browser";

// Chiamate del browser alle route BFF della vetrina (V10 «programma», V11 «azione»). Client-safe: nessun import server.
// Mai token nel browser: la sessione è nel cookie, il POST porta il token CSRF.

export class HttpError extends Error {
  constructor(
    readonly status: number,
    readonly code: string | null,
    readonly extra: Record<string, unknown> = {},
  ) {
    super(`HTTP ${status}`);
  }
}

export async function call<T>(url: string, init?: RequestInit): Promise<T> {
  const res = await fetch(url, {
    ...init,
    credentials: "same-origin",
    headers: { Accept: "application/json", ...(init?.body ? { "Content-Type": "application/json" } : {}), ...csrfHeaders(init?.method), ...init?.headers },
    cache: "no-store",
  });
  const body = (await res.json().catch(() => null)) as Record<string, unknown> | null;
  if (res.status === 401) {
    redirectToLogin();
    throw new HttpError(401, "UNAUTHENTICATED");
  }
  if (!res.ok) throw new HttpError(res.status, typeof body?.code === "string" ? body.code : null, body ?? {});
  return body as T;
}
