import type { Contest, ContestMechanic, ContestPrize, InstantDay } from "@/lib/api/types";

// Concorsi instant win lato backoffice (docs/08 §BO-14, docs/servizi/gamification-service.md §2).

export const MECHANIC_LABEL: Record<ContestMechanic, string> = {
  WHEEL: "Ruota",
  SCRATCH: "Gratta e vinci",
  BOX: "Pacco regalo",
};

export const DISTRIBUTION_LABEL: Record<string, string> = {
  UNIFORM: "Uniforme su tutto il periodo",
  BUSINESS_HOURS: "Solo in orario 8–22",
};

/** Stati dal LIVE in poi: premi, periodo e istanti non si toccano più (docs/03 §6). */
export function isLocked(status: string): boolean {
  return status === "LIVE" || status === "PAUSED" || status === "ENDED" || status === "ARCHIVED";
}

/** "50 punti", "Buono colazione 5 € (RWD-COFFEE-5)", "Powerbank solare (fisico)". */
export function prizeLabel(p: Pick<ContestPrize, "type" | "name" | "points" | "rewardCode">): string {
  if (p.type === "POINTS") return `${p.points ?? 0} punti`;
  if (p.type === "COUPON") return p.rewardCode ? `${p.name} (${p.rewardCode})` : p.name;
  return `${p.name} (fisico)`;
}

/** Montepremi in una riga: "200 × 50 punti · 100 × 100 punti · …". */
export function prizePoolSummary(prizes: ContestPrize[]): string {
  if (prizes.length === 0) return "Nessun premio";
  return prizes.map((p) => `${p.quantityTotal} × ${prizeLabel(p)}`).join(" · ");
}

/** Quota del montepremi ancora da assegnare, 0–100 (0 se il montepremi è vuoto). */
export function remainingPct(c: Pick<Contest, "prizesTotal" | "prizesRemaining">): number {
  return c.prizesTotal <= 0 ? 0 : Math.round((c.prizesRemaining / c.prizesTotal) * 100);
}

/** Tasso di vincita in italiano: "12,5 %"; trattino senza giocate. */
export function formatRate(wins: number, plays: number): string {
  if (plays <= 0) return "—";
  return `${new Intl.NumberFormat("it-IT", { maximumFractionDigits: 1 }).format((wins / plays) * 100)} %`;
}

/**
 * Giorni continui per l'istogramma (i giorni senza istanti a zero): altrimenti un periodo con buchi sembrerebbe più
 * denso di quello che è. Le date sono `YYYY-MM-DD` (giorno Europe/Rome calcolato dal servizio).
 */
export function fillInstantDays(days: InstantDay[]): InstantDay[] {
  if (days.length === 0) return days;
  const byDay = new Map(days.map((d) => [d.day, d]));
  const out: InstantDay[] = [];
  const last = days[days.length - 1].day;
  for (let d = days[0].day; d <= last; d = nextDay(d)) {
    out.push(byDay.get(d) ?? { day: d, total: 0, open: 0, claimed: 0, voided: 0 });
  }
  return out;
}

function nextDay(iso: string): string {
  const [y, m, d] = iso.split("-").map(Number);
  const next = new Date(Date.UTC(y, m - 1, d + 1));
  return next.toISOString().slice(0, 10);
}

/** Da un input `datetime-local` (ora di Roma nel browser della demo) a ISO; vuoto → undefined. */
export function localInputToIso(value: string): string | undefined {
  return value ? new Date(value).toISOString() : undefined;
}

/** Da ISO a valore per `datetime-local` nel fuso del browser. */
export function isoToLocalInput(iso: string | null | undefined): string {
  if (!iso) return "";
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}
