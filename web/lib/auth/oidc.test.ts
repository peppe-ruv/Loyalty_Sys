// @vitest-environment node
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { exportJWK, generateKeyPair, SignJWT, type CryptoKey, type JWK } from "jose";
import { parseAuthConfig, type EnterpriseAuthConfig } from "./config";
import { createOidcClient, isUnavailable, LoginRejectedError, RefreshRejectedError, type OidcClient } from "./oidc";

// Client OIDC reale (openid-client) contro un IdP finto servito da un `fetch` sostituito: niente rete.
// Verifica ciò che la libreria deve fare per noi: PKCE S256, state, nonce, client confidential, validazione
// dell'ID token, rinnovo, logout con id_token_hint.

const ISSUER = "https://idp.lh.test/realms/loyaltyhub";
const ORIGIN = "https://loyalty.lh.test";
const SECRET = "s3cr3t-generato-dall-idp-0123456789";
const cfg = parseAuthConfig({
  LH_PROFILE: "enterprise",
  LH_OIDC_ISSUER: ISSUER,
  LH_WEB_CLIENT_SECRET: SECRET,
  LH_WEB_URL: ORIGIN,
  LH_WEB_SESSION_KEY: Buffer.from(Array.from({ length: 32 }, (_, i) => i + 40)).toString("base64"),
}) as EnterpriseAuthConfig;

const DISCOVERY = {
  issuer: ISSUER,
  authorization_endpoint: `${ISSUER}/protocol/openid-connect/auth`,
  token_endpoint: `${ISSUER}/protocol/openid-connect/token`,
  jwks_uri: `${ISSUER}/protocol/openid-connect/certs`,
  end_session_endpoint: `${ISSUER}/protocol/openid-connect/logout`,
  id_token_signing_alg_values_supported: ["RS256"],
};

let key: CryptoKey;
let jwk: JWK;
let tokenResponse: () => Response;
let tokenRequests: { headers: Headers; body: URLSearchParams }[];
let discoveryCalls: number;
let oidc: OidcClient;

beforeAll(async () => {
  const pair = await generateKeyPair("RS256");
  key = pair.privateKey;
  jwk = { ...(await exportJWK(pair.publicKey)), kid: "k1", alg: "RS256", use: "sig" };
});

beforeEach(() => {
  tokenRequests = [];
  discoveryCalls = 0;
  oidc = createOidcClient(cfg);
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
      const url = String(input instanceof Request ? input.url : input);
      if (url === `${ISSUER}/.well-known/openid-configuration`) {
        discoveryCalls++;
        return Response.json(DISCOVERY);
      }
      if (url === DISCOVERY.jwks_uri) return Response.json({ keys: [jwk] });
      if (url === DISCOVERY.token_endpoint) {
        tokenRequests.push({ headers: new Headers(init?.headers), body: new URLSearchParams(String(init?.body)) });
        return tokenResponse();
      }
      throw new TypeError(`rete non disponibile nei test: ${url}`);
    }),
  );
});
afterEach(() => vi.unstubAllGlobals());

async function idToken(claims: Record<string, unknown>) {
  const now = Math.floor(Date.now() / 1000);
  return new SignJWT({ iss: ISSUER, aud: "web", sub: "kc-user-1", iat: now, exp: now + 300, ...claims })
    .setProtectedHeader({ alg: "RS256", kid: "k1" })
    .sign(key);
}

const checks = { state: "stato-casuale", nonce: "nonce-casuale", codeVerifier: "v".repeat(43) };

