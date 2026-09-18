/**
 * RF-123: token ospite Superset per incorporare i cruscotti nel backoffice. Il CMS si autentica a Superset con un
 * account di servizio, chiede un guest token (5 minuti) per la dashboard richiesta e lo consegna al pannello.
 * L'utente non vede mai le credenziali di Superset: vede solo la dashboard, in sola lettura.
 *
 * Due cose che questo file **non** fa, ed è meglio dirle che lasciarle credere:
 * - non ha credenziali di ripiego. Senza `SUPERSET_SERVICE_USER` e `SUPERSET_SERVICE_PASSWORD` non prova nemmeno
 *   ad autenticarsi: un ripiego `admin/admin` è la password di default dell'immagine Superset, cioè un tentativo
 *   di accesso con le credenziali che un'istanza mal configurata accetta davvero;
 * - non applica row-level security. Servirebbe un campo sull'utente del backoffice che dica *cosa* può vedere,
 *   e oggi quel campo non esiste: l'utente del pannello non ha ruoli collegati (vedi la collezione `roles`, che
 *   non è ancora legata al controllo di accesso). Finché non c'è, il token è senza clausole e va detto, non
 *   simulato con un campo che è sempre `undefined`.
 */
import type { Endpoint, PayloadRequest } from "payload";

const SUPERSET_URL = process.env.SUPERSET_URL || "http://superset:8088";
const SUPERSET_SERVICE_USER = process.env.SUPERSET_SERVICE_USER;
const SUPERSET_SERVICE_PASSWORD = process.env.SUPERSET_SERVICE_PASSWORD;
const DASHBOARDS: Record<string, string> = { kpi: process.env.SUPERSET_DASHBOARD_KPI_ID || "loyalty-kpi" };

let cached: { token: string; csrf: string; cookie: string; at: number } | null = null;

async function serviceLogin() {
  if (cached && Date.now() - cached.at < 10 * 60 * 1000) return cached;
  const login = await fetch(`${SUPERSET_URL}/api/v1/security/login`, {
    method: "POST", headers: { "content-type": "application/json" },
    body: JSON.stringify({ username: SUPERSET_SERVICE_USER, password: SUPERSET_SERVICE_PASSWORD, provider: "db", refresh: true }),
  });
  if (!login.ok) throw new Error(`superset login ${login.status}`);
  const { access_token } = await login.json();
  const csrfRes = await fetch(`${SUPERSET_URL}/api/v1/security/csrf_token/`, { headers: { authorization: `Bearer ${access_token}` } });
  const { result: csrf } = await csrfRes.json();
  cached = { token: access_token, csrf, cookie: csrfRes.headers.get("set-cookie") || "", at: Date.now() };
  return cached;
}

/**
 * Clausole di row-level security del token ospite. Oggi sempre vuote, e non per svista: nel backoffice non
 * esiste ancora un campo che dica quale fetta di dati un operatore può vedere. Quando esisterà, si costruisce
 * qui — con il valore passato come parametro alla clausola, non concatenato nel testo.
 */
function rlsFor(_user: unknown): Array<{ clause: string }> {
  return [];
}

export const biGuestToken: Endpoint = {
  path: "/bi/guest-token",
  method: "get",
  handler: async (req: PayloadRequest) => {
    if (!req.user) return Response.json({ error: "UNAUTHORIZED" }, { status: 401 });
    if (!SUPERSET_SERVICE_USER || !SUPERSET_SERVICE_PASSWORD)
      return Response.json({ error: "SUPERSET_NOT_CONFIGURED" }, { status: 503 });
    const key = (req.query?.dashboard as string) || "kpi";
    const dashboardId = DASHBOARDS[key];
    if (!dashboardId) return Response.json({ error: "UNKNOWN_DASHBOARD" }, { status: 404 });
    const s = await serviceLogin();
    const res = await fetch(`${SUPERSET_URL}/api/v1/security/guest_token/`, {
      method: "POST",
      headers: { "content-type": "application/json", authorization: `Bearer ${s.token}`, "X-CSRFToken": s.csrf, cookie: s.cookie },
      body: JSON.stringify({
        // Identificatore opaco, mai l'email: quello che attraversa il confine verso la BI finisce nei suoi log
        // di accesso, e l'invariante §3 vale anche per gli operatori, non solo per i membri.
        user: { username: `backoffice:${req.user.id}`, first_name: "operatore", last_name: "" },
        resources: [{ type: "dashboard", id: dashboardId }],
        rls: rlsFor(req.user),
      }),
    });
    if (!res.ok) return Response.json({ error: "SUPERSET_UNAVAILABLE", status: res.status }, { status: 503 });
    const { token } = await res.json();
    return Response.json({ token, dashboardId, supersetUrl: process.env.SUPERSET_PUBLIC_URL || SUPERSET_URL, expiresInSeconds: 300 });
  },
};
