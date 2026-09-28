// @vitest-environment node
import { beforeAll, describe, expect, it } from "vitest";
import { createLocalJWKSet, exportJWK, generateKeyPair, SignJWT, type CryptoKey, type JWTPayload } from "jose";
import { BACKCHANNEL_LOGOUT_EVENT, InvalidLogoutTokenError, ReplayCache, verifyLogoutToken, type LogoutTokenChecks } from "./logoutToken";

// Logout token del back-channel logout (OIDC Back-Channel Logout 1.0 §2.4/§2.6), con un JWKS generato qui: niente rete.

const ISSUER = "https://idp.lh.test/realms/loyaltyhub";
const NOW = 1_900_000_000;

let idpKey: CryptoKey;
let otherKey: CryptoKey;
let checks: LogoutTokenChecks;

beforeAll(async () => {
  const pair = await generateKeyPair("RS256");
  idpKey = pair.privateKey;
  otherKey = (await generateKeyPair("RS256")).privateKey;
  const jwk = { ...(await exportJWK(pair.publicKey)), kid: "idp-1", alg: "RS256" };
  checks = { issuer: ISSUER, clientId: "web", keys: createLocalJWKSet({ keys: [jwk] }), now: NOW };
});

function claims(overrides: Record<string, unknown> = {}): JWTPayload {
  const base: Record<string, unknown> = {
    iss: ISSUER,
    aud: "web",
    iat: NOW - 5,
    exp: NOW + 300,
    jti: "jti-1",
    sub: "user-1",
    sid: "sid-1",
    events: { [BACKCHANNEL_LOGOUT_EVENT]: {} },
    ...overrides,
  };
  for (const [k, v] of Object.entries(base)) if (v === undefined) delete base[k];
  return base as JWTPayload;
}

async function sign(payload: JWTPayload, options: { key?: CryptoKey; typ?: string; kid?: string } = {}): Promise<string> {
  const header: { alg: string; kid: string; typ?: string } = { alg: "RS256", kid: options.kid ?? "idp-1" };
  if (options.typ !== undefined) header.typ = options.typ;
  return new SignJWT(payload).setProtectedHeader(header).sign(options.key ?? idpKey);
}

describe("verifyLogoutToken", () => {
  it("token valido: chi disconnettere (sid e sub)", async () => {
    const target = await verifyLogoutToken(await sign(claims(), { typ: "logout+jwt" }), checks);
    expect(target).toEqual({ sid: "sid-1", sub: "user-1", jti: "jti-1", exp: NOW + 300 });
  });

  it("solo sub o solo sid: ammessi", async () => {
    expect((await verifyLogoutToken(await sign(claims({ sid: undefined })), checks)).sub).toBe("user-1");
    expect((await verifyLogoutToken(await sign(claims({ sub: undefined })), checks)).sid).toBe("sid-1");
  });

  it("typ JWT o assente ammesso (Keycloak), aud in array ammessa", async () => {
    await expect(verifyLogoutToken(await sign(claims(), { typ: "JWT" }), checks)).resolves.toBeTruthy();
    await expect(verifyLogoutToken(await sign(claims({ aud: ["web", "altro"] })), checks)).resolves.toBeTruthy();
  });

  it.each([
    ["emittente diverso", claims({ iss: "https://altro-idp.test/realms/x" })],
    ["audience di un altro client", claims({ aud: "widgets" })],
    ["senza evento di back-channel logout", claims({ events: { "http://altro/evento": {} } })],
    ["events non oggetto", claims({ events: "logout" })],
    ["con nonce (è un ID token)", claims({ nonce: "n-1" })],
    ["né sid né sub", claims({ sid: undefined, sub: undefined })],
    ["senza jti", claims({ jti: undefined })],
    ["senza exp", claims({ exp: undefined })],
    ["scaduto", claims({ iat: NOW - 400, exp: NOW - 100 })],
    ["emesso troppo tempo fa", claims({ iat: NOW - 600 })],
    ["emesso nel futuro", claims({ iat: NOW + 600 })],
  ])("rifiutato: %s", async (_, payload) => {
    await expect(verifyLogoutToken(await sign(payload), checks)).rejects.toBeInstanceOf(InvalidLogoutTokenError);
  });

  it("firmato con una chiave che non è dell'IdP", async () => {
    await expect(verifyLogoutToken(await sign(claims(), { key: otherKey }), checks)).rejects.toBeInstanceOf(InvalidLogoutTokenError);
  });

  it("typ di un altro token (access token) rifiutato", async () => {
    await expect(verifyLogoutToken(await sign(claims(), { typ: "at+jwt" }), checks)).rejects.toBeInstanceOf(InvalidLogoutTokenError);
  });

  it("alg none e HMAC rifiutati", async () => {
    const none = `${Buffer.from(JSON.stringify({ alg: "none" })).toString("base64url")}.${Buffer.from(JSON.stringify(claims())).toString("base64url")}.`;
    await expect(verifyLogoutToken(none, checks)).rejects.toBeInstanceOf(InvalidLogoutTokenError);
    const hmac = await new SignJWT(claims()).setProtectedHeader({ alg: "HS256" }).sign(new TextEncoder().encode("segreto-del-client-web-0123456789"));
    await expect(verifyLogoutToken(hmac, checks)).rejects.toBeInstanceOf(InvalidLogoutTokenError);
  });

  it("stringa qualunque rifiutata", async () => {
    await expect(verifyLogoutToken("non.un.jwt", checks)).rejects.toBeInstanceOf(InvalidLogoutTokenError);
  });
});

describe("ReplayCache", () => {
  it("lo stesso jti vale una volta sola finché non scade", () => {
    const cache = new ReplayCache();
    expect(cache.remember("a", NOW + 60, NOW)).toBe(true);
    expect(cache.remember("a", NOW + 60, NOW + 10)).toBe(false);
    expect(cache.remember("b", NOW + 60, NOW + 10)).toBe(true);
    // Dopo la scadenza (e la tolleranza) il jti si dimentica: il token sarebbe comunque rifiutato come scaduto.
    expect(cache.remember("a", NOW + 200, NOW + 120)).toBe(true);
  });

  it("dimensione limitata", () => {
    const cache = new ReplayCache(2);
    cache.remember("a", NOW + 60, NOW);
    cache.remember("b", NOW + 60, NOW);
    cache.remember("c", NOW + 60, NOW);
    expect(cache.remember("a", NOW + 60, NOW)).toBe(true);
    expect(cache.remember("c", NOW + 60, NOW)).toBe(false);
  });
});
