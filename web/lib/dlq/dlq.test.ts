import { describe, expect, it } from "vitest";
import {
  canConfirmDiscard,
  consumerService,
  discardBlock,
  dlqCode,
  dlqQuery,
  explainError,
  prettyPayload,
  reprocessBlock,
  shortStack,
  statusLabel,
  statusTabOf,
} from "./dlq";

describe("filtri e stati", () => {
  it("la scheda di default è quella delle voci nuove", () => {
    expect(statusTabOf(null)).toBe("OPEN");
    expect(statusTabOf("DISCARDED")).toBe("DISCARDED");
    expect(statusTabOf("boh")).toBe("OPEN");
  });
  it("costruisce la query senza i filtri vuoti", () => {
    expect(dlqQuery({ status: "ALL", consumer: " ", errorCode: "" })).toEqual({
      status: undefined,
      consumer: undefined,
      errorCode: undefined,
      page: 0,
      size: 25,
    });
    expect(dlqQuery({ status: "OPEN", consumer: "lh-campaign", errorCode: "loop_guard" }, 2, 10)).toEqual({
      status: "OPEN",
      consumer: "lh-campaign",
      errorCode: "LOOP_GUARD",
      page: 2,
      size: 10,
    });
  });
  it("etichette in italiano (OPEN mostrato come «Nuova»)", () => {
    expect(statusLabel("OPEN")).toBe("Nuova");
    expect(statusLabel("REPROCESSED")).toBe("Riprocessata");
    expect(statusLabel("ALTRO")).toBe("ALTRO");
    expect(consumerService("lh-campaign")).toBe("campaign");
    expect(consumerService(null)).toBe("—");
  });
});

describe("riprocessa e scarta", () => {
  it("riprocessa solo un'azione aperta e solo da ADMIN", () => {
    expect(reprocessBlock({ status: "OPEN", family: "ACTION" }, true)).toBeNull();
    expect(reprocessBlock({ status: "OPEN", family: "ACTION" }, false)).toBe("READ_ONLY_ROLE");
    expect(reprocessBlock({ status: "OPEN", family: "FACT" }, true)).toBe("NOT_ACTION");
    expect(reprocessBlock({ status: "OPEN", family: "EFFECT" }, true)).toBe("NOT_ACTION");
    expect(reprocessBlock({ status: "DISCARDED", family: "ACTION" }, true)).toBe("CLOSED");
  });
  it("scarta qualunque famiglia purché aperta", () => {
    expect(discardBlock({ status: "OPEN" }, true)).toBeNull();
    expect(discardBlock({ status: "REPROCESSED" }, true)).toBe("CLOSED");
    expect(discardBlock({ status: "OPEN" }, false)).toBe("READ_ONLY_ROLE");
  });
  it("lo scarto si conferma con nota e codice della voce", () => {
    const id = "01K5ZZZZZZZZZZZZZZZZABC123";
    expect(dlqCode(id)).toBe("DLQ-ABC123");
    expect(canConfirmDiscard("messaggio di prova", "dlq-abc123", id)).toBe(true);
    expect(canConfirmDiscard("", "DLQ-ABC123", id)).toBe(false);
    expect(canConfirmDiscard("nota", "DLQ-ABC124", id)).toBe(false);
  });
});

describe("spiegazioni e dettaglio", () => {
  it("LOOP_GUARD ha una spiegazione dedicata", () => {
    const e = explainError("LOOP_GUARD");
    expect(e?.title).toContain("LOOP_GUARD");
    expect(e?.body).toContain("lhhop > 3");
    expect(explainError("IllegalStateException")).toBeNull();
    expect(explainError(null)).toBeNull();
  });
  it("abbrevia lo stack", () => {
    const stack = ["java.lang.IllegalStateException: boom", ...Array.from({ length: 12 }, (_, i) => `\tat a.B.m${i}(B.java:${i})`)].join("\n");
    const short = shortStack(stack, 4);
    expect(short.split("\n")).toHaveLength(5);
    expect(short).toContain("altre 9 righe");
    expect(shortStack("una riga")).toBe("una riga");
    expect(shortStack(null)).toBe("");
  });
  it("mostra il payload come JSON indentato", () => {
    expect(prettyPayload({ id: "E1" })).toBe('{\n  "id": "E1"\n}');
    expect(prettyPayload(null)).toBe("—");
  });
});
