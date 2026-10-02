// @vitest-environment node
import { describe, expect, it, vi } from "vitest";
import { NextRequest, type NextResponse } from "next/server";
import { createBff } from "./bff";
import { readCsrfToken, csrfHeaders } from "./browser";
import { InsecureConfigError, parseAuthConfig, type EnterpriseAuthConfig, type Env } from "./config";
import { handleCallback, handleLogin } from "./handlers";
import type { LoginChecks, OidcClient, TokenSet } from "./oidc";
import { parseRealm, realmForApi, realmForPage } from "./realm";

// Due realm (ADR-051 decisione 6): il portale fa login nel realm dei membri con il suo client e una sessione separata;
// senza `LH_OIDC_MEMBER_ISSUER` tutto resta come prima, con un solo realm.

const ORIGIN = "https://loyalty.lh.test";
const OPERATORS = "https://idp.lh.test/realms/loyaltyhub";
const MEMBERS = "https://idp.lh.test/realms/loyaltyhub-members";
const KEY = Buffer.from(Array.from({ length: 32 }, (_, i) => i + 7)).toString("base64");

const BASE: Env = {
  LH_PROFILE: "enterprise",
  LH_OIDC_ISSUER: OPERATORS,
  LH_WEB_CLIENT_SECRET: "s3cr3t-generato-dall-idp-0123456789",
  LH_WEB_URL: ORIGIN,
  LH_WEB_SESSION_KEY: KEY,
};
const TWO_REALMS: Env = { ...BASE, LH_OIDC_MEMBER_ISSUER: MEMBERS, LH_WEB_MEMBER_CLIENT_SECRET: "s3cr3t-del-portale-0123456789" };

function problemsOf(env: Env): string[] {
  try {
    parseAuthConfig(env);
  } catch (err) {
    expect(err).toBeInstanceOf(InsecureConfigError);
    return [...(err as InsecureConfigError).problems];
  }
  throw new Error("configurazione accettata");
}

describe("configurazione del realm dei membri", () => {
  it("assente: un solo realm, come prima", () => {
    expect((parseAuthConfig(BASE) as EnterpriseAuthConfig).members).toBeNull();
  });

  it("presente: emittente, client predefinito `portal` e segreto", () => {
    const cfg = parseAuthConfig(TWO_REALMS) as EnterpriseAuthConfig;
    expect(cfg.members?.issuer.href).toBe(MEMBERS);
    expect(cfg.members?.clientId).toBe("portal");
    expect(cfg.members?.allowInsecureIssuer).toBe(false);
  });

  it("segreto da file, anche per il client del portale", () => {
    const env = { ...TWO_REALMS, LH_WEB_MEMBER_CLIENT_SECRET: undefined, LH_WEB_MEMBER_CLIENT_SECRET_FILE: "/run/secrets/portal" };
    const cfg = parseAuthConfig(env, (p) => (p === "/run/secrets/portal" ? "segreto-portale-da-file-0123\n" : "")) as EnterpriseAuthConfig;
    expect(cfg.members?.clientSecret).toBe("segreto-portale-da-file-0123");
  });

  it("insicuro o incompleto: INSECURE_CONFIG, mai un ripiego su un solo realm", () => {
    expect(problemsOf({ ...TWO_REALMS, LH_WEB_MEMBER_CLIENT_SECRET: undefined })).toContain(
      "LH_WEB_MEMBER_CLIENT_SECRET mancante (oppure LH_WEB_MEMBER_CLIENT_SECRET_FILE)",
    );
    expect(problemsOf({ ...TWO_REALMS, LH_WEB_MEMBER_CLIENT_SECRET: "corto" })).toContain(
      "LH_WEB_MEMBER_CLIENT_SECRET troppo corto (almeno 16 caratteri)",
    );
    expect(problemsOf({ ...TWO_REALMS, LH_OIDC_MEMBER_ISSUER: "http://idp.lh.test/realms/loyaltyhub-members" })).toContain(
      "LH_OIDC_MEMBER_ISSUER deve usare https (http solo verso localhost)",
    );
    expect(problemsOf({ ...TWO_REALMS, LH_OIDC_MEMBER_ISSUER: `${OPERATORS}/` })).toContain(
      "LH_OIDC_MEMBER_ISSUER deve essere un realm diverso da LH_OIDC_ISSUER",
    );
  });

  it("l'impronta cambia con il realm dei membri (BFF ricreato)", () => {
    const one = parseAuthConfig(BASE) as EnterpriseAuthConfig;
    const two = parseAuthConfig(TWO_REALMS) as EnterpriseAuthConfig;
    expect(one.fingerprint).not.toBe(two.fingerprint);
  });
});

