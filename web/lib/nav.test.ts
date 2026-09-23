import { describe, expect, it } from "vitest";
import { activeHref } from "./nav";

describe("activeHref", () => {
  it("sceglie la voce più specifica", () => {
    expect(activeHref("/backoffice/rewards/bands")).toBe("/backoffice/rewards/bands");
    expect(activeHref("/backoffice/rewards/01ABC")).toBe("/backoffice/rewards");
    expect(activeHref("/backoffice/campaigns/new")).toBe("/backoffice/campaigns");
  });
  it("la dashboard è attiva solo sulla radice del backoffice", () => {
    expect(activeHref("/backoffice")).toBe("/backoffice");
    expect(activeHref("/backoffice/members")).toBe("/backoffice/members");
  });
  it("nessuna voce fuori dal backoffice", () => {
    expect(activeHref("/portal")).toBeNull();
  });
});
