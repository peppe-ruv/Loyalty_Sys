import { describe, it, expect } from "vitest";
import { describeCampaign } from "./describe";

describe("describeCampaign", () => {
  it("descrive una campagna acquisti con condizione ed effetti", () => {
    const s = describeCampaign({
      triggerActionTypes: ["purchase.completed"],
      audience: { all: true },
      conditions: { op: "all", rules: [{ field: "data.amount", cmp: "gte", value: 1 }] },
      effects: [
        { type: "GRANT_POINTS", currency: "PTS", mode: "PER_AMOUNT", value: 1, unitStep: 1 },
        { type: "GRANT_POINTS", currency: "STS", mode: "PER_AMOUNT", value: 1, unitStep: 1 },
      ],
      limits: { perMember: [{ max: 3, period: "DAY" }] },
    });
    expect(s).toContain("Acquisto completato");
    expect(s).toContain("importo ≥ 1");
    expect(s).toContain("1 PTS ogni 1 €");
    expect(s).toContain("3 volte al giorno");
    expect(s.endsWith(".")).toBe(true);
  });

  it("include il pubblico per tier e i moltiplicatori", () => {
    const s = describeCampaign({
      triggerActionTypes: ["purchase.completed"],
      audience: { all: false, tiers: ["GOLD", "PLATINUM"] },
      conditions: { op: "all", rules: [{ field: "data.amount", cmp: "gte", value: 50 }] },
      effects: [{ type: "GRANT_PLAYS", contestCode: "IW-AUTUNNO", count: 1 }],
    });
    expect(s).toContain("GOLD o PLATINUM");
    expect(s).toContain("1 giocata a IW-AUTUNNO");
  });

  it("non lancia con una bozza vuota", () => {
    expect(() => describeCampaign({})).not.toThrow();
    expect(describeCampaign({})).toContain("nessun effetto");
  });

  it("rende i gruppi 'almeno una' come oppure", () => {
    const s = describeCampaign({
      triggerActionTypes: ["review.submitted"],
      conditions: {
        op: "any",
        rules: [
          { field: "data.rating", cmp: "gte", value: 4 },
          { field: "data.rating", cmp: "eq", value: 1 },
        ],
      },
      effects: [{ type: "GRANT_POINTS", currency: "PTS", mode: "FIXED", value: 30 }],
    });
    expect(s).toContain("oppure");
  });

  it("nomina il template dell'effetto SEND_MESSAGE (M6.4)", () => {
    const s = describeCampaign({
      triggerActionTypes: ["member.birthday"],
      audience: { all: true },
      conditions: { op: "all", rules: [] },
      effects: [
        { type: "GRANT_POINTS", currency: "PTS", mode: "FIXED", value: 250 },
        { type: "SEND_MESSAGE", templateCode: "MSG-BIRTHDAY" },
      ],
    });
    expect(s).toContain("250 PTS");
    expect(s).toContain("il messaggio MSG-BIRTHDAY");
  });
});
