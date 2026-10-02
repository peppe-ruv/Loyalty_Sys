// Durate leggibili (docs/08 §BO-25, issue #204): secondi con una cifra decimale, mai millisecondi.

const ONE_DECIMAL = new Intl.NumberFormat("it-IT", { minimumFractionDigits: 1, maximumFractionDigits: 1 });

/** Secondi con un decimale: 6426 → "6,4"; 850 → "0,9"; valori negativi o non numerici → "0,0". */
function seconds(ms: number): string {
  const safe = Number.isFinite(ms) && ms > 0 ? ms : 0;
  return ONE_DECIMAL.format(safe / 1000);
}

/** Durata per esteso: 6426 → "6,4 secondi"; 850 → "0,9 secondi". */
export function formatSeconds(ms: number): string {
  return `${seconds(ms)} secondi`;
}

/** Durata breve per le righe di una timeline: 2100 → "2,1 s". */
export function formatSecondsShort(ms: number): string {
  return `${seconds(ms)} s`;
}