describe("createOidcClient", () => {
  it("URL di autorizzazione: code + PKCE S256 + state + nonce + redirect URI pubblico; discovery una volta sola", async () => {
    const [a] = await Promise.all([oidc.authorizationUrl(checks), oidc.authorizationUrl(checks)]);
    expect(discoveryCalls).toBe(1);
    expect(a.origin + a.pathname).toBe(DISCOVERY.authorization_endpoint);
    const p = a.searchParams;
    expect(p.get("response_type")).toBe("code");
    expect(p.get("client_id")).toBe("web");
    expect(p.get("redirect_uri")).toBe(`${ORIGIN}/api/auth/callback`);
    expect(p.get("scope")).toBe("openid");
    expect(p.get("state")).toBe("stato-casuale");
    expect(p.get("nonce")).toBe("nonce-casuale");
    expect(p.get("code_challenge_method")).toBe("S256");
    expect(p.get("code_challenge")).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(p.get("code_challenge")).not.toBe(checks.codeVerifier);
    expect(a.href).not.toContain(SECRET);
  });

  it("discovery fallita: non resta in cache, il tentativo dopo riprova", async () => {
    const real = globalThis.fetch;
    vi.stubGlobal("fetch", vi.fn(async () => { throw new TypeError("fetch failed"); }));
    await expect(oidc.authorizationUrl(checks)).rejects.toThrow();
    vi.stubGlobal("fetch", real);
    await expect(oidc.authorizationUrl(checks)).resolves.toBeInstanceOf(URL);
  });

  it("scambio del codice: client confidential (Basic), code_verifier inviato, ID token validato", async () => {
    const id = await idToken({ nonce: "nonce-casuale", sid: "kc-sid", lh_roles: ["LEGAL"] });
    tokenResponse = () => Response.json({ access_token: "AT", token_type: "Bearer", expires_in: 300, refresh_token: "RT", id_token: id });
    const tokens = await oidc.exchangeCode(new URL(`${ORIGIN}/api/auth/callback?code=c-1&state=stato-casuale`), checks);
    expect(tokens).toMatchObject({ accessToken: "AT", refreshToken: "RT", idToken: id });
    expect(tokens.claims).toMatchObject({ sub: "kc-user-1", sid: "kc-sid", lh_roles: ["LEGAL"] });
    expect(tokens.accessExpiresAt).toBeGreaterThan(Math.floor(Date.now() / 1000) + 290);
    const [req] = tokenRequests;
    // RFC 6749 §2.3.1: id e segreto codificati form-urlencoded prima del base64.
    const basic = Buffer.from(req.headers.get("authorization")!.replace(/^Basic /, ""), "base64").toString();
    expect(basic.split(":").map(decodeURIComponent)).toEqual(["web", SECRET]);
    expect(req.body.get("grant_type")).toBe("authorization_code");
    expect(req.body.get("code")).toBe("c-1");
    expect(req.body.get("code_verifier")).toBe(checks.codeVerifier);
    expect(req.body.get("redirect_uri")).toBe(`${ORIGIN}/api/auth/callback`);
  });

  it.each([
    ["state diverso (CSRF sul login)", "code=c-1&state=altro", { nonce: "nonce-casuale" }],
    ["errore dell'IdP", "error=access_denied&state=stato-casuale", { nonce: "nonce-casuale" }],
    ["nonce diverso (replay dell'ID token)", "code=c-1&state=stato-casuale", { nonce: "altro" }],
    ["ID token per un altro client", "code=c-1&state=stato-casuale", { nonce: "nonce-casuale", aud: "widgets" }],
    ["ID token di un altro emittente", "code=c-1&state=stato-casuale", { nonce: "nonce-casuale", iss: "https://altro.test" }],
  ])("rifiuto: %s ⇒ LoginRejectedError", async (_, query, claims) => {
    const id = await idToken(claims);
    tokenResponse = () => Response.json({ access_token: "AT", token_type: "Bearer", expires_in: 300, id_token: id });
    const err = await oidc.exchangeCode(new URL(`${ORIGIN}/api/auth/callback?${query}`), checks).catch((e) => e);
    expect(err).toBeInstanceOf(LoginRejectedError);
  });

  it("errore dell'IdP access_denied: il motivo arriva al chiamante", async () => {
    const err = await oidc.exchangeCode(new URL(`${ORIGIN}/api/auth/callback?error=access_denied&state=stato-casuale`), checks).catch((e) => e);
    expect((err as LoginRejectedError).reason).toBe("access_denied");
  });

  it("rinnovo: grant refresh_token; invalid_grant ⇒ RefreshRejectedError; 503 ⇒ IdP non disponibile", async () => {
    tokenResponse = () => Response.json({ access_token: "AT-2", token_type: "Bearer", expires_in: 300, refresh_token: "RT-2" });
    expect(await oidc.refresh("RT-1")).toMatchObject({ accessToken: "AT-2", refreshToken: "RT-2" });
    expect(tokenRequests[0].body.get("grant_type")).toBe("refresh_token");
    expect(tokenRequests[0].body.get("refresh_token")).toBe("RT-1");

    tokenResponse = () => Response.json({ error: "invalid_grant", error_description: "Token is not active" }, { status: 400 });
    await expect(oidc.refresh("RT-1")).rejects.toBeInstanceOf(RefreshRejectedError);

    tokenResponse = () => new Response("<html>Bad gateway</html>", { status: 503, headers: { "content-type": "text/html" } });
    const err = await oidc.refresh("RT-1").catch((e) => e);
    expect(err).not.toBeInstanceOf(RefreshRejectedError);
    expect(isUnavailable(err)).toBe(true);
  });

  it("isUnavailable: solo rete, timeout e 5xx; errori di protocollo (TypeError con code) no", () => {
    expect(isUnavailable(new TypeError("fetch failed"))).toBe(true);
    expect(isUnavailable(Object.assign(new Error("timeout"), { name: "TimeoutError" }))).toBe(true);
    expect(isUnavailable(Object.assign(new TypeError('"response" must be an instance of Response'), { code: "ERR_INVALID_ARG_TYPE" }))).toBe(false);
    expect(isUnavailable(new Error("id_token non valido"))).toBe(false);
  });

  it("rinnovo con risposta non conforme (errore di protocollo): sessione da chiudere, non 503", async () => {
    tokenResponse = () => Response.json({ token_type: "Bearer" }); // manca access_token
    await expect(oidc.refresh("RT-1")).rejects.toBeInstanceOf(RefreshRejectedError);
  });

  it("logout: end_session_endpoint con id_token_hint, client_id e ritorno alla home", async () => {
    const url = await oidc.endSessionUrl("ID-HINT", `${ORIGIN}/`);
    expect(url?.origin + url!.pathname).toBe(DISCOVERY.end_session_endpoint);
    expect(Object.fromEntries(url!.searchParams)).toEqual({ id_token_hint: "ID-HINT", post_logout_redirect_uri: `${ORIGIN}/`, client_id: "web" });
  });

  it("verifica dei logout token: emittente esatto dal documento di discovery e chiavi dal jwks_uri", async () => {
    const { issuer, keys } = await oidc.logoutVerification();
    expect(issuer).toBe(ISSUER);
    expect(typeof keys).toBe("function");
  });
});
