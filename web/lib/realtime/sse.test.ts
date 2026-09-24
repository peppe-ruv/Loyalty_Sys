import { describe, it, expect, vi } from "vitest";
import { insightBaseUrl, streamUrl, familyColorVar } from "./sse";

describe("realtime/sse", () => {
  it("recupera l'URL base da env", () => {
    vi.stubEnv("NEXT_PUBLIC_LH_INSIGHT_URL", "http://prod-insight/");
    expect(insightBaseUrl()).toBe("http://prod-insight");
    vi.unstubAllEnvs();
    expect(insightBaseUrl()).toBe("http://localhost:8088");
  });

  it("costruisce l'URL dello stream con i filtri", () => {
    const base = "http://localhost:8088/v1/stream/events";
    expect(streamUrl({})).toBe(base);
    expect(streamUrl({ topics: ["a", "b"] })).toBe(`${base}?topics=a%2Cb`);
    expect(streamUrl({ memberId: "m1", correlationId: "c1" })).toBe(`${base}?memberId=m1&correlationId=c1`);
  });

  it("mappa la famiglia ai colori CSS corretti", () => {
    expect(familyColorVar("ACTION")).toBe("var(--color-topic-actions)");
    expect(familyColorVar("EFFECT")).toBe("var(--color-topic-effects)");
    expect(familyColorVar("FACT")).toBe("var(--color-topic-facts)");
    expect(familyColorVar("AUDIT")).toBe("var(--color-topic-audit)");
    expect(familyColorVar("DLQ")).toBe("var(--color-topic-dlq)");
  });
});
