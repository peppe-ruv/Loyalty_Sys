import { RefreshRejectedError, type OidcClient } from "./oidc";
import type { Session, SessionStore } from "./sessionStore";

// Rinnovo trasparente dell'access token (docs/18 §3.2: access token di 5 minuti, refresh token a rotazione).
// Single-flight per sessione: più richieste parallele della stessa pagina con il token in scadenza fanno UN solo
// rinnovo e aspettano lo stesso esito. Con la rotazione (Keycloak `revokeRefreshToken`, `refreshTokenMaxReuse=0`)
// due rinnovi in gara farebbero revocare la sessione dall'IdP: il single-flight lo impedisce nella replica.
// SPEC-GAP: Q-409 (con più repliche serve un lock nello store condiviso). Uno store su database dovrà anche rileggere
// la sessione DENTRO il rinnovo esclusivo (o aggiornarla con compare-and-set sulla versione del refresh token): un'altra
// replica potrebbe averla già ruotata, e riusare il refresh token vecchio farebbe revocare la sessione dall'IdP.

/** Margine prima della scadenza entro cui si rinnova (orologi non allineati, latenza verso i servizi). */
export const REFRESH_SKEW_SECONDS = 30;

export class IdpUnavailableError extends Error {
  constructor(cause: unknown) {
    super("IdP non raggiungibile per il rinnovo del token", { cause });
    this.name = "IdpUnavailableError";
  }
}

export interface RefreshDeps {
  store: SessionStore;
  oidc: OidcClient;
  /** Rinnovi in corso per id di sessione (condiviso tra le richieste della replica). */
  inflight: Map<string, Promise<Session | null>>;
  now?: () => number;
}

/**
 * Sessione con un access token valido per almeno `REFRESH_SKEW_SECONDS`, rinnovandolo se serve.
 * `null` ⇒ sessione assente, scaduta o rifiutata dall'IdP (chiusa qui): il chiamante risponde 401.
 * Lancia `IdpUnavailableError` se l'IdP non risponde: la sessione resta, il chiamante risponde 503.
 */
export async function freshSession(id: string, deps: RefreshDeps): Promise<Session | null> {
  const now = deps.now ?? (() => Math.floor(Date.now() / 1000));
  const session = await deps.store.get(id);
  if (!session) return null;
  if (session.tokens.accessExpiresAt - REFRESH_SKEW_SECONDS > now()) return session;

  const running = deps.inflight.get(id);
  if (running) return running;
  const attempt = renew(id, session, deps).finally(() => deps.inflight.delete(id));
  deps.inflight.set(id, attempt);
  return attempt;
}

async function renew(id: string, session: Session, deps: RefreshDeps): Promise<Session | null> {
  if (!session.tokens.refreshToken) {
    await deps.store.delete(id);
    return null;
  }
  let renewed;
  try {
    renewed = await deps.oidc.refresh(session.tokens.refreshToken);
  } catch (err) {
    if (err instanceof RefreshRejectedError) {
      await deps.store.delete(id);
      return null;
    }
    throw new IdpUnavailableError(err);
  }
  const tokens = {
    accessToken: renewed.accessToken,
    accessExpiresAt: renewed.accessExpiresAt,
    // Rotazione: il nuovo refresh token sostituisce il vecchio; se l'IdP non ne manda uno, resta il precedente.
    refreshToken: renewed.refreshToken ?? session.tokens.refreshToken,
    idToken: renewed.idToken ?? session.tokens.idToken,
  };
  if (!(await deps.store.updateTokens(id, tokens))) return null; // chiusa nel frattempo (logout, back-channel)
  return { ...session, tokens };
}
