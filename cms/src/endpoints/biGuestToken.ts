/**
 * RF-123: token ospite Superset per incorporare i cruscotti nel backoffice. Il CMS si autentica a Superset con un
 * account di servizio, chiede un guest token (5 minuti) per la dashboard richiesta e applica la row-level security
 * in base al ruolo dell'utente del backoffice (es. operatore di canale → solo il proprio canale). L'utente non vede
 * mai le credenziali di Superset: vede solo la dashboard, in sola lettura.
 */
import type { Endpoint, PayloadRequest } from "payload";

const SUPERSET_URL = process.env.SUPERSET_URL || "http://superset:8088";
const SUPERSET_SERVICE_USER = process.env.SUPERSET_SERVICE_USER || "admin";
const SUPERSET_SERVICE_PASSWORD = process.env.SUPERSET_SERVICE_PASSWORD || "admin";
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

/** Regole RLS per ruolo (RF-43): il customer care vede tutto; un operatore di canale solo il proprio canale. */
function rlsFor(user: any) {
  const channel = user?.channel; // campo opzionale sull'utente del backoffice
  return channel ? [{ clause: `channel = '${String(channel).replace(/'/g, "")}'` }] : [];
}

export const biGuestToken: Endpoint = {
  path: "/bi/guest-token",
  method: "get",
  handler: async (req: PayloadRequest) => {
    if (!req.user) return Response.json({ error: "UNAUTHORIZED" }, { status: 401 });
    const key = (req.query?.dashboard as string) || "kpi";
    const dashboardId = DASHBOARDS[key];
    if (!dashboardId) return Response.json({ error: "UNKNOWN_DASHBOARD" }, { status: 404 });
    const s = await serviceLogin();
    const res = await fetch(`${SUPERSET_URL}/api/v1/security/guest_token/`, {
      method: "POST",
      headers: { "content-type": "application/json", authorization: `Bearer ${s.token}`, "X-CSRFToken": s.csrf, cookie: s.cookie },
      body: JSON.stringify({
        user: { username: `backoffice:${req.user.id}`, first_name: String((req.user as any).email || "operatore"), last_name: "" },
        resources: [{ type: "dashboard", id: dashboardId }],
        rls: rlsFor(req.user),
      }),
    });
    if (!res.ok) return Response.json({ error: "SUPERSET_UNAVAILABLE", status: res.status }, { status: 503 });
    const { token } = await res.json();
    return Response.json({ token, dashboardId, supersetUrl: process.env.SUPERSET_PUBLIC_URL || SUPERSET_URL, expiresInSeconds: 300 });
  },
};
