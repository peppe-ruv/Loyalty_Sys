import type { PortalInvitee, ReferralOverview } from "@/lib/api/types";

// Referral (docs/03 §8, docs/09 PT-11, docs/08 BO-17): logica pura, testata a parte.

/** Link di invito assoluto a partire dall'origine del portale e dal percorso relativo del member-service. */
export function inviteLink(origin: string, shareUrl: string): string {
  return origin.replace(/\/$/, "") + (shareUrl.startsWith("/") ? shareUrl : `/${shareUrl}`);
}

/**
 * Inviti premiati nell'edizione corrente. Le edizioni del seed sono anni solari (ED-2026 = 2026) e il member-service
 * non conosce le edizioni: si contano i completamenti dell'anno.
 * SPEC-GAP: Q-61 — contatore "n di max in questa edizione" calcolato per anno solare lato portale.
 */
export function completedThisEdition(invited: PortalInvitee[], now: Date = new Date()): number {
  const year = now.getFullYear();
  return invited.filter((i) => i.status === "COMPLETED" && i.completedAt && new Date(i.completedAt).getFullYear() === year).length;
}

/** Stato dell'invitato in parole (PT-11): iscritto → premio ottenuto. */
export function inviteeStatusLabel(status: PortalInvitee["status"]): string {
  return status === "COMPLETED" ? "premio ottenuto" : "iscritto";
}

export function formatRate(rate: number): string {
  return `${Math.round(rate * 100).toLocaleString("it-IT")}%`;
}

/**
 * Imbuto di BO-17: invitato → registrato → prima azione qualificante → premiato. Il PoC non traccia gli inviti
 * inviati né i premi (sono del motore campagne): i primi due passi coincidono coi registrati con codice e l'ultimo
 * coi completati, perché le campagne CMP-REFERRAL-* premiano ogni completamento entro i propri limiti.
 * SPEC-GAP: Q-61 — imbuto a 4 passi mostrato con i dati disponibili al member-service.
 */
export function referralFunnel(o: Pick<ReferralOverview, "invited" | "completed">): { label: string; value: number }[] {
  return [
    { label: "Invitati", value: o.invited },
    { label: "Registrati", value: o.invited },
    { label: "Prima azione qualificante", value: o.completed },
    { label: "Premiati", value: o.completed },
  ];
}
