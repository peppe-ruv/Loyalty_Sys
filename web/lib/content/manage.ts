import type { ContentItem, ContentKind, ContentPlacement, ContentStatus } from "./types";

// Gestione contenuti in BO-18 (docs/08 §BO-18): logica pura, testata a parte.

export const PLACEMENTS: ContentPlacement[] = ["HOME_HERO", "HOME_GRID", "CATALOG_TOP", "CONTEST", "WIN"];

export const KIND_LABEL: Record<ContentKind, string> = { CARD: "Card", POPUP: "Pop-up", BANNER: "Banner" };

/** Transizioni valide per stato (i contenuti non richiedono approvazione: Pubblica diretto). */
export const CONTENT_ACTIONS: Record<ContentStatus, { action: string; label: string }[]> = {
  DRAFT: [{ action: "PUBLISH", label: "Pubblica" }, { action: "ARCHIVE", label: "Archivia" }],
  LIVE: [{ action: "PAUSE", label: "Metti in pausa" }, { action: "END", label: "Termina" }],
  PAUSED: [{ action: "RESUME", label: "Riprendi" }, { action: "END", label: "Termina" }],
  ENDED: [{ action: "ARCHIVE", label: "Archivia" }],
  ARCHIVED: [],
};

/** Calendario in parole, rispetto ad adesso. */
export function scheduleLabel(c: Pick<ContentItem, "startAt" | "endAt">, now: Date = new Date()): string {
  const start = c.startAt ? new Date(c.startAt) : null;
  const end = c.endAt ? new Date(c.endAt) : null;
  const fmt = (d: Date) => d.toLocaleDateString("it-IT", { day: "numeric", month: "short" });
  if (start && start > now) return `dal ${fmt(start)}${end ? ` al ${fmt(end)}` : ""}`;
  if (end && end < now) return `terminato il ${fmt(end)}`;
  if (end) return `fino al ${fmt(end)}`;
  return "sempre";
}

export function inSchedule(c: Pick<ContentItem, "startAt" | "endAt">, now: Date = new Date()): boolean {
  return (!c.startAt || new Date(c.startAt) <= now) && (!c.endAt || new Date(c.endAt) > now);
}

/**
 * "Per posizione": per ogni posizionamento l'ordine effettivo, senza considerare il pubblico (che dipende dal membro):
 * LIVE ∧ in calendario, per priorità decrescente (docs/03 §9). I primi `limit` sono quelli che si vedono.
 */
export function effectiveOrder(items: ContentItem[], placement: ContentPlacement, now: Date = new Date()): ContentItem[] {
  return items
    .filter((c) => c.placement === placement && c.kind !== "POPUP" && c.status === "LIVE" && inSchedule(c, now))
    .sort((a, b) => b.priority - a.priority || a.code.localeCompare(b.code));
}

/** Pubblico in parole: "tutti" o l'elenco di livelli/segmenti/stati. */
export function audienceLabel(a: ContentItem["audience"] | null | undefined): string {
  if (!a) return "tutti";
  const parts = [...(a.tiers ?? []), ...(a.segments ?? []), ...(a.statuses ?? [])];
  return parts.length ? parts.join(", ") : "tutti";
}

/** Valore per <input type="datetime-local"> nel fuso del browser. */
export function toLocalInput(iso: string | null): string {
  if (!iso) return "";
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

export function fromLocalInput(value: string): string | null {
  return value ? new Date(value).toISOString() : null;
}

/**
 * Oggetto `LIVE` o `PAUSED` (docs/03 §3.6; docs/08 §3.2; Q-174): si modificano solo i campi "sicuri" (titolo, testo, immagine, priorità,
 * fine calendario); per il resto si duplica, e il servizio risponde `409 CONTENT_LIVE_LOCKED`. Il `PUT` sostituisce
 * tutto: per un `LIVE`/`PAUSED` i campi non sicuri si rimandano come li ha il servizio, così salvare i soli campi sicuri non
 * cambia altro per errore (minuti delle date, chiavi del pubblico che il form non mostra).
 */
export function liveSafeBody<B extends object>(body: B, current: ContentItem): B {
  return {
    ...body,
    placement: current.placement,
    ctaLabel: current.ctaLabel,
    ctaTarget: current.ctaTarget,
    linkType: current.linkType,
    linkCode: current.linkCode,
    audience: current.audience,
    startAt: current.startAt,
    frequency: current.frequency,
    dismissible: current.dismissible,
    style: current.style,
  };
}
