import { describe, expect, it } from "vitest";
import type { PortalCoupon, PortalReward } from "@/lib/api/types";
import { bandProgress, blockReason, isSettled, redemptionWords, rewardBadge, sortCoupons } from "./portal";

const base: PortalReward = {
  code: "RWD-X", name: "X", type: "COUPON", imageUrl: null, category: null, pointsCost: 1500,
  stockState: "AVAILABLE", lockedByTier: null, perMemberLimitReached: false,
};

describe("bandProgress", () => {
  it("raggiunta o punti mancanti", () => {
    expect(bandProgress(2000, 1500)).toEqual({ reached: true, missing: 0, pct: 100 });
    expect(bandProgress(1150, 1500)).toMatchObject({ reached: false, missing: 350 });
  });
});

describe("blockReason", () => {
  it("esaurito e livello prima del saldo", () => {
    expect(blockReason({ ...base, stockState: "SOLD_OUT" }, 99999)).toBe("Esaurito");
    expect(blockReason({ ...base, lockedByTier: { requiredTiers: ["GOLD", "PLATINUM"] } }, 99999)).toBe("Riservato a GOLD e PLATINUM");
    expect(blockReason({ ...base, perMemberLimitReached: true }, 99999)).toBe("Già richiesto");
    expect(blockReason(base, 1150)).toBe("Ti mancano 350 punti");
    expect(blockReason(base, 1500)).toBeNull();
  });
});

describe("rewardBadge", () => {
  it("ultimi pezzi solo se nulla di più importante", () => {
    expect(rewardBadge({ ...base, stockState: "LOW" })).toBe("Ultimi pezzi");
    expect(rewardBadge(base)).toBeNull();
  });
});

describe("redemptionWords", () => {
  it("stato in parole", () => {
    expect(redemptionWords({ status: "PENDING", couponCode: null, fulfilmentNote: null, rejectReason: null })).toBe("In conferma");
    expect(redemptionWords({ status: "FULFILLED", couponCode: null, fulfilmentNote: "Corriere", rejectReason: null })).toBe("Spedita");
    expect(redemptionWords({ status: "CANCELLED", couponCode: null, fulfilmentNote: null, rejectReason: "Danneggiato" }))
      .toBe("Annullata — punti restituiti");
    expect(redemptionWords({ status: "REJECTED", couponCode: null, fulfilmentNote: null, rejectReason: "INSUFFICIENT_BALANCE" }))
      .toBe("Punti non sufficienti");
  });
});

describe("isSettled", () => {
  it("per i coupon aspetta il codice", () => {
    expect(isSettled({ status: "CONFIRMED", needsAttention: false }, true)).toBe(false);
    expect(isSettled({ status: "CONFIRMED", needsAttention: false }, false)).toBe(true);
    expect(isSettled({ status: "CONFIRMED", needsAttention: true }, true)).toBe(true);
    expect(isSettled({ status: "PENDING", needsAttention: false }, false)).toBe(false);
  });
});

describe("sortCoupons", () => {
  it("attivi prima, per scadenza", () => {
    const c = (code: string, status: PortalCoupon["status"], expiresAt: string): PortalCoupon => ({
      code, status, expiresAt, rewardCode: null, rewardName: null, issuedAt: null, origin: null,
    });
    const out = sortCoupons([c("A", "USED", "2026-01-01"), c("B", "ISSUED", "2026-12-01"), c("C", "ISSUED", "2026-10-01")]);
    expect(out.map((x) => x.code)).toEqual(["C", "B", "A"]);
  });
});
