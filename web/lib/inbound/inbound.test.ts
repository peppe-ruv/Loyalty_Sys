import { describe, expect, it } from "vitest";
import {
  actionErrorMessage,
  matchBlock,
  memberSearchHint,
  outcomeCount,
  outcomeOf,
  prettyEvent,
  resolutionLabel,
  resolutionOutcome,
  retryBlock,
  schemaErrors,
} from "./inbound";

describe("regole di Riprova e Abbina (F-ING-04, F-ING-09)", () => {
  it("riprova solo respinti e non abbinati, con inbound.handle", () => {
    expect(retryBlock({ status: "REJECTED" }, true)).toBeNull();
    expect(retryBlock({ status: "UNMATCHED" }, true)).toBeNull();
    expect(retryBlock({ status: "ACCEPTED" }, true)).toBe("NOT_RETRYABLE");
    expect(retryBlock({ status: "DUPLICATE" }, true)).toBe("NOT_RETRYABLE");
    expect(retryBlock({ status: "REJECTED" }, false)).toBe("READ_ONLY_ROLE");
  });
  it("abbina solo i non abbinati", () => {
    expect(matchBlock({ status: "UNMATCHED" }, true)).toBeNull();
    expect(matchBlock({ status: "REJECTED" }, true)).toBe("NOT_UNMATCHED");
    expect(matchBlock({ status: "UNMATCHED" }, false)).toBe("READ_ONLY_ROLE");
  });
  it("la scheda d'esito sconosciuta torna a Tutti", () => {
    expect(outcomeOf("UNMATCHED")).toBe("UNMATCHED");
    expect(outcomeOf("boh")).toBe("");
    expect(outcomeOf(null)).toBe("");
  });
});

describe("errori di schema campo per campo", () => {
  it("separa gli errori e ne ricava il campo", () => {
    expect(
      schemaErrors("INVALID_DATA", "$.amount: integer found, number expected; $: required property 'currency' not found"),
    ).toEqual([
      { field: "data.amount", message: "integer found, number expected" },
      { field: "data.currency", message: "required property 'currency' not found" },
    ]);
  });
  it("gestisce i percorsi annidati e le righe non riconosciute", () => {
    expect(schemaErrors("INVALID_DATA", "$.items[0]: required property 'sku' not found")).toEqual([
      { field: "data.items[0].sku", message: "required property 'sku' not found" },
    ]);
    expect(schemaErrors("INVALID_DATA", "data non è JSON valido: boh")).toEqual([
      { field: "data", message: "data non è JSON valido: boh" },
    ]);
  });
  it("vale solo per INVALID_DATA", () => {
    expect(schemaErrors("SOURCE_DISABLED", "Fonte sconosciuta o disabilitata: x")).toEqual([]);
    expect(schemaErrors("INVALID_DATA", null)).toEqual([]);
  });
});

describe("esito e testi", () => {
  it("descrive l'esito di un'azione", () => {
    expect(resolutionOutcome({ status: "ACCEPTED", rejectCode: null, memberId: "MBR-000003" })).toEqual({
      tone: "success",
      text: "Accettato: l'azione è stata pubblicata per MBR-000003. Segui il tracciato.",
    });
    expect(resolutionOutcome({ status: "UNMATCHED", rejectCode: null, memberId: null }).tone).toBe("warning");
    expect(resolutionOutcome({ status: "DUPLICATE", rejectCode: null, memberId: null }).tone).toBe("warning");
    expect(resolutionOutcome({ status: "REJECTED", rejectCode: "SOURCE_DISABLED", memberId: null }).text).toContain("SOURCE_DISABLED");
  });
  it("etichetta la risoluzione", () => {
    expect(resolutionLabel("MANUAL_MATCH")).toBe("Abbinato a mano");
    expect(resolutionLabel(null)).toBeNull();
    expect(resolutionLabel("ALTRO")).toBe("ALTRO");
  });
  it("suggerisce la ricerca del membro dal subject", () => {
    expect(memberSearchHint("email:anna@clubaurora.example")).toBe("anna@clubaurora.example");
    expect(memberSearchHint("external:CRM-103")).toBe("CRM-103");
    expect(memberSearchHint("MBR-000002")).toBe("MBR-000002");
    expect(memberSearchHint(null)).toBe("");
  });
  it("traduce gli errori delle azioni", () => {
    expect(actionErrorMessage({ code: "X", detail: "", asleep: true })).toContain("non risponde");
    expect(actionErrorMessage({ code: "MEMBER_NOT_FOUND", detail: "Membro MBR-1 non trovato.", asleep: false })).toBe(
      "Membro MBR-1 non trovato.",
    );
    expect(actionErrorMessage({ code: "INBOUND_NOT_UNMATCHED", detail: "Già risolto.", asleep: false })).toContain("Ricarico");
    expect(actionErrorMessage({ code: "FORBIDDEN_ROLE", detail: "", asleep: false })).toContain("ADMIN o CARE");
  });
  it("indenta il CloudEvent", () => {
    expect(prettyEvent({ a: 1 })).toBe('{\n  "a": 1\n}');
    expect(prettyEvent(null)).toBe("—");
  });
});

describe("conteggi per esito (BO-26, F-ING-09)", () => {
  const counts = { ACCEPTED: 31, DUPLICATE: 2, REJECTED: 5, UNMATCHED: 3 };
  it("ogni scheda mostra il proprio conteggio e Tutti la somma", () => {
    expect(outcomeCount("ACCEPTED", counts)).toBe(31);
    expect(outcomeCount("DUPLICATE", counts)).toBe(2);
    expect(outcomeCount("UNMATCHED", counts)).toBe(3);
    expect(outcomeCount("", counts)).toBe(41);
  });
  it("un esito assente conta zero; senza conteggi nessun numero", () => {
    expect(outcomeCount("REJECTED", { ACCEPTED: 1 })).toBe(0);
    expect(outcomeCount("", { ACCEPTED: 1 })).toBe(1);
    expect(outcomeCount("ACCEPTED", undefined)).toBeNull();
    expect(outcomeCount("", null)).toBeNull();
  });
});
