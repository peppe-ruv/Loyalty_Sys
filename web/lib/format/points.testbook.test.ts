import { describe, it, expect } from "vitest";
import { formatPoints, formatEuro } from "./points";

describe("Testbook: Formattazione Numerica", () => {
  it("[TB-WEB-FMT-001] formatPoints: 0", () => {
    expect(formatPoints(0)).toBe("0");
  });

  it("[TB-WEB-FMT-002] formatPoints: 999", () => {
    expect(formatPoints(999)).toBe("999");
  });

  it("[TB-WEB-FMT-003] formatPoints: 1000", () => {
    expect(formatPoints(1000)).toBe("1.000");
  });

  it("[TB-WEB-FMT-004] formatPoints: 1000000", () => {
    expect(formatPoints(1000000)).toBe("1.000.000");
  });

  it("[TB-WEB-FMT-005] formatPoints: negativi", () => {
    expect(formatPoints(-1250)).toBe("-1.250");
  });

  it("[TB-WEB-FMT-006] formatEuro: 0", () => {
    const s = formatEuro(0).replace(/\s/g, ' ');
    expect(s).toContain("0,00");
    expect(s).toContain("€");
  });
});
