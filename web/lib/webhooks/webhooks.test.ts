import { describe, expect, it } from "vitest";
import {
  attemptLabel,
  canRetry,
  countLabel,
  describeFactTypes,
  emptyForm,
  formProblems,
  formatSuccessRate,
  groupedFacts,
  hasOpenDeliveries,
  nextAttemptLabel,
  outcomeLabel,
  prettyPayload,
  successRate,
  toRequest,
  toggleFactType,
  verifyCommand,
} from "./webhooks";

const now = new Date("2026-09-24T10:00:00Z");

describe("webhook: registro consegne", () => {
  it("tasso di successo sulle consegne con esito, null se nessuna", () => {
    expect(successRate({ total: 5, ok: 3, failed: 0, gaveUp: 2, pending: 0 })).toBeCloseTo(0.6);
    expect(successRate({ total: 2, ok: 0, failed: 0, gaveUp: 0, pending: 2 })).toBeNull();
    expect(successRate(undefined)).toBeNull();
    expect(formatSuccessRate(0.6)).toBe("60%");
    expect(formatSuccessRate(null)).toBe("—");
  });

  it("si ritentano a mano solo le consegne fallite o abbandonate", () => {
    expect(canRetry("FAILED")).toBe(true);
    expect(canRetry("GAVE_UP")).toBe(true);
    expect(canRetry("OK")).toBe(false);
    expect(canRetry("PENDING")).toBe(false);
  });

  it("tentativi: automatici su 4, poi manuali", () => {
    expect(attemptLabel({ attempt: 0, maxAttempts: 4 })).toBe("non ancora tentata");
    expect(attemptLabel({ attempt: 2, maxAttempts: 4 })).toBe("2 di 4");
    expect(attemptLabel({ attempt: 5, maxAttempts: 4 })).toBe("5 (manuale)");
  });

  it("prossimo tentativo solo per le consegne aperte", () => {
    expect(nextAttemptLabel({ status: "FAILED", nextAttemptAt: "2026-09-24T10:04:30Z" }, now)).toBe("tra 5 min");
    expect(nextAttemptLabel({ status: "PENDING", nextAttemptAt: "2026-09-24T09:59:00Z" }, now)).toBe("adesso");
    expect(nextAttemptLabel({ status: "FAILED", nextAttemptAt: "2026-09-24T12:00:00Z" }, now)).toBe("tra 2 h");
    expect(nextAttemptLabel({ status: "GAVE_UP", nextAttemptAt: null }, now)).toBe("—");
    expect(nextAttemptLabel({ status: "OK", nextAttemptAt: "2026-09-24T10:04:30Z" }, now)).toBe("—");
  });

  it("esito: stato HTTP o motivo dell'errore di rete", () => {
    expect(outcomeLabel({ attempt: 1, httpStatus: 500, error: "HTTP_ERROR" })).toBe("HTTP 500");
    expect(outcomeLabel({ attempt: 4, httpStatus: null, error: "TIMEOUT" })).toBe("nessuna risposta entro 5 s");
    expect(outcomeLabel({ attempt: 0, httpStatus: null, error: null })).toBe("—");
  });

  it("aggiornamento automatico finché ci sono consegne aperte", () => {
    expect(hasOpenDeliveries([{ status: "OK" }, { status: "FAILED" }])).toBe(true);
    expect(hasOpenDeliveries([{ status: "OK" }, { status: "GAVE_UP" }])).toBe(false);
  });

  it("singolare e plurale", () => {
    expect(countLabel(1, "consegna", "consegne")).toBe("1 consegna");
    expect(countLabel(0, "consegna", "consegne")).toBe("0 consegne");
  });

  it("payload indentato e comando di verifica", () => {
    expect(prettyPayload('{"a":1}')).toBe('{\n  "a": 1\n}');
    expect(prettyPayload("non json")).toBe("non json");
    expect(verifyCommand("sha256=abc")).toContain("verify.mjs --secret <segreto> --signature sha256=abc");
  });
});

describe("webhook: editor", () => {
  it("valida nome, URL https e tipi di fatto", () => {
    expect(formProblems(emptyForm())).toEqual({ name: ["obbligatorio"], url: ["obbligatorio"], factTypes: ["scegli almeno un tipo di fatto"] });
    const ok = { code: "", name: "CRM", url: "https://example.org/hook", factTypes: ["tier.upgraded"], enabled: true };
    expect(formProblems(ok)).toEqual({});
    expect(formProblems({ ...ok, url: "http://example.org/hook" }).url).toHaveLength(1);
    expect(formProblems({ ...ok, url: "http://localhost:4000/hook" })).toEqual({});
    expect(formProblems({ ...ok, code: "wh crm" }).code).toHaveLength(1);
    expect(formProblems({ ...ok, factTypes: ["message.delivered"] }).factTypes).toHaveLength(1);
  });

  it("i tipi si alternano e restano ordinati", () => {
    expect(toggleFactType(["wallet.points.earned"], "tier.upgraded")).toEqual(["tier.upgraded", "wallet.points.earned"]);
    expect(toggleFactType(["tier.upgraded", "wallet.points.earned"], "tier.upgraded")).toEqual(["wallet.points.earned"]);
  });

  it("richiesta: codice solo in creazione, versione in modifica", () => {
    const f = { code: "wh-crm", name: " CRM ", url: " https://example.org/hook ", factTypes: ["wallet.points.earned", "tier.upgraded"], enabled: false };
    expect(toRequest(f)).toEqual({ code: "WH-CRM", name: "CRM", url: "https://example.org/hook", factTypes: ["tier.upgraded", "wallet.points.earned"], enabled: false, version: undefined });
    expect(toRequest(f, { version: 3 })).toMatchObject({ code: undefined, version: 3 });
  });

  it("catalogo dei fatti raggruppato, senza message.delivered", () => {
    const groups = groupedFacts();
    expect(groups.map((g) => g.label)).toEqual(["Membri", "Punti", "Livelli", "Premi e coupon", "Gioco", "Programma"]);
    const all = groups.flatMap((g) => g.facts.map((f) => f.type));
    expect(all).toContain("wallet.points.earned");
    expect(all).not.toContain("message.delivered");
    expect(groupedFacts([{ type: "x.y", label: "X", sample: {} }]).map((g) => g.label)).toEqual(["Altro"]);
  });

  it("descrive i tipi sottoscritti in breve", () => {
    expect(describeFactTypes(["wallet.points.earned"])).toBe("Punti guadagnati");
    expect(describeFactTypes(["member.registered", "tier.upgraded", "wallet.points.earned"])).toBe("Iscrizione al programma, Salita di livello e un altro");
    expect(describeFactTypes([])).toBe("nessuno");
  });
});
