// Utilità pure per i grafici inline-SVG di BO-01 (docs/08 §BO-01). Nessuna dipendenza: scala,
// tacche "tonde", costruzione di path. Testabili in isolamento (scale.test.ts).

/** Massimo "tondo" >= max, per un asse leggibile (1/2/2,5/5 × 10^n). 0 => 1. */
export function niceMax(max: number): number {
  if (max <= 0) return 1;
  const exp = Math.floor(Math.log10(max));
  const base = Math.pow(10, exp);
  const frac = max / base;
  const nice = frac <= 1 ? 1 : frac <= 2 ? 2 : frac <= 2.5 ? 2.5 : frac <= 5 ? 5 : 10;
  return nice * base;
}

/** {count}+1 valori di tacca equidistanti da 0 a max incluso. */
export function ticks(max: number, count = 4): number[] {
  const step = max / count;
  return Array.from({ length: count + 1 }, (_, i) => Math.round(step * i));
}

export interface Scale {
  x: (i: number) => number;
  y: (v: number) => number;
}

/** Scala lineare: indice 0..n-1 su [padLeft, w-padRight], valore 0..max su [h-padBottom, padTop] (y invertita). */
export function linearScale(
  n: number,
  max: number,
  w: number,
  h: number,
  pad: { top: number; right: number; bottom: number; left: number },
): Scale {
  const innerW = w - pad.left - pad.right;
  const innerH = h - pad.top - pad.bottom;
  return {
    x: (i) => pad.left + (n <= 1 ? innerW / 2 : (innerW * i) / (n - 1)),
    y: (v) => pad.top + innerH * (1 - (max <= 0 ? 0 : v / max)),
  };
}

/** Path di una spezzata attraverso i punti [x,y]. */
export function linePath(points: Array<[number, number]>): string {
  return points.map((p, i) => `${i === 0 ? "M" : "L"}${p[0].toFixed(1)},${p[1].toFixed(1)}`).join(" ");
}

/** Path di un'area chiusa sotto la spezzata (o sopra una base) tra due indici. */
export function areaPath(top: Array<[number, number]>, base: Array<[number, number]>): string {
  const up = top.map((p, i) => `${i === 0 ? "M" : "L"}${p[0].toFixed(1)},${p[1].toFixed(1)}`).join(" ");
  const down = [...base].reverse().map((p) => `L${p[0].toFixed(1)},${p[1].toFixed(1)}`).join(" ");
  return `${up} ${down} Z`;
}
