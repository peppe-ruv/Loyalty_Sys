import { describe, expect, it } from "vitest";
import { fromJson, leaves } from "./conditions";
import { conditionUsesAmount, dropAmountExample, effectUsesAmount, hasAmountExample } from "./amount";

// Importo nella bozza di una nuova campagna (BO-06 «2 · Quando», Q-432).

const tree = (rules: unknown[]) => fromJson({ op: "all", rules }).tree!;

describe("importo nella bozza", () => {
  it("riconosce la condizione d'esempio e l'effetto «per importo»", () => {
    const t = tree([{ field: "data.amount", cmp: "gte", value: 1 }]);
    expect(conditionUsesAmount(t)).toBe(true);
    expect(hasAmountExample(t)).toBe(true);
    expect(effectUsesAmount('[{"mode":"PER_AMOUNT","amountField":"data.amount"}]')).toBe(true);
    expect(effectUsesAmount('[{"mode":"FIXED","value":10}]')).toBe(false);
  });

  it("«Togli la condizione d'esempio» toglie solo l'esempio, non le condizioni su Importo dell'operatore", () => {
    const t = tree([
      { field: "data.amount", cmp: "gte", value: 1 },
      { field: "data.amount", cmp: "gte", value: 50 },
      { field: "data.channel", cmp: "eq", value: "APP" },
    ]);
    const after = dropAmountExample(t);
    expect(leaves(after).map((l) => [l.field, l.value])).toEqual([
      ["data.amount", 50],
      ["data.channel", "APP"],
    ]);
    expect(hasAmountExample(after)).toBe(false);
    expect(conditionUsesAmount(after)).toBe(true);
  });

  it("senza esempio non cambia nulla", () => {
    const t = tree([{ field: "data.amount", cmp: "gte", value: 5 }]);
    expect(dropAmountExample(t)).toBe(t);
  });
});
