import { describe, it, expect } from "vitest";
import { entrancesReady, type DemoStatus } from "./status";

function status(upCodes: string[]): DemoStatus {
  const all = ["ingestion", "member", "campaign", "wallet", "reward", "gamification", "engagement", "insight"];
  return {
    services: all.map((code) => ({
      code, name: code, state: upCodes.includes(code) ? "UP" : "SLEEPING", latencyMs: null,
    })),
    kafka: { state: "UP" }, db: { state: "UP" }, readyCount: upCodes.length, totalCount: 10,
    checkedAt: new Date().toISOString(),
  };
}

describe("api/status", () => {
  it("attiva gli ingressi solo con i 4 servizi core UP", () => {
    expect(entrancesReady(status(["ingestion", "member", "campaign"]))).toBe(false);
    expect(entrancesReady(status(["ingestion", "member", "campaign", "wallet"]))).toBe(true);
  });
});
