// @vitest-environment node
import { describe, expect, it, vi } from "vitest";
import { NextRequest } from "next/server";
import { createBff, type Bff } from "./bff";
import { parseAuthConfig, type Env, type EnterpriseAuthConfig } from "./config";
import { handleLogin } from "./handlers";
import type { LoginChecks, OidcClient } from "./oidc";

// HUB-02, ADR-051, Q-676: `login_hint` verso l'IdP solo per gli utenti di test noti, solo con testUsersAllowed, e solo
// per gli username del realm scelto; ogni altro valore è ignorato in silenzio.

const ORIGIN = "https://loyalty.lh.test";
const OPERATORS = "https://idp.lh.test/realms/loyaltyhub";
const MEMBERS = "https://idp.lh.test/realms/loyaltyhub-members";
const BASE: Env = {
  LH_PROFILE: "enterprise",
  LH_OIDC_ISSUER: OPERATORS,
  LH_WEB_CLIENT_SECRET: "s3cr3t-generato-dall-idp-0123456789",
  LH_WEB_URL: ORIGIN,
  LH_WEB_SESSION_KEY: Buffer.from(Array.from({ length: 32 }, (_, i) => i + 11)).toString("base64"),
  LH_OIDC_MEMBER_ISSUER: MEMBERS,
  LH_WEB_MEMBER_CLIENT_SECRET: "s3cr3t-del-portale-0123456789",
};
const TEST: Env = { ...BASE, LH_TEST_USERS_ALLOWED: "true", LH_ENVIRONMENT: "test" };

function setup(env: Env, realm: "operators" | "members") {
  const authorizationUrl = vi.fn(async (c: LoginChecks, extra?: Record<string, string>) => {
    const u = new URL(`${realm === "members" ? MEMBERS : OPERATORS}/protocol/openid-connect/auth?state=${c.state}`);
    for (const [k, v] of Object.entries(extra ?? {})) u.searchParams.set(k, v);
    return u;
  });
  const oidc = { clientId: "x", authorizationUrl } as unknown as OidcClient;
  const bff: Bff = createBff(parseAuthConfig(env) as EnterpriseAuthConfig, { oidc }, realm);
  return { bff, authorizationUrl };
}

async function login(bff: Bff, query: string) {
  const res = await handleLogin(new NextRequest(`${ORIGIN}/api/auth/login?${query}`), bff);
  return new URL(res.headers.get("location") ?? "");
}

describe("login_hint (HUB-02)", () => {
  it("ambiente di test: l'username noto del realm arriva all'IdP con prompt=login", async () => {
    const { bff } = setup(TEST, "operators");
    const url = await login(bff, "returnTo=%2Fbackoffice&login_hint=marta.admin");
    expect(url.searchParams.get("login_hint")).toBe("marta.admin");
    expect(url.searchParams.get("prompt")).toBe("login");
  });

  it("realm dei membri: valgono gli username dei membri, non quelli degli operatori", async () => {
    const { bff } = setup(TEST, "members");
    expect((await login(bff, "returnTo=%2Fportal&login_hint=anna.rossi")).searchParams.get("login_hint")).toBe("anna.rossi");
    expect((await login(bff, "returnTo=%2Fportal&login_hint=laura.conti")).searchParams.get("login_hint")).toBe("laura.conti");
    expect((await login(bff, "returnTo=%2Fportal&login_hint=marta.admin")).searchParams.has("login_hint")).toBe(false);
  });

  it("realm degli operatori: un username di membro è ignorato", async () => {
    const { bff } = setup(TEST, "operators");
    expect((await login(bff, "returnTo=%2Fbackoffice&login_hint=anna.rossi")).searchParams.has("login_hint")).toBe(false);
  });

  it("senza ambiente di test l'hint è ignorato anche se lo username è noto", async () => {
    const { bff, authorizationUrl } = setup(BASE, "operators");
    const url = await login(bff, "returnTo=%2Fbackoffice&login_hint=marta.admin");
    expect(url.searchParams.has("login_hint")).toBe(false);
    expect(url.searchParams.has("prompt")).toBe(false);
    expect(authorizationUrl.mock.calls[0]).toHaveLength(1);
  });

  it.each(["", "root", "marta.admin ", "MARTA.ADMIN", "marta.admin&prompt=none", "<script>", "admin@example.org"])(
    "valore non in elenco (%j): ignorato in silenzio",
    async (hint) => {
      const { bff } = setup(TEST, "operators");
      const res = await handleLogin(
        new NextRequest(`${ORIGIN}/api/auth/login?returnTo=%2Fbackoffice&login_hint=${encodeURIComponent(hint)}`),
        bff,
      );
      expect(res.status).toBe(303);
      expect(new URL(res.headers.get("location") ?? "").searchParams.has("login_hint")).toBe(false);
    },
  );
});
