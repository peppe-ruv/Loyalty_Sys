import { describe, expect, it } from "vitest";
import { activeHref } from "./nav";

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
  it("nessuna voce fuori dal backoffice", () => {
    expect(activeHref("/portal")).toBeNull();
  });
});
