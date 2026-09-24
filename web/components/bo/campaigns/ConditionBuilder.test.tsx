import { useState } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fromJson, toJson, type UiGroup } from "@/lib/campaign/conditions";
import { ConditionBuilder, useConditionCatalog } from "./ConditionBuilder";
import { TriggerPicker } from "./TriggerPicker";

// Test dei componenti di BO-06 con le API simulate (fetch finto sul proxy /api/lh/**).

const EVENT_TYPES = [
  { code: "purchase.completed", name: "Acquisto completato", origin: "SYSTEM", category: "TRANSACTION", icon: "shopping-cart", enabled: true, dataSchema: {}, sampleData: null },
  { code: "review.submitted", name: "Recensione inviata", origin: "SYSTEM", category: "ENGAGEMENT", icon: "star", enabled: true, dataSchema: {}, sampleData: null },
  { code: "store.visit", name: "Visita in negozio", origin: "CUSTOM", category: "CUSTOM", icon: null, enabled: true, dataSchema: {}, sampleData: null },
  { code: "old.type", name: "Tipo spento", origin: "SYSTEM", category: "SERVICE", icon: null, enabled: false, dataSchema: null, sampleData: null },
];

const FIELDS: Record<string, unknown> = {
  "purchase.completed": [
    { path: "data.amount", type: "number", required: true },
    { path: "data.channel", type: "string", required: false, enum: ["ONLINE", "STORE", "APP"] },
  ],
  "store.visit": [
    { path: "data.storeId", type: "string", required: true },
    { path: "data.amount", type: "number", required: false },
  ],
};

let asleep = false;

function mockFetch(url: string): Response {
  const path = url.replace(/^\/api\/lh\//, "").split("?")[0];
  const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
  if (path.startsWith("ingestion/") && asleep) return json({ type: "SERVICE_ASLEEP", detail: "zzz" }, 503);
  if (path === "ingestion/v1/event-types") return json(EVENT_TYPES);
  const m = /^ingestion\/v1\/event-types\/([^/]+)\/fields$/.exec(path);
  if (m) {
    const f = FIELDS[decodeURIComponent(m[1])];
    return f ? json(f) : json({ code: "NOT_FOUND" }, 404);
  }
  if (path === "wallet/v1/tiers") return json([{ code: "GOLD", rank: 3 }, { code: "BASE", rank: 1 }]);
  if (path === "member/v1/segments") return json({ items: [{ code: "SEG-BIG", name: "Grandi spese" }], page: { number: 0, size: 100, totalItems: 1, totalPages: 1 } });
  if (path === "member/v1/attribute-definitions") return json([{ key: "householdSize", label: "Componenti del nucleo", type: "NUMBER", options: null }]);
  return json({ code: "NOT_FOUND" }, 404);
}

beforeEach(() => {
  asleep = false;
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => mockFetch(String(input))));
});
afterEach(() => vi.unstubAllGlobals());

function wrap(ui: React.ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>);
}

function PickerHarness({ onChange }: { onChange: (v: string[]) => void }) {
  const [value, setValue] = useState<string[]>(["purchase.completed"]);
  return (
    <TriggerPicker
      value={value}
      onChange={(v) => {
        setValue(v);
        onChange(v);
      }}
    />
  );
}

describe("TriggerPicker", () => {
  it("mostra i tipi abilitati per categoria, con la pill custom, e li seleziona", async () => {
    const onChange = vi.fn();
    wrap(<PickerHarness onChange={onChange} />);
    const custom = await screen.findByRole("checkbox", { name: /Visita in negozio/ });
    expect(within(custom).getByText("custom")).toBeInTheDocument();
    expect(screen.queryByRole("checkbox", { name: /Tipo spento/ })).toBeNull();
    expect(screen.getByRole("group", { name: "Personalizzati" })).toBeInTheDocument();
    expect(screen.getByRole("checkbox", { name: /Acquisto completato/ })).toHaveAttribute("aria-checked", "true");
    fireEvent.click(custom);
    expect(onChange).toHaveBeenLastCalledWith(["purchase.completed", "store.visit"]);
    fireEvent.change(screen.getByPlaceholderText(/Cerca/), { target: { value: "recens" } });
    expect(screen.queryByRole("checkbox", { name: /Acquisto completato/ })).toBeNull();
    expect(screen.getByRole("checkbox", { name: /Recensione inviata/ })).toBeInTheDocument();
  });

  it("degraded: con ingestion addormentato torna al campo di testo", async () => {
    asleep = true;
    const onChange = vi.fn();
    wrap(<PickerHarness onChange={onChange} />);
    const input = await screen.findByLabelText(/codici separati da virgola/);
    expect(screen.getByText(/non raggiungibile/)).toBeInTheDocument();
    expect(input).toHaveValue("purchase.completed");
    fireEvent.change(input, { target: { value: "purchase.completed, store.visit" } });
    expect(onChange).toHaveBeenLastCalledWith(["purchase.completed", "store.visit"]);
  });
});

