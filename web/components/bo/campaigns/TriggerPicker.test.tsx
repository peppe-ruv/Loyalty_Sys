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
let sourcePuts: { url: string; body: unknown }[];
let failSourcePut: boolean;

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
  sourcePuts = [];
  failSourcePut = false;
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
      if (url.includes("/v1/sources/") && init?.method === "PUT") {
        sourcePuts.push({ url, body: JSON.parse(String(init.body)) });
        return failSourcePut ? json({ code: "ERROR", detail: "Errore" }, 500) : json({});
      }
      if (url.includes("ingestion/v1/event-types")) return json(types);
      const one = url.match(/ingestion\/v1\/sources\/([a-z]+)/);
      if (one) return json(SOURCES.find((x) => x.code === one[1]));
      if (url.includes("ingestion/v1/sources")) return json(SOURCES);
      return json([]);
    }),
  );
});
afterEach(() => vi.unstubAllGlobals());

function Harness({
  onChange,
  conditionUsesAmount = true,
  effectUsesAmount = false,
  onDrop,
  initial = ["purchase.completed"],
}: {
  onChange: (v: string[]) => void;
  conditionUsesAmount?: boolean;
  effectUsesAmount?: boolean;
  onDrop?: () => void;
  initial?: string[];
}) {
  const [value, setValue] = useState<string[]>(initial);
  return (
    <TriggerPicker
      value={value}
      onChange={(v) => {
        setValue(v);
        onChange(v);
      }}
      conditionUsesAmount={conditionUsesAmount}
      effectUsesAmount={effectUsesAmount}
      onDropAmountExample={onDrop}
    />
  );
}

