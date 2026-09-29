import { describe, expect, it } from "vitest";
import { demoSourceActor } from "./demoSource";

const ev = (source: unknown) => JSON.stringify({ specversion: "1.0", id: "x", source, type: "purchase.completed" });

describe("demoSourceActor (Q-492): identità di fonte del pannello demo", () => {
  it("evento con URN: SOURCE:src-<codice>", () => {
    expect(demoSourceActor("ingestion", "POST", ["v1", "events"], ev("urn:loyaltyhub:source:ecommerce"))).toBe("SOURCE:src-ecommerce");
  });

  it("evento con codice breve e transazione", () => {
    expect(demoSourceActor("ingestion", "POST", ["v1", "events"], ev("app"))).toBe("SOURCE:src-app");
    expect(demoSourceActor("ingestion", "POST", ["v1", "transactions"], JSON.stringify({ source: "ecommerce" }))).toBe("SOURCE:src-ecommerce");
  });

  it("batch: solo se tutti gli elementi dichiarano la stessa fonte", () => {
    const same = JSON.stringify([{ source: "urn:loyaltyhub:source:crm" }, { source: "crm" }]);
    expect(demoSourceActor("ingestion", "POST", ["v1", "events", "batch"], same)).toBe("SOURCE:src-crm");
    const mixed = JSON.stringify([{ source: "crm" }, { source: "app" }]);
    expect(demoSourceActor("ingestion", "POST", ["v1", "events", "batch"], mixed)).toBeNull();
    expect(demoSourceActor("ingestion", "POST", ["v1", "events", "batch"], "[]")).toBeNull();
  });

  it("altri servizi, metodi, percorsi o corpi non validi: nessuna identità di fonte", () => {
    expect(demoSourceActor("wallet", "POST", ["v1", "events"], ev("crm"))).toBeNull();
    expect(demoSourceActor("ingestion", "GET", ["v1", "events"], ev("crm"))).toBeNull();
    expect(demoSourceActor("ingestion", "POST", ["v1", "sources"], ev("crm"))).toBeNull();
    expect(demoSourceActor("ingestion", "POST", ["v1", "events"], undefined)).toBeNull();
    expect(demoSourceActor("ingestion", "POST", ["v1", "events"], "non json")).toBeNull();
    expect(demoSourceActor("ingestion", "POST", ["v1", "events"], "null")).toBeNull();
  });

  it("codice con caratteri non ammessi (maiuscole, spazi, a capo, URN estraneo): nessuna identità", () => {
    for (const source of ["CRM", "crm evil", "crm\nX-Evil: 1", "urn:other:crm", "", 7, null]) {
      expect(demoSourceActor("ingestion", "POST", ["v1", "events"], ev(source))).toBeNull();
    }
  });
});
