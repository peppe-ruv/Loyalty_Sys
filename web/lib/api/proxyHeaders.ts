// Header che il proxy /api/lh manda ai servizi (docs/07 §3). Elenco CHIUSO: dal browser passano solo `content-type`
// e `idempotency-key` (import file BO-32, Q-353); l'identità la aggiunge il server. Nessun `Authorization`,
// `X-LH-Actor`, cookie o altro header scelto dal client arriva mai ai servizi (CLAUDE.md regole 18 e 20).
// - profilo demo: `x-lh-actor` dal cookie persona (identità simulata, invariata dalla Fase 1);
// - profilo enterprise: `authorization: Bearer <access token>` dalla sessione del BFF (ADR-027).

/** Id di correlazione accettato dal browser: ULID, UUID o simili; altrimenti il proxy ne genera uno nuovo. */
const CORRELATION_ID = /^[A-Za-z0-9-]{1,64}$/;

/** `X-Correlation-Id` del browser se ha una forma sicura (niente testo arbitrario nei log e verso i servizi). */
export function correlationIdFrom(incoming: Headers): string | null {
  const sent = incoming.get("x-correlation-id");
  return sent !== null && CORRELATION_ID.test(sent) ? sent : null;
}

export type UpstreamIdentity = { "x-lh-actor": string } | { authorization: string };

/**
 * `accept` è deciso dal server, mai copiato dal browser: `application/json` per le API, il tipo del file per un
 * download (`upstreamAccept` in lib/api/proxyBody.ts), altrimenti Spring risponde 406 a un endpoint `produces`.
 */
export function upstreamHeaders(
  incoming: Headers,
  identity: UpstreamIdentity,
  correlationId: string,
  accept = "application/json",
): Headers {
  const headers = new Headers();
  const contentType = incoming.get("content-type");
  if (contentType) headers.set("content-type", contentType);
  headers.set("accept", accept);
  for (const [name, value] of Object.entries(identity)) headers.set(name, value);
  headers.set("x-correlation-id", correlationId);
  // Import file (BO-32, Q-353): stessa chiave → stesso lavoro, anche se il browser ripete l'invio.
  const idempotencyKey = incoming.get("idempotency-key");
  if (idempotencyKey) headers.set("idempotency-key", idempotencyKey);
  return headers;
}