function renderPicker(role: Role = "MARKETING", props: Partial<Parameters<typeof Harness>[0]> = {}, mode: "demo" | "enterprise" = "demo") {
  const onChange = vi.fn();
  renderWithProviders(<Harness onChange={onChange} {...props} />, role, mode);
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
    // MARKETING non abilita fonti: l'azione arriva solo dal simulatore, e il banner lo dice.
    expect(screen.getByText(/Per ora questa azione arriva solo dal simulatore/)).toBeInTheDocument();
    const warning = screen.getByText(/«Visita al punto vendita» non ha un Importo/);
    expect(warning).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Togli la condizione d'esempio" }));
    expect(onDrop).toHaveBeenCalled();
  });

  it("il link al simulatore si apre in una nuova scheda: la bozza della campagna resta aperta", async () => {
    renderPicker();
    fireEvent.click(await screen.findByRole("button", { name: "+ Crea una nuova azione" }));
    const sheet = await screen.findByRole("dialog", { name: "Nuova azione" });
    fireEvent.change(within(sheet).getByLabelText("Nome"), { target: { value: "Visita in fiera" } });
    fireEvent.change(within(sheet).getByLabelText("Etichetta 1"), { target: { value: "Codice fiera" } });
    await waitFor(() => expect(within(sheet).getByRole("button", { name: "Crea l'azione" })).not.toBeDisabled());
    fireEvent.click(within(sheet).getByRole("button", { name: "Crea l'azione" }));
    const link = await screen.findByRole("link", { name: /Prova nel simulatore/ });
    expect(link).toHaveAttribute("target", "_blank");
    expect(link).toHaveAttribute("href", "/backoffice/demo/simulator?type=visita.fiera");
    expect(link).toHaveTextContent("(si apre in una nuova scheda)");
  });

  it("enterprise: dopo la creazione non c'è il collegamento al simulatore (/v1/demo non esiste)", async () => {
    renderPicker("MARKETING", {}, "enterprise");
    fireEvent.click(await screen.findByRole("button", { name: "+ Crea una nuova azione" }));
    const sheet = await screen.findByRole("dialog", { name: "Nuova azione" });
    fireEvent.change(within(sheet).getByLabelText("Nome"), { target: { value: "Visita in fiera" } });
    fireEvent.change(within(sheet).getByLabelText("Etichetta 1"), { target: { value: "Codice fiera" } });
    await waitFor(() => expect(within(sheet).getByRole("button", { name: "Crea l'azione" })).not.toBeDisabled());
    fireEvent.click(within(sheet).getByRole("button", { name: "Crea l'azione" }));
    expect(await screen.findByText("Visita in fiera è stata creata e aggiunta a Quando.")).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /Prova nel simulatore/ })).toBeNull();
  });

  it("ADMIN: se abilitare la nuova azione su una fonte non riesce, lo dice", async () => {
    failSourcePut = true;
    renderPicker("ADMIN");
    fireEvent.click(await screen.findByRole("button", { name: "+ Crea una nuova azione" }));
    const sheet = await screen.findByRole("dialog");
    fireEvent.change(within(sheet).getByLabelText("Nome"), { target: { value: "Visita in fiera" } });
    fireEvent.change(within(sheet).getByLabelText("Etichetta 1"), { target: { value: "Codice fiera" } });
    fireEvent.click(await within(sheet).findByRole("checkbox", { name: "App mobile" }));
    await waitFor(() => expect(within(sheet).getByRole("button", { name: "Crea l'azione" })).not.toBeDisabled());
    fireEvent.click(within(sheet).getByRole("button", { name: "Crea l'azione" }));
    expect(await screen.findByText(/Non è stato possibile abilitarla su: App mobile/)).toHaveAttribute("role", "alert");
    expect(sourcePuts).toEqual([{ url: expect.stringContaining("/v1/sources/app"), body: { allowedTypes: ["purchase.completed", "visita.fiera"] } }]);
    expect(screen.getByText(/arriva solo dal simulatore/)).toBeInTheDocument();
  });

  it("«Usa quella esistente» da un modello aggiunge l'azione di sistema ai trigger", async () => {
    const onChange = renderPicker("MARKETING", { initial: [] });
    fireEvent.click(await screen.findByRole("button", { name: "+ Crea una nuova azione" }));
    const sheet = await screen.findByRole("dialog");
    const template = within(sheet).getByLabelText("Parti da un modello");
    await waitFor(() => expect(template).not.toBeDisabled());
    fireEvent.change(template, { target: { value: "purchase" } });
    fireEvent.click(within(sheet).getByRole("button", { name: "Usa quella esistente" }));
    expect(onChange).toHaveBeenLastCalledWith(["purchase.completed"]);
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("avvisa subito se un trigger scelto non ha Importo e la bozza lo usa (anche senza creare nulla)", async () => {
    renderPicker("MARKETING", { initial: ["review.submitted"], effectUsesAmount: true, onDrop: vi.fn() });
    const alert = await screen.findByText(/«Recensione inviata» non ha un Importo/);
    expect(alert.closest("[role=alert]")).toHaveTextContent("sezione 5 · Allora");
    expect(screen.getByRole("button", { name: "Togli la condizione d'esempio" })).toBeInTheDocument();
  });

  it("senza la condizione d'esempio resta solo il richiamo all'effetto, senza pulsante", async () => {
    renderPicker("MARKETING", { initial: ["review.submitted"], conditionUsesAmount: false, effectUsesAmount: true });
    expect(await screen.findByText(/non ha un Importo/)).toBeInTheDocument();
    expect(screen.queryByText(/sezione 4 · Se/)).toBeNull();
    expect(screen.queryByRole("button", { name: "Togli la condizione d'esempio" })).toBeNull();
  });

  it("nessun avviso sull'Importo quando i trigger ce l'hanno o la bozza non lo usa", async () => {
    renderPicker("MARKETING", { effectUsesAmount: true });
    await screen.findByRole("checkbox", { name: /Acquisto completato/ });
    expect(screen.queryByText(/non ha un Importo/)).toBeNull();
  });

  it("dalla ricerca senza risultati: «Crea «…» come nuova azione» con il nome già scritto", async () => {
    renderPicker();
    fireEvent.change(await screen.findByPlaceholderText(/Cerca per nome o codice/), { target: { value: "Degustazione vini" } });
    fireEvent.click(screen.getByRole("button", { name: "Crea «Degustazione vini» come nuova azione" }));
    // Una sola proposta di creazione quando la ricerca non trova nulla.
    expect(screen.queryByText("Non trovi l'azione che ti serve?")).toBeNull();
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
    const manage = screen.getByRole("link", { name: /Gestisci in Azioni e fonti/ });
    expect(manage).toHaveAttribute("href", "/backoffice/program/actions");
    expect(manage).toHaveAttribute("target", "_blank");
    expect(screen.getAllByRole("link", { name: /Azioni e fonti/ }).every((l) => l.getAttribute("target") === "_blank")).toBe(true);
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
