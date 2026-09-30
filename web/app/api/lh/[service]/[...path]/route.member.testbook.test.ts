// @vitest-environment node
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextRequest } from "next/server";
import { GET, POST } from "./route";
import { bffFor } from "@/lib/auth/bff";
import { getAuthConfig, type EnterpriseAuthConfig } from "@/lib/auth/config";
import { csrfTokenFor } from "@/lib/auth/csrf";
import type { SessionUser } from "@/lib/auth/sessionStore";
import { rows } from "@/test/testbook";

// Testbook TB-WEB §PRX (PRX-016…036 e 044): `X-LH-Member` verso i servizi (docs/07 §3, docs/06 §3.4, ADR-048, Q-555).
// Profilo demo: solo sulle API del portale (`/v1/portal/**`), col membro della persona MEMBER (ripiego MBR-000002 senza
// cookie), mai quello scelto dal browser e mai con una persona di backoffice (Q-560, ADR-048 punto 6). Profilo enterprise: mai (il membro viene dal token, regole 6-bis e 18).

let personaCookie: string | undefined;
vi.mock("next/headers", () => ({
  cookies: async () => ({ get: (name: string) => (name === "lh_persona" && personaCookie !== undefined ? { name, value: personaCookie } : undefined) }),
}));

const ORIGIN = "https://loyalty.lh.test";
const ENTERPRISE_ENV = {
  LH_PROFILE: "enterprise",
  LH_OIDC_ISSUER: "https://idp.lh.test/realms/loyaltyhub",
  LH_WEB_CLIENT_SECRET: "s3cr3t-generato-dall-idp-0123456789",
  LH_WEB_URL: ORIGIN,
  LH_WEB_SESSION_KEY: Buffer.from(Array.from({ length: 32 }, (_, i) => 200 - i)).toString("base64"),
};
const SERVICE_ENV = ["LH_SVC_CAMPAIGN_URL", "LH_SVC_WALLET_URL", "LH_SVC_REWARD_URL", "LH_SVC_MEMBER_URL", "LH_SVC_GAMIFICATION_URL"];

const BOARD = {
  code: "LDB-MONTH-PTS",
  name: "Classifica del mese",
  metric: "PTS_EARNED",
  period: "MONTH",
  periodKey: "2026-09",
  topN: 10,
  top: [{ rank: 1, memberId: "MBR-000005", score: 1840, isMe: false }],
  me: { rank: 2, score: 900 },
  participants: 2,
};

let seen: { url: string; init: RequestInit }[];

beforeEach(() => {
  seen = [];
  personaCookie = undefined;
  process.env.LH_SVC_CAMPAIGN_URL = "http://campaign.test";
  process.env.LH_SVC_WALLET_URL = "http://wallet.test";
  process.env.LH_SVC_REWARD_URL = "http://reward.test";
  process.env.LH_SVC_MEMBER_URL = "http://member.test";
  process.env.LH_SVC_GAMIFICATION_URL = "http://gam.test";
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: URL | string, init: RequestInit) => {
      const u = String(url);
      seen.push({ url: u, init });
      const target = new URL(u);
      if (target.origin === "http://member.test" && target.pathname === "/v1/members/nicknames") {
        return Response.json({ items: [{ memberId: "MBR-000005", nickname: "fra_r" }] });
      }
      if (u.includes("/v1/portal/leaderboards")) return Response.json(BOARD);
      return Response.json({ ok: true });
    }),
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
  for (const key of [...Object.keys(ENTERPRISE_ENV), ...SERVICE_ENV]) delete process.env[key];
  delete (globalThis as Record<symbol, unknown>)[Symbol.for("io.loyaltyhub.web.bff")];
});

const ctx = (service: string, path: string[]) => ({ params: Promise.resolve({ service, path }) });
const enc = (v: unknown) => encodeURIComponent(JSON.stringify(v));
/** Header inviati al servizio dalla chiamata `i`. */
const sent = (i = 0) => new Headers(seen[i].init.headers as HeadersInit);

const WALLET = ["v1", "portal", "wallets", "MBR-000005"];
const walletReq = (headers: Record<string, string> = {}) => new NextRequest("http://web.test/api/lh/wallet/v1/portal/wallets/MBR-000005", { headers });

