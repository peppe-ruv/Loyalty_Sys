// @vitest-environment node
import { afterEach, describe, expect, it, vi } from "vitest";
import { testMode } from "./testMode";
import { GET as totpGet } from "@/app/api/vetrina/totp/route";
import { OPERATORS_TOTP_SEED } from "./testUsers";
import { totp } from "./totp";
import { consoleTarget, memberConsoleUrl, realmName } from "@/lib/auth/idpConsole";

// HUB-02, ADR-051, Q-676: l'ambiente di test dichiarato, la route dell'OTP e l'indirizzo delle console di Keycloak.

const BASE = {
  LH_PROFILE: "enterprise",
  LH_OIDC_ISSUER: "https://idp.lh.test/realms/loyaltyhub",
  LH_WEB_CLIENT_SECRET: "s3cr3t-generato-dall-idp-0123456789",
  LH_WEB_URL: "https://loyalty.lh.test",
  LH_WEB_SESSION_KEY: Buffer.from(Array.from({ length: 32 }, (_, i) => i + 11)).toString("base64"),
  LH_OIDC_MEMBER_ISSUER: "https://idp2.lh.test/realms/loyaltyhub-members",
  LH_WEB_MEMBER_CLIENT_SECRET: "s3cr3t-del-portale-0123456789",
};
const TEST = { ...BASE, LH_TEST_USERS_ALLOWED: "true", LH_ENVIRONMENT: "test" };

afterEach(() => {
  vi.unstubAllEnvs();
  vi.useRealTimers();
});

function setEnv(env: Record<string, string>) {
  for (const k of ["LH_PROFILE", "LH_OIDC_ISSUER", "LH_WEB_CLIENT_SECRET", "LH_WEB_URL", "LH_WEB_SESSION_KEY", "LH_OIDC_MEMBER_ISSUER", "LH_WEB_MEMBER_CLIENT_SECRET", "LH_TEST_USERS_ALLOWED", "LH_ENVIRONMENT"]) {
    vi.stubEnv(k, env[k] ?? "");
  }
}

describe("testMode", () => {
  it("enterprise con le due variabili: realm e console ricavati dagli emittenti", () => {
    expect(testMode(TEST)).toEqual({
      operators: { realm: "loyaltyhub", url: "https://idp.lh.test/admin/loyaltyhub/console/" },
      members: { realm: "loyaltyhub-members", url: "https://idp2.lh.test/admin/loyaltyhub-members/console/" },
    });
  });
  it("senza il realm dei membri: members null", () => {
    const { LH_OIDC_MEMBER_ISSUER: _a, LH_WEB_MEMBER_CLIENT_SECRET: _b, ...single } = TEST;
    expect(testMode(single)?.members).toBeNull();
  });
  it.each([
    ["demo", { LH_TEST_USERS_ALLOWED: "true", LH_ENVIRONMENT: "test" }],
    ["enterprise senza le variabili", BASE],
    ["solo LH_ENVIRONMENT=test", { ...BASE, LH_ENVIRONMENT: "test" }],
    ["solo LH_TEST_USERS_ALLOWED", { ...BASE, LH_TEST_USERS_ALLOWED: "true" }],
    ["configurazione rifiutata (ambiente diverso da test)", { ...BASE, LH_TEST_USERS_ALLOWED: "true", LH_ENVIRONMENT: "prod" }],
    ["profilo sconosciuto", { ...TEST, LH_PROFILE: "boh" }],
  ])("%s ⇒ null", (_n, env) => expect(testMode(env)).toBeNull());
});

describe("GET /api/vetrina/totp", () => {
  it("404 fuori dall'ambiente di test (demo, enterprise normale), come problema RFC 9457 senza il seme", async () => {
    for (const env of [{ LH_PROFILE: "demo" }, BASE]) {
      setEnv(env);
      const res = totpGet();
      expect(res.status).toBe(404);
      expect(res.headers.get("content-type")).toBe("application/problem+json");
      expect(await res.text()).not.toContain(OPERATORS_TOTP_SEED);
    }
  });

  it("nell'ambiente di test: {code, remainingSeconds} da 6 cifre, no-store, mai il seme", async () => {
    setEnv(TEST);
    vi.useFakeTimers();
    vi.setSystemTime(1_700_000_010_000);
    const res = totpGet();
    expect(res.status).toBe(200);
    expect(res.headers.get("cache-control")).toBe("no-store");
    const text = await res.text();
    expect(text).not.toContain(OPERATORS_TOTP_SEED);
    expect(JSON.parse(text)).toEqual(totp(OPERATORS_TOTP_SEED, 1_700_000_010_000));
    expect(JSON.parse(text).code).toMatch(/^\d{6}$/);
  });

  it("espone solo GET", async () => {
    const mod = (await import("@/app/api/vetrina/totp/route")) as Record<string, unknown>;
    expect(Object.keys(mod).filter((k) => ["POST", "PUT", "PATCH", "DELETE"].includes(k))).toEqual([]);
  });
});

describe("memberConsoleUrl", () => {
  it("enterprise con il realm dei membri: origine dell'IdP dei membri + /admin/loyaltyhub-members/console/", () => {
    expect(memberConsoleUrl(BASE)).toBe("https://idp2.lh.test/admin/loyaltyhub-members/console/");
  });
  it("demo, un solo realm, configurazione rifiutata: null", () => {
    const { LH_OIDC_MEMBER_ISSUER: _a, LH_WEB_MEMBER_CLIENT_SECRET: _b, ...single } = BASE;
    expect(memberConsoleUrl({})).toBeNull();
    expect(memberConsoleUrl(single)).toBeNull();
    expect(memberConsoleUrl({ LH_PROFILE: "enterprise" })).toBeNull();
  });
});

describe("nome del realm dall'emittente", () => {
  it("si ricava da /realms/<nome>, qualunque sia, non da una costante", () => {
    expect(realmName(new URL("https://idp.lh.test/realms/altro-realm"))).toBe("altro-realm");
    expect(realmName(new URL("https://idp.lh.test/realms/altro-realm/"))).toBe("altro-realm");
    expect(consoleTarget(new URL("https://idp2.lh.test/realms/soci"))).toEqual({
      realm: "soci",
      url: "https://idp2.lh.test/admin/soci/console/",
    });
    const env = { ...BASE, LH_OIDC_MEMBER_ISSUER: "https://idp2.lh.test/realms/soci" };
    expect(memberConsoleUrl(env)).toBe("https://idp2.lh.test/admin/soci/console/");
    expect(testMode({ ...env, LH_TEST_USERS_ALLOWED: "true", LH_ENVIRONMENT: "test" })?.members?.realm).toBe("soci");
  });
  it.each(["https://idp.lh.test", "https://idp.lh.test/", "https://idp.lh.test/auth/realms/x", "https://idp.lh.test/realms/", "https://idp.lh.test/realms/a/b", "https://idp.lh.test/realms/a%2Fb"])(
    "percorso %s: nessuna console (null)",
    (issuer) => {
      expect(realmName(new URL(issuer))).toBeNull();
      expect(consoleTarget(new URL(issuer))).toBeNull();
    },
  );
  it("emittente dei membri con un percorso diverso: nessun indirizzo", () => {
    expect(memberConsoleUrl({ ...BASE, LH_OIDC_MEMBER_ISSUER: "https://idp2.lh.test/auth/realms/x" })).toBeNull();
  });
});
