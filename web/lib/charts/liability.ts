import type { LiabilityMonth } from "@/lib/api/types";

export interface MonthBucket {
  key: string; // YYYY-MM, "later" o "never"
  label: string;
  amount: number;
}

const MONTH = new Intl.DateTimeFormat("it-IT", { month: "short", year: "2-digit", timeZone: "UTC" });

/**
 * Ripartisce la passività su {@code months} mesi a partire dal mese di {@code from} (inclusi i mesi a zero),
 * più "oltre" (scadenze successive e mesi passati non ancora scaduti) e "senza scadenza". Pura e testabile.
 */
export function liabilityBuckets(rows: LiabilityMonth[], from: Date, months = 12): MonthBucket[] {
  const start = Date.UTC(from.getFullYear(), from.getMonth(), 1);
  const keys: string[] = [];
  const buckets: MonthBucket[] = [];
  for (let i = 0; i < months; i++) {
    const d = new Date(start);
    d.setUTCMonth(d.getUTCMonth() + i);
    const key = `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, "0")}`;
    keys.push(key);
    buckets.push({ key, label: MONTH.format(d), amount: 0 });
  }
  let later = 0;
  let never = 0;
  for (const r of rows) {
    if (r.month === null) {
      never += r.amount;
      continue;
    }
    const i = keys.indexOf(r.month);
    if (i >= 0) buckets[i].amount += r.amount;
    else later += r.amount; // oltre l'orizzonte (o mese passato: lotto non ancora passato dal job di scadenza)
  }
  buckets.push({ key: "later", label: "oltre", amount: later });
  if (never > 0) buckets.push({ key: "never", label: "mai", amount: never });
  return buckets;
}
