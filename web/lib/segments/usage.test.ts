import { describe, expect, it } from "vitest";
import { parseMemberIds, refreshMessage, segmentUsage, usageCount, usageLabel } from "./usage";

describe("usato da (BO-04)", () => {
  const sources = {
    campaigns: [
      { code: "CMP-REVIEW", audience: { segments: ["SEG-AT-RISK"] } },
      { code: "CMP-PURCHASE-BASE", audience: { segments: [] } },
      { code: "CMP-NULL", audience: null },
    ],
    rewards: [
      { code: "RWD-EBIKE-RENT", eligibleSegments: ["SEG-TORINO"] },
      { code: "RWD-COFFEE-5", eligibleSegments: [] },
    ],
    contents: [
      { code: "CNT-EBILL", audience: { segments: ["SEG-NOT-EBILL"] } },
      { code: "POP-COMEBACK", audience: { segments: ["SEG-AT-RISK"] } },
    ],
  };

  it("trova campagne, premi e contenuti che citano il codice", () => {
    const u = segmentUsage("SEG-AT-RISK", sources);
    expect(u).toEqual({ campaigns: ["CMP-REVIEW"], rewards: [], contents: ["POP-COMEBACK"] });
    expect(usageCount(u)).toBe(2);
    expect(usageLabel(u)).toBe("1 campagna · 1 contenuto");
    expect(usageLabel(segmentUsage("SEG-VIP-EVENT", sources))).toBe("nessuno");
    expect(usageLabel({ campaigns: ["a", "b"], rewards: ["c", "d"], contents: [] })).toBe("2 campagne · 2 premi");
  });

  it("fonti assenti (servizio che dorme) = nessun uso da quella fonte", () => {
    expect(segmentUsage("SEG-TORINO", { rewards: sources.rewards })).toEqual({ campaigns: [], rewards: ["RWD-EBIKE-RENT"], contents: [] });
  });

  it("esito del ricalcolo", () => {
    expect(refreshMessage({ code: "SEG-DIGITAL", entered: 1, left: 0, total: 4 })).toBe("SEG-DIGITAL: 1 entrato, 0 usciti · 4 membri");
    expect(refreshMessage({ code: "SEG-AT-RISK", entered: 0, left: 0, total: 1 })).toBe("SEG-AT-RISK: nessuna variazione · 1 membro");
  });

  it("elenco manuale dei membri", () => {
    expect(parseMemberIds("MBR-000005, mbr-000004\n11 5 MBR-000005 pippo")).toEqual({
      ids: ["MBR-000005", "MBR-000004", "MBR-000011"],
      invalid: ["pippo"],
    });
    expect(parseMemberIds("")).toEqual({ ids: [], invalid: [] });
  });
});
