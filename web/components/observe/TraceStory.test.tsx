import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { FAILED_TRACE, PURCHASE_TRACE } from "@/test/fixtures/traces";
import { TraceStoryView } from "./TraceStory";
import { TraceWaterfall } from "./TraceWaterfall";

// BO-25 (issue #204): racconto, «Perché» con campaign giù, cascata senza sovrapposizioni, passo bloccato → DLQ.

type Route = (url: string) => Response | undefined;
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });

function routeFetch(...routes: Route[]) {
  vi.mocked(fetch).mockImplementation(async (input) => {
    const url = String(input);
    for (const r of routes) {
      const res = r(url);
      if (res) return res;
    }
    return json({ code: "NOT_FOUND" }, 404);
  });
}

const currencies: Route = (u) =>
  u.includes("/api/lh/wallet/v1/currencies") ? json([{ code: "PTS", name: "Punti" }, { code: "STS", name: "Punti status" }]) : undefined;
const evaluations: Route = (u) =>
  u.includes("/api/lh/campaign/v1/evaluations/e01")
    ? json([
        { campaignCode: "CMP-PURCHASE-BASE", campaignName: "Punti sugli acquisti", matched: true, reason: null, failedConditions: [], effects: [{ type: "POINTS", currency: "PTS", amount: 100 }] },
        { campaignCode: "CMP-WEEKEND-X2", campaignName: "Weekend a punti doppi", matched: false, reason: "CONDITION", failedConditions: [{}], effects: [] },
      ])
    : undefined;

beforeEach(() => vi.stubGlobal("fetch", vi.fn()));
afterEach(() => vi.unstubAllGlobals());

describe("TraceStoryView", () => {
  it("racconta il giro in italiano con durata in secondi e le quattro fasi", async () => {
    routeFetch(currencies, evaluations);
    renderWithProviders(<TraceStoryView trace={PURCHASE_TRACE} memberName="Anna Rossi" />);
    expect(screen.getByRole("heading", { level: 2 })).toHaveTextContent(/^Anna Rossi ha completato un acquisto da €\s24,90$/);
    expect(screen.getByText(/elaborato in 6,4 secondi/)).toBeInTheDocument();
    expect(screen.getByText("Completato")).toBeInTheDocument();
    for (const phase of ["Azione ricevuta", "Regole controllate", "Effetti decisi e registrati", "Obiettivi, badge e messaggi"]) {
      expect(screen.getByText(phase)).toBeInTheDocument();
    }
    expect(screen.getByText(/Obiettivo avanzato ×3/)).toBeInTheDocument();
    expect(screen.getByText("Ha innescato a sua volta:")).toBeInTheDocument();
    expect(screen.queryByText(/ms\b/)).not.toBeInTheDocument();
    expect(await screen.findByText("Punti sugli acquisti")).toBeInTheDocument();
    expect(screen.getByText("Weekend a punti doppi")).toBeInTheDocument();
    expect(screen.getByText("Condizione non soddisfatta: 1 condizione non soddisfatta")).toBeInTheDocument();
    expect(await screen.findByText("Punti status")).toBeInTheDocument();
  });

  it("con il servizio campagne non raggiungibile il «Perché» degrada da solo e il resto resta usabile", async () => {
    routeFetch(currencies, (u) =>
      u.includes("/api/lh/campaign/") ? json({ type: "SERVICE_ASLEEP", detail: "campaign dorme" }, 503) : undefined,
    );
    renderWithProviders(<TraceStoryView trace={PURCHASE_TRACE} memberName="Anna Rossi" />);
    const why = await screen.findAllByTestId("trace-why");
    await waitFor(() => expect(within(why[0]).getByText(/Il servizio campaign si sta svegliando/)).toBeInTheDocument());
    expect(screen.getByText("Passo per passo")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Dettaglio tecnico/ })).toBeEnabled();
  });

  it("un tracciato FAILED evidenzia il passo bloccato e porta alla DLQ", () => {
    routeFetch(currencies);
    renderWithProviders(<TraceStoryView trace={FAILED_TRACE} memberName="Francesca Romano" />);
    expect(screen.getAllByText("Bloccato").length).toBeGreaterThan(0);
    const step = screen.getByTestId("trace-step-failed");
    expect(step).toHaveTextContent("Punti da accreditare");
    expect(within(step).getByRole("link", { name: /Apri in DLQ/ })).toHaveAttribute("href", "/backoffice/observe/dlq?status=ALL&e=dlq-77");
    expect(screen.getByText(/fermo dopo 1,2 secondi/)).toBeInTheDocument();
  });

  it("il dettaglio tecnico è ripiegato; il clic su un marcatore apre il payload dell'evento", async () => {
    routeFetch(currencies, evaluations, (u) =>
      u.includes("/api/lh/insight/v1/events/e05") ? json({ eventId: "e05", payload: { data: { currency: "PTS", amount: 100 } } }) : undefined,
    );
    renderWithProviders(<TraceStoryView trace={PURCHASE_TRACE} memberName="Anna Rossi" />);
    const toggle = screen.getByRole("button", { name: /Dettaglio tecnico/ });
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryAllByTestId("trace-marker")).toHaveLength(0);
    fireEvent.click(toggle);
    fireEvent.click(screen.getByRole("button", { name: /Punti accreditati \(wallet\.points\.earned\), 1,6 s/ }));
    const payload = await screen.findByTestId("trace-payload");
    await waitFor(() => expect(within(payload).getByLabelText("Contenuto dell'evento")).toHaveTextContent('"amount": 100'));
    expect(vi.mocked(fetch)).toHaveBeenCalledWith(expect.stringContaining("/api/lh/insight/v1/events/e05"), expect.anything());
  });
});

describe("TraceWaterfall", () => {
  it("con 16 nodi in 6,4 s ogni marcatore ha larghezza fissa e nessun testo inline", () => {
    renderWithProviders(<TraceWaterfall trace={PURCHASE_TRACE} />);
    const markers = screen.getAllByTestId("trace-marker");
    expect(markers).toHaveLength(16);
    for (const m of markers) {
      expect(m.className).toContain("size-3.5");
      expect(m.className).not.toMatch(/whitespace-nowrap|px-/);
      expect(m.textContent).toBe("");
      expect(m).toHaveAccessibleName(/\(.+\), \d+,\d s$/);
    }
  });
});
