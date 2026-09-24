import { describe, expect, it } from "vitest";
import {
  COMPARATORS,
  describeCriteria,
  fieldPath,
  fromCriteria,
  newRow,
  parseCriteriaJson,
  parseFieldPath,
  toCriteria,
  validateRows,
} from "./criteria";
import type { CriteriaNode } from "./types";

describe("criteri dei segmenti (BO-04)", () => {
  it("righe → JSON nel formato delle condizioni (docs/03 §3.3)", () => {
    const c = toCriteria({
      op: "all",
      rows: [
        { field: "member.tier", param: "", cmp: "in", value: "GOLD, PLATINUM" },
        { field: "member.lastActivityDaysAgo", param: "", cmp: "gt", value: "45" },
        { field: "member.labels", param: "", cmp: "ncontains", value: "ebill" },
        { field: "actions", param: "purchase.completed", cmp: "between", value: "2, 5" },
        { field: "member.city", param: "", cmp: "exists", value: "ignorato" },
      ],
    });
    expect(c).toEqual({
      op: "all",
      rules: [
        { field: "member.tier", cmp: "in", value: ["GOLD", "PLATINUM"] },
        { field: "member.lastActivityDaysAgo", cmp: "gt", value: 45 },
        { field: "member.labels", cmp: "ncontains", value: "ebill" },
        { field: "member.actions.purchase.completed.count30d", cmp: "between", value: [2, 5] },
        { field: "member.city", cmp: "exists" },
      ],
    });
  });

  it("JSON → righe e ritorno, anche senza prefisso member. e con una foglia sola", () => {
    const seed: CriteriaNode = {
      op: "all",
      rules: [
        { field: "member.labels", cmp: "contains", value: "ebill" },
        { field: "member.labels", cmp: "contains", value: "directdebit" },
      ],
    };
    const state = fromCriteria(seed);
    expect(state?.rows.map((r) => r.value)).toEqual(["ebill", "directdebit"]);
    expect(toCriteria(state!)).toEqual(seed);
    expect(fromCriteria({ field: "tier", cmp: "eq", value: "GOLD" })).toEqual({
      op: "all",
      rows: [{ field: "member.tier", param: "", cmp: "eq", value: "GOLD" }],
    });
    expect(fromCriteria({ field: "member.attributes.householdSize", cmp: "gte", value: 3 })?.rows[0]).toEqual({
      field: "attributes",
      param: "householdSize",
      cmp: "gte",
      value: "3",
    });
  });

  it("gruppi annidati, not o campi sconosciuti → vista JSON", () => {
    expect(fromCriteria({ op: "not", rules: [{ field: "member.tier", cmp: "eq", value: "GOLD" }] })).toBeNull();
    expect(fromCriteria({ op: "all", rules: [{ op: "any", rules: [] }] })).toBeNull();
    expect(fromCriteria({ field: "data.amount", cmp: "gte", value: 1 })).toBeNull();
    expect(fromCriteria({ field: "member.tier", cmp: "gt", value: 1 })).toBeNull();
    expect(fromCriteria(null)).toEqual({ op: "all", rows: [] });
  });

  it("campi con parametro", () => {
    expect(fieldPath({ field: "actions", param: " app.login.daily " })).toBe("member.actions.app.login.daily.count30d");
    expect(parseFieldPath("member.actions.app.login.daily.count30d")).toEqual({ field: "actions", param: "app.login.daily" });
    expect(parseFieldPath("member.nope")).toBeNull();
  });

  it("validazione delle righe prima del salvataggio", () => {
    const problems = validateRows({
      op: "all",
      rows: [
        { field: "member.tier", param: "", cmp: "eq", value: "ORO" },
        { field: "member.balance.PTS", param: "", cmp: "gte", value: "tanti" },
        { field: "actions", param: "", cmp: "gte", value: "1" },
        { field: "member.age", param: "", cmp: "between", value: "30" },
        { field: "member.city", param: "", cmp: "eq", value: "" },
        { field: "member.city", param: "", cmp: "nexists", value: "" },
      ],
    });
    expect(problems).toEqual({
      0: "Valore non valido: ORO",
      1: "Serve un numero",
      2: "Indica il tipo di azione",
      3: "Servono due numeri: minimo, massimo",
      4: "Valore mancante",
    });
    expect(validateRows({ op: "all", rows: [] })).toEqual({ [-1]: "Aggiungi almeno una condizione" });
  });

  it("una riga nuova usa il primo comparatore ammesso", () => {
    expect(newRow("member.labels").cmp).toBe(COMPARATORS.labels[0]);
    expect(newRow().field).toBe("member.tier");
  });

  it("frase italiana dei criteri", () => {
    expect(
      describeCriteria({
        op: "all",
        rules: [
          { field: "member.status", cmp: "eq", value: "ACTIVE" },
          { field: "member.labels", cmp: "ncontains", value: "ebill" },
        ],
      }),
    ).toBe("stato è ACTIVE e etichette non contiene «ebill»");
    expect(describeCriteria({ op: "any", rules: [{ field: "member.tier", cmp: "in", value: ["GOLD", "PLATINUM"] }, { field: "member.city", cmp: "eq", value: "Torino" }] })).toBe(
      "livello è uno tra GOLD, PLATINUM oppure città è «Torino»",
    );
    expect(describeCriteria({ field: "member.actions.purchase.completed.count30d", cmp: "between", value: [2, 5] })).toBe(
      "azioni «purchase.completed» in 30 giorni tra 2 e 5",
    );
    expect(describeCriteria(null)).toBe("—");
  });

  it("JSON scritto a mano", () => {
    expect(parseCriteriaJson('{"field":"member.tier","cmp":"eq","value":"GOLD"}').error).toBeNull();
    expect(parseCriteriaJson("[1]").error).toMatch(/oggetto/);
    expect(parseCriteriaJson("{").error).toMatch(/JSON non valido/);
    expect(parseCriteriaJson("  ").error).toBe("Criteri vuoti");
  });
});
