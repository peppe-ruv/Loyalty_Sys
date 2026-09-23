import type { Role } from "./personas";

// Permessi lato UI (docs/08 §2): nascondono o disabilitano; il backend rifiuta comunque (@RequiresRole).
// `can(role, capability)` è la fonte unica usata dal componente <Can>.

export type Capability =
  | "member.write"
  | "member.anonymize"
  | "points.adjust"
  | "segment.write"
  | "object.edit"
  | "object.approve"
  | "content.write"
  | "program.config"
  | "actiontype.custom"
  | "redemption.handle"
  | "coupon.use"
  | "coupon.void"
  | "inbound.handle"
  | "demo.simulate"
  | "demo.admin";

const MATRIX: Record<Capability, Role[]> = {
  "member.write": ["ADMIN", "CARE"],
  "member.anonymize": ["ADMIN"],
  "points.adjust": ["ADMIN", "CARE"],
  "segment.write": ["ADMIN", "MARKETING"],
  "object.edit": ["ADMIN", "MARKETING"],
  "object.approve": ["ADMIN", "LEGAL"],
  "content.write": ["ADMIN", "MARKETING"],
  "program.config": ["ADMIN"],
  "actiontype.custom": ["ADMIN", "MARKETING"],
  "redemption.handle": ["ADMIN", "CARE"],
  // Cassa simulata di BO-12: ogni ruolo operativo. `coupon.void` come redemption.handle (SPEC-GAP: Q-52).
  "coupon.use": ["ADMIN", "MARKETING", "LEGAL", "CARE"],
  "coupon.void": ["ADMIN", "CARE"],
  "inbound.handle": ["ADMIN", "CARE"],
  "demo.simulate": ["ADMIN", "MARKETING", "LEGAL", "CARE"],
  "demo.admin": ["ADMIN"],
};

export function can(role: Role, capability: Capability): boolean {
  return MATRIX[capability].includes(role);
}

export function isReadOnly(role: Role): boolean {
  return role === "ANALYST";
}

/** Etichetta del ruolo richiesto, per il tooltip del pulsante disabilitato. */
export function requiredRoleHint(capability: Capability): string {
  return `Richiede uno dei ruoli: ${MATRIX[capability].join(", ")}`;
}

// Compatibilità con le viste M0.6.
export function canWrite(role: Role): boolean {
  return !isReadOnly(role);
}
export function canApprove(role: Role): boolean {
  return can(role, "object.approve");
}
export function canAdjustPoints(role: Role): boolean {
  return can(role, "points.adjust");
}
export function canRunDemoAdmin(role: Role): boolean {
  return can(role, "demo.admin");
}