describe("realm di pagine e API", () => {
  it("il portale e le sue API sono del realm dei membri, tutto il resto degli operatori", () => {
    expect(realmForPage("/portal")).toBe("members");
    expect(realmForPage("/portal/wallet?tab=1")).toBe("members");
    expect(realmForPage("/portale")).toBe("operators");
    expect(realmForPage("/backoffice")).toBe("operators");
    expect(realmForPage("/")).toBe("operators");
    expect(realmForApi(["v1", "portal", "me"])).toBe("members");
    expect(realmForApi(["v1", "members"])).toBe("operators");
    expect(parseRealm("members")).toBe("members");
    expect(parseRealm("master")).toBeNull();
  });
});

const fakeOidc = (issuer: string, claims: Record<string, unknown>) =>
  ({
    clientId: "x",
    authorizationUrl: vi.fn(async (c: LoginChecks) => new URL(`${issuer}/protocol/openid-connect/auth?state=${c.state}`)),
    exchangeCode: vi.fn(async (): Promise<TokenSet> => ({
      accessToken: "AT",
      accessExpiresAt: Math.floor(Date.now() / 1000) + 300,
      refreshToken: "RT",
      idToken: "ID",
      claims: { sub: "kc-1", ...claims },
    })),
    refresh: vi.fn(),
    endSessionUrl: vi.fn(),
    logoutVerification: vi.fn(),
  }) as unknown as OidcClient;

function cookieValue(res: NextResponse, name: string): string | undefined {
  return res.headers.getSetCookie().find((c) => c.startsWith(`${name}=`))?.split(";")[0].slice(name.length + 1);
}

async function loginAndCallback(realm: "operators" | "members", env: Env, claims: Record<string, unknown>) {
  const cfg = parseAuthConfig(env) as EnterpriseAuthConfig;
  const bff = createBff(cfg, { oidc: fakeOidc(realm === "members" ? MEMBERS : OPERATORS, claims) }, realm);
  const login = await handleLogin(new NextRequest(`${ORIGIN}/api/auth/login?returnTo=%2Fportal`), bff);
  const flow = cookieValue(login, bff.cookies.flow) ?? "";
  const state = new URL(login.headers.get("location") ?? "").searchParams.get("state");
  const res = await handleCallback(
    new NextRequest(`${ORIGIN}${bff.callbackPath}?code=c&state=${state}`, { headers: { cookie: `${bff.cookies.flow}=${flow}` } }),
    bff,
  );
  return { bff, res };
}

