import { describe, expect, it } from "vitest";
import { activeHref, visibleNav } from "./nav";

describe("activeHref", () => {
  it("sceglie la voce più specifica", () => {
    expect(activeHref("/backoffice/rewards/bands")).toBe("/backoffice/rewards/bands");
    expect(activeHref("/backoffice/rewards/01ABC")).toBe("/backoffice/rewards");
    expect(activeHref("/backoffice/campaigns/new")).toBe("/backoffice/campaigns");
    // BO-19 vive sotto BO-18: accende solo *Messaggi*; un contenuto accende *Card e pop-up*
    expect(activeHref("/backoffice/content/messages")).toBe("/backoffice/content/messages");
    expect(activeHref("/backoffice/content/01ABC")).toBe("/backoffice/content");
  });
  it("la dashboard è attiva solo sulla radice del backoffice", () => {
    expect(activeHref("/backoffice")).toBe("/backoffice");
    expect(activeHref("/backoffice/members")).toBe("/backoffice/members");
  });
  it("BO-04 Segmenti vive nel gruppo Clienti (M6.6)", () => {
    expect(activeHref("/backoffice/segments/SEG-DIGITAL")).toBe("/backoffice/segments");
    expect(visibleNav().find((g) => g.label === "Clienti")?.items.map((i) => i.id)).toEqual(["BO-02", "BO-04"]);
  });
  it("BO-27 DLQ è in Osservabilità da M7 (M7.3)", () => {
    const observe = (m: number) => visibleNav(m).find((g) => g.label === "Osservabilità")?.items.map((i) => i.id);
    expect(observe(6)).not.toContain("BO-27");
    expect(observe(7)).toContain("BO-27");
    expect(activeHref("/backoffice/observe/dlq", visibleNav(7))).toBe("/backoffice/observe/dlq");
  });
  it("nessuna voce fuori dal backoffice", () => {
    expect(activeHref("/portal")).toBeNull();
  });
});
