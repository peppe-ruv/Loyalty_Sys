import { describe, it, expect, vi } from "vitest";
import { insightBaseUrl, streamUrl, familyColorVar, liveFeedView } from "./sse";

describe("stati del flusso live (BO-24, docs/07 §6)", () => {
  it("senza righe: scheletro in collegamento, ambra se disconnesso, attesa se collegato", () => {
    expect(liveFeedView("connecting", 0)).toBe("loading");
    expect(liveFeedView("disconnected", 0)).toBe("degraded");
    expect(liveFeedView("live", 0)).toBe("empty");
    expect(liveFeedView("reduced", 0)).toBe("empty");
  });
  it("con righe già arrivate le tiene, qualunque sia lo stato", () => {
    expect(liveFeedView("disconnected", 3)).toBe("rows");
    expect(liveFeedView("connecting", 1)).toBe("rows");
    expect(liveFeedView("live", 12)).toBe("rows");
  });
});

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
