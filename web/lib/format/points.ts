// Formati numerici it-IT (docs/07 §9): punti "1.850", valute "€ 129,90".

// useGrouping "always": l'italiano di default non raggruppa i numeri a 4 cifre (min2),
// ma la specifica vuole "1.850" (docs/07 §9).
const POINTS = new Intl.NumberFormat("it-IT", { maximumFractionDigits: 0, useGrouping: "always" });
const EURO = new Intl.NumberFormat("it-IT", { style: "currency", currency: "EUR" });

export function formatPoints(value: number): string {
  return POINTS.format(value);
}

export function formatEuro(value: number): string {
  return EURO.format(value);
}

/** Etichetta valuta del programma: PTS punti premio, STS punti status (docs/01 glossario). */
export function currencyLabel(currency: "PTS" | "STS"): string {
  return currency === "PTS" ? "punti" : "status";
}
