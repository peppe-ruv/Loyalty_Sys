import { describe, expect, it } from "vitest";
import type { ContentItem } from "./types";
import { audienceLabel, effectiveOrder, liveSafeBody, scheduleLabel } from "./manage";

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

  it("su un LIVE rimanda i campi non sicuri come sono e cambia solo quelli sicuri (docs/03 §3.6)", () => {
    const live: ContentItem = {
      ...base, placement: "HOME_HERO", ctaLabel: "Scopri", ctaTarget: "/portal/earn", startAt: "2026-09-01T08:00:30Z",
      audience: { tiers: ["GOLD"], segments: [], statuses: [] }, style: { tone: "COIN" },
    };
    const body = {
      version: 3, title: "Nuovo", body: "Testo", imageUrl: "/demo/x.webp", priority: 90, endAt: "2026-12-31T23:00:00.000Z",
      placement: "HOME_GRID", ctaLabel: "Altro", ctaTarget: null, linkType: "NONE", linkCode: null,
      audience: { tiers: [], segments: [], statuses: [] }, startAt: "2026-09-01T08:00:00.000Z", frequency: null,
      dismissible: true, style: { tone: "PRIMARY" },
    };
    const out = liveSafeBody(body, live);
    expect(out).toMatchObject({ version: 3, title: "Nuovo", body: "Testo", imageUrl: "/demo/x.webp", priority: 90,
      endAt: "2026-12-31T23:00:00.000Z" });
    expect(out).toMatchObject({ placement: "HOME_HERO", ctaLabel: "Scopri", ctaTarget: "/portal/earn",
      startAt: "2026-09-01T08:00:30Z", audience: live.audience, style: { tone: "COIN" } });
  });
});
