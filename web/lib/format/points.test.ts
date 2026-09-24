import { describe, it, expect } from "vitest";
import { formatPoints, formatEuro, currencyLabel } from "./points";

describe("format/points", () => {
  it("formatta i punti in stile it-IT con i separatori", () => {
    // Expected style: "1.850" depending on node version it might be different space character
    // We replace thin spaces and non breaking spaces with normal dot or space for robust test
    const formatted = formatPoints(1850).replace(/\s/g, '.');
    expect(formatted).toBe("1.850");

    expect(formatPoints(0)).toBe("0");

    const formattedBig = formatPoints(1234567).replace(/\s/g, '.');
    expect(formattedBig).toBe("1.234.567");
  });

  it("formatta gli euro", () => {
    const formatted = formatEuro(129.90).replace(/\s/g, ' ');
    // usually "129,90 €" or "€ 129,90"
    expect(formatted).toContain("129,90");
    expect(formatted).toContain("€");
  });

  it("etichetta la valuta", () => {
    expect(currencyLabel("PTS")).toBe("punti");
    expect(currencyLabel("STS")).toBe("status");
  });
});
