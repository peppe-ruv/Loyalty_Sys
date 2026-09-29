import { DEFAULT_MEMBER_ID } from "./personas";
import type { Persona } from "./cookie";

// Membro attivo del profilo `demo` (docs/07 §4, Q-555). Unica fonte, condivisa da `app/portal/layout.tsx` (id che il
// portale mostra e usa come chiave di cache) e dal proxy `/api/lh` (header `X-LH-Member` verso i servizi, docs/07 §3):
// i due non possono discordare, altrimenti in demo i servizi risponderebbero `400 MEMBER_MISMATCH` (docs/06 §3.4).

/** Forma dell'id membro che i servizi accettano in `X-LH-Member` (docs/06 §3.4; stesso pattern dei contratti evento). */
export const MEMBER_ID_PATTERN = /^MBR-[0-9]{6}$/;

export function isMemberId(value: unknown): value is string {
  return typeof value === "string" && MEMBER_ID_PATTERN.test(value);
}

/**
 * Membro con cui il portale demo lavora: quello della persona `MEMBER`; con una persona da operatore o senza cookie il
 * portale mostra il membro di default (`MBR-000002`). L'id è quello del cookie, senza validarlo: non cambia ciò che
 * il portale ha sempre fatto.
 *
 * SPEC-GAP: Q-560 - il ripiego per la persona BO (Q-555) e la vista generica per l'operatore (docs/06 §3.4, ADR-048
 * punto 6, Q-554) non concordano su `/v1/portal/campaigns?codes=` di BO-17: vedi docs/15.
 */
export function demoPortalMember(persona: Persona | null): string {
  return persona?.kind === "MEMBER" ? persona.memberId : DEFAULT_MEMBER_ID;
}

/**
 * Valore di `X-LH-Member` per il proxy, o `null` se non va inviato: un id del cookie che non ha la forma `MBR-nnnnnn`
 * non si inoltra mai (il servizio lo rifiuterebbe con `400`, e un valore con a capo farebbe fallire la richiesta).
 */
export function demoMemberHeader(persona: Persona | null): string | null {
  const id = demoPortalMember(persona);
  return isMemberId(id) ? id : null;
}
