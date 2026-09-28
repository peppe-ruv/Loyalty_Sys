import { jwtVerify, type JWTVerifyGetKey } from "jose";

// Validazione del logout token (OpenID Connect Back-Channel Logout 1.0, §2.4 e §2.6). L'IdP chiama
// `POST /api/auth/backchannel-logout` quando una sessione finisce lì (logout altrove, utente disabilitato, sessione
// scaduta): il BFF chiude le proprie sessioni con quel `sid`/`sub` (docs/18 §3.2 e §3.15 punto 4, deprovisioning).

export const BACKCHANNEL_LOGOUT_EVENT = "http://schemas.openid.net/event/backchannel-logout";
/** Algoritmi ammessi: solo firme asimmetriche (mai `none`, mai HMAC col segreto del client). */
const ALGORITHMS = ["RS256", "RS384", "RS512", "PS256", "PS384", "PS512", "ES256", "ES384", "ES512", "EdDSA"];
/** Un logout token vale per poco: oltre questa età dall'`iat` si rifiuta (difesa dal replay insieme a `jti`). */
const MAX_TOKEN_AGE_SECONDS = 120;
const CLOCK_TOLERANCE_SECONDS = 30;

export interface LogoutTarget {
  sid?: string;
  sub?: string;
  jti: string;
  /** Scadenza (secondi epoch) per la cache anti-replay. */
  exp: number;
}

export class InvalidLogoutTokenError extends Error {
  constructor(reason: string) {
    super(`logout token non valido: ${reason}`);
    this.name = "InvalidLogoutTokenError";
  }
}

export interface LogoutTokenChecks {
  issuer: string;
  clientId: string;
  keys: JWTVerifyGetKey;
  /** Orologio (secondi epoch) per i test. */
  now?: number;
}

/** Verifica firma e claim; restituisce chi disconnettere. Lancia `InvalidLogoutTokenError` a ogni violazione. */
export async function verifyLogoutToken(token: string, checks: LogoutTokenChecks): Promise<LogoutTarget> {
  let payload;
  let header;
  try {
    ({ payload, protectedHeader: header } = await jwtVerify(token, checks.keys, {
      issuer: checks.issuer,
      audience: checks.clientId,
      algorithms: ALGORITHMS,
      requiredClaims: ["iat", "exp", "jti", "events"],
      maxTokenAge: MAX_TOKEN_AGE_SECONDS,
      clockTolerance: CLOCK_TOLERANCE_SECONDS,
      currentDate: checks.now === undefined ? undefined : new Date(checks.now * 1000),
    }));
  } catch (err) {
    throw new InvalidLogoutTokenError(err instanceof Error ? err.message : "firma o claim");
  }
  // Tipo esplicito (§2.4): se presente deve essere `logout+jwt`; un ID token o un access token non valgono come logout.
  if (header.typ !== undefined && header.typ.toLowerCase() !== "logout+jwt" && header.typ.toUpperCase() !== "JWT") {
    throw new InvalidLogoutTokenError(`typ ${header.typ}`);
  }
  const events = payload.events;
  if (typeof events !== "object" || events === null || Array.isArray(events)) {
    throw new InvalidLogoutTokenError("events assente");
  }
  const event = (events as Record<string, unknown>)[BACKCHANNEL_LOGOUT_EVENT];
  if (typeof event !== "object" || event === null || Array.isArray(event)) {
    throw new InvalidLogoutTokenError("evento di back-channel logout assente");
  }
  if ("nonce" in payload) throw new InvalidLogoutTokenError("nonce presente");
  const sid = typeof payload.sid === "string" && payload.sid ? payload.sid : undefined;
  const sub = typeof payload.sub === "string" && payload.sub ? payload.sub : undefined;
  if (!sid && !sub) throw new InvalidLogoutTokenError("né sid né sub");
  if (typeof payload.jti !== "string" || !payload.jti) throw new InvalidLogoutTokenError("jti");
  return { sid, sub, jti: payload.jti, exp: payload.exp as number };
}

/** Memoria dei `jti` già usati fino alla loro scadenza: lo stesso logout token non si riusa. Limitata in dimensione. */
export class ReplayCache {
  private readonly seen = new Map<string, number>();
  constructor(private readonly max = 10_000) {}

  /** `true` se il `jti` è nuovo (e lo registra), `false` se è un replay. */
  remember(jti: string, exp: number, now: number): boolean {
    for (const [key, until] of this.seen) {
      if (until + CLOCK_TOLERANCE_SECONDS < now) this.seen.delete(key);
    }
    if (this.seen.has(jti)) return false;
    this.seen.set(jti, exp);
    while (this.seen.size > this.max) {
      const oldest = this.seen.keys().next().value;
      if (oldest === undefined) break;
      this.seen.delete(oldest);
    }
    return true;
  }
}
