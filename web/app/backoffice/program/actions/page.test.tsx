import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import ActionsPage from "./page";
import { renderWithProviders } from "../../../../test/test-utils";
import { HOW_IT_WORKS_KEY } from "@/components/bo/actiontypes/HowItWorks";

// BO-09 Azioni e fonti (docs/08 §BO-09; F-ING-05, F-ING-06; Q-433, Q-438).

let tab = "types";
vi.mock("next/navigation", () => ({
  useSearchParams: () => new URLSearchParams(`tab=${tab}`),
  usePathname: () => "/backoffice/program/actions",
  useRouter: () => ({}),
}));

const SOURCES = [
  { code: "ecommerce", name: "E-commerce", kind: "HTTP", enabled: true, allowedTypes: ["purchase.completed"], description: "Negozio online" },
  { code: "partner", name: "Partner", kind: "HTTP", enabled: false, allowedTypes: [], description: null },
  { code: "simulator", name: "Simulatore demo", kind: "INTERNAL", enabled: true, allowedTypes: [], description: null },
];

const HTTP_ONLY = [
  { code: "ecommerce", name: "E-commerce", kind: "HTTP", enabled: true, allowedTypes: ["purchase.completed"], description: null },
  { code: "simulator", name: "Simulatore demo", kind: "INTERNAL", enabled: true, allowedTypes: [], description: null },
];

const TYPES = [
  { code: "purchase.completed", name: "Acquisto completato", description: null, origin: "SYSTEM", category: "TRANSACTION", icon: "shopping-cart", enabled: true, dataSchema: { type: "object", properties: {} }, sampleData: {} },
  { code: "store.visited", name: "Visita in negozio", description: null, origin: "CUSTOM", category: "ENGAGEMENT", icon: "map-pin", enabled: true, dataSchema: { type: "object", properties: { storeId: { type: "string" } } }, sampleData: {} },
];

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}

function mockApi(sources: unknown[] = SOURCES, types: unknown[] = TYPES, onWrite?: (url: string, init: RequestInit) => Response | undefined) {
  vi.mocked(fetch).mockImplementation(async (url, init) => {
    const u = url.toString();
    if (init?.method && init.method !== "GET") {
      const r = onWrite?.(u, init);
      if (r) return r;
      return json({});
    }
    if (u.includes("/v1/sources")) return json(sources);
    if (u.includes("/fields")) return json([]);
    if (u.includes("/v1/event-types")) return json(types);
    if (u.includes("/v1/campaigns")) return json([{ id: "c-9", code: "CMP-V", name: "Premio visite", status: "LIVE", triggerActionTypes: ["store.visited"] }]);
    return json([]);
  });
}

const writes = () =>
  vi
    .mocked(fetch)
    .mock.calls.filter((c) => c[1]?.method && c[1].method !== "GET")
    .map((c) => ({ url: c[0].toString(), body: JSON.parse(c[1]!.body as string) }));

