// Date su fuso Europe/Rome (docs/07 §9): "18 set 2026, 10:42"; relative sotto le 24 h.

const DATE_TIME = new Intl.DateTimeFormat("it-IT", {
  day: "numeric",
  month: "short",
  year: "numeric",
  hour: "2-digit",
  minute: "2-digit",
  timeZone: "Europe/Rome",
});

const TIME = new Intl.DateTimeFormat("it-IT", {
  hour: "2-digit",
  minute: "2-digit",
  timeZone: "Europe/Rome",
});

export function formatDateTime(value: Date | string | number): string {
  return DATE_TIME.format(new Date(value));
}

export function formatTime(value: Date | string | number): string {
  return TIME.format(new Date(value));
}

/** "3 min fa" sotto le 24 h, altrimenti la data assoluta. */
export function formatRelative(value: Date | string | number, now: Date = new Date()): string {
  const then = new Date(value).getTime();
  const diffMs = now.getTime() - then;
  const diffMin = Math.round(diffMs / 60_000);
  if (diffMin < 1) return "ora";
  if (diffMin < 60) return `${diffMin} min fa`;
  const diffH = Math.round(diffMin / 60);
  if (diffH < 24) return `${diffH} h fa`;
  return formatDateTime(value);
}

/**
 * Aggiunge i mesi alla data di partenza e imposta il giorno alla fine del mese risultante.
 * @param months numero di mesi da aggiungere
 * @param from data di partenza (default = oggi)
 */
export function computeRollingExpiry(months: number, from: Date = new Date()): Date {
  // Prima al giorno 1, così lo spostamento di mese non "sfora" (31 gen + 1 mese ≠ 3 mar).
  const d = new Date(from.getFullYear(), from.getMonth() + months + 1, 1, 12); // mezzogiorno: stessa data in UTC
  d.setDate(0); // ultimo giorno del mese precedente = fine del mese di (from + months)
  return d;
}
