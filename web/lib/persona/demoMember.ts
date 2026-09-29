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
 * Membro che il portale demo MOSTRA (layout, chiave di cache): quello della persona `MEMBER`; con una persona da
 * operatore o senza cookie il portale mostra il membro di default (`MBR-000002`). È solo interfaccia, non identità:
 * l'identità verso i servizi è `demoMemberHeader`, che per l'operatore non manda nulla (Q-560). L'id è quello del
 * cookie, senza validarlo: non cambia ciò che il portale ha sempre fatto.
 */
export function demoPortalMember(persona: Persona | null): string {
  return persona?.kind === "MEMBER" ? persona.memberId : DEFAULT_MEMBER_ID;
}

/**
 * Valore di `X-LH-Member` per il proxy, o `null` se non va inviato (docs/07 §3, Q-555, Q-560):
 * - persona `MEMBER`: il suo id, se ha la forma `MBR-nnnnnn`; se è malformato nessun header (il servizio lo
 *   rifiuterebbe con `400`, e un valore con a capo farebbe fallire la richiesta);
 * - nessun cookie (o cookie non valido): `MBR-000002`, lo stesso membro che il layout del portale mostra a un visitatore
 *   anonimo (Q-555);
 * - persona da operatore (`BO`): MAI. Un operatore non agisce mai come membro (ADR-048 punto 6, Q-554): le letture
 *   `OPTIONAL` come `/v1/portal/campaigns?codes=` di BO-17 ricevono la vista generica (docs/06 §3.4). Il portale aperto
 *   con una persona BO mostra comunque `MBR-000002` (`demoPortalMember`) e lavora col `memberId` esplicito.
 */
export function demoMemberHeader(persona: Persona | null): string | null {
  if (persona?.kind === "BO") return null;
  const id = demoPortalMember(persona);
  return isMemberId(id) ? id : null;
}