describe("profilo demo", () => {
  it("[TB-WEB-PRX-016] API del portale con persona MEMBER → X-LH-Member = membro della persona", async () => {
    personaCookie = enc({ kind: "MEMBER", memberId: "MBR-000005" });
    const res = await GET(walletReq(), ctx("wallet", WALLET));
    expect(res.status).toBe(200);
    expect(seen[0].url).toBe("http://wallet.test/v1/portal/wallets/MBR-000005");
    expect(sent().get("x-lh-member")).toBe("MBR-000005");
    // X-LH-Actor non cambia: le personas membro restano `ANALYST:anonymous` (TB-WEB-PERS-023).
    expect(sent().get("x-lh-actor")).toBe("ANALYST:anonymous");
  });

  it.each(rows([
    { id: "TB-WEB-PRX-017", desc: "cookie assente", cookie: undefined as string | undefined },
    { id: "TB-WEB-PRX-018", desc: "cookie non valido", cookie: "non-json" as string | undefined },
  ]))("[%s] API del portale, %s → X-LH-Member = membro di default MBR-000002", async (_id, _desc, { cookie }) => {
    // Il portale mostra lo stesso membro a un visitatore anonimo (app/portal/layout.tsx, lib/persona/demoMember.ts).
    personaCookie = cookie;
    await GET(walletReq(), ctx("wallet", WALLET));
    expect(sent().get("x-lh-member")).toBe("MBR-000002");
    expect(sent().get("x-lh-actor")).toBe("ANALYST:anonymous");
  });

  it("[TB-WEB-PRX-019] API del portale con persona del backoffice (CARE) → nessun X-LH-Member, X-LH-Actor della persona (Q-560)", async () => {
    // Un operatore non agisce mai come membro (ADR-048 punto 6): il portale aperto da BO mostra MBR-000002 ma lavora col
    // `memberId` esplicito, il proxy non gli presta un'identità di membro.
    personaCookie = enc({ kind: "BO", username: "paolo.care", role: "CARE" });
    await GET(walletReq(), ctx("wallet", WALLET));
    expect(seen).toHaveLength(1);
    expect(sent().has("x-lh-member")).toBe(false);
    expect(sent().get("x-lh-actor")).toBe("CARE:paolo.care");
  });

  it("[TB-WEB-PRX-045] BO-17: /v1/portal/campaigns?codes= con persona BO → nessun X-LH-Member (vista generica), query inoltrata", async () => {
    personaCookie = enc({ kind: "BO", username: "marta.admin", role: "ADMIN" });
    const res = await GET(
      new NextRequest("http://web.test/api/lh/campaign/v1/portal/campaigns?codes=CMP-REFERRAL-01,CMP-REFERRAL-02", {
        headers: { "x-lh-member": "MBR-000009" },
      }),
      ctx("campaign", ["v1", "portal", "campaigns"]),
    );
    expect(res.status).toBe(200);
    expect(seen).toHaveLength(1);
    expect(seen[0].url).toBe("http://campaign.test/v1/portal/campaigns?codes=CMP-REFERRAL-01,CMP-REFERRAL-02");
    expect(sent().has("x-lh-member")).toBe(false);
    expect(JSON.stringify([...sent()])).not.toMatch(/MBR-/);
    expect(sent().get("x-lh-actor")).toBe("ADMIN:marta.admin");
  });

  it("[TB-WEB-PRX-020] POST del portale: header presente, memberId di query e corpo inoltrati come prima", async () => {
    personaCookie = enc({ kind: "MEMBER", memberId: "MBR-000005" });
    const body = JSON.stringify({ memberId: "MBR-000005", rewardCode: "RWD-1" });
    const req = new NextRequest("http://web.test/api/lh/reward/v1/portal/redemptions?memberId=MBR-000005", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body,
    });
    const res = await POST(req, ctx("reward", ["v1", "portal", "redemptions"]));
    expect(res.status).toBe(200);
    expect(seen[0].url).toBe("http://reward.test/v1/portal/redemptions?memberId=MBR-000005");
    expect(seen[0].init.body).toBe(body);
    expect(sent().get("x-lh-member")).toBe("MBR-000005");
  });

  it.each(rows([
    { id: "TB-WEB-PRX-021", desc: "gestione wallet", service: "wallet", path: ["v1", "wallets", "MBR-000005"] },
    { id: "TB-WEB-PRX-022", desc: "gestione membri", service: "member", path: ["v1", "members", "MBR-000005"] },
    { id: "TB-WEB-PRX-023", desc: "prefisso simile (/v1/portalx)", service: "wallet", path: ["v1", "portalx", "tiers"] },
    { id: "TB-WEB-PRX-024", desc: "portal non al secondo segmento", service: "wallet", path: ["v1", "x", "portal", "tiers"] },
  ]))("[%s] percorso fuori dal portale (%s) → nessun X-LH-Member", async (_id, _desc, { service, path }) => {
    personaCookie = enc({ kind: "MEMBER", memberId: "MBR-000005" });
    const res = await GET(new NextRequest(`http://web.test/api/lh/${service}/${path.join("/")}`), ctx(service, path));
    expect(res.status).toBe(200);
    expect(seen).toHaveLength(1);
    expect(sent().has("x-lh-member")).toBe(false);
    expect(sent().get("x-lh-actor")).toBe("ANALYST:anonymous");
  });

  it.each(rows([
    { id: "TB-WEB-PRX-025", desc: "meno di sei cifre", memberId: "MBR-00005" },
    { id: "TB-WEB-PRX-026", desc: "più di sei cifre", memberId: "MBR-0000005" },
    { id: "TB-WEB-PRX-027", desc: "prefisso in minuscolo", memberId: "mbr-000005" },
    { id: "TB-WEB-PRX-028", desc: "a capo e un secondo header", memberId: "MBR-000005\r\nX-LH-Actor: ADMIN:evil" },
  ]))("[%s] id malformato nel cookie persona (%s) → X-LH-Member non inoltrato, richiesta servita", async (_id, _desc, { memberId }) => {
    // Un valore così i servizi lo rifiuterebbero (400) e uno con a capo farebbe fallire la costruzione dell'header.
    personaCookie = enc({ kind: "MEMBER", memberId });
    const res = await GET(walletReq(), ctx("wallet", WALLET));
    expect(res.status).toBe(200);
    expect(sent().has("x-lh-member")).toBe(false);
    expect(sent().get("x-lh-actor")).toBe("ANALYST:anonymous");
  });

  it.each(["X-LH-Member", "x-lh-member", "X-Lh-MeMbEr"])(
    "[TB-WEB-PRX-029] X-LH-Member del browser (%s) sul portale: scartato, vale il membro della persona",
    async (name) => {
      personaCookie = enc({ kind: "MEMBER", memberId: "MBR-000005" });
      await GET(walletReq({ [name]: "MBR-000009" }), ctx("wallet", WALLET));
      expect(sent().get("x-lh-member")).toBe("MBR-000005");
      expect(JSON.stringify([...sent()])).not.toContain("MBR-000009");
    },
  );

  it("[TB-WEB-PRX-030] X-LH-Member del browser con persona malformata: scartato, nessun header (mai quello del browser)", async () => {
    personaCookie = enc({ kind: "MEMBER", memberId: "MBR-5" });
    await GET(walletReq({ "x-lh-member": "MBR-000009" }), ctx("wallet", WALLET));
    expect(sent().has("x-lh-member")).toBe(false);
  });

  it("[TB-WEB-PRX-031] X-LH-Member del browser fuori dal portale: scartato", async () => {
    personaCookie = enc({ kind: "MEMBER", memberId: "MBR-000005" });
    await GET(
      new NextRequest("http://web.test/api/lh/member/v1/members/MBR-000009", { headers: { "x-lh-member": "MBR-000009" } }),
      ctx("member", ["v1", "members", "MBR-000009"]),
    );
    expect(sent().has("x-lh-member")).toBe(false);
  });

  it("[TB-WEB-PRX-044] percorso che esce dal portale con un segmento decodificato (`..%2Fmembers`) → 400 INVALID_PATH, nessuna chiamata a valle (quindi nessun X-LH-Member)", async () => {
    personaCookie = enc({ kind: "MEMBER", memberId: "MBR-000005" });
    const path = ["v1", "portal", "../members", "MBR-000009"]; // Next decodifica `..%2Fmembers` in un solo segmento
    const res = await GET(
      new NextRequest("http://web.test/api/lh/member/v1/portal/..%2Fmembers/MBR-000009", { headers: { "x-lh-member": "MBR-000009" } }),
      ctx("member", path),
    );
    expect(res.status).toBe(400);
    expect((await res.json()).code).toBe("INVALID_PATH");
    expect(seen).toHaveLength(0);
  });

  it("[TB-WEB-PRX-032] soprannomi: gamification riceve X-LH-Member, la chiamata del proxy a member-service no", async () => {
    personaCookie = enc({ kind: "MEMBER", memberId: "MBR-000002" });
    const res = await GET(
      new NextRequest("http://web.test/api/lh/gamification/v1/portal/leaderboards/LDB-MONTH-PTS?memberId=MBR-000002"),
      ctx("gamification", ["v1", "portal", "leaderboards", "LDB-MONTH-PTS"]),
    );
    expect(res.status).toBe(200);
    expect(seen).toHaveLength(2);
    expect(seen[0].url).toContain("http://gam.test/v1/portal/leaderboards/LDB-MONTH-PTS");
    expect(sent(0).get("x-lh-member")).toBe("MBR-000002");
    expect(seen[1].url).toBe("http://member.test/v1/members/nicknames");
    expect(sent(1).has("x-lh-member")).toBe(false);
    expect(sent(1).get("x-lh-actor")).toBe("ANALYST:anonymous");
  });
});

