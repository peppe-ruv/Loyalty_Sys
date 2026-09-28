import * as client from "openid-client";
import { createRemoteJWKSet, type JWTVerifyGetKey } from "jose";
import type { EnterpriseAuthConfig } from "./config";
import { resilientKeys } from "./logoutToken";

// Client OIDC del BFF (ADR-027, docs/18 §3.2): client confidential `web`, Authorization Code + PKCE (S256) + state +
// nonce, rinnovo con refresh token a rotazione, logout avviato dal BFF con `id_token_hint`. SOLO LATO SERVER.
// Unico modulo che conosce `openid-client`: il resto del BFF usa l'interfaccia `OidcClient`, sostituibile nei test.

export const CALLBACK_PATH = "/api/auth/callback";
/** Scope richiesti: `openid`; ruoli (`lh_roles`), profilo e audience `hub` arrivano dagli scope predefiniti del realm. */
const SCOPE = "openid";
const HTTP_TIMEOUT_SECONDS = 10;

export interface TokenSet {
  accessToken: string;
  /** Secondi epoch. */
  accessExpiresAt: number;
  refreshToken: string | null;
  idToken: string | null;
  /** Claim dell'ID token già validato (firma, iss, aud, exp, nonce); `null` se l'IdP non l'ha mandato. */
  claims: Record<string, unknown> | null;
}

export interface LoginChecks {
  state: string;
  nonce: string;
  codeVerifier: string;
}

/** Il refresh token non vale più (revocato, scaduto, già usato): la sessione va chiusa. */
export class RefreshRejectedError extends Error {
  constructor() {
    super("refresh token rifiutato dall'IdP");
    this.name = "RefreshRejectedError";
  }
}

/** Risposta di autorizzazione con errore (es. `access_denied`) o non valida (state, iss). */
export class LoginRejectedError extends Error {
  constructor(readonly reason: string) {
    super(`login non riuscito: ${reason}`);
    this.name = "LoginRejectedError";
  }
}

export interface OidcClient {
  readonly clientId: string;
  authorizationUrl(checks: LoginChecks): Promise<URL>;
  /** Scambia il codice (URL di callback completo) e valida l'ID token; lancia `LoginRejectedError` sui rifiuti. */
  exchangeCode(callbackUrl: URL, checks: LoginChecks): Promise<TokenSet>;
  /** Lancia `RefreshRejectedError` se l'IdP rifiuta il refresh token; altri errori = IdP non raggiungibile. */
  refresh(refreshToken: string): Promise<TokenSet>;
  /** URL di logout dell'IdP (`end_session_endpoint`), o `null` se l'IdP non lo pubblica. */
  endSessionUrl(idTokenHint: string | null, postLogoutRedirectUri: string): Promise<URL | null>;
  /** Emittente esatto (dal documento di discovery) e chiavi pubbliche (JWKS) per verificare i logout token. */
  logoutVerification(): Promise<{ issuer: string; keys: JWTVerifyGetKey }>;
}

export function redirectUri(cfg: EnterpriseAuthConfig): string {
  return new URL(CALLBACK_PATH, cfg.publicUrl).href;
}

