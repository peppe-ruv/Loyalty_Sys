// Persone del backoffice (identità simulata, docs/07 §4). I 12 membri del portale arrivano dal seed (M1.2).

export type Role = "ADMIN" | "MARKETING" | "LEGAL" | "CARE" | "ANALYST";

export interface BackofficePersona {
  username: string;
  displayName: string;
  role: Role;
  /** Cosa può fare, in una riga (per le schede del Demo Hub). */
  summary: string;
}

export const BACKOFFICE_PERSONAS: BackofficePersona[] = [
  { username: "marta.admin", displayName: "Marta Bianchi", role: "ADMIN", summary: "Amministra tutto: configurazioni, job, reset demo." },
  { username: "luca.marketing", displayName: "Luca Verdi", role: "MARKETING", summary: "Crea e manda in revisione campagne, premi, concorsi." },
  { username: "elena.legal", displayName: "Elena Conti", role: "LEGAL", summary: "Approva o rifiuta concorsi e premi; vede gli istanti vincenti." },
  { username: "anna.care", displayName: "Anna Ferri", role: "CARE", summary: "Assiste i membri: rettifiche punti, scheda 360°." },
  { username: "giovanni.analyst", displayName: "Giovanni Sala", role: "ANALYST", summary: "Sola lettura: cruscotti, tracciati, audit." },
];

export const DEFAULT_BACKOFFICE_USERNAME = "marta.admin";
export const DEFAULT_MEMBER_ID = "MBR-000002";

export function findBackofficePersona(username: string): BackofficePersona | undefined {
  return BACKOFFICE_PERSONAS.find((p) => p.username === username);
}
