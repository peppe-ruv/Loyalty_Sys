import { describe, expect, it } from "vitest";
import type { ContestPrize } from "@/lib/api/types";
import { fillInstantDays, formatRate, isLocked, prizeLabel, prizePoolSummary, remainingPct } from "./contests";

const prize = (p: Partial<ContestPrize>): ContestPrize => ({
  id: "P",
  code: "X",
  name: "Premio",
  type: "POINTS",
  points: null,
  rewardCode: null,
  quantityTotal: 1,
  quantityRemaining: 1,
  imageUrl: null,
  wheelColor: null,
  sortOrder: 1,
  ...p,
});

describe("montepremi", () => {
  it("descrive ogni tipo di premio", () => {
    expect(prizeLabel(prize({ type: "POINTS", points: 50 }))).toBe("50 punti");
    expect(prizeLabel(prize({ type: "COUPON", name: "Buono colazione 5 €", rewardCode: "RWD-COFFEE-5" }))).toBe(
      "Buono colazione 5 € (RWD-COFFEE-5)",
    );
    expect(prizeLabel(prize({ type: "PHYSICAL", name: "Powerbank solare" }))).toBe("Powerbank solare (fisico)");
  });
  it("riassume il montepremi in una riga", () => {
    expect(
      prizePoolSummary([
        prize({ type: "POINTS", points: 50, quantityTotal: 200 }),
        prize({ type: "PHYSICAL", name: "Powerbank solare", quantityTotal: 5 }),
      ]),
    ).toBe("200 × 50 punti · 5 × Powerbank solare (fisico)");
    expect(prizePoolSummary([])).toBe("Nessun premio");
  });
  it("calcola la quota residua", () => {
    expect(remainingPct({ prizesTotal: 355, prizesRemaining: 355 })).toBe(100);
    expect(remainingPct({ prizesTotal: 200, prizesRemaining: 50 })).toBe(25);
    expect(remainingPct({ prizesTotal: 0, prizesRemaining: 0 })).toBe(0);
  });
});

describe("stato e statistiche", () => {
  it("blocca premi e istanti dal LIVE in poi", () => {
    expect(isLocked("DRAFT")).toBe(false);
    expect(isLocked("APPROVED")).toBe(false);
    expect(isLocked("LIVE")).toBe(true);
    expect(isLocked("ENDED")).toBe(true);
  });
  it("formatta il tasso di vincita", () => {
    expect(formatRate(1, 8)).toBe("12,5 %");
    expect(formatRate(0, 0)).toBe("—");
  });
  it("riempie i giorni senza istanti", () => {
    const days = fillInstantDays([
      { day: "2026-09-29", total: 2, open: 2, claimed: 0, voided: 0 },
      { day: "2026-10-02", total: 1, open: 1, claimed: 0, voided: 0 },
    ]);
    expect(days.map((d) => d.day)).toEqual(["2026-09-29", "2026-09-30", "2026-10-01", "2026-10-02"]);
    expect(days[1].total).toBe(0);
  });
});
