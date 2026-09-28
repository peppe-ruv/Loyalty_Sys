// @vitest-environment node
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextRequest } from "next/server";
import { GET, POST } from "./route";
import { POST as personaPOST } from "@/app/api/persona/route";
import { GET as loginGET } from "@/app/api/auth/login/route";
import { bffFor } from "@/lib/auth/bff";
import { getAuthConfig, type EnterpriseAuthConfig } from "@/lib/auth/config";
import { csrfTokenFor } from "@/lib/auth/csrf";
import type { SessionUser } from "@/lib/auth/sessionStore";

// Proxy /api/lh nei due profili (ADR-027, regole 6-bis, 18, 20).
// Demo: header verso i servizi IDENTICI alla Fase 1 (fotografia). Enterprise: sessione + CSRF + Bearer lato server,
// niente header d'identità dal browser, membro solo dal token sulle API del portale; configurazione insicura ⇒ 500.

let personaCookie: string | undefined;
vi.mock("next/headers", () => ({
  cookies: async () => ({ get: (name: string) => (name === "lh_persona" && personaCookie ? { value: personaCookie } : undefined) }),
}));

const ORIGIN = "https://loyalty.lh.test";
const ENTERPRISE_ENV = {
  LH_PROFILE: "enterprise",
  LH_OIDC_ISSUER: "https://idp.lh.test/realms/loyaltyhub",
  LH_WEB_CLIENT_SECRET: "s3cr3t-generato-dall-idp-0123456789",
  LH_WEB_URL: ORIGIN,
  LH_WEB_SESSION_KEY: Buffer.from(Array.from({ length: 32 }, (_, i) => 200 - i)).toString("base64"),
};

let seen: { url: string; init: RequestInit }[];

beforeEach(() => {
  seen = [];
  personaCookie = undefined;
  process.env.LH_SVC_WALLET_URL = "http://wallet.test";
  process.env.LH_SVC_REWARD_URL = "http://reward.test";
  process.env.LH_SVC_MEMBER_URL = "http://member.test";
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: URL | string, init: RequestInit) => {
      seen.push({ url: String(url), init });
      return Response.json({ ok: true });
    }),
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
  for (const key of [...Object.keys(ENTERPRISE_ENV), "LH_SVC_WALLET_URL", "LH_SVC_REWARD_URL", "LH_SVC_MEMBER_URL"]) delete process.env[key];
  delete (globalThis as Record<symbol, unknown>)[Symbol.for("io.loyaltyhub.web.bff")];
});

const ctx = (service: string, path: string[]) => ({ params: Promise.resolve({ service, path }) });
const sentHeaders = (i = 0) => Object.fromEntries(new Headers(seen[i].init.headers as HeadersInit));

/** Richiesta come la manderebbe un browser ostile: prova a scegliersi l'identità con i propri header. */
const HOSTILE = {
  authorization: "Bearer token-rubato",
  "x-lh-actor": "ADMIN:attaccante",
  cookie: "altro=1",
  "x-forwarded-user": "admin",
  "x-correlation-id": "01JCORRELAZIONE0000000000",
};

describe("profilo demo: comportamento invariato", () => {
  it("fotografia degli header verso il servizio (persona BO dal cookie, niente dal browser oltre l'elenco)", async () => {
    personaCookie = encodeURIComponent(JSON.stringify({ kind: "BO", username: "paolo.care", role: "CARE" }));
    const req = new NextRequest("http://web.test/api/lh/wallet/v1/wallets/MBR-000002?currency=POINTS", {
      method: "POST",
      headers: { ...HOSTILE, "content-type": "application/json", "idempotency-key": "k-1" },
      body: JSON.stringify({ memberId: "MBR-000002", amount: 10 }),
    });
    const res = await POST(req, ctx("wallet", ["v1", "wallets", "MBR-000002"]));
    expect(res.status).toBe(200);
    expect(seen[0].url).toBe("http://wallet.test/v1/wallets/MBR-000002?currency=POINTS");
    expect(sentHeaders()).toMatchInlineSnapshot(`
      {
        "accept": "application/json",
        "content-type": "application/json",
        "idempotency-key": "k-1",
        "x-correlation-id": "01JCORRELAZIONE0000000000",
        "x-lh-actor": "CARE:paolo.care",
      }
    `);
    expect(seen[0].init.body).toBe(JSON.stringify({ memberId: "MBR-000002", amount: 10 }));
    expect(res.headers.get("cache-control")).toBeNull();
  });

  it("portale in demo: memberId esplicito inoltrato com'è, nessun CSRF richiesto", async () => {
    const req = new NextRequest("http://web.test/api/lh/reward/v1/portal/redemptions?memberId=MBR-000002", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ memberId: "MBR-000002", rewardCode: "RWD-1" }),
    });
    const res = await POST(req, ctx("reward", ["v1", "portal", "redemptions"]));
    expect(res.status).toBe(200);
    expect(seen[0].url).toBe("http://reward.test/v1/portal/redemptions?memberId=MBR-000002");
    expect(seen[0].init.body).toBe(JSON.stringify({ memberId: "MBR-000002", rewardCode: "RWD-1" }));
    expect(sentHeaders()["x-lh-actor"]).toBe("ANALYST:anonymous");
  });

  it("gli endpoint di login non esistono in demo", async () => {
    const res = await loginGET(new NextRequest("http://web.test/api/auth/login?returnTo=/portal"));
    expect(res.status).toBe(404);
    expect((await res.json()).code).toBe("AUTH_DISABLED");
  });
});

