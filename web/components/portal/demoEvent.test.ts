import { describe, expect, it } from "vitest";
import { buildDemoEvent } from "./demoEvent";

describe("buildDemoEvent", () => {
  it("invia la fonte come URN (Q-258)", () => {
    const e = buildDemoEvent("MBR-1", "purchase.completed", "ecommerce", { amount: 10 }, "01X", new Date("2026-01-01T00:00:00Z"));
    expect(e.source).toBe("urn:loyaltyhub:source:ecommerce");
    expect(e.subject).toBe("member:MBR-1");
    expect(e.time).toBe("2026-01-01T00:00:00.000Z");
  });

  it("non raddoppia il prefisso se la fonte è già un URN", () => {
    expect(buildDemoEvent("MBR-1", "t", "urn:loyaltyhub:source:app", {}, "01X").source).toBe("urn:loyaltyhub:source:app");
  });
});