describe("profilo enterprise: X-LH-Member non viaggia mai", () => {
  const MEMBER: SessionUser = { sub: "kc-2", sid: "sid-2", username: "giulia", name: null, roles: ["MEMBER"], role: "ANALYST", kind: "member" };
  const OPERATOR: SessionUser = { sub: "kc-1", sid: "sid-1", username: "paolo.care", name: "Paolo Neri", roles: ["CARE"], role: "CARE", kind: "operator" };

  beforeEach(() => {
    Object.assign(process.env, ENTERPRISE_ENV);
  });

  async function cookieFor(user: SessionUser) {
    const bff = bffFor(getAuthConfig() as EnterpriseAuthConfig);
    const id = await bff.store.create(user, {
      accessToken: `AT-${user.sub}`,
      accessExpiresAt: Math.floor(Date.now() / 1000) + 300,
      refreshToken: "RT",
      idToken: "ID",
    });
    return { cookie: `__Host-lh_session=${id}`, csrf: csrfTokenFor(id, bff.csrfKey) };
  }

  it.each(rows([
    { id: "TB-WEB-PRX-033", desc: "token di membro", user: MEMBER, bearer: "Bearer AT-kc-2" },
    { id: "TB-WEB-PRX-034", desc: "token di operatore (backoffice che legge il portale)", user: OPERATOR, bearer: "Bearer AT-kc-1" },
  ]))("[%s] API del portale, %s: solo Bearer, X-LH-Member del browser e persona simulata ignorati", async (_id, _desc, { user, bearer }) => {
    const { cookie } = await cookieFor(user);
    // Un cookie persona demo non conta in enterprise e non produce l'header; quello del browser nemmeno.
    personaCookie = enc({ kind: "MEMBER", memberId: "MBR-000005" });
    const res = await GET(
      new NextRequest(`${ORIGIN}/api/lh/reward/v1/portal/coupons?size=5`, { headers: { cookie: `${cookie}; lh_persona=x`, "x-lh-member": "MBR-000009" } }),
      ctx("reward", ["v1", "portal", "coupons"]),
    );
    expect(res.status).toBe(200);
    expect(seen).toHaveLength(1);
    expect(Object.fromEntries(sent())).toEqual({ accept: "application/json", authorization: bearer, "x-correlation-id": expect.stringMatching(/^[0-9A-HJKMNP-TV-Z]{26}$/) });
  });

  it("[TB-WEB-PRX-035] POST del portale in enterprise con X-LH-Member del browser: inoltrato senza", async () => {
    const { cookie, csrf } = await cookieFor(MEMBER);
    const res = await POST(
      new NextRequest(`${ORIGIN}/api/lh/reward/v1/portal/redemptions`, {
        method: "POST",
        headers: { cookie, origin: ORIGIN, "x-lh-csrf": csrf, "content-type": "application/json", "X-LH-Member": "MBR-000009" },
        body: '{"rewardCode":"RWD-1"}',
      }),
      ctx("reward", ["v1", "portal", "redemptions"]),
    );
    expect(res.status).toBe(200);
    expect(sent().has("x-lh-member")).toBe(false);
    expect(sent().get("authorization")).toBe("Bearer AT-kc-2");
  });

  it("[TB-WEB-PRX-036] soprannomi in enterprise: nessuna chiamata a member-service porta X-LH-Member", async () => {
    const { cookie } = await cookieFor(OPERATOR);
    const res = await GET(
      new NextRequest(`${ORIGIN}/api/lh/gamification/v1/portal/leaderboards/LDB-MONTH-PTS`, { headers: { cookie, "x-lh-member": "MBR-000009" } }),
      ctx("gamification", ["v1", "portal", "leaderboards", "LDB-MONTH-PTS"]),
    );
    expect(res.status).toBe(200);
    expect(seen.length).toBeGreaterThan(0);
    for (let i = 0; i < seen.length; i++) expect(sent(i).has("x-lh-member")).toBe(false);
  });
});
