import type { NextRequest, NextResponse } from "next/server";
import { problem, type Bff } from "./bff";
import { SESSION_COOKIE } from "./cookies";
import { checkCsrf } from "./csrf";
import { csrfRejected } from "./handlers";
import { hasMemberIdInPath, isPortalPath } from "./memberScope";
import { freshSession, IdpUnavailableError } from "./refresh";

// Identità delle chiamate del proxy /api/lh nel profilo enterprise (ADR-027, ADR-042, docs/18 §3.2 e §3.10).
// Browser → BFF: cookie di sessione + CSRF. BFF → servizi: `Authorization: Bearer <access token>` aggiunto qui, lato
// server; il proxy non copia mai `Authorization`, `X-LH-Actor` o cookie dal browser (elenco chiuso di header).

export type ProxyAuthorization =
  | { ok: true; authorization: string; portal: boolean }
  | { ok: false; response: NextResponse };

/** Codice del 401 del BFF: la UI (lib/api/client.ts) lo riconosce e porta al login. */
export const UNAUTHENTICATED = "UNAUTHENTICATED";

export async function authorizeProxy(req: NextRequest, path: readonly string[], bff: Bff): Promise<ProxyAuthorization> {
  const id = req.cookies.get(SESSION_COOKIE)?.value;
  if (!id) return { ok: false, response: unauthenticated() };
  if (checkCsrf(req, bff.cfg.publicUrl.origin, { id, csrfKey: bff.csrfKey })) return { ok: false, response: csrfRejected() };

  let session;
  try {
    session = await freshSession(id, { store: bff.store, oidc: bff.oidc, inflight: bff.inflight });
  } catch (err) {
    if (!(err instanceof IdpUnavailableError)) throw err;
    return {
      ok: false,
      response: problem(503, "IDP_UNAVAILABLE", "Accesso momentaneamente non verificabile", "Il servizio di accesso non risponde: riprova tra poco."),
    };
  }
  if (!session) return { ok: false, response: unauthenticated() };

  const portal = isPortalPath(path);
  // Difesa in profondità: i servizi rifiutano comunque (403) un token di solo membro fuori da /v1/portal/**.
  if (session.user.kind === "member" && !portal) {
    return { ok: false, response: problem(403, "FORBIDDEN_ROLE", "Operazione non consentita", "Un membro può usare solo le funzioni del portale.") };
  }
  if (hasMemberIdInPath(path)) {
    return {
      ok: false,
      response: problem(403, "MEMBER_FROM_TOKEN", "Operazione non consentita", "Il membro si ricava dall'accesso, non dall'indirizzo della richiesta."),
    };
  }
  return { ok: true, authorization: `Bearer ${session.tokens.accessToken}`, portal };
}

/**
 * 401 del BFF: sessione assente o chiusa. I cookie non si cancellano qui: una risposta in ritardo di una richiesta
 * partita con la sessione vecchia cancellerebbe quella appena aperta da un'altra scheda; un id senza sessione nello
 * store non vale nulla e il prossimo login lo sostituisce.
 */
function unauthenticated(): NextResponse {
  return problem(401, UNAUTHENTICATED, "Accesso richiesto", "La sessione è scaduta o assente: accedi di nuovo.");
}
