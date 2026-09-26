import { describe, expect, it } from "vitest";
import { describeCampaign, type ConditionNode } from "./describe";
import { sourceCode, sourceUrn, splitAllowedSources, withAllowedSources } from "./sources";

// Q-208: fonti ammesse della campagna = regola context.source alla radice delle condizioni (docs/03 §3.3).
const AMOUNT: ConditionNode = { field: "data.amount", cmp: "gte", value: 50 };
const SRC: ConditionNode = { field: "context.source", cmp: "in", value: ["urn:loyaltyhub:source:ecommerce", "urn:loyaltyhub:source:app"] };

describe("fonti ammesse (Q-208)", () => {
  it("codice ↔ URN", () => {
    expect(sourceUrn("ecommerce")).toBe("urn:loyaltyhub:source:ecommerce");
    expect(sourceUrn("urn:loyaltyhub:source:app")).toBe("urn:loyaltyhub:source:app");
    expect(sourceCode("urn:loyaltyhub:source:app")).toBe("app");
  });

  it("nessuna fonte = tutte: nessuna regola aggiunta", () => {
    expect(withAllowedSources(null, [])).toBeNull();
    expect(withAllowedSources({ op: "all", rules: [AMOUNT] }, [])).toEqual({ op: "all", rules: [AMOUNT] });
  });

  it("fonti scelte → regola context.source in [URN…] in testa al gruppo TUTTE", () => {
    expect(withAllowedSources({ op: "all", rules: [AMOUNT] }, ["ecommerce", "app"])).toEqual({ op: "all", rules: [SRC, AMOUNT] });
    expect(withAllowedSources(null, ["ecommerce", "app"])).toEqual({ op: "all", rules: [SRC] });
  });

  it("radice ALMENO UNA → avvolta in TUTTE, la semantica non cambia", () => {
    const any: ConditionNode = { op: "any", rules: [AMOUNT] };
    expect(withAllowedSources(any, ["ecommerce"])).toEqual({
      op: "all",
      rules: [{ field: "context.source", cmp: "in", value: ["urn:loyaltyhub:source:ecommerce"] }, any],
    });
  });

  it("ri-salvataggio: la regola esistente si sostituisce, niente doppioni", () => {
    const saved = withAllowedSources({ op: "all", rules: [AMOUNT] }, ["ecommerce"]);
    expect(withAllowedSources(saved, ["app"])).toEqual({
      op: "all",
      rules: [{ field: "context.source", cmp: "in", value: ["urn:loyaltyhub:source:app"] }, AMOUNT],
    });
    expect(withAllowedSources(saved, [])).toEqual({ op: "all", rules: [AMOUNT] });
  });

  it("split: estrae le fonti dalla radice TUTTE e restituisce il resto", () => {
    expect(splitAllowedSources({ op: "all", rules: [SRC, AMOUNT] })).toEqual({ sources: ["ecommerce", "app"], rest: { op: "all", rules: [AMOUNT] } });
    expect(splitAllowedSources({ op: "all", rules: [SRC] })).toEqual({ sources: ["ecommerce", "app"], rest: null });
    expect(splitAllowedSources({ field: "context.source", cmp: "eq", value: "urn:loyaltyhub:source:app" })).toEqual({ sources: ["app"], rest: null });
  });

  it("split: una regola sulle fonti dentro ALMENO UNA o NESSUNA resta una condizione", () => {
    const any: ConditionNode = { op: "any", rules: [SRC, AMOUNT] };
    expect(splitAllowedSources(any)).toEqual({ sources: [], rest: any });
    const nested: ConditionNode = { op: "all", rules: [{ op: "not", rules: [SRC] }] };
    expect(splitAllowedSources(nested)).toEqual({ sources: [], rest: nested });
  });

  it("frase generata: fonti lette dalle condizioni, non ripetute fra i «se»", () => {
    const s = describeCampaign({
      triggerActionTypes: ["purchase.completed"],
      conditions: { op: "all", rules: [SRC, AMOUNT] },
      effects: [{ type: "GRANT_POINTS", currency: "PTS", mode: "FIXED", value: 10 }],
    });
    expect(s).toContain("da ecommerce o app");
    expect(s).not.toContain("context.source");
  });
});
