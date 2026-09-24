import { describe, expect, it } from "vitest";
import { mergeQueues, toApproveBy, sentByMe, outcomeOf } from "./queue";
import type { ApprovalItem } from "./types";

const item = (over: Partial<ApprovalItem>): ApprovalItem => ({
  entityType: "CONTEST", id: "1", code: "IW-X", name: "X", status: "IN_REVIEW", submittedBy: "MARKETING:luca.marketing",
  submittedAt: "2026-09-23T10:00:00Z", requiredRole: "LEGAL", reason: null, summary: null, decidedBy: null,
  decidedAt: null, decision: null, comment: null, ...over,
});

describe("Testbook: Approvazioni Queue", () => {
  it("[TB-WEB-QUEUE-001] mergeQueues: deduplica su entityType:id", () => {
    const all = [item({ code: "A" }), item({ code: "C", entityType: "CAMPAIGN", id: "2" })];
    const merged = mergeQueues([
      { entityType: "CAMPAIGN", items: all, failed: false },
      { entityType: "REWARD", items: all, failed: false },
      { entityType: "CONTEST", items: all, failed: false },
    ]);
    expect(merged).toHaveLength(2);
  });

  it("[TB-WEB-QUEUE-002] toApproveBy: role LEGAL", () => {
    const items = [item({}), item({ code: "L", status: "LIVE" })];
    expect(toApproveBy(items, "LEGAL").map((i) => i.code)).toEqual(["IW-X"]);
    expect(toApproveBy(items, "MARKETING")).toHaveLength(0);
  });

  it("[TB-WEB-QUEUE-003] toApproveBy: role ADMIN", () => {
    const items = [item({})];
    expect(toApproveBy(items, "ADMIN")).toHaveLength(1);
  });

  it("[TB-WEB-QUEUE-004] sentByMe", () => {
    const sorted = sentByMe([
      item({ code: "A", submittedAt: "1" }),
      item({ code: "B", submittedAt: "2" })
    ]);
    expect(sorted[0].code).toBe("B");
  });

  it("[TB-WEB-QUEUE-005] outcomeOf: REJECT", () => {
    expect(outcomeOf(item({ status: "DRAFT", decision: "REJECT", comment: "no" })).label).toBe("Rifiutato");
  });
});
