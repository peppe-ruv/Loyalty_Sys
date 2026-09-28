import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import type { Role } from "@/lib/persona/personas";
import type { ActionType } from "@/lib/actiontypes/types";
import { ActionTypeEditor } from "./ActionTypeEditor";

// Editor a sezioni numerate di BO-09 (docs/08 §BO-09; Q-429…Q-436): codice proposto dal nome, controlli dal vivo,
// modelli e duplica, blocco dei dati personali, abilitazione sulle fonti (solo ADMIN), impatto e PUT completa.

vi.mock("next/navigation", () => ({
  useSearchParams: () => new URLSearchParams(),
  usePathname: () => "/backoffice/program/actions",
  useRouter: () => ({}),
}));

const SYSTEM: ActionType[] = [
  {
    code: "purchase.completed",
    name: "Acquisto completato",
    description: null,
    origin: "SYSTEM",
    category: "TRANSACTION",
    icon: "shopping-cart",
    enabled: true,
    dataSchema: { type: "object", required: ["orderId"], properties: { orderId: { type: "string" }, amount: { type: "number" } } },
    sampleData: {},
  },
  {
    code: "selfreading.submitted",
    name: "Autolettura inviata",
    description: "Un membro ha inviato l'autolettura del contatore",
    origin: "SYSTEM",
    category: "SERVICE",
    icon: "gauge",
    enabled: true,
    dataSchema: { type: "object", required: ["meterId", "reading"], properties: { meterId: { type: "string" }, reading: { type: "number" } } },
    sampleData: {},
  },
];

const CUSTOM: ActionType = {
  code: "store.visited",
  name: "Visita in negozio",
  description: "Il membro ha visitato un punto vendita.",
  origin: "CUSTOM",
  category: "ENGAGEMENT",
  icon: "map-pin",
  enabled: true,
  dataSchema: {
    $schema: "https://json-schema.org/draft/2020-12/schema",
    type: "object",
    required: ["storeId"],
    properties: { storeId: { type: "string", title: "Codice negozio", "x-lh-pii": false } },
  },
  sampleData: { storeId: "NEG-001" },
};

const SOURCES = [
  { code: "app", name: "App mobile", kind: "HTTP", enabled: true, allowedTypes: ["purchase.completed"], description: null },
  { code: "simulator", name: "Simulatore demo", kind: "INTERNAL", enabled: true, allowedTypes: [], description: null },
];

const CAMPAIGNS = [
  { id: "c-1", code: "CMP-VISITE", name: "Premio visite", status: "LIVE", triggerActionTypes: ["store.visited"] },
  { id: "c-2", code: "CMP-ALTRO", name: "Altro", status: "LIVE", triggerActionTypes: ["purchase.completed"] },
];

let calls: { url: string; method: string; body: unknown }[];

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}

beforeEach(() => {
  calls = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      const method = init?.method ?? "GET";
      const body = init?.body ? JSON.parse(String(init.body)) : undefined;
      if (method !== "GET") calls.push({ url, method, body });
      if (method === "POST" && url.includes("/v1/event-types")) return json({ ...body, origin: "CUSTOM" }, 201);
      if (method === "PUT" && url.includes("/v1/event-types/")) return json({ ...CUSTOM, ...body });
      if (method === "PUT" && url.includes("/v1/sources/")) return json({ ...SOURCES[0], ...body });
      if (url.includes("ingestion/v1/event-types")) return json([...SYSTEM, CUSTOM]);
      if (url.includes("ingestion/v1/sources")) return json(SOURCES);
      if (url.includes("campaign/v1/campaigns")) return json(CAMPAIGNS);
      return json([]);
    }),
  );
});
afterEach(() => vi.unstubAllGlobals());

function renderEditor(props: Partial<Parameters<typeof ActionTypeEditor>[0]> = {}, role: Role = "MARKETING") {
  const onSaved = vi.fn();
  const onUseExisting = vi.fn();
  renderWithProviders(
    <ActionTypeEditor initial={null} onSaved={onSaved} onCancel={vi.fn()} onUseExisting={onUseExisting} {...props} />,
    role,
  );
  return { onSaved, onUseExisting };
}

const createButton = () => screen.getByRole("button", { name: "Crea l'azione" });

