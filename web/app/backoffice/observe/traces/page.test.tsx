import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { PURCHASE_TRACE, TRACE_LIST } from "@/test/fixtures/traces";
import TracesPage from "./page";

// BO-25 (issue #204): elenco leggibile, stato mai troncato, eventi solo tecnici nascosti, deep-link ?c=.

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });

beforeEach(() => {
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.includes("/api/lh/insight/v1/traces/")) return json(PURCHASE_TRACE);
    if (url.includes("/api/lh/insight/v1/traces")) return json({ items: TRACE_LIST, page: { number: 0, size: 50, totalItems: 4, totalPages: 1 } });
    if (url.includes("/api/lh/member/v1/members"))
      return json({ items: [{ id: "MBR-000001", firstName: "Anna", lastName: "Rossi", status: "ACTIVE" }], page: { number: 0, size: 100, totalItems: 1, totalPages: 1 } });
    if (url.includes("/api/lh/wallet/v1/currencies")) return json([{ code: "PTS", name: "Punti" }, { code: "STS", name: "Punti status" }]);
    return json({ code: "NOT_FOUND" }, 404);
  }));
});
afterEach(() => {
  vi.unstubAllGlobals();
  window.history.replaceState(null, "", "/");
});

describe("BO-25 Tracciati, elenco", () => {
  it("mostra azione e stato in italiano, con chip di stato che non vanno mai a capo né si troncano", async () => {
    renderWithProviders(<TracesPage />);
    expect(await screen.findByText(/^Anna Rossi/)).toBeInTheDocument();
    expect(screen.getAllByText("Acquisto completato").length).toBeGreaterThan(0);
    const statuses = screen.getAllByTestId("trace-status");
    expect(statuses.map((s) => s.textContent)).toEqual(["Completato", "In corso", "Bloccato"]);
    for (const s of statuses) {
      // A 1280 px e a 390 px la chip resta intera: niente troncamento né a capo, e non si restringe nella riga.
      expect(s.className).toMatch(/\bwhitespace-nowrap\b/);
      expect(s.className).toMatch(/\bshrink-0\b/);
      expect(s.className).not.toMatch(/truncate|overflow-hidden|text-ellipsis/);
    }
    expect(screen.getByText("+100 punti")).toBeInTheDocument();
    expect(screen.getByText("+24 status")).toBeInTheDocument();
    expect(screen.getByText("in elaborazione")).toBeInTheDocument();
    expect(screen.getByText("nessun premio")).toBeInTheDocument();
    expect(screen.queryByText(/\bPTS\b|COMPLETE|DLQ/)).not.toBeInTheDocument();
  });

  it("nasconde di default gli eventi solo tecnici e li mostra togliendo l'interruttore", async () => {
    renderWithProviders(<TracesPage />);
    await screen.findByText(/^Anna Rossi/);
    const list = () => within(screen.getByRole("region", { name: "Elenco dei tracciati" }));
    expect(list().queryByText("Entrato in un segmento")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("checkbox", { name: "Nascondi eventi solo tecnici" }));
    expect(await list().findByText("Entrato in un segmento")).toBeInTheDocument();
    expect(screen.getByText("solo tecnico, nessun effetto")).toBeInTheDocument();
  });

  it("filtra per stato", async () => {
    renderWithProviders(<TracesPage />);
    await screen.findByText(/^Anna Rossi/);
    fireEvent.change(screen.getByRole("combobox", { name: "Stato" }), { target: { value: "FAILED" } });
    expect(screen.getAllByTestId("trace-status").map((s) => s.textContent)).toEqual(["Bloccato"]);
  });

  it("il deep-link ?c=<correlationId> preseleziona il tracciato", async () => {
    window.history.replaceState(null, "", `/backoffice/observe/traces?c=${PURCHASE_TRACE.correlationId}`);
    renderWithProviders(<TracesPage />);
    await waitFor(() =>
      expect(vi.mocked(fetch)).toHaveBeenCalledWith(
        expect.stringContaining(`/api/lh/insight/v1/traces/${PURCHASE_TRACE.correlationId}`),
        expect.anything(),
      ),
    );
    expect(await screen.findByText("Passo per passo")).toBeInTheDocument();
  });

  it("l'id di correlazione nel percorso del dettaglio è codificato", async () => {
    window.history.replaceState(null, "", "/backoffice/observe/traces?c=a%2Fb%20c");
    renderWithProviders(<TracesPage />);
    await waitFor(() =>
      expect(vi.mocked(fetch)).toHaveBeenCalledWith(expect.stringContaining("/api/lh/insight/v1/traces/a%2Fb%20c"), expect.anything()),
    );
  });
});
