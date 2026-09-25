import { expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { buildCatalog, type UiGroup } from "@/lib/campaign/conditions";
import { ConditionBuilder, type ConditionCatalog } from "./ConditionBuilder";

// Testbook TB-WEB §COND (avviso NESSUNA): Q-90 — etichetta NESSUNA della spec mantenuta, con avviso quando il gruppo ha
// più di una regola (il motore lo valuta come «non tutte vere insieme»).

const catalog: ConditionCatalog = {
  catalog: buildCatalog({ dataFields: [{ path: "data.amount", type: "number" }] }),
  fieldsByTrigger: { "purchase.completed": [{ path: "data.amount", type: "number" }] },
  sources: { data: "ok", tiers: "ok", segments: "ok", attributes: "ok" },
  retry: () => {},
};

const tree = (n: number): UiGroup => ({
  kind: "group",
  id: "root",
  op: "all",
  rules: [
    {
      kind: "group",
      id: "none",
      op: "not",
      rules: Array.from({ length: n }, (_, i) => ({ kind: "leaf" as const, id: `l${i}`, field: "member.tier", cmp: "eq" as const, value: "BASE" })),
    },
  ],
});

const warning = /Il motore valuta NESSUNA come «non tutte vere insieme»/;

it("[TB-WEB-COND-051] gruppo NESSUNA con due righe → avviso sulla semantica del motore (Q-90)", () => {
  render(<ConditionBuilder tree={tree(2)} onChange={vi.fn()} catalog={catalog} triggers={["purchase.completed"]} />);
  expect(screen.getByText(warning)).toBeInTheDocument();
});

it("[TB-WEB-COND-052] gruppo NESSUNA con una riga → nessun avviso", () => {
  render(<ConditionBuilder tree={tree(1)} onChange={vi.fn()} catalog={catalog} triggers={["purchase.completed"]} />);
  expect(screen.queryByText(warning)).toBeNull();
});
