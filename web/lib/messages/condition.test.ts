import { describe, expect, it } from "vitest";
import { conditionProblems, describeCondition, fromBuilder, parseConditionJson, parseValue, toBuilder } from "./condition";

describe("costruttore della condizione", () => {
  it("nessuna condizione ↔ nessuna riga", () => {
    expect(toBuilder(null)).toEqual({ op: "all", rows: [] });
    expect(fromBuilder({ op: "all", rows: [] })).toBeNull();
  });

  it("una foglia resta una foglia, più righe diventano un gruppo", () => {
    const leaf = { field: "data.currency", cmp: "eq", value: "PTS" };
    expect(toBuilder(leaf)).toEqual({ op: "all", rows: [{ field: "data.currency", cmp: "eq", value: "PTS" }] });
    expect(fromBuilder({ op: "all", rows: [{ field: "data.currency", cmp: "eq", value: "PTS" }] })).toEqual(leaf);
    expect(
      fromBuilder({
        op: "any",
        rows: [
          { field: "data.amount", cmp: "gte", value: "100" },
          { field: "data.origin", cmp: "exists", value: "ignorato" },
        ],
      }),
    ).toEqual({ op: "any", rules: [{ field: "data.amount", cmp: "gte", value: 100 }, { field: "data.origin", cmp: "exists" }] });
  });

  it("gruppi annidati o `not` non entrano nel costruttore semplice", () => {
    expect(toBuilder({ op: "not", rules: [{ field: "data.role", cmp: "eq", value: "REFERRER" }] })).toBeNull();
    expect(toBuilder({ op: "all", rules: [{ op: "any", rules: [] }] })).toBeNull();
  });

  it("interpreta i valori come JSON quando possibile", () => {
    expect(parseValue("100")).toBe(100);
    expect(parseValue('["GOLD","PLATINUM"]')).toEqual(["GOLD", "PLATINUM"]);
    expect(parseValue("true")).toBe(true);
    expect(parseValue("PTS")).toBe("PTS");
    expect(parseValue("  ")).toBe("");
  });
});

describe("validazione (come DataCondition.problems)", () => {
  it("accetta condizioni valide e l'assenza di condizione", () => {
    expect(conditionProblems(null)).toEqual([]);
    expect(conditionProblems({})).toEqual([]);
    expect(conditionProblems({ field: "data.currency", cmp: "eq", value: "PTS" })).toEqual([]);
    expect(conditionProblems({ op: "any", rules: [{ field: "data.x", cmp: "exists" }] })).toEqual([]);
  });

  it("segnala campo fuori da data.*, comparatore sconosciuto, value mancante, gruppi malformati", () => {
    expect(conditionProblems({ field: "member.tierCode", cmp: "eq", value: "GOLD" })[0]).toContain("solo data.*");
    expect(conditionProblems({ field: "data.x", cmp: "like", value: 1 })).toContain('comparatore "like" sconosciuto');
    expect(conditionProblems({ field: "data.x", cmp: "eq" })).toContain('la foglia su "data.x" richiede value');
    expect(conditionProblems({ op: "xor", rules: [] })[0]).toContain("operatore di gruppo");
    expect(conditionProblems({ op: "all" })).toContain('il gruppo "all" richiede l\'elenco rules');
    expect(conditionProblems([1])).toEqual(["ogni nodo della condizione è un oggetto"]);
  });

  it("riporta l'errore di sintassi JSON", () => {
    expect(parseConditionJson("").condition).toBeNull();
    expect(parseConditionJson("{").error).toMatch(/JSON non valido/);
    expect(parseConditionJson("[]").error).toMatch(/oggetto JSON/);
    expect(parseConditionJson('{"field":"data.role","cmp":"eq","value":"REFERRER"}').condition).toEqual({
      field: "data.role",
      cmp: "eq",
      value: "REFERRER",
    });
  });
});

describe("describeCondition", () => {
  it("rende la condizione in una frase", () => {
    expect(describeCondition(null)).toBe("sempre");
    expect(describeCondition({ field: "data.currency", cmp: "eq", value: "PTS" })).toBe("data.currency è uguale a PTS");
    expect(
      describeCondition({ op: "any", rules: [{ field: "data.amount", cmp: "gte", value: 100 }, { field: "data.origin", cmp: "exists" }] }),
    ).toBe("data.amount è almeno 100 oppure data.origin è presente");
  });
});
