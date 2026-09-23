import type { PortalCoupon, PortalReward, Redemption } from "@/lib/api/types";
import { formatPoints } from "@/lib/format/points";

// Regole di presentazione del portale premi (docs/09 PT-03, PT-04, PT-13). Pure: testabili senza React.

/** Stato di una fascia rispetto al saldo: raggiunta, oppure quanti punti mancano (con la quota per la barra). */
export function bandProgress(balance: number, threshold: number): { reached: boolean; missing: number; pct: number } {
  const missing = Math.max(0, threshold - balance);
  return { reached: missing === 0, missing, pct: threshold <= 0 ? 100 : Math.min(100, (balance / threshold) * 100) };
}

/** Perché un premio non si può richiedere ora (barra d'azione di PT-04), o {@code null} se si può. */
export function blockReason(reward: PortalReward, balance: number): string | null {
  if (reward.stockState === "SOLD_OUT") return "Esaurito";
  if (reward.lockedByTier?.requiredTiers.length) return `Riservato a ${reward.lockedByTier.requiredTiers.join(" e ")}`;
  if (reward.perMemberLimitReached) return "Già richiesto";
  if (balance < reward.pointsCost) return `Ti mancano ${formatPoints(reward.pointsCost - balance)} punti`;
  return null;
}

/** Etichetta di stato del premio nella griglia di PT-03 (una sola, la più importante). */
export function rewardBadge(reward: PortalReward): string | null {
  if (reward.stockState === "SOLD_OUT") return "Esaurito";
  if (reward.lockedByTier?.requiredTiers.length) return `Riservato a ${reward.lockedByTier.requiredTiers.join(" e ")}`;
  if (reward.perMemberLimitReached) return "Già richiesto";
  if (reward.stockState === "LOW") return "Ultimi pezzi";
  return null;
}

/** Stato della richiesta "in parole" (PT-13). */
export function redemptionWords(r: Pick<Redemption, "status" | "couponCode" | "fulfilmentNote" | "rejectReason">): string {
  switch (r.status) {
    case "PENDING":
      return "In conferma";
    case "CONFIRMED":
      return "Confermata";
    case "FULFILLED":
      return r.couponCode ? "Coupon emesso" : r.fulfilmentNote ? "Spedita" : "Completata";
    case "CANCELLED":
      return r.rejectReason === "MEMBER" ? "Annullata da te" : "Annullata — punti restituiti";
    case "REJECTED":
      return r.rejectReason === "INSUFFICIENT_BALANCE" ? "Punti non sufficienti" : "Non andata a buon fine";
  }
}

/** La saga ha un esito da mostrare in PT-04 (si smette di attendere). */
export function isSettled(r: Pick<Redemption, "status" | "needsAttention">, isCoupon: boolean): boolean {
  if (r.status === "FULFILLED" || r.status === "REJECTED" || r.status === "CANCELLED") return true;
  // Per i coupon si aspetta il codice; senza pool la richiesta resta confermata ("needsAttention").
  return r.status === "CONFIRMED" && (!isCoupon || r.needsAttention);
}

/** Coupon di PT-13: attivi prima (scadenza più vicina in cima), poi i non attivi. */
export function sortCoupons(list: PortalCoupon[]): PortalCoupon[] {
  const active = (c: PortalCoupon) => c.status === "ISSUED";
  return [...list].sort((a, b) => {
    if (active(a) !== active(b)) return active(a) ? -1 : 1;
    return (a.expiresAt ?? "").localeCompare(b.expiresAt ?? "");
  });
}

export const COUPON_WORDS: Record<PortalCoupon["status"], string> = {
  AVAILABLE: "Disponibile",
  ISSUED: "Attivo",
  USED: "Usato",
  EXPIRED: "Scaduto",
  VOID: "Annullato",
};
