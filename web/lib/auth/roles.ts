import type { Role } from "@/lib/persona/personas";

// Ruolo mostrato dal web nel profilo enterprise, dal claim `lh_roles` del token (docs/18 §3.2, docs/06 §3.1).
// Stessa regola di `ActorContext.fromToken` nei servizi (Q-365): `ADMIN` vince; un solo ruolo operatore vale quel
// ruolo; più ruoli operatore diversi o nessuno ⇒ `ANALYST` (sola lettura), mai l'unione dei poteri. Serve solo a
// nascondere o disabilitare le azioni: l'autorizzazione vera resta nei servizi (`@RequiresRole`).

const OPERATOR_ROLES: readonly Role[] = ["ADMIN", "MARKETING", "LEGAL", "CARE", "ANALYST"];
export const MEMBER_ROLE = "MEMBER";

export type SessionKind = "operator" | "member";

/** Ruoli dal claim: solo stringhe, senza duplicati; qualunque altra forma ⇒ nessun ruolo. */
export function rolesFromClaim(claim: unknown): string[] {
  if (!Array.isArray(claim)) return [];
  return [...new Set(claim.filter((r): r is string => typeof r === "string"))];
}

export function effectiveRole(roles: readonly string[]): Role {
  const operator = OPERATOR_ROLES.filter((r) => roles.includes(r));
  if (operator.includes("ADMIN")) return "ADMIN";
  return operator.length === 1 ? operator[0] : "ANALYST";
}

/** Membro = ha `MEMBER` e nessun ruolo operatore (come `OidcActorFilter`): vale solo sulle API `/v1/portal/**`. */
export function sessionKind(roles: readonly string[]): SessionKind {
  const operator = roles.some((r) => (OPERATOR_ROLES as readonly string[]).includes(r));
  return roles.includes(MEMBER_ROLE) && !operator ? "member" : "operator";
}
