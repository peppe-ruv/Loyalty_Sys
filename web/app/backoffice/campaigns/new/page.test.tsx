import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, screen, within } from "@testing-library/react";
import NewCampaignPage from "./page";
import { renderWithProviders } from "@/test/test-utils";

// BO-06 Nuova campagna: `?trigger=` da BO-09 *Crea una campagna con questa azione* e avviso sull'Importo in
// «2 · Quando» (docs/08 §BO-06; Q-432).

let params = "";
vi.mock("next/navigation", () => ({
  useSearchParams: () => new URLSearchParams(params),
  usePathname: () => "/backoffice/campaigns/new",
  useRouter: () => ({ push: vi.fn(), back: vi.fn() }),
}));

const TYPES = [
  { code: "purchase.completed", name: "Acquisto completato", origin: "SYSTEM", category: "TRANSACTION", icon: "shopping-cart", enabled: true, dataSchema: { type: "object", properties: { amount: { type: "number" } } } },
  { code: "store.visited", name: "Visita in negozio", origin: "CUSTOM", category: "ENGAGEMENT", icon: "map-pin", enabled: true, dataSchema: { type: "object", properties: { storeId: { type: "string" } } } },
];

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}

beforeEach(() => {
  params = "";
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes("/fields")) return json(url.includes("store.visited") ? [{ path: "data.storeId", type: "string", required: true }] : [{ path: "data.amount", type: "number", required: true }]);
      if (url.includes("ingestion/v1/event-types")) return json(TYPES);
      if (url.includes("ingestion/v1/sources")) return json([]);
      if (url.includes("/v1/segments")) return json({ items: [], page: { number: 0, size: 100, totalElements: 0, totalPages: 0 } });
      return json([]);
    }),
  );
});
afterEach(() => vi.unstubAllGlobals());

const when = () => screen.getByRole("heading", { name: "2 · Quando" }).parentElement!;

describe("BO-06 nuova campagna", () => {
  it("senza ?trigger= parte da «Acquisto completato» e non avvisa sull'Importo", async () => {
    renderWithProviders(<NewCampaignPage />, "MARKETING");
    expect(await within(when()).findByRole("checkbox", { name: /Acquisto completato/ })).toHaveAttribute("aria-checked", "true");
    expect(screen.queryByText(/non ha un Importo/)).toBeNull();
  });

  it("?trigger=store.visited precompila il trigger e avvisa subito che la bozza usa l'Importo", async () => {
    params = "trigger=store.visited";
    renderWithProviders(<NewCampaignPage />, "MARKETING");
    expect(await within(when()).findByRole("checkbox", { name: /Visita in negozio/ })).toHaveAttribute("aria-checked", "true");
    const alert = (await screen.findByText(/«Visita in negozio» non ha un Importo/)).closest("[role=alert]")!;
    expect(alert).toHaveTextContent("sezione 4 · Se");
    expect(alert).toHaveTextContent("sezione 5 · Allora");

    // Il pulsante toglie solo la condizione d'esempio; resta il richiamo all'effetto «per importo».
    fireEvent.click(within(alert as HTMLElement).getByRole("button", { name: "Togli la condizione d'esempio" }));
    const after = (await screen.findByText(/non ha un Importo/)).closest("[role=alert]")!;
    expect(after).not.toHaveTextContent("sezione 4 · Se");
    expect(after).toHaveTextContent("sezione 5 · Allora");
    expect(within(after as HTMLElement).queryByRole("button", { name: "Togli la condizione d'esempio" })).toBeNull();

    // Con un effetto fisso l'avviso sparisce.
    const effects = screen.getAllByRole("textbox").find((el) => (el as HTMLTextAreaElement).value.includes("PER_AMOUNT"))!;
    fireEvent.change(effects, { target: { value: '[ { "type": "GRANT_POINTS", "currency": "PTS", "mode": "FIXED", "value": 10 } ]' } });
    expect(screen.queryByText(/non ha un Importo/)).toBeNull();
  });

  it("un ?trigger= che non è un codice d'azione si ignora", async () => {
    params = "trigger=Visita%20in%20negozio";
    renderWithProviders(<NewCampaignPage />, "MARKETING");
    expect(await within(when()).findByRole("checkbox", { name: /Acquisto completato/ })).toHaveAttribute("aria-checked", "true");
    expect(within(when()).getByRole("checkbox", { name: /Visita in negozio/ })).toHaveAttribute("aria-checked", "false");
  });
});
