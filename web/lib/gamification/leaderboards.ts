import type { LeaderboardMetric, LeaderboardPeriod } from "@/lib/api/types";

// Classifiche (docs/08 §BO-16, docs/09 §PT-10): etichette, periodi in parole, unità del punteggio.

export const LB_METRIC_LABEL: Record<LeaderboardMetric, string> = {
  PTS_EARNED: "Punti guadagnati",
  STS_EARNED: "Punti status",
  ACTION_COUNT: "Numero di azioni",
};

export const LB_PERIOD_LABEL: Record<LeaderboardPeriod, string> = {
  MONTH: "Mese",
  EDITION: "Edizione",
  ALL_TIME: "Sempre",
};

const MONTHS = ["gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno", "luglio", "agosto", "settembre", "ottobre", "novembre", "dicembre"];

/** "settembre 2026", "Edizione 2026", "Da sempre". */
export function periodKeyLabel(key: string): string {
  const month = /^(\d{4})-(\d{2})$/.exec(key);
  if (month) return `${MONTHS[Number(month[2]) - 1]} ${month[1]}`;
  const edition = /^ED-(\d{4})$/.exec(key);
  if (edition) return `Edizione ${edition[1]}`;
  return "Da sempre";
}

/** Unità del punteggio per il portale: "punti", "punti status", "azioni". */
export function scoreUnit(metric: LeaderboardMetric): string {
  return metric === "PTS_EARNED" ? "punti" : metric === "STS_EARNED" ? "punti status" : "azioni";
}

/** "Sei 14°" / "Non sei ancora in classifica". */
export function myPositionLine(me: { rank: number } | null): string {
  return me ? `Sei ${me.rank}°` : "Non sei ancora in classifica";
}