describe("profilo enterprise mal configurato", () => {
  it("500 INSECURE_CONFIG senza chiamare il servizio e senza ripiegare sul demo", async () => {
    process.env.LH_PROFILE = "enterprise";
    const error = vi.spyOn(console, "error").mockImplementation(() => undefined);
    const res = await GET(new NextRequest("http://web.test/api/lh/wallet/v1/portal/tiers"), ctx("wallet", ["v1", "portal", "tiers"]));
    expect(res.status).toBe(500);
    const body = await res.json();
    expect(body.code).toBe("INSECURE_CONFIG");
    // Il dettaglio con i nomi delle variabili resta nel log del server.
    expect(JSON.stringify(body)).not.toContain("LH_");
    expect(seen).toHaveLength(0);
    error.mockRestore();
  });
});

describe("profilo enterprise", () => {
  const OPERATOR: SessionUser = { sub: "kc-1", sid: "sid-1", username: "paolo.care", name: "Paolo Neri", roles: ["CARE"], role: "CARE", kind: "operator" };
  const MEMBER: SessionUser = { sub: "kc-2", sid: "sid-2", username: "giulia", name: null, roles: ["MEMBER"], role: "ANALYST", kind: "member" };

  beforeEach(() => {
    Object.assign(process.env, ENTERPRISE_ENV);
  });

  async function login(user: SessionUser) {
    const bff = bffFor(getAuthConfig() as EnterpriseAuthConfig);
    const id = await bff.store.create(user, {
      accessToken: `AT-${user.sub}`,
      accessExpiresAt: Math.floor(Date.now() / 1000) + 300,
      refreshToken: "RT",
      idToken: "ID",
    });
    return { cookie: `__Host-lh_session=${id}`, csrf: csrfTokenFor(id, bff.csrfKey), bff };
  }

  it("senza sessione: 401 UNAUTHENTICATED (la UI porta al login), servizio non chiamato", async () => {
    const res = await GET(new NextRequest(`${ORIGIN}/api/lh/wallet/v1/portal/tiers`, { headers: HOSTILE }), ctx("wallet", ["v1", "portal", "tiers"]));
    expect(res.status).toBe(401);
    expect(await res.json()).toMatchObject({ code: "UNAUTHENTICATED", status: 401 });
    expect(seen).toHaveLength(0);
  });

  it("sessione chiusa o inventata: 401; i cookie restano (una risposta in ritardo non chiude la sessione nuova di un'altra scheda)", async () => {
    const res = await GET(
      new NextRequest(`${ORIGIN}/api/lh/wallet/v1/portal/tiers`, { headers: { cookie: "__Host-lh_session=inventata" } }),
      ctx("wallet", ["v1", "portal", "tiers"]),
    );
    expect(res.status).toBe(401);
    expect(res.headers.get("content-type")).toBe("application/problem+json");
    expect(res.headers.getSetCookie()).toEqual([]);
    expect(seen).toHaveLength(0);
  });

  it("access token scaduto: rinnovo trasparente prima di chiamare il servizio", async () => {
    const bff = bffFor(getAuthConfig() as EnterpriseAuthConfig);
    const refresh = vi.spyOn(bff.oidc, "refresh").mockResolvedValue({
      accessToken: "AT-RINNOVATO",
      accessExpiresAt: Math.floor(Date.now() / 1000) + 300,
      refreshToken: "RT-2",
      idToken: null,
      claims: null,
    });
    const id = await bff.store.create(OPERATOR, { accessToken: "AT-VECCHIO", accessExpiresAt: 0, refreshToken: "RT-1", idToken: "ID" });
    const res = await GET(
      new NextRequest(`${ORIGIN}/api/lh/wallet/v1/portal/tiers`, { headers: { cookie: `__Host-lh_session=${id}` } }),
      ctx("wallet", ["v1", "portal", "tiers"]),
    );
    expect(res.status).toBe(200);
    expect(refresh).toHaveBeenCalledWith("RT-1");
    expect(sentHeaders().authorization).toBe("Bearer AT-RINNOVATO");
  });

  it("GET con sessione: solo Bearer lato server; Authorization, X-LH-Actor e cookie del browser mai inoltrati", async () => {
    const { cookie } = await login(OPERATOR);
    const res = await GET(
      new NextRequest(`${ORIGIN}/api/lh/member/v1/members/MBR-000002?view=360`, { headers: { ...HOSTILE, cookie: `${cookie}; ${HOSTILE.cookie}` } }),
      ctx("member", ["v1", "members", "MBR-000002"]),
    );
    expect(res.status).toBe(200);
    // Un operatore (CARE) lavora sui membri: fuori dal portale il memberId resta.
    expect(seen[0].url).toBe("http://member.test/v1/members/MBR-000002?view=360");
    expect(sentHeaders()).toEqual({
      accept: "application/json",
      authorization: "Bearer AT-kc-1",
      "x-correlation-id": "01JCORRELAZIONE0000000000",
    });
    expect(res.headers.get("cache-control")).toBe("private, no-store");
    expect(JSON.stringify([...res.headers])).not.toContain("AT-kc-1");
  });

  describe("CSRF sulle richieste che cambiano stato", () => {
    const post = async (headers: Record<string, string>) => {
      const { cookie, csrf } = await login(OPERATOR);
      const all: Record<string, string> = { cookie, "content-type": "application/json", origin: ORIGIN, "sec-fetch-site": "same-origin", "x-lh-csrf": csrf, ...headers };
      for (const [k, v] of Object.entries(all)) if (v === "") delete all[k];
      return POST(
        new NextRequest(`${ORIGIN}/api/lh/wallet/v1/wallets/MBR-000002/adjustments`, { method: "POST", headers: all, body: '{"points":10}' }),
        ctx("wallet", ["v1", "wallets", "MBR-000002", "adjustments"]),
      );
    };

    it("origine, Sec-Fetch-Site e token giusti: inoltrata", async () => {
      expect((await post({})).status).toBe(200);
      expect(seen).toHaveLength(1);
    });

    it.each([
      ["token assente", { "x-lh-csrf": "" }],
      ["token sbagliato", { "x-lh-csrf": "abc" }],
      ["Origin di un altro sito", { origin: "https://attaccante.example" }],
      ["Origin assente", { origin: "" }],
      ["Sec-Fetch-Site cross-site", { "sec-fetch-site": "cross-site" }],
    ])("%s: 403 CSRF_REJECTED, servizio non chiamato", async (_, headers) => {
      const res = await post(headers);
      expect(res.status).toBe(403);
      expect((await res.json()).code).toBe("CSRF_REJECTED");
      expect(seen).toHaveLength(0);
    });
  });

  describe("portale: membro solo dal token", () => {
    it("memberId tolto dalla query, gli altri parametri restano", async () => {
      const { cookie } = await login(MEMBER);
      await GET(
        new NextRequest(`${ORIGIN}/api/lh/reward/v1/portal/coupons?memberId=MBR-000009&size=5`, { headers: { cookie } }),
        ctx("reward", ["v1", "portal", "coupons"]),
      );
      expect(seen[0].url).toBe("http://reward.test/v1/portal/coupons?size=5");
      expect(sentHeaders().authorization).toBe("Bearer AT-kc-2");
    });

    it("memberId tolto dal corpo JSON", async () => {
      const { cookie, csrf } = await login(MEMBER);
      const res = await POST(
        new NextRequest(`${ORIGIN}/api/lh/reward/v1/portal/redemptions`, {
          method: "POST",
          headers: { cookie, origin: ORIGIN, "x-lh-csrf": csrf, "content-type": "application/json" },
          body: JSON.stringify({ memberId: "MBR-000009", rewardCode: "RWD-1" }),
        }),
        ctx("reward", ["v1", "portal", "redemptions"]),
      );
      expect(res.status).toBe(200);
      expect(JSON.parse(seen[0].init.body as string)).toEqual({ rewardCode: "RWD-1" });
    });

    it("memberId nel percorso: 403 MEMBER_FROM_TOKEN, servizio non chiamato", async () => {
      const { cookie } = await login(MEMBER);
      const res = await GET(
        new NextRequest(`${ORIGIN}/api/lh/wallet/v1/portal/wallets/MBR-000009`, { headers: { cookie } }),
        ctx("wallet", ["v1", "portal", "wallets", "MBR-000009"]),
      );
      expect(res.status).toBe(403);
      expect((await res.json()).code).toBe("MEMBER_FROM_TOKEN");
      expect(seen).toHaveLength(0);
    });

    it("un membro fuori dalle API del portale: 403 FORBIDDEN_ROLE", async () => {
      const { cookie } = await login(MEMBER);
      const res = await GET(
        new NextRequest(`${ORIGIN}/api/lh/member/v1/members/MBR-000009`, { headers: { cookie } }),
        ctx("member", ["v1", "members", "MBR-000009"]),
      );
      expect(res.status).toBe(403);
      expect((await res.json()).code).toBe("FORBIDDEN_ROLE");
      expect(seen).toHaveLength(0);
    });
  });

  it("la persona simulata non esiste in enterprise", async () => {
    const res = await personaPOST(
      new NextRequest(`${ORIGIN}/api/persona`, { method: "POST", body: JSON.stringify({ kind: "BO", username: "marta.admin" }) }),
    );
    expect(res.status).toBe(404);
    expect(res.headers.getSetCookie()).toEqual([]);
  });
});
