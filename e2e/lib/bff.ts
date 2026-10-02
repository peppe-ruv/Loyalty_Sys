import type { Page } from '@playwright/test';

// Chiamate al proxy del BFF (`/api/lh/<servizio>/...`) dal contesto della pagina, cioè dal browser: i cookie di
// sessione (HttpOnly) viaggiano da soli, il token CSRF si legge dal cookie leggibile come fa il frontend (docs/07 §4-bis)
// e TLS lo verifica il browser. Il token di accesso non passa mai di qui (resta lato server, regola 20).
// La pagina deve già essere sull'origine del web: si può usare anche una pagina di errore o /api/demo/status.

export const CSRF_COOKIE = '__Host-lh_csrf';
export const SESSION_COOKIE = '__Host-lh_session';

export interface ApiResult {
  status: number;
  /** Codice dell'errore RFC 9457 del BFF o dei servizi (`code`, `error` o `type`), se è una stringa breve e sicura. */
  code: string | null;
  json: unknown;
}

export async function bffRequest(
  page: Page,
  path: string,
  init: { method?: 'GET' | 'POST' | 'PUT' | 'DELETE'; body?: unknown } = {},
): Promise<ApiResult> {
  return page.evaluate(
    async ({ path, method, body, csrfCookie }) => {
      const headers: Record<string, string> = { accept: 'application/json' };
      if (body !== undefined) headers['content-type'] = 'application/json';
      if (method !== 'GET') {
        const raw = document.cookie
          .split(';')
          .map((c) => c.trim())
          .find((c) => c.startsWith(`${csrfCookie}=`));
        if (raw) headers['x-lh-csrf'] = decodeURIComponent(raw.slice(csrfCookie.length + 1));
      }
      const res = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body), credentials: 'same-origin' });
      const text = await res.text();
      let json: unknown = null;
      try {
        json = JSON.parse(text);
      } catch {
        json = null;
      }
      const o = json && typeof json === 'object' ? (json as Record<string, unknown>) : null;
      const raw = o ? (o.code ?? o.error ?? o.type) : null;
      const code = typeof raw === 'string' && /^[A-Za-z0-9_.:-]{1,64}$/.test(raw) ? raw : null;
      return { status: res.status, code, json };
    },
    { path, method: init.method ?? 'GET', body: init.body, csrfCookie: CSRF_COOKIE },
  );
}
