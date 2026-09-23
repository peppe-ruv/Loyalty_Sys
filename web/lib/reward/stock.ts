// Stato dello stock di un premio (docs/08 BO-10 note; docs/servizi/reward-service.md §3 `stockState`):
// sotto il 10 % del totale → "in esaurimento", a zero → "esaurito", totale nullo → illimitato.
export type StockState = "UNLIMITED" | "AVAILABLE" | "LOW" | "SOLD_OUT";

export function stockState(total: number | null, remaining: number | null): StockState {
  if (total == null) return "UNLIMITED";
  const left = Math.max(0, remaining ?? 0);
  if (left === 0) return "SOLD_OUT";
  return left * 10 < total ? "LOW" : "AVAILABLE";
}

/** Quota residua in percentuale (0–100); 100 per lo stock illimitato. */
export function stockPercent(total: number | null, remaining: number | null): number {
  if (total == null) return 100;
  if (total <= 0) return 0;
  return Math.min(100, Math.max(0, ((remaining ?? 0) / total) * 100));
}

/** Premi LIVE che cambierebbero costo se la soglia della fascia cambiasse (BO-11, "impatto prima di salvare"). */
export function liveRewardsInBand(rewards: { bandCode: string; status: string }[], band: string): number {
  return rewards.filter((r) => r.bandCode === band && r.status === "LIVE").length;
}
