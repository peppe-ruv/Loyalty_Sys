// Membri anonimizzati (F-MBR-05, docs/03 §2, docs/08 BO-02/BO-03, M7.5): stato irreversibile, i dati personali sono
// sostituiti dal segnaposto "Membro anonimo". Puro: usato da backoffice e portale.

export const ANONYMIZED = "ANONYMIZED";
export const ANONYMIZED_LABEL = "Membro anonimo";

export function isAnonymized(status: string | null | undefined): boolean {
  return status === ANONYMIZED;
}

/** Nome da mostrare: nome e cognome, altrimenti nickname, altrimenti il segnaposto (o `fallback`). */
export function memberDisplayName(
  m: { firstName?: string | null; lastName?: string | null; nickname?: string | null; status?: string | null },
  fallback: string = ANONYMIZED_LABEL,
): string {
  if (isAnonymized(m.status)) return ANONYMIZED_LABEL;
  const full = [m.firstName, m.lastName].filter((v) => v && v.trim()).join(" ").trim();
  if (full) return full;
  return m.nickname?.trim() || fallback;
}

/** Valore di un campo personale nella scheda: segnaposto se anonimizzato, trattino se vuoto. */
export function personalValue(status: string | null | undefined, value: string | null | undefined): string {
  if (isAnonymized(status)) return ANONYMIZED_LABEL;
  return value && value.trim() ? value : "—";
}

/** La conferma dell'anonimizzazione richiede di digitare esattamente l'ID del membro (docs/08 §3, azioni irreversibili). */
export function canConfirmAnonymize(typed: string, memberId: string): boolean {
  return typed.trim() === memberId && memberId.length > 0;
}

/** Messaggio per gli errori dell'endpoint di anonimizzazione. */
export function anonymizeErrorMessage(err: { code?: string | null; detail?: string | null; asleep?: boolean } | null): string | null {
  if (!err) return null;
  if (err.asleep) return "Il servizio membri non risponde: riprova quando la demo è accesa.";
  switch (err.code) {
    case "CONFIRM_MISMATCH":
      return "L'ID digitato non corrisponde al membro.";
    case "MEMBER_ANONYMIZED":
      return "Il membro è già anonimizzato.";
    case "FORBIDDEN_ROLE":
      return "Solo il ruolo ADMIN può anonimizzare un membro.";
    case "VERSION_CONFLICT":
      return "Il membro è stato modificato nel frattempo: ricarica e riprova.";
    default:
      return err.detail || err.code || "Anonimizzazione non riuscita.";
  }
}
