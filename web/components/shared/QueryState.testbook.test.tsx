import { afterEach, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen } from "@testing-library/react";
import type { UseQueryResult } from "@tanstack/react-query";
import { LhError, lhFetch } from "@/lib/api/client";
import { QueryState } from "./QueryState";

// Testbook TB-WEB §QST: stati obbligatori di ogni vista con dati (docs/07 §6) nel componente condiviso QueryState
// (backoffice e portale).

type Q = UseQueryResult<string[], LhError>;
function q(over: Partial<Q>): Q {
  return { isLoading: false, isError: false, error: null, data: undefined, refetch: vi.fn(), ...over } as unknown as Q;
}
const view = (query: Q, isEmpty?: (d: string[]) => boolean) =>
  render(
    <QueryState query={query} service="wallet" isEmpty={isEmpty} emptyTitle="Nessuna campagna in bozza" emptyHint="Crea la prima campagna.">
      {(d) => <ul>{d.map((x) => <li key={x}>{x}</li>)}</ul>}
    </QueryState>,
  );

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

it("[TB-WEB-QST-001] loading → scheletro della forma finale, nessuno spinner, nessun contenuto", () => {
  const { container } = view(q({ isLoading: true }));
  expect(container.querySelectorAll(".animate-pulse").length).toBeGreaterThan(0);
  expect(container.querySelector(".animate-spin, [role='progressbar']")).toBeNull();
  expect(screen.queryByRole("list")).toBeNull();
});

it("[TB-WEB-QST-002] loading di una tabella → 8 righe scheletro", () => {
  const { container } = view(q({ isLoading: true }));
  expect(container.querySelectorAll(".animate-pulse")).toHaveLength(8);
});

it("[TB-WEB-QST-003] empty → frase che spiega perché è vuoto", () => {
  view(q({ data: [] }), (d) => d.length === 0);
  expect(screen.getByText("Nessuna campagna in bozza")).toBeInTheDocument();
  expect(screen.getByText("Crea la prima campagna.")).toBeInTheDocument();
});

it("[TB-WEB-QST-004] empty → azione primaria (es. «Crea la prima»)", () => {
  view(q({ data: [] }), (d) => d.length === 0);
  expect(screen.queryAllByRole("button").length + screen.queryAllByRole("link").length).toBeGreaterThan(0);
});

it("[TB-WEB-QST-005] dati presenti → contenuto", () => {
  view(q({ data: ["CMP-A", "CMP-B"] }), (d) => d.length === 0);
  expect(screen.getAllByRole("listitem")).toHaveLength(2);
});

it("[TB-WEB-QST-006] elenco vuoto senza regola di vuoto → contenuto (vuoto) invece dello stato empty", () => {
  // TESTBOOK: ambiguo, vedi TB-WEB-QST-006 — la vista decide cosa è "vuoto"; senza isEmpty non c'è stato empty.
  view(q({ data: [] }));
  expect(screen.getByRole("list")).toBeInTheDocument();
  expect(screen.queryByText("Nessuna campagna in bozza")).toBeNull();
});

it("[TB-WEB-QST-007] error → dettaglio del problema e «Riprova» che rilancia la query", () => {
  const refetch = vi.fn();
  view(q({ isError: true, error: new LhError(409, "VERSION_CONFLICT", "Versione superata", false), refetch } as Partial<Q>));
  expect(screen.getByText("Versione superata")).toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "Riprova" }));
  expect(refetch).toHaveBeenCalledTimes(1);
});

async function problemError(): Promise<LhError> {
  const problem = { type: "about:blank", title: "Nota troppo corta", status: 422, detail: "La nota deve avere almeno 10 caratteri", code: "NOTE_TOO_SHORT", correlationId: "01JCORR0000000000000000000" };
  vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify(problem), { status: 422, headers: { "content-type": "application/problem+json", "x-correlation-id": problem.correlationId } })));
  return lhFetch("wallet", "/v1/x").then(
    () => {
      throw new Error("atteso un errore");
    },
    (e: LhError) => e,
  );
}

it("[TB-WEB-QST-008] error → «title» del problema RFC 9457", async () => {
  const error = await problemError();
  view(q({ isError: true, error }));
  expect(screen.getByText(/Nota troppo corta/)).toBeInTheDocument();
});

it("[TB-WEB-QST-009] error → correlationId mostrato (copiabile)", async () => {
  const error = await problemError();
  view(q({ isError: true, error }));
  expect(document.body.textContent).toContain("01JCORR0000000000000000000");
});

it("[TB-WEB-QST-010] degraded (SERVICE_ASLEEP) → riquadro ambra «Il servizio wallet si sta svegliando…»", () => {
  const { container } = view(q({ isError: true, error: new LhError(503, "SERVICE_ASLEEP", "", true) }));
  expect(container.innerHTML).toMatch(/amber/);
  expect(document.body.textContent).toMatch(/Il servizio .?wallet.? si sta svegliando/);
});

it("[TB-WEB-QST-011] degraded → riprova automatica ogni 5 s fino a 90 s", () => {
  vi.useFakeTimers();
  const refetch = vi.fn();
  view(q({ isError: true, error: new LhError(503, "SERVICE_ASLEEP", "", true), refetch } as Partial<Q>));
  act(() => {
    vi.advanceTimersByTime(5_000);
  });
  expect(refetch).toHaveBeenCalledTimes(1);
  act(() => {
    vi.advanceTimersByTime(85_000);
  });
  expect(refetch).toHaveBeenCalledTimes(18);
  act(() => {
    vi.advanceTimersByTime(30_000);
  });
  expect(refetch).toHaveBeenCalledTimes(18);
});

it("[TB-WEB-QST-012] degraded → «Riprova» manuale disponibile", () => {
  const refetch = vi.fn();
  view(q({ isError: true, error: new LhError(503, "SERVICE_ASLEEP", "", true), refetch } as Partial<Q>));
  fireEvent.click(screen.getByRole("button", { name: "Riprova" }));
  expect(refetch).toHaveBeenCalledTimes(1);
});
