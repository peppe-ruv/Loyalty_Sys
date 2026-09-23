import { describe, it, expect } from "vitest";
import { computeRollingExpiry } from "./dates";

describe("computeRollingExpiry", () => {
  it("aggiunge i mesi e va a fine mese (mese normale)", () => {
    // 15 gennaio 2024
    const from = new Date("2024-01-15T10:00:00Z");
    const expiry = computeRollingExpiry(12, from);

    expect(expiry.getUTCFullYear()).toBe(2025);
    expect(expiry.getUTCMonth()).toBe(0); // Gennaio
    expect(expiry.getUTCDate()).toBe(31);
  });

  it("gestisce gli anni bisestili (febbraio)", () => {
    // 10 febbraio 2023 -> +12 mesi = fine feb 2024 (bisestile)
    const from = new Date("2023-02-10T10:00:00Z");
    const expiry = computeRollingExpiry(12, from);

    expect(expiry.getUTCFullYear()).toBe(2024);
    expect(expiry.getUTCMonth()).toBe(1); // Febbraio
    expect(expiry.getUTCDate()).toBe(29);
  });

  it("dal 31 del mese non sfora nel mese successivo", () => {
    const expiry = computeRollingExpiry(12, new Date(2024, 0, 31, 12));
    expect(expiry.getFullYear()).toBe(2025);
    expect(expiry.getMonth()).toBe(0);
    expect(expiry.getDate()).toBe(31);
  });
});
