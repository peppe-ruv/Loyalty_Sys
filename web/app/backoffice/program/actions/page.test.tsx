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

const INTERNAL_TYPE = { code: "referral.completed", name: "Presentazione di un amico", description: null, origin: "SYSTEM", category: "INTERNAL", icon: "users", enabled: true, dataSchema: { type: "object", properties: {} }, sampleData: {} };

/** Versione «riletta» di una fonte (GET /v1/sources/{code}); null = uguale all'elenco. */
let fresh: Record<string, unknown> | null = null;

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
    const one = u.match(/\/v1\/sources\/([a-z]+)/);
    if (one) return json(fresh ?? (sources as { code: string }[]).find((x) => x.code === one[1]));
    if (u.includes("/v1/sources")) return json(sources);
    if (u.includes("/v1/inbound-events/counts")) return json(u.includes("source=ecommerce") ? { ACCEPTED: 12, REJECTED: 3 } : {});
    if (u.includes("/v1/inbound-events"))
      return json(u.includes("source=ecommerce") ? [{ id: "1", typeCode: "purchase.completed", receivedAt: new Date(Date.now() - 5 * 60_000).toISOString() }] : []);
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
  fresh = null;
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
    expect(screen.getByRole("button", { name: /Come funziona/ })).toHaveAttribute("aria-controls", "bo09-how-body");
    fireEvent.click(screen.getByRole("button", { name: /Come funziona/ }));
    expect(document.getElementById("bo09-how-body")).not.toBeNull();
    expect(screen.queryByRole("list", { name: /Percorso di un'azione/ })).toBeNull();
    expect(window.localStorage.getItem(HOW_IT_WORKS_KEY)).toBe("closed");
    expect(screen.getByRole("link", { name: "Azioni" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Azioni generate dal programma" })).toBeInTheDocument();
  });

  it("nel profilo enterprise non compare la nota sul ripristino della demo", async () => {
    mockApi();
    renderWithProviders(<ActionsPage />, "MARKETING", "enterprise");
    expect(screen.getByRole("list", { name: /Percorso di un'azione/ })).toBeInTheDocument();
    expect(screen.queryByText(/In demo, il ripristino dei dati/)).toBeNull();
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

  it("un'azione generata dal programma non si abilita su nessuna fonte esterna, nemmeno da ADMIN", async () => {
    mockApi(SOURCES, [...TYPES, INTERNAL_TYPE]);
    renderWithProviders(<ActionsPage />, "ADMIN");
    fireEvent.click(await screen.findByText("Presentazione di un amico"));
    const sheet = await screen.findByRole("dialog", { name: "Presentazione di un amico" });
    expect(within(sheet).getByText(/Generata dal programma: arriva dal ponte interno/)).toBeInTheDocument();
    expect(within(sheet).getAllByText("Non la invia: la genera il programma").length).toBe(2);
    expect(within(sheet).queryByRole("button", { name: /Abilita/ })).toBeNull();
    expect(within(sheet).queryByText("Non accetta: serve un amministratore")).toBeNull();
  });

  it("«Abilita» non compare su una fonte spenta", async () => {
    mockApi([{ code: "partner", name: "Partner", kind: "HTTP", enabled: false, allowedTypes: ["survey.completed"], description: null }]);
    renderWithProviders(<ActionsPage />, "ADMIN");
    fireEvent.click(await screen.findByText("Visita in negozio"));
    const sheet = await screen.findByRole("dialog");
    expect(within(sheet).getByText("Spenta")).toBeInTheDocument();
    expect(within(sheet).queryByRole("button", { name: /Abilita/ })).toBeNull();
  });

  it("*Abilita* rilegge la fonte: un'azione aggiunta nel frattempo da un altro ADMIN non va persa", async () => {
    fresh = { code: "ecommerce", name: "E-commerce", kind: "HTTP", enabled: true, allowedTypes: ["purchase.completed", "review.submitted"], description: null };
    mockApi(HTTP_ONLY);
    renderWithProviders(<ActionsPage />, "ADMIN");
    fireEvent.click(await screen.findByText("Visita in negozio"));
    const sheet = await screen.findByRole("dialog");
    fireEvent.click(within(sheet).getByRole("button", { name: "Abilita: E-commerce" }));
    await waitFor(() =>
      expect(writes()).toEqual([
        { url: expect.stringContaining("/v1/sources/ecommerce"), body: { allowedTypes: ["purchase.completed", "review.submitted", "store.visited"] } },
      ]),
    );
  });

  it("stato delle campagne che la usano scritto in italiano", async () => {
    mockApi(HTTP_ONLY);
    renderWithProviders(<ActionsPage />, "MARKETING");
    fireEvent.click(await screen.findByText("Visita in negozio"));
    const sheet = await screen.findByRole("dialog");
    expect(await within(sheet).findByText("(attiva)")).toBeInTheDocument();
  });

  it("dopo la creazione, se abilitare l'azione su una fonte non riesce, il dettaglio lo dice", async () => {
    const types: Record<string, unknown>[] = [...TYPES];
    mockApi(HTTP_ONLY, types, (url) => {
      if (url.includes("/v1/sources/")) return json({ code: "ERROR", detail: "Errore" }, 500);
      if (!url.includes("/v1/event-types")) return undefined;
      const created = { ...TYPES[1], code: "visita.fiera", name: "Visita in fiera" };
      types.push(created);
      return json(created, 201);
    });
    renderWithProviders(<ActionsPage />, "ADMIN");
    fireEvent.click(await screen.findByRole("button", { name: "Nuova azione" }));
    const editor = await screen.findByRole("dialog", { name: "Nuova azione" });
    fireEvent.change(within(editor).getByLabelText("Nome"), { target: { value: "Visita in fiera" } });
    fireEvent.change(within(editor).getByLabelText("Etichetta 1"), { target: { value: "Codice fiera" } });
    fireEvent.click(await within(editor).findByRole("checkbox", { name: "E-commerce" }));
    await waitFor(() => expect(within(editor).getByRole("button", { name: "Crea l'azione" })).not.toBeDisabled());
    fireEvent.click(within(editor).getByRole("button", { name: "Crea l'azione" }));
    expect(await screen.findByText(/Non è stato possibile abilitarla su: E-commerce/)).toHaveAttribute("role", "alert");
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

  it("la conferma dello spegnimento mette il focus su «Annulla»", async () => {
    mockApi();
    renderWithProviders(<ActionsPage />, "ADMIN");
    fireEvent.click(await screen.findByRole("switch", { name: "Fonte E-commerce accesa" }));
    expect(document.activeElement).toBe(screen.getByRole("button", { name: "Annulla" }));
  });

  it("volumi delle ultime 24 ore e ultimo evento per fonte", async () => {
    mockApi();
    renderWithProviders(<ActionsPage />, "MARKETING");
    expect(await screen.findByText("15 (12 accettate)")).toBeInTheDocument();
    expect(await screen.findByText("5 min fa")).toBeInTheDocument();
    expect(screen.getAllByText("nessuno").length).toBeGreaterThan(0);
    const counts = vi.mocked(fetch).mock.calls.map((c) => c[0].toString()).find((u) => u.includes("/v1/inbound-events/counts") && u.includes("source=ecommerce"));
    expect(counts).toMatch(/from=/);
  });

  it("le fonti interne accettano sempre tutto: niente «Modifica azioni ammesse»", async () => {
    mockApi();
    renderWithProviders(<ActionsPage />, "ADMIN");
    await screen.findByText("Negozio online");
    expect(screen.queryByRole("button", { name: "Modifica azioni ammesse: Simulatore demo" })).toBeNull();
    expect(screen.getByText(/Sempre tutte: il ponte interno e il simulatore non si limitano/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Modifica azioni ammesse: E-commerce" })).toBeInTheDocument();
  });

  it("azioni ammesse: le azioni generate dal programma non si offrono alle fonti esterne", async () => {
    mockApi(SOURCES, [...TYPES, INTERNAL_TYPE]);
    renderWithProviders(<ActionsPage />, "ADMIN");
    fireEvent.click(await screen.findByRole("button", { name: "Modifica azioni ammesse: E-commerce" }));
    const sheet = await screen.findByRole("dialog", { name: "Azioni ammesse da «E-commerce»" });
    expect(within(sheet).queryByRole("checkbox", { name: /Presentazione di un amico/ })).toBeNull();
    expect(within(sheet).getByRole("checkbox", { name: /Visita in negozio/ })).toBeInTheDocument();
  });

  it("azioni ammesse: se l'elenco è cambiato nel frattempo, lo dice e non sovrascrive senza conferma", async () => {
    fresh = { code: "ecommerce", name: "E-commerce", kind: "HTTP", enabled: true, allowedTypes: ["purchase.completed", "review.submitted"], description: null };
    mockApi();
    renderWithProviders(<ActionsPage />, "ADMIN");
    fireEvent.click(await screen.findByRole("button", { name: "Modifica azioni ammesse: E-commerce" }));
    const sheet = await screen.findByRole("dialog");
    fireEvent.click(within(sheet).getByRole("checkbox", { name: /Visita in negozio/ }));
    fireEvent.click(within(sheet).getByRole("button", { name: "Salva l'elenco" }));
    expect(await within(sheet).findByText(/L'elenco è cambiato mentre lo modificavi/)).toBeInTheDocument();
    expect(writes()).toEqual([]);
    fireEvent.click(within(sheet).getByRole("button", { name: "Ricarica l'elenco" }));
    expect(within(sheet).getByRole("checkbox", { name: /Visita in negozio/ })).not.toBeChecked();
    fireEvent.click(within(sheet).getByRole("checkbox", { name: /Visita in negozio/ }));
    fireEvent.click(within(sheet).getByRole("button", { name: "Salva l'elenco" }));
    await waitFor(() =>
      expect(writes()).toEqual([
        { url: expect.stringContaining("/v1/sources/ecommerce"), body: { allowedTypes: ["purchase.completed", "review.submitted", "store.visited"] } },
      ]),
    );
  });

  it("chiudere l'elenco modificato chiede conferma con una frase sull'elenco", async () => {
    mockApi();
    renderWithProviders(<ActionsPage />, "ADMIN");
    fireEvent.click(await screen.findByRole("button", { name: "Modifica azioni ammesse: E-commerce" }));
    const sheet = await screen.findByRole("dialog");
    fireEvent.click(within(sheet).getByRole("checkbox", { name: /Visita in negozio/ }));
    fireEvent.keyDown(window, { key: "Escape" });
    expect(screen.getByRole("alertdialog")).toHaveTextContent("Le modifiche all'elenco andranno perse.");
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

  it("nessuna fonte nel profilo enterprise: niente Console demo", async () => {
    mockApi([]);
    renderWithProviders(<ActionsPage />, "ADMIN", "enterprise");
    expect(await screen.findByText("Nessuna fonte")).toBeInTheDocument();
    expect(screen.getByText(/controlla l'installazione del servizio ingestion/)).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Apri la Console demo" })).toBeNull();
  });
});
