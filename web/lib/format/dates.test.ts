import { describe, it, expect } from "vitest";
import { formatDate, formatDateTime, formatTime, formatRelative, computeRollingExpiry } from "./dates";

describe("format/dates", () => {
  it("formatta le date in it-IT", () => {
    const date = new Date("2026-09-18T08:42:00Z");

    expect(formatDate(date)).toMatch(/18 set 2026/i);
    expect(formatDateTime(date)).toMatch(/18 set 2026/i);
    expect(formatDateTime(date)).toMatch(/10:42/);
    expect(formatTime(date)).toMatch(/10:42/);
  });

  it("gestisce i valori vuoti", () => {
    expect(formatDate(null)).toBe("—");
    expect(formatDate(undefined)).toBe("—");
  });

  it("formatta le date relative", () => {
    const now = new Date("2024-01-15T12:00:00Z");

    // < 1 min -> "ora" (Math.round(30_000 / 60_000) = 1, so wait, 30s is 1. If diff is 29s, Math.round(29/60) = 0).
    expect(formatRelative(new Date("2024-01-15T11:59:31Z"), now)).toBe("ora");

    // < 60 min -> "X min fa"
    expect(formatRelative(new Date("2024-01-15T11:45:00Z"), now)).toBe("15 min fa");

    // < 24 h -> "X h fa"
    expect(formatRelative(new Date("2024-01-15T09:00:00Z"), now)).toBe("3 h fa");

    // >= 24 h -> data assoluta
    const old = new Date("2024-01-10T12:00:00Z");
    expect(formatRelative(old, now)).toMatch(/10 gen 2024/i);
  });

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
});
