import type { PortalContest, PortalContestPrize } from "@/lib/api/types";

// Logica pura di PT-05/PT-06 (docs/09 §PT-05, §PT-06): l'esito lo decide sempre il server, qui si calcola solo come
// rivelarlo (spicchio della ruota su cui fermarsi) e come dirlo in parole.

export interface WheelSegment {
  key: string;
  label: string;
  color: string;
  prizeCode: string | null; // null = "Ritenta"
}

const LOSE_COLOR = "#e6ecf1";

/** Uno spicchio per premio alternato a uno "Ritenta" (docs/09 §PT-06), colori da `wheelColor`. */
export function wheelSegments(prizes: PortalContestPrize[]): WheelSegment[] {
  const out: WheelSegment[] = [];
  prizes.forEach((p, i) => {
    out.push({ key: `p-${p.code}`, label: shortPrize(p), color: p.wheelColor ?? "#1fb98f", prizeCode: p.code });
    out.push({ key: `l-${i}`, label: "Ritenta", color: LOSE_COLOR, prizeCode: null });
  });
  return out;
}

export function shortPrize(p: Pick<PortalContestPrize, "type" | "name" | "points">): string {
  if (p.type === "POINTS" && p.points != null) return `${p.points} punti`;
  return p.name;
}

/**
 * Rotazione finale (gradi, in senso orario) perché l'indicatore in alto cada al centro dello spicchio dell'esito,
 * dopo almeno `spins` giri completi oltre la rotazione attuale. Per `LOSE` sceglie uno spicchio "Ritenta" stabile
 * rispetto a `seed` (id della giocata), così la stessa giocata si rivela sempre allo stesso modo.
 */
export function targetRotation(
  segments: WheelSegment[],
  prizeCode: string | null,
  current: number,
  seed: string,
  spins = 5,
): number {
  const n = segments.length;
  if (n === 0) return current;
  const candidates = segments.map((s, i) => ({ s, i })).filter(({ s }) => s.prizeCode === prizeCode);
  const pool = candidates.length > 0 ? candidates : segments.map((s, i) => ({ s, i }));
  const index = pool[hash(seed) % pool.length].i;
  const slice = 360 / n;
  const center = index * slice + slice / 2; // posizione dello spicchio sulla ruota ferma
  const wanted = (360 - center) % 360; // ruotare di questo porta il centro in alto
  const base = current - (((current % 360) + 360) % 360);
  let target = base + spins * 360 + wanted;
  if (target - current < spins * 360) target += 360;
  return target;
}

function hash(s: string): number {
  let h = 2166136261;
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return Math.abs(h);
}

/** "termina oggi", "termina domani", "termina tra 12 giorni". */
export function endsIn(endAt: string, now: Date = new Date()): string {
  const days = Math.floor((new Date(endAt).getTime() - now.getTime()) / 864e5);
  if (days <= 0) return "termina oggi";
  if (days === 1) return "termina domani";
  return `termina tra ${days} giorni`;
}

/** Stato della giocata gratuita in una frase (PT-05). */
export function freePlayLine(c: Pick<PortalContest, "freePlayDaily" | "freePlayAvailable">): string | null {
  if (!c.freePlayDaily) return null;
  return c.freePlayAvailable ? "Giocata di oggi disponibile" : "Giocata gratuita usata: torna domani";
}

/** Messaggi degli errori di giocata (docs/servizi/gamification-service.md §3). */
export const PLAY_ERRORS: Record<string, string> = {
  NO_PLAYS_AVAILABLE: "Hai finito le giocate. Ne guadagni altre con le promozioni.",
  DAILY_LIMIT_REACHED: "Per oggi hai raggiunto il massimo di giocate. Torna domani!",
  CONTEST_NOT_LIVE: "Il concorso non è in corso.",
  MEMBER_NOT_ACTIVE: "Il tuo profilo al momento non può partecipare.",
};

/** Cosa succede dopo una vincita, in parole (docs/09 §PT-06). */
export function winFollowUp(type: string | undefined): string {
  if (type === "POINTS") return "I punti stanno arrivando…";
  if (type === "COUPON") return "Il buono arriva tra i tuoi coupon.";
  if (type === "PHYSICAL") return "Ti contatteremo per la consegna.";
  return "";
}
