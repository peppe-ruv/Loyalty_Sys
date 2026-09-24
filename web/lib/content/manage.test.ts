import { describe, expect, it } from "vitest";
import type { ContentItem } from "./types";
import { audienceLabel, effectiveOrder, scheduleLabel } from "./manage";

const base: ContentItem = {
  id: "1", code: "A", kind: "CARD", placement: "HOME_GRID", title: "A", body: null, imageUrl: null, ctaLabel: null,
  ctaTarget: null, linkType: "NONE", linkCode: null, style: null, audience: { tiers: [], segments: [], statuses: [] },
  startAt: null, endAt: null, priority: 10, frequency: null, dismissible: true, status: "LIVE", version: 0, updatedAt: null,
};
const now = new Date("2026-09-24T10:00:00Z");

describe("gestione contenuti", () => {
  it("ordina per priorità i soli LIVE in calendario del posizionamento", () => {
    const items: ContentItem[] = [
      { ...base, code: "LOW", priority: 10 },
      { ...base, code: "HIGH", priority: 90 },
      { ...base, code: "DRAFT", priority: 99, status: "DRAFT" },
      { ...base, code: "FUTURE", priority: 95, startAt: "2026-11-27T00:00:00Z" },
      { ...base, code: "HERO", placement: "HOME_HERO" },
    ];
    expect(effectiveOrder(items, "HOME_GRID", now).map((c) => c.code)).toEqual(["HIGH", "LOW"]);
  });

  it("descrive calendario e pubblico", () => {
    expect(scheduleLabel({ startAt: null, endAt: null }, now)).toBe("sempre");
    expect(scheduleLabel({ startAt: "2026-11-27T00:00:00Z", endAt: null }, now)).toMatch(/^dal /);
    expect(scheduleLabel({ startAt: null, endAt: "2026-01-01T00:00:00Z" }, now)).toMatch(/^terminato/);
    expect(audienceLabel({ tiers: ["GOLD", "PLATINUM"], segments: [], statuses: [] })).toBe("GOLD, PLATINUM");
    expect(audienceLabel({ tiers: [], segments: [], statuses: [] })).toBe("tutti");
  });
});
