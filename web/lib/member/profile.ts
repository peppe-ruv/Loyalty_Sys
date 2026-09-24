import type { ProfileField } from "@/lib/api/types";

// Profilo e registrazione dal portale (docs/03 §2, docs/09 PT-08; F-MBR-06/07).

export const PROFILE_FIELD_LABEL: Record<ProfileField, string> = {
  firstName: "nome",
  lastName: "cognome",
  email: "e-mail",
  phone: "telefono",
  birthDate: "data di nascita",
  city: "città",
};

/** "Mancano città e data di nascita". */
export function missingFieldsSentence(fields: ProfileField[]): string {
  if (fields.length === 0) return "Profilo completo";
  const labels = fields.map((f) => PROFILE_FIELD_LABEL[f]);
  const list = labels.length === 1 ? labels[0] : `${labels.slice(0, -1).join(", ")} e ${labels[labels.length - 1]}`;
  return `${fields.length === 1 ? "Manca" : "Mancano"} ${list}`;
}

/** Percentuale dei 6 campi richiesti valorizzati. */
export function completenessPct(missing: ProfileField[]): number {
  const total = Object.keys(PROFILE_FIELD_LABEL).length;
  return Math.round(((total - missing.length) / total) * 100);
}

export interface JoinForm {
  firstName: string;
  lastName: string;
  email: string;
  referralCode: string;
  terms: boolean;
  marketing: boolean;
}

export type JoinErrors = Partial<Record<keyof JoinForm, string>>;

const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const CODE = /^[A-Z2-9]{8}$/;

/** Validazione lato portale; il member-service resta l'arbitro (EMAIL_TAKEN, REFERRAL_CODE_INVALID). */
export function validateJoin(f: JoinForm): JoinErrors {
  const e: JoinErrors = {};
  if (!f.firstName.trim()) e.firstName = "Scrivi il tuo nome";
  if (!f.lastName.trim()) e.lastName = "Scrivi il tuo cognome";
  if (!EMAIL.test(f.email.trim())) e.email = "Serve un indirizzo e-mail valido";
  const code = normalizeCode(f.referralCode);
  if (code && !CODE.test(code)) e.referralCode = "Il codice amico ha 8 caratteri (lettere e cifre 2-9)";
  if (!f.terms) e.terms = "Per iscriverti accetta regolamento e informativa";
  return e;
}

export function normalizeCode(code: string): string {
  return code.replace(/\s+/g, "").toUpperCase();
}

/** Errore del member-service → campo del form (docs/09 PT-08: "codice amico non valido → errore sul campo"). */
export function joinErrorFromApi(code: string, detail: string): JoinErrors {
  if (code === "REFERRAL_CODE_INVALID" || code === "REFERRAL_SELF") return { referralCode: "Codice amico non valido" };
  if (code === "EMAIL_TAKEN") return { email: "Questa e-mail è già iscritta al Club" };
  return { firstName: detail || code };
}
