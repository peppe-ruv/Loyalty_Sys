import { describe, expect, it } from "vitest";
import { bandProgress, blockReason } from "./portal";
import type { PortalReward } from "@/lib/api/types";

describe("Testbook: Progressi Tier ed Esposizione Portale Premi", () => {
  it("[TB-WEB-TIER-001] bandProgress: balance < threshold", () => {
    const res = bandProgress(50, 100);
    expect(res.missing).toBe(50);
    expect(res.pct).toBe(50);
    expect(res.reached).toBe(false);
  });

  it("[TB-WEB-TIER-002] bandProgress: balance >= threshold", () => {
    const res = bandProgress(150, 100);
    expect(res.missing).toBe(0);
    expect(res.pct).toBe(100);
    expect(res.reached).toBe(true);
  });

  it("[TB-WEB-TIER-003] blockReason: SOLD_OUT", () => {
    const reward: PortalReward = {
      code: "R1", name: "R1", type: "PHYSICAL", category: "Cat", imageUrl: null,
      pointsCost: 100, stockState: "SOLD_OUT", lockedByTier: null, perMemberLimitReached: false
    };
    expect(blockReason(reward, 200)).toBe("Esaurito");
  });

  it("[TB-WEB-TIER-004] blockReason: lockedByTier", () => {
    const reward: PortalReward = {
      code: "R1", name: "R1", type: "PHYSICAL", category: "Cat", imageUrl: null,
      pointsCost: 100, stockState: "AVAILABLE", lockedByTier: { requiredTiers: ["GOLD"] }, perMemberLimitReached: false
    };
    expect(blockReason(reward, 200)).toBe("Riservato a GOLD");
  });

  it("[TB-WEB-TIER-005] blockReason: balance < cost", () => {
    const reward: PortalReward = {
      code: "R1", name: "R1", type: "PHYSICAL", category: "Cat", imageUrl: null,
      pointsCost: 100, stockState: "AVAILABLE", lockedByTier: null, perMemberLimitReached: false
    };
    expect(blockReason(reward, 50)).toContain("Ti mancano 50 punti");
  });
});
