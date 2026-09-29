import { isPortalPath } from "@/lib/auth/memberScope";
import { actorHeader, type Persona } from "@/lib/persona/cookie";
import { demoMemberHeader } from "@/lib/persona/demoMember";

// Header che il proxy /api/lh manda ai servizi (docs/07 §3). Elenco CHIUSO: dal browser passano solo `content-type`
// e `idempotency-key` (import file BO-32, Q-353); l'identità la aggiunge il server. Nessun `Authorization`,
// `X-LH-Actor`, `X-LH-Member`, cookie o altro header scelto dal client arriva mai ai servizi (CLAUDE.md regole 18 e 20).
// - profilo demo: `x-lh-actor` dal cookie persona (identità simulata, invariata dalla Fase 1) e, solo sulle API del
//   portale (`/v1/portal/**`), `x-lh-member` col membro attivo della persona (ADR-048, Q-555);
// - profilo enterprise: `authorization: Bearer <access token>` dalla sessione del BFF (ADR-027). `x-lh-member` non
//   esiste: il membro lo ricavano i servizi dal token (regole 6-bis e 18) e un servizio rifiuta l'header (400).

/** Id di correlazione accettato dal browser: ULID, UUID o simili; altrimenti il proxy ne genera uno nuovo. */
const CORRELATION_ID = /^[A-Za-z0-9-]{1,64}$/;

/** `X-Correlation-Id` del browser se ha una forma sicura (niente testo arbitrario nei log e verso i servizi). */
export function correlationIdFrom(incoming: Headers): string | null {
  const sent = incoming.get("x-correlation-id");
  return sent !== null && CORRELATION_ID.test(sent) ? sent : null;
}

/** Identità verso i servizi: demo (`x-lh-actor`, più `x-lh-member` sul portale) oppure enterprise (`authorization`). */
export type UpstreamIdentity = { "x-lh-actor": string; "x-lh-member"?: string } | { authorization: string };

/**
 * Identità del profilo demo (docs/07 §3, §4). `x-lh-member` viaggia SOLO su `/v1/portal/**` e solo se il membro attivo ha
 * la forma `MBR-nnnnnn` (`lib/persona/demoMember.ts`): senza cookie o con una persona da operatore vale il membro di
 * default, con un id malformato l'header manca e i servizi si comportano come prima. Un percorso con segmenti vuoti,
 * `.` o `..` non conta come portale: il proxy demo non lo normalizza, quindi il servizio potrebbe leggerlo altrove.
 */
export function demoIdentity(persona: Persona | null, path: readonly string[]): UpstreamIdentity {
  const actor = { "x-lh-actor": actorHeader(persona) };
  const portal = isPortalPath(path) && path.length > 2 && path.every((segment) => segment !== "" && segment !== "." && segment !== "..");
  const member = portal ? demoMemberHeader(persona) : null;
  return member ? { ...actor, "x-lh-member": member } : actor;
}

/** Identità senza `x-lh-member`, per le chiamate che il proxy fa in proprio (soprannomi a member-service, Q-368). */
export function withoutMember(identity: UpstreamIdentity): Record<string, string> {
  return Object.fromEntries(Object.entries(identity).filter(([name]) => name !== "x-lh-member"));
}

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