describe("BFF con due realm", () => {
  it("realm dei membri: client, callback, cookie e chiavi propri", () => {
    const cfg = parseAuthConfig(TWO_REALMS) as EnterpriseAuthConfig;
    const operators = createBff(cfg, {}, "operators");
    const members = createBff(cfg, {}, "members");
    expect(members.realm).toBe("members");
    expect(members.cfg.issuer.href).toBe(MEMBERS);
    expect(members.cfg.clientId).toBe("portal");
    expect(members.callbackPath).toBe("/api/auth/callback/members");
    expect(members.cookies).toEqual({ session: "__Host-lh_msession", csrf: "__Host-lh_mcsrf", flow: "__Host-lh_mauth" });
    expect(members.csrfKey.equals(operators.csrfKey)).toBe(false);
    expect(members.flowKey.equals(operators.flowKey)).toBe(false);
    expect(members.store).not.toBe(operators.store);
  });

  it("un solo realm: il portale usa il BFF degli operatori, come prima", () => {
    const cfg = parseAuthConfig(BASE) as EnterpriseAuthConfig;
    const members = createBff(cfg, {}, "members");
    expect(members.realm).toBe("operators");
    expect(members.cookies.session).toBe("__Host-lh_session");
    expect(members.callbackPath).toBe("/api/auth/callback");
  });

  it("login del membro nel suo realm: sessione nei cookie del portale, non in quelli del backoffice", async () => {
    const { res } = await loginAndCallback("members", TWO_REALMS, { lh_roles: ["MEMBER"], preferred_username: "anna" });
    expect(res.status).toBe(303);
    expect(new URL(res.headers.get("location") ?? "").pathname).toBe("/portal");
    expect(cookieValue(res, "__Host-lh_msession")).toBeTruthy();
    expect(cookieValue(res, "__Host-lh_mcsrf")).toBeTruthy();
    expect(cookieValue(res, "__Host-lh_session")).toBeUndefined();
  });

  it("account del tipo sbagliato per il realm: login rifiutato", async () => {
    const operatorInMembers = await loginAndCallback("members", TWO_REALMS, { lh_roles: ["ADMIN"] });
    expect(new URL(operatorInMembers.res.headers.get("location") ?? "").searchParams.get("reason")).toBe("rejected");
    const memberInOperators = await loginAndCallback("operators", TWO_REALMS, { lh_roles: ["MEMBER"] });
    expect(new URL(memberInOperators.res.headers.get("location") ?? "").searchParams.get("reason")).toBe("rejected");
  });

  it("un solo realm: il membro entra ancora dalla callback degli operatori", async () => {
    const { res } = await loginAndCallback("operators", BASE, { lh_roles: ["MEMBER"] });
    expect(cookieValue(res, "__Host-lh_session")).toBeTruthy();
  });
});

describe("utenti di test (Q-676)", () => {
  it("LH_TEST_USERS_ALLOWED solo con LH_ENVIRONMENT=test", () => {
    expect(problemsOf({ ...BASE, LH_TEST_USERS_ALLOWED: "true" })).toContain(
      "LH_TEST_USERS_ALLOWED=true vale solo con LH_ENVIRONMENT=test (credenziali di test pubbliche, Q-676)",
    );
    expect((parseAuthConfig({ ...BASE, LH_TEST_USERS_ALLOWED: "true", LH_ENVIRONMENT: "test" }) as EnterpriseAuthConfig).testUsersAllowed).toBe(true);
    expect((parseAuthConfig(BASE) as EnterpriseAuthConfig).testUsersAllowed).toBe(false);
  });

  it("login di un utente di test rifiutato in ogni realm, salvo nell'ambiente di test", async () => {
    const op = await loginAndCallback("operators", TWO_REALMS, { lh_roles: ["ADMIN", "LH_TEST_USER"] });
    expect(new URL(op.res.headers.get("location") ?? "").searchParams.get("reason")).toBe("rejected");
    const mem = await loginAndCallback("members", TWO_REALMS, { lh_roles: ["MEMBER", "LH_TEST_USER"] });
    expect(new URL(mem.res.headers.get("location") ?? "").searchParams.get("reason")).toBe("rejected");
    const allowed = await loginAndCallback("members", { ...TWO_REALMS, LH_TEST_USERS_ALLOWED: "true", LH_ENVIRONMENT: "test" }, {
      lh_roles: ["MEMBER", "LH_TEST_USER"],
    });
    expect(cookieValue(allowed.res, "__Host-lh_msession")).toBeTruthy();
  });
});

describe("token CSRF nel browser", () => {
  it("le API del portale usano il cookie dei membri, con ripiego su quello degli operatori (un solo realm)", () => {
    const both = "__Host-lh_csrf=op; __Host-lh_mcsrf=mem";
    expect(readCsrfToken(both)).toBe("op");
    expect(readCsrfToken(both, "members")).toBe("mem");
    expect(readCsrfToken("__Host-lh_csrf=op", "members")).toBe("op");
    expect(csrfHeaders("POST", both, "members")).toEqual({ "X-LH-CSRF": "mem" });
    expect(csrfHeaders("GET", both, "members")).toEqual({});
  });
});
