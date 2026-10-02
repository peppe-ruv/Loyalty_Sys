// @vitest-environment node
import { beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { NextRequest, type NextResponse } from "next/server";
import { createLocalJWKSet, exportJWK, generateKeyPair, SignJWT, type CryptoKey } from "jose";
import { createBff, type Bff } from "./bff";
import { parseAuthConfig, type EnterpriseAuthConfig } from "./config";
import { csrfTokenFor } from "./csrf";
import { handleBackchannelLogout, handleCallback, handleLogin, handleLogout, userFromClaims } from "./handlers";
import { BACKCHANNEL_LOGOUT_EVENT, resilientKeys } from "./logoutToken";
import { LoginRejectedError, type LoginChecks, type OidcClient, type TokenSet } from "./oidc";

// Endpoint /api/auth/* con un IdP finto (nessuna rete): login con PKCE/state/nonce in cookie cifrato, callback con
// sessione nuova e cookie __Host-, logout con CSRF e id_token_hint, back-channel logout con logout token firmato.

const ORIGIN = "https://loyalty.lh.test";
const ISSUER = "https://idp.lh.test/realms/loyaltyhub";
const cfg = parseAuthConfig({
  LH_PROFILE: "enterprise",
  LH_OIDC_ISSUER: ISSUER,
  LH_WEB_CLIENT_SECRET: "s3cr3t-generato-dall-idp-0123456789",
  LH_WEB_URL: ORIGIN,
  LH_WEB_SESSION_KEY: Buffer.from(Array.from({ length: 32 }, (_, i) => i + 11)).toString("base64"),
}) as EnterpriseAuthConfig;

let idpKey: CryptoKey;
let jwks: ReturnType<typeof createLocalJWKSet>;

beforeAll(async () => {
  const pair = await generateKeyPair("ES256");
  idpKey = pair.privateKey;
  jwks = createLocalJWKSet({ keys: [{ ...(await exportJWK(pair.publicKey)), kid: "k1", alg: "ES256" }] });
});

const TOKENS: TokenSet = {
  accessToken: "AT-1",
  accessExpiresAt: Math.floor(Date.now() / 1000) + 300,
  refreshToken: "RT-1",
  idToken: "ID-TOKEN-1",
  claims: { sub: "kc-user-1", sid: "kc-sid-1", preferred_username: "luca.marketing", name: "Luca Serra", lh_roles: ["MARKETING"] },
};

let oidc: {
  authorizationUrl: ReturnType<typeof vi.fn>;
  exchangeCode: ReturnType<typeof vi.fn>;
  refresh: ReturnType<typeof vi.fn>;
  endSessionUrl: ReturnType<typeof vi.fn>;
  logoutVerification: ReturnType<typeof vi.fn>;
};
let bff: Bff;

beforeEach(() => {
  oidc = {
    authorizationUrl: vi.fn(async (c: LoginChecks) => new URL(`${ISSUER}/protocol/openid-connect/auth?state=${c.state}`)),
    exchangeCode: vi.fn(async () => TOKENS),
    refresh: vi.fn(),
    endSessionUrl: vi.fn(async (hint: string | null, back: string) => new URL(`${ISSUER}/logout?id_token_hint=${hint}&post_logout_redirect_uri=${encodeURIComponent(back)}`)),
    logoutVerification: vi.fn(async () => ({ issuer: ISSUER, keys: jwks })),
  };
  bff = createBff(cfg, { oidc: { clientId: "web", ...oidc } as unknown as OidcClient });
});

function cookieOf(res: NextResponse, name: string): string | undefined {
  return res.headers.getSetCookie().find((c) => c.startsWith(`${name}=`));
}

async function login(returnTo: string) {
  const res = await handleLogin(new NextRequest(`${ORIGIN}/api/auth/login?returnTo=${encodeURIComponent(returnTo)}`), bff);
  const flowCookie = cookieOf(res, "__Host-lh_auth");
  return { res, flow: flowCookie?.split(";")[0].slice("__Host-lh_auth=".length) ?? "" };
}

function callback(query: string, cookies: Record<string, string>) {
  const cookie = Object.entries(cookies).map(([k, v]) => `${k}=${v}`).join("; ");
  return handleCallback(new NextRequest(`http://interno:3000/api/auth/callback?${query}`, { headers: { cookie } }), bff);
}

describe("GET /api/auth/login", () => {
  it("redirect 303 all'IdP; stato del login in un cookie __Host- cifrato, HttpOnly, Lax, 10 minuti", async () => {
    const { res, flow } = await login("/backoffice/campaigns");
    expect(res.status).toBe(303);
    expect(res.headers.get("location")).toMatch(new RegExp(`^${ISSUER}/protocol/openid-connect/auth\\?state=`));
    expect(res.headers.get("cache-control")).toBe("no-store");
    const set = cookieOf(res, "__Host-lh_auth")!;
    expect(set).toMatch(/; Path=\//);
    expect(set).toMatch(/; Secure/);
    expect(set).toMatch(/; HttpOnly/);
    expect(set).toMatch(/; SameSite=lax/i);
    expect(set).toMatch(/; Max-Age=600/);
    expect(set).not.toMatch(/Domain=/i);
    // Il cookie non rivela state, nonce o verifier.
    const checks = oidc.authorizationUrl.mock.calls[0][0] as LoginChecks;
    for (const secret of [checks.state, checks.nonce, checks.codeVerifier]) expect(flow).not.toContain(secret);
    expect(checks.codeVerifier).toMatch(/^[A-Za-z0-9_-]{43,128}$/);
    expect(new Set([checks.state, checks.nonce, checks.codeVerifier]).size).toBe(3);
  });

  it("IdP non raggiungibile: pagina d'errore, nessun cookie", async () => {
    oidc.authorizationUrl.mockRejectedValueOnce(new TypeError("fetch failed"));
    vi.spyOn(console, "error").mockImplementationOnce(() => undefined);
    const { res, flow } = await login("/portal");
    expect(res.headers.get("location")).toBe(`${ORIGIN}/auth/error?reason=idp_unavailable&returnTo=%2Fportal`);
    expect(flow).toBe("");
  });
});

describe("GET /api/auth/callback", () => {
  it("sessione nuova, cookie __Host- corretti, ritorno alla pagina chiesta sull'origine pubblica", async () => {
    const { flow } = await login("/backoffice/campaigns?tab=draft");
    const res = await callback("code=abc&state=xyz&iss=x", { "__Host-lh_auth": flow });
    expect(res.status).toBe(303);
    expect(res.headers.get("location")).toBe(`${ORIGIN}/backoffice/campaigns?tab=draft`);

    // Scambio del codice con gli stessi controlli del login e l'URL di callback pubblico (non l'host interno).
    const [callbackUrl, checks] = oidc.exchangeCode.mock.calls[0] as [URL, LoginChecks];
    expect(callbackUrl.href).toBe(`${ORIGIN}/api/auth/callback?code=abc&state=xyz&iss=x`);
    expect(checks).toEqual(oidc.authorizationUrl.mock.calls[0][0]);

    const session = cookieOf(res, "__Host-lh_session")!;
    expect(session).toMatch(/^__Host-lh_session=[A-Za-z0-9_-]{43}; Path=\/; Secure; HttpOnly; SameSite=lax$/);
    const id = session.split(";")[0].split("=")[1];
    const csrf = cookieOf(res, "__Host-lh_csrf")!;
    expect(csrf).toBe(`__Host-lh_csrf=${csrfTokenFor(id, bff.csrfKey)}; Path=/; Secure; SameSite=lax`);
    expect(cookieOf(res, "__Host-lh_auth")).toMatch(/^__Host-lh_auth=; Path=\/; Expires=Thu, 01 Jan 1970/);
    // Nessun token nella risposta al browser.
    const raw = JSON.stringify([...res.headers]);
    for (const token of ["AT-1", "RT-1", "ID-TOKEN-1"]) expect(raw).not.toContain(token);

    const stored = await bff.store.get(id);
    expect(stored?.user).toEqual({
      sub: "kc-user-1",
      sid: "kc-sid-1",
      username: "luca.marketing",
      name: "Luca Serra",
      // Claim di profilo per precompilare la registrazione del membro (PT-16): assenti nel token di prova.
      givenName: null,
      familyName: null,
      email: null,
      roles: ["MARKETING"],
      role: "MARKETING",
      kind: "operator",
    });
    expect(stored?.tokens.refreshToken).toBe("RT-1");
  });

  it("session fixation: la sessione presentata dal browser viene chiusa e non riusata", async () => {
    const old = await bff.store.create(userFromClaims(TOKENS.claims)!, { accessToken: "OLD", accessExpiresAt: 0, refreshToken: null, idToken: "x" });
    const { flow } = await login("/");
    const res = await callback("code=abc&state=xyz", { "__Host-lh_auth": flow, "__Host-lh_session": old });
    expect(await bff.store.get(old)).toBeNull();
    expect(cookieOf(res, "__Host-lh_session")).not.toContain(old);
  });

  it.each([
    ["cookie assente", undefined],
    ["cookie alterato", "manomesso"],
  ])("stato del login %s: errore «expired», nessuno scambio del codice", async (_, flow) => {
    const res = await callback("code=abc&state=xyz", flow ? { "__Host-lh_auth": flow } : {});
    expect(res.headers.get("location")).toBe(`${ORIGIN}/auth/error?reason=expired&returnTo=%2F`);
    expect(oidc.exchangeCode).not.toHaveBeenCalled();
    expect(cookieOf(res, "__Host-lh_session")).toBeUndefined();
  });

  it("stato del login oltre 10 minuti: «expired»", async () => {
    const { flow } = await login("/portal");
    vi.useFakeTimers({ now: Date.now() + 11 * 60 * 1000 });
    try {
      const res = await callback("code=abc&state=xyz", { "__Host-lh_auth": flow });
      expect(res.headers.get("location")).toContain("reason=expired");
    } finally {
      vi.useRealTimers();
    }
  });

  it.each([
    ["access_denied", "denied"],
    ["invalid_grant", "rejected"],
  ])("IdP risponde %s ⇒ «%s»", async (error, reason) => {
    oidc.exchangeCode.mockRejectedValueOnce(new LoginRejectedError(error));
    const { flow } = await login("/portal");
    const res = await callback("error=x&state=xyz", { "__Host-lh_auth": flow });
    expect(res.headers.get("location")).toBe(`${ORIGIN}/auth/error?reason=${reason}&returnTo=%2Fportal`);
    expect(cookieOf(res, "__Host-lh_session")).toBeUndefined();
  });

  it("ID token senza sub ⇒ «rejected»", async () => {
    oidc.exchangeCode.mockResolvedValueOnce({ ...TOKENS, claims: { preferred_username: "x" } });
    const { flow } = await login("/portal");
    const res = await callback("code=abc&state=xyz", { "__Host-lh_auth": flow });
    expect(res.headers.get("location")).toContain("reason=rejected");
  });

  it("un membro (solo MEMBER) ha una sessione di tipo membro", () => {
    expect(userFromClaims({ sub: "m1", lh_roles: ["MEMBER"] })).toMatchObject({ kind: "member", role: "ANALYST", username: "m1", sid: null });
  });
});

describe("POST /api/auth/logout", () => {
  async function withSession() {
    const id = await bff.store.create(userFromClaims(TOKENS.claims)!, { accessToken: "AT", accessExpiresAt: 0, refreshToken: "RT", idToken: "ID-TOKEN-1" });
    return { id, cookie: `__Host-lh_session=${id}`, token: csrfTokenFor(id, bff.csrfKey) };
  }

  const form = (fields: Record<string, string>) => new URLSearchParams(fields).toString();
  const logout = (headers: Record<string, string>, body = "") =>
    handleLogout(
      new NextRequest(`${ORIGIN}/api/auth/logout`, {
        method: "POST",
        headers: { "content-type": "application/x-www-form-urlencoded", ...headers },
        body,
      }),
      bff,
    );

  it("modulo con Origin e token CSRF: sessione chiusa, cookie cancellati, 303 lato server verso il logout dell'IdP", async () => {
    const { id, cookie, token } = await withSession();
    const res = await logout({ cookie, origin: ORIGIN, "sec-fetch-site": "same-origin" }, form({ csrf: token }));
    expect(res.status).toBe(303);
    expect(res.headers.get("location")).toBe(`${ISSUER}/logout?id_token_hint=ID-TOKEN-1&post_logout_redirect_uri=${encodeURIComponent(`${ORIGIN}/`)}`);
    expect(res.headers.get("cache-control")).toBe("no-store");
    // Nessun corpo leggibile dal JavaScript: l'ID token viaggia solo nell'header Location verso l'IdP.
    expect(await res.text()).not.toContain("ID-TOKEN-1");
    expect(await bff.store.get(id)).toBeNull();
    expect(cookieOf(res, "__Host-lh_session")).toMatch(/Expires=Thu, 01 Jan 1970.*Secure; HttpOnly/);
    expect(cookieOf(res, "__Host-lh_csrf")).toMatch(/Expires=Thu, 01 Jan 1970/);
  });

  it.each([
    ["senza token CSRF", { origin: ORIGIN }, ""],
    ["token sbagliato", { origin: ORIGIN }, "csrf=abc"],
    ["da un altro sito", { origin: "https://attaccante.example" }, "csrf=TOKEN"],
    ["Sec-Fetch-Site cross-site", { origin: ORIGIN, "sec-fetch-site": "cross-site" }, "csrf=TOKEN"],
  ])("%s: 303 verso /auth/error e sessione intatta", async (_, headers, body) => {
    const { id, cookie, token } = await withSession();
    const res = await logout({ cookie, ...headers }, body.replace("TOKEN", token));
    expect(res.status).toBe(303);
    expect(res.headers.get("location")).toBe(`${ORIGIN}/auth/error?reason=logout_failed&returnTo=%2F`);
    expect(await bff.store.get(id)).not.toBeNull();
    expect(cookieOf(res, "__Host-lh_session")).toBeUndefined();
  });

  it("il token CSRF vale anche come header (client che non usa il modulo)", async () => {
    const { id, cookie, token } = await withSession();
    const res = await logout({ cookie, origin: ORIGIN, "x-lh-csrf": token });
    expect(res.status).toBe(303);
    expect(await bff.store.get(id)).toBeNull();
  });

  it("senza sessione: nessun errore, ritorno alla pagina iniziale", async () => {
    const res = await logout({ origin: ORIGIN });
    expect(res.status).toBe(303);
    expect(res.headers.get("location")).toBe(`${ORIGIN}/`);
  });
});

describe("POST /api/auth/backchannel-logout", () => {
  const now = () => Math.floor(Date.now() / 1000);
  const logoutToken = (claims: Record<string, unknown> = {}) =>
    new SignJWT({ iss: ISSUER, aud: "web", iat: now(), exp: now() + 120, jti: crypto.randomUUID(), sid: "kc-sid-1", sub: "kc-user-1", events: { [BACKCHANNEL_LOGOUT_EVENT]: {} }, ...claims })
      .setProtectedHeader({ alg: "ES256", kid: "k1", typ: "logout+jwt" })
      .sign(idpKey);
  const post = (body: string, contentType = "application/x-www-form-urlencoded") =>
    handleBackchannelLogout(new NextRequest(`${ORIGIN}/api/auth/backchannel-logout`, { method: "POST", headers: { "content-type": contentType }, body }), bff);

  it("token valido: chiude le sessioni con quel sid, 200 senza cache", async () => {
    const user = userFromClaims(TOKENS.claims)!;
    const mine = await bff.store.create(user, { accessToken: "a", accessExpiresAt: 0, refreshToken: null, idToken: "i" });
    const other = await bff.store.create({ ...user, sid: "altra-sessione-idp" }, { accessToken: "a", accessExpiresAt: 0, refreshToken: null, idToken: "i" });
    const res = await post(`logout_token=${await logoutToken()}`);
    expect(res.status).toBe(200);
    expect(res.headers.get("cache-control")).toBe("no-store");
    expect(await bff.store.get(mine)).toBeNull();
    expect(await bff.store.get(other)).not.toBeNull();
  });

  it("solo sub: chiude tutte le sessioni di quell'utente (deprovisioning)", async () => {
    const user = userFromClaims(TOKENS.claims)!;
    const a = await bff.store.create(user, { accessToken: "a", accessExpiresAt: 0, refreshToken: null, idToken: "i" });
    const b = await bff.store.create({ ...user, sid: "s2" }, { accessToken: "a", accessExpiresAt: 0, refreshToken: null, idToken: "i" });
    expect((await post(`logout_token=${await logoutToken({ sid: undefined })}`)).status).toBe(200);
    expect(await bff.store.get(a)).toBeNull();
    expect(await bff.store.get(b)).toBeNull();
  });

  it("lo stesso token due volte: la seconda è 400", async () => {
    const token = await logoutToken();
    expect((await post(`logout_token=${token}`)).status).toBe(200);
    const replay = await post(`logout_token=${token}`);
    expect(replay.status).toBe(400);
    expect(await replay.json()).toEqual({ error: "invalid_request", error_description: "logout token già usato" });
  });

  it.each([
    ["content-type JSON", async () => post(JSON.stringify({ logout_token: await logoutToken() }), "application/json")],
    ["logout_token assente", async () => post("altro=1")],
    ["token con nonce", async () => post(`logout_token=${await logoutToken({ nonce: "n" })}`)],
    ["token per un altro client", async () => post(`logout_token=${await logoutToken({ aud: "widgets" })}`)],
    ["corpo oltre 16 KiB", async () => post(`logout_token=${"a".repeat(17 * 1024)}`)],
  ])("%s: 400 e nessuna sessione chiusa", async (_, send) => {
    const id = await bff.store.create(userFromClaims(TOKENS.claims)!, { accessToken: "a", accessExpiresAt: 0, refreshToken: null, idToken: "i" });
    const res = await send();
    expect(res.status).toBe(400);
    expect(await bff.store.get(id)).not.toBeNull();
  });

  it("JWKS non scaricabile durante la verifica (rete, timeout): 503 ritentabile, nessuna sessione chiusa", async () => {
    const failing = resilientKeys(async () => {
      throw new TypeError("fetch failed");
    });
    oidc.logoutVerification.mockResolvedValueOnce({ issuer: ISSUER, keys: failing });
    vi.spyOn(console, "error").mockImplementationOnce(() => undefined);
    const id = await bff.store.create(userFromClaims(TOKENS.claims)!, { accessToken: "a", accessExpiresAt: 0, refreshToken: null, idToken: "i" });
    const res = await post(`logout_token=${await logoutToken()}`);
    expect(res.status).toBe(503);
    expect((await res.json()).error).toBe("temporarily_unavailable");
    expect(await bff.store.get(id)).not.toBeNull();
  });

  it("chiave sconosciuta (kid non nel JWKS): 400, non 503", async () => {
    const other = await generateKeyPair("ES256");
    const token = await new SignJWT({ iss: ISSUER, aud: "web", iat: now(), exp: now() + 120, jti: "j-x", sid: "s", events: { [BACKCHANNEL_LOGOUT_EVENT]: {} } })
      .setProtectedHeader({ alg: "ES256", kid: "sconosciuta" })
      .sign(other.privateKey);
    oidc.logoutVerification.mockResolvedValueOnce({ issuer: ISSUER, keys: resilientKeys(jwks) });
    expect((await post(`logout_token=${token}`)).status).toBe(400);
  });

  it("chiavi dell'IdP non disponibili: 503", async () => {
    oidc.logoutVerification.mockRejectedValueOnce(new TypeError("fetch failed"));
    vi.spyOn(console, "error").mockImplementationOnce(() => undefined);
    expect((await post(`logout_token=${await logoutToken()}`)).status).toBe(503);
  });
});
