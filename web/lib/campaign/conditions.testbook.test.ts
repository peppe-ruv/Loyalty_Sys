import { describe, it, expect } from "vitest";
import { fromJson, toJson, fieldWarnings, leafProblem, type ConditionField, type UiGroup, type UiLeaf } from "./conditions";

describe("Testbook: Condition Builder", () => {
  it("[TB-WEB-COND-001] Albero profondità = MAX_DEPTH", () => {
    // MAX_DEPTH = 3 in conditions.ts. Creiamo un albero con profondità 3.
    const deepCondition = {
      op: "all",
      rules: [
        {
          op: "any",
          rules: [
            {
              op: "all",
              rules: [
                { field: "data.amount", cmp: "eq", value: 1 }
              ]
            }
          ]
        }
      ]
    };
    const res = fromJson(deepCondition);
    expect(res.error).toBeNull();
    expect(res.tree).not.toBeNull();
  });

  it("[TB-WEB-COND-002] Albero profondità > MAX_DEPTH", () => {
    // MAX_DEPTH = 3. Creiamo un albero con profondità 4.
    const tooDeepCondition = {
      op: "all",
      rules: [
        {
          op: "any",
          rules: [
            {
              op: "all",
              rules: [
                {
                  op: "any",
                  rules: [
                    { field: "data.amount", cmp: "eq", value: 1 }
                  ]
                }
              ]
            }
          ]
        }
      ]
    };
    const res = fromJson(tooDeepCondition);
    expect(res.error).toContain("Al massimo 3 livelli");
    expect(res.tree).toBeNull();
  });

  it("[TB-WEB-COND-003] toJson: esclude i gruppi vuoti", () => {
    const tree: UiGroup = {
      kind: "group",
      id: "1",
      op: "all",
      rules: [
        { kind: "group", id: "2", op: "any", rules: [] },
        { kind: "leaf", id: "3", field: "data.amount", cmp: "eq", value: 10 }
      ]
    };
    const json = toJson(tree);
    // Deve rimuovere il gruppo 2 vuoto, mantenendo solo la foglia 3 sotto un "all"
    expect(json).toEqual({
      op: "all",
      rules: [{ field: "data.amount", cmp: "eq", value: 10 }]
    });
  });

  it("[TB-WEB-COND-004] fieldWarnings: path data.* non in nessun trigger", () => {
    const tree: UiGroup = {
      kind: "group",
      id: "1",
      op: "all",
      rules: [{ kind: "leaf", id: "leaf-1", field: "data.unknown", cmp: "eq", value: "x" }]
    };
    const warnings = fieldWarnings(tree, {
      triggers: ["action1"],
      fieldsByTrigger: {
        action1: [{ path: "data.amount", type: "number", required: false }]
      },
      catalog: []
    });
    expect(warnings["leaf-1"]).toContain("potrebbe non essere mai vera");
  });

  it("[TB-WEB-COND-005] fieldWarnings: path data.* comune ad alcuni ma non tutti", () => {
    const tree: UiGroup = {
      kind: "group",
      id: "1",
      op: "all",
      rules: [{ kind: "leaf", id: "leaf-1", field: "data.partial", cmp: "eq", value: "x" }]
    };
    const warnings = fieldWarnings(tree, {
      triggers: ["action1", "action2"],
      fieldsByTrigger: {
        action1: [{ path: "data.partial", type: "string", required: false }],
        action2: [{ path: "data.other", type: "string", required: false }]
      },
      catalog: []
    });
    expect(warnings["leaf-1"]).toContain("manca in action2");
  });

  it("[TB-WEB-COND-006] fieldWarnings: path fuori catalogo", () => {
    const tree: UiGroup = {
      kind: "group",
      id: "1",
      op: "all",
      rules: [{ kind: "leaf", id: "leaf-1", field: "member.unknown", cmp: "eq", value: "x" }]
    };
    const warnings = fieldWarnings(tree, {
      triggers: ["action1"],
      fieldsByTrigger: { action1: [] },
      catalog: [{ path: "member.known", type: "string", space: "member", label: "Known" }]
    });
    expect(warnings["leaf-1"]).toContain("fuori catalogo");
  });

  it("[TB-WEB-COND-007] leafProblem: campo vuoto", () => {
    const leaf: UiLeaf = { kind: "leaf", id: "1", field: "  ", cmp: "eq", value: 1 };
    const problem = leafProblem(leaf, { path: "data.x", type: "number", space: "data", label: "X" });
    expect(problem).toBe("Scegli un campo");
  });

  it("[TB-WEB-COND-008] leafProblem: comparatore non idoneo (es. gt per boolean)", () => {
    const leaf: UiLeaf = { kind: "leaf", id: "1", field: "data.active", cmp: "gt", value: true };
    // 'gt' per i booleani non è tra i comparatorsFor("boolean")
    const problem = leafProblem(leaf, { path: "data.active", type: "boolean", space: "data", label: "Active" });
    expect(problem).toBe("Operatore non ammesso per questo campo");
  });
});