beforeEach(() => {
  tab = "types";
  vi.stubGlobal("fetch", vi.fn());
  window.localStorage.clear();
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("BO-09 pagina", () => {
  it("«Come funziona» spiega il percorso e ricorda se l'operatore lo chiude", async () => {
    mockApi();
    renderWithProviders(<ActionsPage />, "MARKETING");
    expect(screen.getByRole("list", { name: /Percorso di un'azione/ })).toBeInTheDocument();
    expect(screen.getByText("In demo, il ripristino dei dati elimina le azioni personalizzate.")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /Come funziona/ }));
    expect(screen.queryByRole("list", { name: /Percorso di un'azione/ })).toBeNull();
    expect(window.localStorage.getItem(HOW_IT_WORKS_KEY)).toBe("closed");
    expect(screen.getByRole("link", { name: "Azioni" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Azioni generate dal programma" })).toBeInTheDocument();
  });

  it("funziona anche se il localStorage non è disponibile", async () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("bloccato");
    });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("bloccato");
    });
    mockApi();
    renderWithProviders(<ActionsPage />, "MARKETING");
    fireEvent.click(screen.getByRole("button", { name: /Come funziona/ }));
    expect(screen.queryByRole("list", { name: /Percorso di un'azione/ })).toBeNull();
  });
});

describe("BO-09 azioni", () => {
  it("elenco con icona, origine in italiano, fonti e «solo simulatore» in ambra", async () => {
    mockApi(HTTP_ONLY);
    renderWithProviders(<ActionsPage />, "MARKETING");
    const row = (await screen.findByText("Visita in negozio")).closest("tr")!;
    expect(within(row).getByText("Personalizzata")).toBeInTheDocument();
    expect(within(row).getByText("solo simulatore")).toHaveClass("bg-amber-100");
    expect(within(row).getByRole("img", { name: "Luogo" })).toBeInTheDocument();
    const purchase = screen.getByText("Acquisto completato").closest("tr")!;
    expect(within(purchase).getByText("E-commerce")).toBeInTheDocument();
    expect(within(purchase).getByText("Di sistema")).toBeInTheDocument();
  });

  it("filtro «Personalizzata» senza risultati: spiegazione e azione", async () => {
    mockApi(HTTP_ONLY, [TYPES[0]]);
    renderWithProviders(<ActionsPage />, "MARKETING");
    await screen.findByText("Acquisto completato");
    fireEvent.change(screen.getByLabelText("Origine"), { target: { value: "CUSTOM" } });
    expect(screen.getByText("Nessuna azione personalizzata")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Crea un'azione" })).toBeInTheDocument();
  });

  it("dettaglio: «Da dove può arrivare», link alle campagne, e ADMIN abilita su una fonte con l'elenco completo", async () => {
    mockApi(HTTP_ONLY);
    renderWithProviders(<ActionsPage />, "ADMIN");
    fireEvent.click(await screen.findByText("Visita in negozio"));
    const sheet = await screen.findByRole("dialog");
    expect(within(sheet).getByText("Non accetta: serve un amministratore")).toBeInTheDocument();
    expect(within(sheet).getByText("Accetta tutte le azioni")).toBeInTheDocument();
    expect(await within(sheet).findByRole("link", { name: "Premio visite" })).toHaveAttribute("href", "/backoffice/campaigns/c-9");
    expect(within(sheet).getByRole("link", { name: "Crea una campagna con questa azione" })).toHaveAttribute(
      "href",
      "/backoffice/campaigns/new?trigger=store.visited",
    );
    fireEvent.click(within(sheet).getByRole("button", { name: "Abilita: E-commerce" }));
    await waitFor(() => expect(writes()).toEqual([{ url: expect.stringContaining("/v1/sources/ecommerce"), body: { allowedTypes: ["purchase.completed", "store.visited"] } }]));
  });

  it("MARKETING vede il limite ma non può abilitare fonti", async () => {
    mockApi(HTTP_ONLY);
    renderWithProviders(<ActionsPage />, "MARKETING");
    fireEvent.click(await screen.findByText("Visita in negozio"));
    const sheet = await screen.findByRole("dialog");
    expect(within(sheet).getByText(/arriva solo dal simulatore/)).toBeInTheDocument();
    expect(within(sheet).queryByRole("button", { name: /Abilita/ })).toBeNull();
  });

  it("l'ultima azione ammessa di una fonte non si toglie dal dettaglio (non diventa «tutte»)", async () => {
    mockApi(HTTP_ONLY);
    renderWithProviders(<ActionsPage />, "ADMIN");
    fireEvent.click(await screen.findByText("Acquisto completato"));
    const sheet = await screen.findByRole("dialog");
    expect(within(sheet).queryByRole("button", { name: "Togli: E-commerce" })).toBeNull();
    expect(within(sheet).getByText(/È l'unica azione ammessa da questa fonte/)).toBeInTheDocument();
  });
});

describe("BO-09 fonti", () => {
  beforeEach(() => {
    tab = "sources";
  });

  it("ADMIN spegne una fonte solo dopo la conferma, con PUT {enabled:false}", async () => {
    mockApi();
    renderWithProviders(<ActionsPage />, "ADMIN");
    fireEvent.click(await screen.findByRole("switch", { name: "Fonte E-commerce accesa" }));
    expect(writes()).toEqual([]);
    expect(screen.getByRole("alertdialog")).toHaveTextContent("Spegnendo «E-commerce», tutte le azioni inviate da E-commerce verranno scartate");
    fireEvent.click(screen.getByRole("button", { name: "Spegni" }));
    await waitFor(() => expect(writes()).toEqual([{ url: expect.stringContaining("/v1/sources/ecommerce"), body: { enabled: false } }]));
  });

  it("riaccendere non chiede conferma; tipo e descrizione in italiano; link al Monitor ingressi", async () => {
    mockApi();
    renderWithProviders(<ActionsPage />, "ADMIN");
    expect(await screen.findByText("Negozio online")).toBeInTheDocument();
    expect(screen.getAllByText("Esterna (HTTP)").length).toBe(2);
    expect(screen.getByText("Interna")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Apri il Monitor ingressi" })).toHaveAttribute("href", "/backoffice/observe/inbound");
    fireEvent.click(screen.getByRole("switch", { name: "Fonte Partner accesa" }));
    await waitFor(() => expect(writes()).toEqual([{ url: expect.stringContaining("/v1/sources/partner"), body: { enabled: true } }]));
  });

  it("MARKETING vede interruttore e modifica disabilitati con il ruolo richiesto", async () => {
    mockApi();
    renderWithProviders(<ActionsPage />, "MARKETING");
    const toggle = await screen.findByRole("switch", { name: "Fonte E-commerce accesa" });
    expect(toggle).toBeDisabled();
    expect(toggle.closest("span[title]")?.getAttribute("title")).toMatch(/ADMIN/);
    expect(screen.getByRole("button", { name: "Modifica azioni ammesse: E-commerce" })).toBeDisabled();
  });

  it("azioni ammesse: sempre l'elenco completo, mai [] togliendo l'ultima casella", async () => {
    mockApi();
    renderWithProviders(<ActionsPage />, "ADMIN");
    fireEvent.click(await screen.findByRole("button", { name: "Modifica azioni ammesse: E-commerce" }));
    const sheet = await screen.findByRole("dialog");
    const purchase = within(sheet).getByRole("checkbox", { name: /Acquisto completato/ });
    fireEvent.click(purchase);
    expect(within(sheet).getByText("Scegli almeno un'azione, oppure «Tutte le azioni».")).toBeInTheDocument();
    expect(within(sheet).getByRole("button", { name: "Salva l'elenco" })).toBeDisabled();
    fireEvent.click(purchase);
    fireEvent.click(within(sheet).getByRole("checkbox", { name: /Visita in negozio/ }));
    fireEvent.click(within(sheet).getByRole("button", { name: "Salva l'elenco" }));
    await waitFor(() =>
      expect(writes()).toEqual([{ url: expect.stringContaining("/v1/sources/ecommerce"), body: { allowedTypes: ["purchase.completed", "store.visited"] } }]),
    );
  });

  it("«Tutte le azioni» è una scelta esplicita con avviso", async () => {
    mockApi();
    renderWithProviders(<ActionsPage />, "ADMIN");
    fireEvent.click(await screen.findByRole("button", { name: "Modifica azioni ammesse: E-commerce" }));
    const sheet = await screen.findByRole("dialog");
    fireEvent.click(within(sheet).getByRole("radio", { name: /Tutte le azioni/ }));
    expect(within(sheet).getByText(/Sceglilo solo per sistemi di cui ti fidi del tutto/)).toBeInTheDocument();
    fireEvent.click(within(sheet).getByRole("button", { name: "Salva l'elenco" }));
    await waitFor(() => expect(writes()).toEqual([{ url: expect.stringContaining("/v1/sources/ecommerce"), body: { allowedTypes: [] } }]));
  });

  it("errore del servizio mostrato sopra la tabella", async () => {
    mockApi(SOURCES, TYPES, () => json({ code: "SOURCE_INVALID", detail: "Indicare enabled e/o allowedTypes." }, 422));
    renderWithProviders(<ActionsPage />, "ADMIN");
    fireEvent.click(await screen.findByRole("switch", { name: "Fonte Partner accesa" }));
    expect(await screen.findByText("Indicare enabled e/o allowedTypes.")).toBeInTheDocument();
  });

  it("nessuna fonte: spiegazione e link alla Console demo", async () => {
    mockApi([]);
    renderWithProviders(<ActionsPage />, "ADMIN");
    expect(await screen.findByText("Nessuna fonte")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Apri la Console demo" })).toHaveAttribute("href", "/backoffice/demo/console");
  });
});