describe("ActionTypeEditor: nuova azione", () => {
  it("*Crea* disattivato dice che cosa manca; nessun errore rosso sulla riga vuota prima di toccarla", async () => {
    renderEditor();
    await screen.findByText(/Per salvare manca:/);
    expect(createButton()).toBeDisabled();
    expect(screen.getByText(/il nome · un codice valido · informazioni corrette/)).toBeInTheDocument();
    expect(screen.queryByRole("alert")).toBeNull();
    expect(screen.getByText(/il codice non si può cambiare e l'azione non si può eliminare/)).toBeInTheDocument();
  });

  it("propone il codice dal nome e mostra i controlli e l'anteprima per l'integratore", async () => {
    renderEditor();
    fireEvent.change(screen.getByLabelText("Nome"), { target: { value: "Partecipazione all'evento" } });
    await waitFor(() => expect(screen.getAllByText("partecipazione.evento").length).toBeGreaterThan(0));
    expect(screen.getByText("type = partecipazione.evento")).toBeInTheDocument();
    expect(screen.getByText("io.loyaltyhub.action.partecipazione.evento")).toBeInTheDocument();
    const checks = screen.getByRole("list", { name: "Codice" });
    expect(within(checks).getAllByText(": rispettato")).toHaveLength(5);
  });

  it("un nome di una parola chiede «Che cosa è successo?»", async () => {
    renderEditor();
    fireEvent.change(screen.getByLabelText("Nome"), { target: { value: "Iscrizione" } });
    fireEvent.change(await screen.findByLabelText("Che cosa è successo?"), { target: { value: "completed" } });
    expect(screen.getAllByText("iscrizione.completed").length).toBeGreaterThan(0);
  });

  it("«Personalizza codice»: prefisso riservato e codice già usato bloccano il salvataggio", async () => {
    renderEditor();
    fireEvent.change(screen.getByLabelText("Nome"), { target: { value: "Visita speciale" } });
    fireEvent.click(screen.getByRole("button", { name: "Personalizza codice" }));
    const input = screen.getByRole("textbox", { name: "Codice" });
    expect(input).toHaveValue("visita.speciale");
    fireEvent.change(input, { target: { value: "io.loyaltyhub.effect.x" } });
    expect(within(screen.getByRole("list", { name: "Codice" })).getByText(/Non inizia con «io»/).parentElement).toHaveTextContent("da correggere");
    fireEvent.change(input, { target: { value: "store.visited" } });
    expect(await screen.findByText(/Questo codice è già usato da «Visita in negozio»/)).toBeInTheDocument();
    fireEvent.change(input, { target: { value: "purchase.partner" } });
    expect(screen.getByText(/inizia come l'azione di sistema purchase.completed/)).toBeInTheDocument();
  });

  it("blocca i nomi che fanno pensare a dati personali", async () => {
    renderEditor();
    fireEvent.change(screen.getByLabelText("Etichetta 1"), { target: { value: "Email del membro" } });
    expect(await screen.findByText("I dati personali non viaggiano nelle azioni: usa l'identificativo del membro.")).toBeInTheDocument();
  });

  it("un modello che esiste già propone «Usa quella esistente»", async () => {
    const { onUseExisting } = renderEditor();
    const select = await screen.findByLabelText("Parti da un modello");
    await waitFor(() => expect(select).not.toBeDisabled());
    fireEvent.change(select, { target: { value: "purchase" } });
    expect(screen.getByText(/Esiste già «Acquisto completato»/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Usa quella esistente" }));
    expect(onUseExisting).toHaveBeenCalledWith(expect.objectContaining({ code: "purchase.completed" }));
  });

  it("modello nuovo e duplica compilano campi, codice inglese e icona", async () => {
    renderEditor();
    const template = await screen.findByLabelText("Parti da un modello");
    await waitFor(() => expect(template).not.toBeDisabled());
    fireEvent.change(template, { target: { value: "eventAttended" } });
    expect(screen.getByLabelText("Nome")).toHaveValue("Partecipazione a un evento");
    expect(screen.getByRole("textbox", { name: "Codice" })).toHaveValue("event.attended");
    expect(screen.getByLabelText("Nome tecnico 1")).toHaveValue("eventId");
    expect(screen.getByRole("radio", { name: "Calendario" })).toHaveAttribute("aria-checked", "true");

    fireEvent.change(screen.getByLabelText("Duplica da un'azione esistente"), { target: { value: "selfreading.submitted" } });
    expect(screen.getByLabelText("Nome tecnico 1")).toHaveValue("meterId");
    expect(screen.getByLabelText("Nome tecnico 2")).toHaveValue("reading");
    expect(screen.getByRole("radio", { name: "Contatore" })).toHaveAttribute("aria-checked", "true");
  });

  it("MARKETING: dopo la creazione l'azione arriva solo dal simulatore (nessuna fonte da scegliere)", async () => {
    const { onSaved } = renderEditor();
    fireEvent.change(screen.getByLabelText("Nome"), { target: { value: "Visita in fiera" } });
    fireEvent.change(screen.getByLabelText("Etichetta 1"), { target: { value: "Codice fiera" } });
    expect(await screen.findByText(/arriverà solo dal simulatore/)).toBeInTheDocument();
    expect(screen.queryByRole("checkbox", { name: "App mobile" })).toBeNull();
    await waitFor(() => expect(createButton()).not.toBeDisabled());
    fireEvent.click(createButton());
    await waitFor(() => expect(onSaved).toHaveBeenCalled());
    expect(calls.filter((c) => c.url.includes("/v1/sources/"))).toEqual([]);
    expect(calls[0].body).toMatchObject({ code: "visita.fiera", enabled: true, category: "ENGAGEMENT", icon: "zap" });
  });

  it("ADMIN: abilita subito l'azione sulle fonti scelte con l'elenco completo", async () => {
    const { onSaved } = renderEditor({}, "ADMIN");
    fireEvent.change(screen.getByLabelText("Nome"), { target: { value: "Visita in fiera" } });
    fireEvent.change(screen.getByLabelText("Etichetta 1"), { target: { value: "Codice fiera" } });
    fireEvent.click(await screen.findByRole("checkbox", { name: "App mobile" }));
    fireEvent.click(createButton());
    await waitFor(() => expect(onSaved).toHaveBeenCalled());
    const put = calls.find((c) => c.url.includes("/v1/sources/app"));
    expect(put?.body).toEqual({ allowedTypes: ["purchase.completed", "visita.fiera"] });
    expect(onSaved.mock.calls[0][1]).toEqual({ enabledOn: ["App mobile"], failedOn: [] });
  });

  it("dirty: segnala le modifiche al contenitore", async () => {
    const onDirtyChange = vi.fn();
    renderEditor({ onDirtyChange });
    await screen.findByText(/Per salvare manca:/);
    expect(onDirtyChange).toHaveBeenLastCalledWith(false);
    fireEvent.change(screen.getByLabelText("Nome"), { target: { value: "X" } });
    expect(onDirtyChange).toHaveBeenLastCalledWith(true);
  });
});

describe("ActionTypeEditor: modifica", () => {
  it("il codice è in sola lettura; cambiando lo schema avvisa sull'impatto e la PUT manda tutto", async () => {
    const { onSaved } = renderEditor({ initial: CUSTOM });
    expect(screen.getByText("Il codice non si può più cambiare.")).toBeInTheDocument();
    expect(screen.queryByRole("textbox", { name: "Codice" })).toBeNull();
    fireEvent.change(screen.getByLabelText("Tipo 1"), { target: { value: "integer" } });
    expect(await screen.findByText(/Usata da 1 campagna \(1 attiva\)/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Premio visite" })).toHaveAttribute("href", "/backoffice/campaigns/c-1");
    fireEvent.click(screen.getByRole("button", { name: "Salva" }));
    await waitFor(() => expect(onSaved).toHaveBeenCalled());
    const put = calls.find((c) => c.method === "PUT")!;
    expect(put.url).toContain("/v1/event-types/store.visited");
    expect(put.body).toMatchObject({
      name: "Visita in negozio",
      description: "Il membro ha visitato un punto vendita.",
      category: "ENGAGEMENT",
      icon: "map-pin",
      enabled: true,
      sampleData: { storeId: "NEG-001" },
      dataSchema: { properties: { storeId: { type: "integer", title: "Codice negozio", "x-lh-pii": false } } },
    });
    expect(put.body).not.toHaveProperty("code");
  });

  it("disattivare un'azione usata spiega le conseguenze", async () => {
    renderEditor({ initial: CUSTOM });
    fireEvent.click(screen.getByRole("checkbox", { name: "Abilitata" }));
    expect(await screen.findByText(/Se disattivi l'azione, quelle in arrivo verranno scartate/)).toBeInTheDocument();
  });

  it("un'icona fuori elenco resta visibile con un avviso", async () => {
    renderEditor({ initial: { ...CUSTOM, icon: "log-out" } });
    expect(screen.getByText(/Questa icona non è disponibile e viene mostrata come ⚡/)).toBeInTheDocument();
    expect(screen.getByRole("radiogroup").querySelectorAll('[aria-checked="true"]')).toHaveLength(0);
  });

  it("griglia delle icone: frecce, Home e Fine scelgono e spostano il focus", async () => {
    renderEditor({ initial: CUSTOM });
    const selected = screen.getByRole("radio", { name: "Luogo" });
    expect(selected).toHaveAttribute("tabindex", "0");
    fireEvent.keyDown(selected, { key: "ArrowRight" });
    const next = screen.getByRole("radio", { name: "Negozio" });
    expect(next).toHaveAttribute("aria-checked", "true");
    expect(document.activeElement).toBe(next);
    fireEvent.keyDown(next, { key: "Home" });
    expect(screen.getByRole("radio", { name: "Carrello" })).toHaveAttribute("aria-checked", "true");
    fireEvent.change(screen.getByPlaceholderText("Cerca un'icona"), { target: { value: "sostenibil" } });
    expect(screen.getAllByRole("radio").map((r) => r.getAttribute("aria-label"))).toEqual(["Foglia"]);
  });

  it("un'azione di sistema cambia solo nome, descrizione, icona e abilitazione", async () => {
    const { onSaved } = renderEditor({ initial: SYSTEM[1] }, "ADMIN");
    expect(screen.getByText(/Azione di sistema: si cambiano solo/)).toBeInTheDocument();
    expect(screen.queryByLabelText("Etichetta 1")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Salva" }));
    await waitFor(() => expect(onSaved).toHaveBeenCalled());
    expect(calls[0].body).toEqual({ name: "Autolettura inviata", description: "Un membro ha inviato l'autolettura del contatore", icon: "gauge", enabled: true });
  });
});
