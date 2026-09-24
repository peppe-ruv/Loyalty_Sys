import { describe, it, expect } from "vitest";
import { formatDate, formatRelative, computeRollingExpiry } from "./dates";

describe("Testbook: Formattazione Date", () => {
  it("[TB-WEB-FMT-007] formatDate: null", () => {
    expect(formatDate(null)).toBe("—");
    expect(formatDate(undefined)).toBe("—");
  });

  it("[TB-WEB-FMT-008] formatRelative: < 1 min", () => {
    const now = new Date("2024-01-01T12:00:00Z");
    const diffSeconds = new Date("2024-01-01T11:59:31Z");
    expect(formatRelative(diffSeconds, now)).toBe("ora");
  });

  it("[TB-WEB-FMT-009] formatRelative: 59 min", () => {
    const now = new Date("2024-01-01T12:00:00Z");
    const diffMin = new Date("2024-01-01T11:01:00Z");
    expect(formatRelative(diffMin, now)).toBe("59 min fa");
  });

  it("[TB-WEB-FMT-010] formatRelative: 23 ore", () => {
    const now = new Date("2024-01-02T12:00:00Z");
    const diffHours = new Date("2024-01-01T13:00:00Z");
    expect(formatRelative(diffHours, now)).toBe("23 h fa");
  });

  it("[TB-WEB-FMT-011] computeRollingExpiry: mese normale (15 gen + 1 mese)", () => {
    const from = new Date("2024-01-15T12:00:00Z");
    const expiry = computeRollingExpiry(1, from);
    expect(expiry.getFullYear()).toBe(2024);
    expect(expiry.getMonth()).toBe(1); // febbraio
    expect(expiry.getDate()).toBe(29); // 2024 è bisestile
  });

  it("[TB-WEB-FMT-012] computeRollingExpiry: fine mese (31 gen + 1 mese)", () => {
    const from = new Date("2024-01-31T12:00:00Z");
    const expiry = computeRollingExpiry(1, from);
    expect(expiry.getFullYear()).toBe(2024);
    expect(expiry.getMonth()).toBe(1); // febbraio
    expect(expiry.getDate()).toBe(29);
  });
});
