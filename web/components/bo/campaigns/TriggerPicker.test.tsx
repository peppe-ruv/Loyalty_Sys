import { useState } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import type { Role } from "@/lib/persona/personas";
import { TriggerPicker, hasAmountField } from "./TriggerPicker";

// Scorciatoia da BO-06 «2 · Quando» verso BO-09 (docs/08 §BO-06; Q-432): l'editor si apre in un foglio laterale dentro
// la campagna, la nuova azione entra nei trigger e la bozza (qui: il valore del picker) non si perde.

vi.mock("next/navigation", () => ({
  useSearchParams: () => new URLSearchParams(),
  usePathname: () => "/backoffice/campaigns/new",
  useRouter: () => ({ push: vi.fn(), back: vi.fn() }),
}));

type Row = Record<string, unknown>;
let types: Row[];
let posted: Row[];

const BASE: Row[] = [
  { code: "purchase.completed", name: "Acquisto completato", origin: "SYSTEM", category: "TRANSACTION", icon: "shopping-cart", enabled: true, dataSchema: { type: "object", properties: { amount: { type: "number" } } }, sampleData: null },
  { code: "review.submitted", name: "Recensione inviata", origin: "SYSTEM", category: "ENGAGEMENT", icon: "star", enabled: true, dataSchema: { type: "object", properties: {} }, sampleData: null },
];
const SOURCES = [
  { code: "app", name: "App mobile", kind: "HTTP", enabled: true, allowedTypes: ["purchase.completed"], description: null },
  { code: "simulator", name: "Simulatore demo", kind: "INTERNAL", enabled: true, allowedTypes: [], description: null },
];

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}

beforeEach(() => {
  types = [...BASE];
  posted = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.includes("/v1/event-types") && init?.method === "POST") {
        const body = JSON.parse(String(init.body)) as Row;
        posted.push(body);
        const created = { ...body, origin: "CUSTOM" };
        types = [...types, created];
        return json(created, 201);
      }
      if (url.includes("ingestion/v1/event-types")) return json(types);
      if (url.includes("ingestion/v1/sources")) return json(SOURCES);
      return json([]);
    }),
  );
});
afterEach(() => vi.unstubAllGlobals());

function Harness({ onChange, draftUsesAmount = true, onDrop }: { onChange: (v: string[]) => void; draftUsesAmount?: boolean; onDrop?: () => void }) {
  const [value, setValue] = useState<string[]>(["purchase.completed"]);
  return (
    <TriggerPicker
      value={value}
      onChange={(v) => {
        setValue(v);
        onChange(v);
      }}
      draftUsesAmount={draftUsesAmount}
      onDropAmountExample={onDrop}
    />
  );
}

function renderPicker(role: Role = "MARKETING", props: Partial<Parameters<typeof Harness>[0]> = {}) {
  const onChange = vi.fn();
  renderWithProviders(<Harness onChange={onChange} {...props} />, role);
  return onChange;
}

describe("TriggerPicker: crea una nuova azione", () => {
  it("dal fondo dell'elenco: il foglio si apre, la nuova azione entra nei trigger con l'avviso sull'importo", async () => {
    const onDrop = vi.fn();
    const onChange = renderPicker("MARKETING", { onDrop });
    fireEvent.click(await screen.findByRole("button", { name: "+ Crea una nuova azione" }));
    const sheet = await screen.findByRole("dialog");
    fireEvent.change(within(sheet).getByLabelText("Nome"), { target: { value: "Visita al punto vendita" } });
    fireEvent.change(within(sheet).getByLabelText("Etichetta 1"), { target: { value: "Codice negozio" } });
    expect(within(sheet).getByLabelText("Nome tecnico 1")).toHaveValue("storeId");
    await waitFor(() => expect(within(sheet).getByText("visita.punto.vendita")).toBeInTheDocument());
    fireEvent.click(within(sheet).getByRole("button", { name: "Crea l'azione" }));

    await waitFor(() => expect(onChange).toHaveBeenLastCalledWith(["purchase.completed", "visita.punto.vendita"]));
    expect(posted[0]).toMatchObject({
      code: "visita.punto.vendita",
      name: "Visita al punto vendita",
      enabled: true,
      dataSchema: { properties: { storeId: { type: "string", title: "Codice negozio", "x-lh-pii": false } } },
      sampleData: { storeId: "NEG-001" },
    });
    expect(await screen.findByText("Visita al punto vendita è stata creata e aggiunta a Quando.")).toBeInTheDocument();
    expect(screen.queryByRole("dialog")).toBeNull();
    const warning = screen.getByText(/non ha un Importo/);
    expect(warning).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Togli la condizione d'esempio" }));
    expect(onDrop).toHaveBeenCalled();
  });

  it("dalla ricerca senza risultati: «Crea «…» come nuova azione» con il nome già scritto", async () => {
    renderPicker();
    fireEvent.change(await screen.findByPlaceholderText(/Cerca per nome o codice/), { target: { value: "Degustazione vini" } });
    fireEvent.click(screen.getByRole("button", { name: "Crea «Degustazione vini» come nuova azione" }));
    const sheet = await screen.findByRole("dialog");
    expect(within(sheet).getByLabelText("Nome")).toHaveValue("Degustazione vini");
    expect(within(sheet).getByText("degustazione.vini")).toBeInTheDocument();
  });

  it("chi non ha actiontype.custom non vede le scorciatoie", async () => {
    renderPicker("ANALYST");
    await screen.findByRole("checkbox", { name: /Acquisto completato/ });
    expect(screen.queryByRole("button", { name: "+ Crea una nuova azione" })).toBeNull();
  });

  it("senza azioni abilitate lo stato vuoto offre la creazione e il link ad «Azioni e fonti»", async () => {
    types = BASE.map((t) => ({ ...t, enabled: false }));
    renderPicker();
    expect(await screen.findByText("Nessuna azione abilitata")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "+ Crea una nuova azione" })).toBeInTheDocument();
    expect(screen.getAllByRole("link", { name: "Azioni e fonti" })[0]).toHaveAttribute("href", "/backoffice/program/actions");
  });

  it("«Aggiorna» rilegge le azioni create in un'altra scheda", async () => {
    renderPicker();
    await screen.findByRole("checkbox", { name: /Acquisto completato/ });
    types = [...types, { code: "store.visited", name: "Visita in negozio", origin: "CUSTOM", category: "ENGAGEMENT", icon: "map-pin", enabled: true, dataSchema: {}, sampleData: null }];
    expect(screen.queryByRole("checkbox", { name: /Visita in negozio/ })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Aggiorna l'elenco delle azioni" }));
    expect(await screen.findByRole("checkbox", { name: /Visita in negozio/ })).toBeInTheDocument();
  });

  it("hasAmountField riconosce il campo amount", () => {
    expect(hasAmountField({ dataSchema: { properties: { amount: {} } } })).toBe(true);
    expect(hasAmountField({ dataSchema: { properties: {} } })).toBe(false);
    expect(hasAmountField({ dataSchema: null })).toBe(false);
  });
});