export function createOidcClient(cfg: EnterpriseAuthConfig): OidcClient {
  let discovered: Promise<client.Configuration> | null = null;
  let jwks: JWTVerifyGetKey | null = null;

  // Discovery pigra e condivisa (una sola richiesta anche con molte chiamate insieme); un fallimento non resta in
  // cache: l'IdP può avviarsi dopo il web.
  const configuration = (): Promise<client.Configuration> => {
    if (!discovered) {
      discovered = client
        .discovery(cfg.issuer, cfg.clientId, undefined, client.ClientSecretBasic(cfg.clientSecret), {
          timeout: HTTP_TIMEOUT_SECONDS,
          // HTTP in chiaro solo verso un emittente su loopback (config.ts lo ammette solo lì).
          execute: cfg.allowInsecureIssuer ? [client.allowInsecureRequests] : [],
        })
        .catch((err: unknown) => {
          discovered = null;
          throw err;
        });
    }
    return discovered;
  };

  return {
    clientId: cfg.clientId,

    async authorizationUrl(checks) {
      const config = await configuration();
      return client.buildAuthorizationUrl(config, {
        redirect_uri: redirectUri(cfg),
        scope: SCOPE,
        state: checks.state,
        nonce: checks.nonce,
        code_challenge: await client.calculatePKCECodeChallenge(checks.codeVerifier),
        code_challenge_method: "S256",
      });
    },

    async exchangeCode(callbackUrl, checks) {
      const config = await configuration();
      try {
        const tokens = await client.authorizationCodeGrant(config, callbackUrl, {
          pkceCodeVerifier: checks.codeVerifier,
          expectedState: checks.state,
          expectedNonce: checks.nonce,
          idTokenExpected: true,
        });
        return toTokenSet(tokens);
      } catch (err) {
        if (isUnavailable(err)) throw err;
        // Errore dell'IdP (access_denied…), state o iss sbagliati, ID token non valido (firma, nonce, aud, scadenza).
        const reason =
          err instanceof client.AuthorizationResponseError || err instanceof client.ResponseBodyError ? err.error : "invalid_response";
        throw new LoginRejectedError(reason);
      }
    },

    async refresh(refreshToken) {
      const config = await configuration();
      try {
        return toTokenSet(await client.refreshTokenGrant(config, refreshToken));
      } catch (err) {
        // Rete o 5xx: la sessione resta e si riprova. Tutto il resto (invalid_grant, risposta non valida): chiusa.
        if (isUnavailable(err)) throw err;
        throw new RefreshRejectedError();
      }
    },

    async endSessionUrl(idTokenHint, postLogoutRedirectUri) {
      const config = await configuration();
      if (!config.serverMetadata().end_session_endpoint) return null;
      const params: Record<string, string> = { post_logout_redirect_uri: postLogoutRedirectUri };
      if (idTokenHint) params.id_token_hint = idTokenHint;
      return client.buildEndSessionUrl(config, params);
    },

    async logoutVerification() {
      const metadata = (await configuration()).serverMetadata();
      if (!jwks) {
        if (!metadata.jwks_uri) throw new Error("l'IdP non pubblica jwks_uri");
        // Cache delle chiavi con rinnovo su `kid` sconosciuto (rotazione delle chiavi dell'IdP), gestita da jose.
        // Un JWKS non scaricabile è ritentabile (503), una chiave sconosciuta no (400): resilientKeys li distingue.
        jwks = resilientKeys(createRemoteJWKSet(new URL(metadata.jwks_uri), { timeoutDuration: HTTP_TIMEOUT_SECONDS * 1000 }));
      }
      return { issuer: metadata.issuer, keys: jwks };
    },
  };
}

/** IdP irraggiungibile o in errore interno (rete, timeout, 5xx): condizione temporanea, non un rifiuto. */
export function isUnavailable(err: unknown): boolean {
  if (err instanceof client.ResponseBodyError) return err.status >= 500;
  // fetch rifiutato per rete, DNS o TLS: un TypeError SENZA `code`. Gli errori di protocollo o di configurazione di
  // oauth4webapi/openid-client sono anch'essi TypeError ma con un `code` (`ERR_INVALID_ARG_TYPE`…): non temporanei.
  if (err instanceof TypeError) return typeof (err as { code?: unknown }).code !== "string";
  // Risposta non conforme (es. pagina HTML di un proxy con 502): oauth4webapi mette la Response in `cause`.
  if (err instanceof Error && err.cause instanceof Response) return err.cause.status >= 500;
  return err instanceof Error && (err.name === "AbortError" || err.name === "TimeoutError");
}

function toTokenSet(tokens: client.TokenEndpointResponse & client.TokenEndpointResponseHelpers): TokenSet {
  const expiresIn = tokens.expiresIn();
  return {
    accessToken: tokens.access_token,
    // Senza `expires_in` si considera l'access token da rinnovare subito: mai un token tenuto «per sempre».
    accessExpiresAt: Math.floor(Date.now() / 1000) + (expiresIn ?? 0),
    refreshToken: tokens.refresh_token ?? null,
    idToken: tokens.id_token ?? null,
    claims: (tokens.claims() as Record<string, unknown> | undefined) ?? null,
  };
}