function BuilderHarness({ triggers, initial, onTree }: { triggers: string[]; initial: unknown; onTree?: (t: UiGroup) => void }) {
  const [tree, setTree] = useState<UiGroup>(() => fromJson(initial).tree!);
  const catalog = useConditionCatalog(triggers);
  return (
    <ConditionBuilder
      tree={tree}
      onChange={(t) => {
        setTree(t);
        onTree?.(t);
      }}
      catalog={catalog}
      triggers={triggers}
    />
  );
}

describe("ConditionBuilder", () => {
  it("modifica il valore mantenendo i numeri come numeri", async () => {
    const onTree = vi.fn();
    wrap(<BuilderHarness triggers={["purchase.completed"]} initial={{ op: "all", rules: [{ field: "data.amount", cmp: "gte", value: 1 }] }} onTree={onTree} />);
    const value = await screen.findByLabelText("Valore della condizione 1");
    fireEvent.change(value, { target: { value: "50" } });
    expect(toJson(onTree.mock.lastCall![0])).toEqual({ op: "all", rules: [{ field: "data.amount", cmp: "gte", value: 50 }] });
    fireEvent.change(screen.getByLabelText("Operatore della condizione 1"), { target: { value: "between" } });
    expect(toJson(onTree.mock.lastCall![0])).toEqual({ op: "all", rules: [{ field: "data.amount", cmp: "between", value: [50, null] }] });
    expect(screen.getByText(/Servono due valori/)).toBeInTheDocument();
  });

  it("combobox raggruppata per spazio con attributi custom e percorso libero", async () => {
    const onTree = vi.fn();
    wrap(<BuilderHarness triggers={["purchase.completed"]} initial={{ op: "all", rules: [{ field: "data.amount", cmp: "gte", value: 1 }] }} onTree={onTree} />);
    fireEvent.click(await screen.findByRole("button", { name: /Campo della condizione 1/ }));
    const listbox = await screen.findByRole("listbox");
    await waitFor(() => expect(within(listbox).getByRole("group", { name: "Dati dell'azione" })).toBeInTheDocument());
    expect(within(listbox).getByRole("group", { name: "Membro" })).toBeInTheDocument();
    await waitFor(() => expect(within(listbox).getByText("member.attributes.householdSize")).toBeInTheDocument());
    fireEvent.change(screen.getByRole("combobox", { name: "Campo della condizione 1" }), { target: { value: "data.promo.code" } });
    fireEvent.click(screen.getByRole("option", { name: /Usa il percorso/ }));
    expect(toJson(onTree.mock.lastCall![0])).toEqual({ op: "all", rules: [{ field: "data.promo.code", cmp: "eq", value: "" }] });
  });

  it("con più trigger avvisa sui campi non comuni senza toglierli", async () => {
    wrap(
      <BuilderHarness
        triggers={["purchase.completed", "store.visit"]}
        initial={{ op: "all", rules: [{ field: "data.channel", cmp: "eq", value: "APP" }, { field: "data.amount", cmp: "gte", value: 5 }] }}
      />,
    );
    expect(await screen.findByText(/manca in store.visit/)).toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: /Campo della condizione/ })).toHaveLength(2);
  });

  it("aggiunge gruppi fino a 3 livelli", async () => {
    const onTree = vi.fn();
    wrap(<BuilderHarness triggers={["purchase.completed"]} initial={null} onTree={onTree} />);
    expect(screen.getByText(/Nessuna condizione/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Gruppo" }));
    const level2 = screen.getByRole("group", { name: "Gruppo 1" });
    fireEvent.click(within(level2).getAllByRole("button", { name: "Gruppo" })[0]);
    const level3 = screen.getByRole("group", { name: "Gruppo 1.2" });
    expect(within(level3).getByRole("button", { name: "Gruppo" })).toBeDisabled();
    fireEvent.change(screen.getByLabelText("Combinazione del gruppo 1.2"), { target: { value: "not" } });
    const json = toJson(onTree.mock.lastCall![0]) as { rules: { op: string; rules: { op?: string }[] }[] };
    expect(json.rules[0].op).toBe("any");
    expect(json.rules[0].rules[1].op).toBe("not");
  });
});
