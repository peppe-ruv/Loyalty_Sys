import { describe, it, expect } from "vitest";
import { describeCampaign, type CampaignDraft } from "./describe";

describe("Testbook: describeCampaign", () => {
  it("[TB-WEB-DESC-001] trigger multipli + label custom", () => {
    const s = describeCampaign({
      triggerActionTypes: ["purchase.completed", "custom.action"],
      actionLabels: { "custom.action": "Azione custom" },
    });
    expect(s).toContain("Quando arriva **Acquisto completato o Azione custom**");
  });

  it("[TB-WEB-DESC-002] audience.tiers = [\"GOLD\"]", () => {
    const s = describeCampaign({
      audience: { tiers: ["GOLD"] },
    });
    expect(s).toContain("se il membro è **GOLD**");
  });

  it("[TB-WEB-DESC-003] audience.segments = [\"SEG-1\", \"SEG-2\"]", () => {
    const s = describeCampaign({
      audience: { segments: ["SEG-1", "SEG-2"] },
    });
    expect(s).toContain("se è nel segmento **SEG-1 o SEG-2**");
  });

  it("[TB-WEB-DESC-004] audience combinata tier + segment", () => {
    const s = describeCampaign({
      audience: { tiers: ["GOLD"], segments: ["SEG-1"] },
    });
    expect(s).toContain("se il membro è **GOLD** e è nel segmento **SEG-1**");
  });

  it("[TB-WEB-DESC-005] condition op: any", () => {
    const s = describeCampaign({
      conditions: {
        op: "any",
        rules: [
          { field: "data.amount", cmp: "eq", value: 1 },
          { field: "data.amount", cmp: "eq", value: 2 },
        ],
      },
    });
    expect(s).toContain("se (**importo = 1** oppure **importo = 2**)");
  });

  it("[TB-WEB-DESC-006] condition op: not", () => {
    const s = describeCampaign({
      conditions: {
        op: "not",
        rules: [
          { field: "data.amount", cmp: "eq", value: 1 },
        ],
      },
    });
    expect(s).toContain("se non (**importo = 1**)");
  });

  it("[TB-WEB-DESC-007] condition operators: eq, gt, exists", () => {
    const draft: CampaignDraft = {
      conditions: {
        op: "all",
        rules: [
          { field: "data.amount", cmp: "eq", value: 10 },
          { field: "data.amount", cmp: "gt", value: 5 },
          { field: "data.note", cmp: "exists" },
        ],
      },
    };
    const s = describeCampaign(draft);
    expect(s).toContain("**importo = 10**");
    expect(s).toContain("**importo > 5**");
    expect(s).toContain("**note presente**");
  });

  it("[TB-WEB-DESC-008] effect: GRANT_POINTS PER_AMOUNT", () => {
    const s = describeCampaign({
      effects: [{ type: "GRANT_POINTS", currency: "PTS", mode: "PER_AMOUNT", value: 1, unitStep: 10 }],
    });
    expect(s).toContain("assegna **1 PTS ogni 10 €**");
  });

  it("[TB-WEB-DESC-009] effect: GRANT_POINTS FIXED", () => {
    const s = describeCampaign({
      effects: [{ type: "GRANT_POINTS", currency: "PTS", mode: "FIXED", value: 10 }],
    });
    expect(s).toContain("assegna **10 PTS**");
  });

  it("[TB-WEB-DESC-010] effect: MULTIPLIER", () => {
    const s = describeCampaign({
      effects: [{ type: "MULTIPLIER", currency: "PTS", factor: 2 }],
    });
    expect(s).toContain("assegna **PTS ×2**");
  });

  it("[TB-WEB-DESC-011] effect: GRANT_PLAYS", () => {
    const s = describeCampaign({
      effects: [{ type: "GRANT_PLAYS", contestCode: "IW-X", count: 1 }],
    });
    expect(s).toContain("assegna **1 giocata su IW-X**");
  });

  it("[TB-WEB-DESC-012] effect: ISSUE_COUPON", () => {
    const s = describeCampaign({
      effects: [{ type: "ISSUE_COUPON" }],
    });
    expect(s).toContain("assegna **un coupon**");
  });

  it("[TB-WEB-DESC-013] effect: AWARD_BADGE", () => {
    const s = describeCampaign({
      effects: [{ type: "AWARD_BADGE" }],
    });
    expect(s).toContain("assegna **un badge**");
  });

  it("[TB-WEB-DESC-014] effect: SEND_MESSAGE", () => {
    const s = describeCampaign({
      effects: [{ type: "SEND_MESSAGE", templateCode: "MSG-1" }],
    });
    expect(s).toContain("assegna **il messaggio MSG-1**");
  });

  it("[TB-WEB-DESC-015] limit: 1 per DAY", () => {
    const s = describeCampaign({
      limits: { perMember: [{ max: 1, period: "DAY" }] },
    });
    expect(s).toContain("al massimo **1 volta/e al giorno**");
  });

  it("[TB-WEB-DESC-016] limit: 2 per EDITION", () => {
    const s = describeCampaign({
      limits: { perMember: [{ max: 2, period: "EDITION" }] },
    });
    expect(s).toContain("al massimo **2 volta/e per edizione**");
  });

  it("[TB-WEB-DESC-017] limit: 1 per ALWAYS", () => {
    const s = describeCampaign({
      limits: { perMember: [{ max: 1, period: "ALWAYS" }] },
    });
    expect(s).toContain("al massimo **1 volta/e in totale**");
  });
});
