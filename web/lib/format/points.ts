// Formati numerici it-IT (docs/07 §9): punti "1.850", valute "€ 129,90".

// useGrouping "always": l'italiano di default non raggruppa i numeri a 4 cifre (min2),
// ma la specifica vuole "1.850" (docs/07 §9).
const POINTS = new Intl.NumberFormat("it-IT", { maximumFractionDigits: 0, useGrouping: "always" });
// Valute: la specifica vuole il simbolo davanti ("€ 129,90") e il separatore anche a 4 cifre ("€ 1.234,50"), mentre la
// valuta it-IT di Intl dà "1234,50 €": si compone il simbolo con un numero a 2 decimali.
const EURO = new Intl.NumberFormat("it-IT", { minimumFractionDigits: 2, maximumFractionDigits: 2, useGrouping: "always" });

/** Intl mostra il segno dello zero negativo ("-0", anche per −0,4 arrotondato): lo zero è sempre senza segno. */
function unsignedZero(formatted: string): string {
  return /^-0([,.]0*)?$/.test(formatted) ? formatted.slice(1) : formatted;
}

export function formatPoints(value: number): string {
  return unsignedZero(POINTS.format(value));
}

/** "€ 129,90", "€ 1.234,50"; negativo "-€ 5,00". Spazio non separabile tra simbolo e cifra. */
export function formatEuro(value: number): string {
  const abs = unsignedZero(EURO.format(Math.abs(value)));
  const negative = value < 0 && abs !== "0,00";
  return `${negative ? "-" : ""}€\u00a0${abs}`;
}

/** Etichetta valuta del programma: PTS punti premio, STS punti status (docs/01 glossario). */
export function currencyLabel(currency: "PTS" | "STS"): string {
  return currency === "PTS" ? "punti" : "status";
}
