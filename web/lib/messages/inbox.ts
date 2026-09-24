import type { MessageCategory, MessageChannel, PortalMessage } from "./types";

// Regole pure dell'inbox (PT-12, campanella della shell, docs/09 §1) e delle etichette di BO-19.

export const CATEGORY_LABEL: Record<MessageCategory, string> = {
  POINTS: "Punti",
  TIER: "Livello",
  REWARD: "Premi",
  GAME: "Gioco",
  PROGRAM: "Programma",
};

export const CATEGORIES = Object.keys(CATEGORY_LABEL) as MessageCategory[];

export const CHANNEL_LABEL: Record<MessageChannel, string> = {
  INAPP: "In app",
  EMAIL_FAKE: "E-mail (anteprima)",
};

export const CHANNELS = Object.keys(CHANNEL_LABEL) as MessageChannel[];

/** Intervallo di aggiornamento della campanella (docs/09 §1: "ogni 30 s e a ogni fatto ricevuto"). */
export const UNREAD_POLL_MS = 30_000;

/** Contatore della campanella: niente sotto 1, "9+" oltre 9 (il pallino resta leggibile a 44 px). */
export function unreadBadge(unread: number | null | undefined): string | null {
  if (unread == null || !Number.isFinite(unread) || unread < 1) return null;
  return unread > 9 ? "9+" : String(Math.floor(unread));
}

/**
 * Destinazione del tocco su una notifica: solo percorsi interni del portale (niente URL esterni, schemi, `//host`,
 * risalite `..` o spazi). Un link non sicuro o assente → nessuna navigazione (la notifica si segna solo come letta).
 */
export function inboxHref(linkTarget: string | null | undefined): string | null {
  if (!linkTarget) return null;
  const t = linkTarget.trim();
  if (!/^\/portal(?:[/?#]|$)/.test(t)) return null;
  if (t.includes("//") || t.includes("\\") || t.includes("..") || /\s/.test(t)) return null;
  return t;
}

const DAY_KEY = new Intl.DateTimeFormat("en-CA", { timeZone: "Europe/Rome", year: "numeric", month: "2-digit", day: "2-digit" });
const TIME = new Intl.DateTimeFormat("it-IT", { hour: "2-digit", minute: "2-digit", timeZone: "Europe/Rome" });
const DATE = new Intl.DateTimeFormat("it-IT", { day: "numeric", month: "short", year: "numeric", timeZone: "Europe/Rome" });

function dayIndex(d: Date): number {
  // Giorno di calendario a Roma → numero di giorni dall'epoca (per "ieri", "3 giorni fa").
  return Math.round(Date.parse(`${DAY_KEY.format(d)}T00:00:00Z`) / 86_400_000);
}

/**
 * Quando, nel linguaggio del portale (docs/09 §2): "adesso", "12 min fa", "oggi alle 10:42", "ieri alle 18:05",
 * "3 giorni fa" (fino a 6), poi la data estesa "18 set 2026". Giorni di calendario sul fuso Europe/Rome.
 */
export function relativeWhen(value: string | number | Date, now: Date = new Date()): string {
  const then = new Date(value);
  const diffMin = Math.floor((now.getTime() - then.getTime()) / 60_000);
  if (diffMin < 1) return "adesso";
  if (diffMin < 60) return `${diffMin} min fa`;
  const days = dayIndex(now) - dayIndex(then);
  if (days <= 0) return `oggi alle ${TIME.format(then)}`;
  if (days === 1) return `ieri alle ${TIME.format(then)}`;
  if (days < 7) return `${days} giorni fa`;
  return DATE.format(then);
}

/** Stato locale dopo "segna come letta": la voce passa a letta, le altre restano come sono. */
export function markReadLocally(items: PortalMessage[], id: string, at: string): PortalMessage[] {
  return items.map((m) => (m.id === id && !m.read ? { ...m, read: true, readAt: at } : m));
}

/** Stato locale dopo "segna tutte come lette". */
export function markAllReadLocally(items: PortalMessage[], at: string): PortalMessage[] {
  return items.map((m) => (m.read ? m : { ...m, read: true, readAt: at }));
}

/** Nuovi arrivi rispetto a un elenco già mostrato (per evidenziare le righe comparse in cima). */
export function newArrivals(previous: PortalMessage[] | undefined, next: PortalMessage[]): Set<string> {
  if (!previous) return new Set();
  const known = new Set(previous.map((m) => m.id));
  return new Set(next.filter((m) => !known.has(m.id)).map((m) => m.id));
}
