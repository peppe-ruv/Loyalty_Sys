import type { RedemptionStatus } from "@/lib/api/types";

// Schede di BO-13 (docs/08 §BO-13) → filtri di reward GET /v1/redemptions. "Da evadere" = CONFIRMED a evasione
// manuale; "Da verificare" = needsAttention (pool coupon vuoto al momento dell'evasione).
export type RedemptionTab = "todo" | "pending" | "fulfilled" | "closed" | "attention";

export const REDEMPTION_TABS: { key: RedemptionTab; label: string; query: Record<string, string> }[] = [
  { key: "todo", label: "Da evadere", query: { status: "CONFIRMED", fulfilment: "MANUAL" } },
  { key: "pending", label: "In attesa", query: { status: "PENDING" } },
  { key: "fulfilled", label: "Evase", query: { status: "FULFILLED" } },
  { key: "closed", label: "Rifiutate/Annullate", query: { status: "REJECTED,CANCELLED" } },
  { key: "attention", label: "Da verificare", query: { needsAttention: "true" } },
];

export function tabOf(value: string | null): RedemptionTab {
  return REDEMPTION_TABS.some((t) => t.key === value) ? (value as RedemptionTab) : "todo";
}

export const REDEMPTION_STATUS_LABEL: Record<RedemptionStatus, string> = {
  PENDING: "In attesa",
  CONFIRMED: "Confermata",
  FULFILLED: "Evasa",
  REJECTED: "Rifiutata",
  CANCELLED: "Annullata",
};

/** Motivi di rifiuto/annullo in chiaro; i motivi liberi dell'operatore passano invariati. */
export function reasonLabel(reason: string | null): string | null {
  if (!reason) return null;
  const known: Record<string, string> = {
    INSUFFICIENT_BALANCE: "Saldo insufficiente",
    MEMBER_NOT_ACTIVE: "Membro non attivo",
    TIMEOUT: "Nessuna risposta dal wallet entro 10 minuti",
    MEMBER: "Annullata dal membro",
    LATE_SPEND: "Spesa arrivata dopo il timeout",
  };
  return known[reason] ?? reason;
}

/** Età di una richiesta ("3 h", "2 g") rispetto a {@code now}. */
export function ageLabel(requestedAt: string, now: Date = new Date()): string {
  const minutes = Math.max(0, Math.floor((now.getTime() - new Date(requestedAt).getTime()) / 60000));
  if (minutes < 60) return `${minutes} min`;
  const hours = Math.floor(minutes / 60);
  if (hours < 48) return `${hours} h`;
  return `${Math.floor(hours / 24)} g`;
}

/** Il rimborso è arrivato quando nel tracciato della richiesta compare il fatto del wallet. */
export function refundSeen(trace: { nodes: { shortType: string }[] } | null | undefined): boolean {
  return !!trace?.nodes.some((n) => n.shortType === "wallet.points.refunded");
}
