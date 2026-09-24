import { describe, expect, it } from "vitest";
import { myPositionLine, periodKeyLabel, scoreUnit } from "./leaderboards";

describe("classifiche", () => {
  it("periodi in parole", () => {
    expect(periodKeyLabel("2026-09")).toBe("settembre 2026");
    expect(periodKeyLabel("ED-2026")).toBe("Edizione 2026");
    expect(periodKeyLabel("ALL")).toBe("Da sempre");
  });
  it("unità e posizione", () => {
    expect(scoreUnit("STS_EARNED")).toBe("punti status");
    expect(myPositionLine({ rank: 14 })).toBe("Sei 14°");
    expect(myPositionLine(null)).toBe("Non sei ancora in classifica");
  });
});
