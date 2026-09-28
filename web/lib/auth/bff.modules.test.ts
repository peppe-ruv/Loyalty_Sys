// @vitest-environment node
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { NextRequest } from "next/server";
import type { SessionUser } from "./sessionStore";

// Regressione (revisione di sicurezza, blocker 1): Next carica i moduli del BFF in più istanze nello stesso processo
// (route handler e layout RSC hanno chunk diversi). Con il confronto per identità dell'oggetto di configurazione ogni
// render di pagina sostituiva il BFF e il suo store in memoria: tutte le sessioni chiuse, login in loop.
// Qui ogni `vi.resetModules()` produce istanze nuove di config.ts, bff.ts, viewer.ts e del route handler.

let sessionCookie: string | undefined;
vi.mock("next/headers", () => ({
  cookies: async () => ({ get: (name: string) => (name === "__Host-lh_session" && sessionCookie ? { value: sessionCookie } : undefined) }),
}));

const ORIGIN = "https://loyalty.lh.test";
const ENV = {
  LH_PROFILE: "enterprise",
  LH_OIDC_ISSUER: "https://idp.lh.test/realms/loyaltyhub",
  LH_WEB_CLIENT_SECRET: "s3cr3t-generato-dall-idp-0123456789",
  LH_WEB_URL: ORIGIN,
  LH_WEB_SESSION_KEY: Buffer.from(Array.from({ length: 32 }, (_, i) => 90 + i)).toString("base64"),
  LH_SVC_WALLET_URL: "http://wallet.test",
};
const USER: SessionUser = { sub: "kc-2", sid: "sid-2", username: "giulia", name: null, roles: ["MEMBER"], role: "ANALYST", kind: "member" };

function clearProcessState() {
  for (const key of ["io.loyaltyhub.web.bff", "io.loyaltyhub.web.authConfig"]) {
    delete (globalThis as Record<symbol, unknown>)[Symbol.for(key)];
  }
}

beforeEach(() => {
  Object.assign(process.env, ENV);
  clearProcessState();
  sessionCookie = undefined;
  vi.stubGlobal("fetch", vi.fn(async () => Response.json({ items: [] })));
});
afterEach(() => {
  for (const key of Object.keys(ENV)) delete process.env[key];
  clearProcessState();
  vi.unstubAllGlobals();
  vi.resetModules();
});

it("una sessione aperta dal route handler sopravvive al render del layout e alle chiamate successive", async () => {
  // Istanza 1: la callback del login crea la sessione.
  const callbackSide = await import("./bff");
  const { getAuthConfig: cfgA } = await import("./config");
  const cfg = cfgA();
  if (cfg.mode !== "enterprise") throw new Error("atteso enterprise");
  sessionCookie = await callbackSide.bffFor(cfg).store.create(USER, {
    accessToken: "AT",
    accessExpiresAt: Math.floor(Date.now() / 1000) + 300,
    refreshToken: "RT",
    idToken: "ID",
  });

  // Istanza 2: il layout RSC del portale (getViewer) con moduli propri.
  vi.resetModules();
  const layoutSide = await import("./viewer");
  expect(await layoutSide.getViewer()).toMatchObject({ mode: "enterprise", user: { username: "giulia" } });

  // Istanza 3: il proxy /api/lh con moduli propri, DOPO il render del layout.
  vi.resetModules();
  const proxy = await import("@/app/api/lh/[service]/[...path]/route");
  const res = await proxy.GET(
    new NextRequest(`${ORIGIN}/api/lh/wallet/v1/portal/tiers`, { headers: { cookie: `__Host-lh_session=${sessionCookie}` } }),
    { params: Promise.resolve({ service: "wallet", path: ["v1", "portal", "tiers"] }) },
  );
  expect(res.status).toBe(200);

  // E di nuovo il layout: la sessione c'è ancora.
  vi.resetModules();
  expect(await (await import("./viewer")).getViewer()).toMatchObject({ user: { sub: "kc-2" } });
});

it("due oggetti di configurazione con gli stessi valori condividono lo stesso BFF; valori diversi no", async () => {
  const { parseAuthConfig } = await import("./config");
  const { bffFor } = await import("./bff");
  const a = parseAuthConfig(ENV);
  const b = parseAuthConfig({ ...ENV });
  if (a.mode !== "enterprise" || b.mode !== "enterprise") throw new Error("atteso enterprise");
  expect(a).not.toBe(b);
  expect(a.fingerprint).toBe(b.fingerprint);
  expect(bffFor(b)).toBe(bffFor(a));
  const other = parseAuthConfig({ ...ENV, LH_WEB_SESSION_KEY: Buffer.alloc(32, 7).fill(8, 0, 1).toString("base64") });
  if (other.mode !== "enterprise") throw new Error("atteso enterprise");
  expect(other.fingerprint).not.toBe(a.fingerprint);
  expect(bffFor(other)).not.toBe(bffFor(a));
});
