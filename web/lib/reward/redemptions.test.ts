import { describe, expect, it } from "vitest";
import { ageLabel, reasonLabel, refundSeen, tabOf } from "./redemptions";

describe("tabOf", () => {
  it("scheda sconosciuta → Da evadere", () => {
    expect(tabOf("closed")).toBe("closed");
    expect(tabOf("nope")).toBe("todo");
    expect(tabOf(null)).toBe("todo");
  });
});

describe("ageLabel", () => {
  const now = new Date("2026-09-23T12:00:00Z");
  it("minuti, ore, giorni", () => {
    expect(ageLabel("2026-09-23T11:45:00Z", now)).toBe("15 min");
    expect(ageLabel("2026-09-22T09:00:00Z", now)).toBe("27 h");
    expect(ageLabel("2026-09-18T12:00:00Z", now)).toBe("5 g");
  });
});

describe("reasonLabel", () => {
  it("traduce i codici noti e lascia i motivi liberi", () => {
    expect(reasonLabel("TIMEOUT")).toContain("10 minuti");
    expect(reasonLabel("Articolo danneggiato")).toBe("Articolo danneggiato");
    expect(reasonLabel(null)).toBeNull();
  });
});

describe("refundSeen", () => {
  it("cerca il fatto wallet.points.refunded", () => {
    expect(refundSeen({ nodes: [{ shortType: "reward.redemption.cancelled" }] })).toBe(false);
    expect(refundSeen({ nodes: [{ shortType: "wallet.points.refunded" }] })).toBe(true);
    expect(refundSeen(null)).toBe(false);
  });
});
