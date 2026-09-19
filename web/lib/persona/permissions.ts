import type { Role } from "./personas";

// Permessi minimi lato UI (docs/07 §4): nascondono/disabilitano; il backend rifiuta comunque (@RequiresRole).
// La matrice completa per schermata arriva con docs/08 §2 (M1+).

export function isReadOnly(role: Role): boolean {
  return role === "ANALYST";
}

/** Può eseguire scritture generiche (qualsiasi ruolo tranne ANALYST). */
export function canWrite(role: Role): boolean {
  return !isReadOnly(role);
}

/** Può approvare/rifiutare oggetti governati (LEGAL o ADMIN, docs/06 §7). */
export function canApprove(role: Role): boolean {
  return role === "LEGAL" || role === "ADMIN";
}

/** Può fare rettifiche punti (CARE o ADMIN). */
export function canAdjustPoints(role: Role): boolean {
  return role === "CARE" || role === "ADMIN";
}

/** Può usare la console demo (reset/job): solo ADMIN. */
export function canRunDemoAdmin(role: Role): boolean {
  return role === "ADMIN";
}
