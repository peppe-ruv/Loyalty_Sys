import { describe, expect, it } from "vitest";
import { formatActor, mergeQueues, outcomeOf, requiresApproval, sentByMe, toApproveBy } from "./queue";
import type { ApprovalItem } from "./types";

const item = (over: Partial<ApprovalItem>): ApprovalItem => ({
  entityType: "CONTEST", id: "1", code: "IW-X", name: "X", status: "IN_REVIEW", submittedBy: "MARKETING:luca.marketing",
  submittedAt: "2026-09-23T10:00:00Z", requiredRole: "LEGAL", reason: null, summary: null, decidedBy: null,
  decidedAt: null, decision: null, comment: null, ...over,
});

describe("coda approvazioni", () => {
  it("unisce le fonti dal più vecchio e ignora quelle cadute", () => {
    const merged = mergeQueues([
      { entityType: "CONTEST", items: [item({ code: "B", submittedAt: "2026-09-23T12:00:00Z" })], failed: false },
      { entityType: "REWARD", items: undefined, failed: true },
      { entityType: "CAMPAIGN", items: [item({ code: "A", entityType: "CAMPAIGN", submittedAt: "2026-09-22T12:00:00Z" })], failed: false },
    ]);
    expect(merged.map((i) => i.code)).toEqual(["A", "B"]);
  });

  it("deduplica le code identiche dell'hub consolidato", () => {
    const all = [item({ code: "A" }), item({ code: "C", entityType: "CAMPAIGN", id: "2" })];
    const merged = mergeQueues([
      { entityType: "CAMPAIGN", items: all, failed: false },
      { entityType: "REWARD", items: all, failed: false },
      { entityType: "CONTEST", items: all, failed: false },
    ]);
    expect(merged).toHaveLength(2);
  });

  it("mostra da approvare solo al ruolo della policy o ad ADMIN", () => {
    const items = [item({}), item({ code: "L", status: "LIVE" })];
    expect(toApproveBy(items, "LEGAL").map((i) => i.code)).toEqual(["IW-X"]);
    expect(toApproveBy(items, "ADMIN")).toHaveLength(1);
    expect(toApproveBy(items, "MARKETING")).toHaveLength(0);
  });

  it("riassume l'esito degli oggetti inviati", () => {
    expect(outcomeOf(item({})).tone).toBe("wait");
    expect(outcomeOf(item({ status: "DRAFT", decision: "REJECT", comment: "no" })).label).toBe("Rifiutato");
    expect(outcomeOf(item({ status: "LIVE", decision: "APPROVE" })).label).toBe("Approvato e pubblicato");
    expect(sentByMe([item({ code: "A", submittedAt: "1" }), item({ code: "B", submittedAt: "2" })])[0].code).toBe("B");
  });

  it("sa quando serve l'approvazione", () => {
    const policy = { enabled: true, campaignBudgetThreshold: 100000, rows: [] };
    expect(requiresApproval("CONTEST", policy)).toBe(true);
    expect(requiresApproval("CAMPAIGN", policy, { budgetPoints: 50000 })).toBe(false);
    expect(requiresApproval("CAMPAIGN", policy, { budgetPoints: 500000 })).toBe(true);
    expect(requiresApproval("CAMPAIGN", policy, { requiresLegal: true })).toBe(true);
    expect(requiresApproval("REWARD", { ...policy, enabled: false })).toBe(false);
    expect(requiresApproval("REWARD", undefined)).toBeUndefined();
    expect(formatActor("LEGAL:elena.legal")).toBe("elena.legal (LEGAL)");
  });
});
