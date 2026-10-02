import { describe, expect, it } from "vitest";
import { PURCHASE_TRACE } from "@/test/fixtures/traces";
import { layoutLane, LANE_BASE_PX, LANE_ROW_PX, MIN_GAP_PCT } from "./waterfallLayout";

describe("layoutLane (BO-25)", () => {
  it("con una sola riga la corsia resta di 28 px", () => {
    const l = layoutLane([{ id: "a", pct: 0 }, { id: "b", pct: 50 }]);
    expect(l.rows).toBe(1);
    expect(l.heightPx).toBe(LANE_BASE_PX);
  });

  it("nella corsia hub del giro d'acquisto nessuna coppia sulla stessa riga è più vicina del 4%", () => {
    const axisMs = 7000;
    const hub = PURCHASE_TRACE.nodes.filter((n) => n.service === "hub").map((n) => ({ id: n.eventId, pct: (n.offsetMs / axisMs) * 100 }));
    const l = layoutLane(hub);
    expect(l.slots).toHaveLength(hub.length);
    expect(l.rows).toBeGreaterThan(1);
    expect(l.heightPx).toBe(LANE_BASE_PX + LANE_ROW_PX * (l.rows - 1));
    for (const a of l.slots) {
      for (const b of l.slots) {
        if (a.id !== b.id && a.row === b.row) expect(Math.abs(a.pct - b.pct)).toBeGreaterThanOrEqual(MIN_GAP_PCT);
      }
    }
  });
});
