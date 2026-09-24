import { describe, expect, it } from "vitest";
import { contentHref, safeTarget } from "./links";

describe("contentHref", () => {
  it("risolve i collegamenti tipizzati nei percorsi del portale", () => {
    expect(contentHref({ linkType: "CONTEST", linkCode: "IW-AUTUNNO", ctaTarget: null })).toBe("/portal/play/IW-AUTUNNO");
    expect(contentHref({ linkType: "CAMPAIGN", linkCode: "CMP-EBILL", ctaTarget: null })).toBe("/portal/earn#CMP-EBILL");
    expect(contentHref({ linkType: "REWARD", linkCode: "RWD-COFFEE-5", ctaTarget: null })).toBe("/portal/rewards/RWD-COFFEE-5");
    expect(contentHref({ linkType: "PRIZE", linkCode: "PTS-50", ctaTarget: "/portal" })).toBeNull();
  });

  it("accetta solo pagine del portale e URL https", () => {
    expect(contentHref({ linkType: "NONE", linkCode: null, ctaTarget: "/portal/invite" })).toBe("/portal/invite");
    expect(safeTarget("https://example.org/regolamento")).toBe("https://example.org/regolamento");
    expect(safeTarget("javascript:alert(1)")).toBeNull();
    expect(safeTarget("http://example.org")).toBeNull();
    expect(safeTarget("/backoffice")).toBeNull();
  });
});
