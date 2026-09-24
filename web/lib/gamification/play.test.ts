import { describe, expect, it } from "vitest";
import { endsIn, freePlayLine, targetRotation, wheelSegments } from "./play";

const prizes = [
  { code: "PTS-50", name: "50 punti", type: "POINTS" as const, points: 50, imageUrl: null, wheelColor: "#2a78d6" },
  { code: "COFFEE", name: "Buono colazione", type: "COUPON" as const, points: null, imageUrl: null, wheelColor: "#eb6834" },
];

/** Indice dello spicchio sotto l'indicatore in alto dopo una rotazione oraria di `deg`. */
function under(deg: number, n: number): number {
  const slice = 360 / n;
  const atTop = (((360 - (deg % 360)) % 360) + 360) % 360;
  return Math.floor(atTop / slice);
}

describe("ruota", () => {
  it("alterna premi e Ritenta", () => {
    const s = wheelSegments(prizes);
    expect(s.map((x) => x.label)).toEqual(["50 punti", "Ritenta", "Buono colazione", "Ritenta"]);
  });
  it("si ferma sullo spicchio del premio vinto dopo almeno 5 giri", () => {
    const s = wheelSegments(prizes);
    const r = targetRotation(s, "COFFEE", 0, "PLAY-1");
    expect(r).toBeGreaterThanOrEqual(5 * 360);
    expect(s[under(r, s.length)].prizeCode).toBe("COFFEE");
    const r2 = targetRotation(s, "PTS-50", r, "PLAY-2");
    expect(r2 - r).toBeGreaterThanOrEqual(5 * 360);
    expect(s[under(r2, s.length)].prizeCode).toBe("PTS-50");
  });
  it("per una giocata persa si ferma su un Ritenta, sempre lo stesso per la stessa giocata", () => {
    const s = wheelSegments(prizes);
    const r = targetRotation(s, null, 0, "PLAY-3");
    expect(s[under(r, s.length)].prizeCode).toBeNull();
    expect(targetRotation(s, null, 0, "PLAY-3")).toBe(r);
  });
});

describe("testi", () => {
  it("scadenza in parole", () => {
    const now = new Date("2026-09-24T10:00:00Z");
    expect(endsIn("2026-09-24T20:00:00Z", now)).toBe("termina oggi");
    expect(endsIn("2026-09-25T12:00:00Z", now)).toBe("termina domani");
    expect(endsIn("2026-10-06T12:00:00Z", now)).toBe("termina tra 12 giorni");
  });
  it("giocata gratuita", () => {
    expect(freePlayLine({ freePlayDaily: true, freePlayAvailable: true })).toBe("Giocata di oggi disponibile");
    expect(freePlayLine({ freePlayDaily: false, freePlayAvailable: false })).toBeNull();
  });
});
