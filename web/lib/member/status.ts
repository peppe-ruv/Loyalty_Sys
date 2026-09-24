// Cambio di stato del membro dal menu della scheda 360° (BO-03, F-MBR-04; docs/08 §BO-03 "menu: Blocca/Sblocca,
// Disattiva, Anonimizza ●"). API: member POST /v1/members/{id}/status {status, reason} con status ACTIVE/INACTIVE/BLOCKED
// (docs/servizi/member-service.md §3). Capacità `member.write` (ADMIN, CARE; docs/08 §2). Azioni reversibili →
// conferma semplice (docs/08 §3.5). Puro: usato dalla scheda e dai test.
//
// SPEC-GAP: Q-D3 — contracts/events/fact/member.status.changed.schema.json elenca ACTIVE/SUSPENDED/BLOCKED/CLOSED/
// ANONYMIZED, mentre docs/02 F-MBR-04, docs/03 §2 e member-service usano INACTIVE: *Disattiva* emette newStatus=INACTIVE
// come fa già il servizio (nessun contratto cambiato qui).

import { isAnonymized } from "./anonymized";

export type MemberStatusTarget = "ACTIVE" | "BLOCKED" | "INACTIVE";
export type MemberStatusActionKey = "block" | "unblock" | "deactivate";

export interface MemberStatusAction {
  key: MemberStatusActionKey;
  /** Voce del menu. */
  label: string;
  target: MemberStatusTarget;
  /** Motivo per cui la voce è disabilitata (tooltip), `null` se utilizzabile. */
  disabledReason: string | null;
  /** Titolo e testo del dialogo di conferma. */
  confirmTitle: string;
  confirmBody: string;
  /** Esito mostrato nella scheda dopo il cambio. */
  doneMessage: string;
}

const ANONYMIZED_REASON = "Membro anonimizzato: azioni disabilitate";

/**
 * Voci di stato del menu per lo stato attuale del membro, nell'ordine di docs/08: *Blocca* (o *Sblocca* se BLOCKED),
 * poi *Disattiva*.
 */
export function memberStatusActions(status: string | null | undefined): MemberStatusAction[] {
  const anonymized = isAnonymized(status);
  const blocked = status === "BLOCKED";
  const inactive = status === "INACTIVE";

  const toggle: MemberStatusAction = blocked
    ? {
        key: "unblock",
        label: "Sblocca",
        target: "ACTIVE",
        disabledReason: null,
        confirmTitle: "Sbloccare il membro?",
        confirmBody:
          "Il membro torna ACTIVE: potrà di nuovo accumulare, spendere e giocare. Saldi e storico sono rimasti intatti.",
        doneMessage: "Membro sbloccato: torna ACTIVE e può di nuovo accumulare, spendere e giocare.",
      }
    : {
        key: "block",
        label: "Blocca",
        target: "BLOCKED",
        // SPEC-GAP: Q-D1 — il menu di docs/08 non prevede *Riattiva*: per un membro INACTIVE nessun cambio di stato dal
        // backoffice (Blocca → Sblocca lo riattiverebbe di fatto). Scelta conservativa: voce disabilitata.
        disabledReason: anonymized ? ANONYMIZED_REASON : inactive ? "Membro disattivato: nessun cambio di stato dal menu" : null,
        confirmTitle: "Bloccare il membro?",
        confirmBody:
          "Il membro passa a BLOCKED: conserva saldi e storico, ma ogni nuova azione in ingresso viene respinta (MEMBER_NOT_ACTIVE) e non può spendere né giocare. Si annulla con Sblocca.",
        doneMessage: "Membro bloccato: le nuove azioni in ingresso saranno respinte finché non viene sbloccato.",
      };

  const deactivate: MemberStatusAction = {
    key: "deactivate",
    label: "Disattiva",
    target: "INACTIVE",
    disabledReason: anonymized ? ANONYMIZED_REASON : inactive ? "Membro già disattivato" : null,
    confirmTitle: "Disattivare il membro?",
    confirmBody:
      "Il membro passa a INACTIVE (uscito dal programma): conserva saldi e storico, ma non accumula, non spende e non gioca più.",
    doneMessage: "Membro disattivato: non accumula e non spende più; saldi e storico restano.",
  };

  return [toggle, deactivate];
}

/**
 * Corpo di `POST /v1/members/{id}/status`. Il motivo è facoltativo e finisce nell'audit e nel fatto
 * `member.status.changed`.
 * SPEC-GAP: Q-D2 — nessuna fonte dice se il motivo sia obbligatorio (l'API lo accetta assente; docs/08 §3.5 chiede una
 * conferma semplice): facoltativo, mandato solo se non vuoto.
 */
export function statusChangeBody(target: MemberStatusTarget, reason: string): { status: MemberStatusTarget; reason?: string } {
  const r = reason.trim();
  return r ? { status: target, reason: r } : { status: target };
}

/** Messaggio per gli errori del cambio di stato. */
export function statusChangeErrorMessage(err: { code?: string | null; detail?: string | null; asleep?: boolean } | null): string | null {
  if (!err) return null;
  if (err.asleep) return "Il servizio membri non risponde: riprova quando la demo è accesa.";
  switch (err.code) {
    case "MEMBER_ANONYMIZED":
      return "Il membro è anonimizzato: lo stato non si può più cambiare.";
    case "FORBIDDEN_ROLE":
      return "Il tuo ruolo non può cambiare lo stato di un membro (servono ADMIN o CARE).";
    default:
      return err.detail || err.code || "Cambio di stato non riuscito.";
  }
}
