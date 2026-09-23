import type { CouponStatus } from "@/lib/api/types";

// Barra segmentata dei codici per stato (BO-12). Ordine fisso degli stati = ordine fisso dei colori (palette
// categoriale di riferimento validata con dataviz: blu, arancio, acqua, giallo, magenta): il colore segue lo stato,
// mai la sua posizione tra i segmenti presenti.
export const COUPON_STATUSES: CouponStatus[] = ["AVAILABLE", "ISSUED", "USED", "EXPIRED", "VOID"];

export const COUPON_STATUS_COLOR: Record<CouponStatus, string> = {
  AVAILABLE: "#2a78d6",
  ISSUED: "#eb6834",
  USED: "#1baf7a",
  EXPIRED: "#eda100",
  VOID: "#e87ba4",
};

export const COUPON_STATUS_LABEL: Record<CouponStatus, string> = {
  AVAILABLE: "Disponibili",
  ISSUED: "Emessi",
  USED: "Usati",
  EXPIRED: "Scaduti",
  VOID: "Annullati",
};

export interface Segment {
  status: CouponStatus;
  count: number;
  pct: number;
}

/** Segmenti non vuoti in ordine fisso, con la quota sul totale. */
export function couponSegments(counts: Partial<Record<CouponStatus, number>>): Segment[] {
  const total = COUPON_STATUSES.reduce((s, k) => s + (counts[k] ?? 0), 0);
  if (total === 0) return [];
  return COUPON_STATUSES.filter((k) => (counts[k] ?? 0) > 0).map((k) => ({
    status: k,
    count: counts[k] ?? 0,
    pct: ((counts[k] ?? 0) / total) * 100,
  }));
}

/** Righe incollate → codici (una per riga, o separati da virgola/punto e virgola), senza vuoti. */
export function parseCodes(text: string): string[] {
  return text
    .split(/[\n,;]+/)
    .map((c) => c.trim())
    .filter(Boolean);
}
