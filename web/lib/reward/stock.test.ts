import { describe, expect, it } from "vitest";
import { liveRewardsInBand, stockPercent, stockState } from "./stock";

describe("stockState", () => {
  it("illimitato quando il totale manca", () => {
    expect(stockState(null, null)).toBe("UNLIMITED");
  });
  it("esaurito a zero, in esaurimento sotto il 10 %", () => {
    expect(stockState(60, 0)).toBe("SOLD_OUT");
    expect(stockState(150, 9)).toBe("LOW");
    expect(stockState(150, 15)).toBe("AVAILABLE");
    expect(stockState(100, 10)).toBe("AVAILABLE");
  });
});

describe("stockPercent", () => {
  it("resta nell'intervallo 0–100", () => {
    expect(stockPercent(150, 9)).toBeCloseTo(6);
    expect(stockPercent(10, 20)).toBe(100);
    expect(stockPercent(0, 0)).toBe(0);
    expect(stockPercent(null, null)).toBe(100);
  });
});

describe("liveRewardsInBand", () => {
  it("conta solo i premi LIVE della fascia", () => {
    const rs = [
      { bandCode: "F2", status: "LIVE" },
      { bandCode: "F2", status: "DRAFT" },
      { bandCode: "F3", status: "LIVE" },
    ];
    expect(liveRewardsInBand(rs, "F2")).toBe(1);
  });
});
