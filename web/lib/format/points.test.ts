import { describe, it, expect } from "vitest";
import { formatPoints, formatEuro, currencyLabel } from "./points";

describe("format/points", () => {
  it("formatta i punti in stile it-IT", () => {
    expect(formatPoints(1850)).toBe("1.850");
    expect(formatPoints(0)).toBe("0");
  });
  it("formatta gli euro", () => {
    expect(formatEuro(129.9)).toContain("129,90");
  });
  it("etichetta la valuta", () => {
    expect(currencyLabel("PTS")).toBe("punti");
    expect(currencyLabel("STS")).toBe("status");
  });
});
