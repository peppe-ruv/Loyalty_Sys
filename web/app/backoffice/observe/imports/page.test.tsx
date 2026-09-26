import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, screen, waitFor } from "@testing-library/react";
import ImportsPage from "./page";
import { renderWithProviders } from "@/test/test-utils";

// BO-32 Import (docs/08 §BO-32; F2-ING-02, M8.7): elenco, stati della vista, caricamento multipart, dettaglio con
// rapporto e «Riprova non abbinati».

let params = new URLSearchParams();
const replace = vi.fn();
vi.mock("next/navigation", () => ({
  useSearchParams: () => params,
  usePathname: () => "/backoffice/observe/imports",
  useRouter: () => ({ replace }),
}));

const JOB = {
  id: "01JIMPORT0000000000000001",
  kind: "EVENTS",
  format: "CSV",
  fileName: "ordini-ecommerce-ieri.csv",
  sizeBytes: 1620,
  sha256: "x",
  defaultSource: "ecommerce",
  status: "DONE",
  rowsTotal: 9,
  rowsDone: 9,
  counts: { accepted: 1, duplicate: 1, rejected: 4, unmatched: 2, invalid: 1 },
  attempts: 1,
  createdBy: "ADMIN:marta.admin",
  createdAt: "2026-09-24T08:18:00Z",
  finishedAt: "2026-09-24T08:18:09Z",
};
const PAGE = { items: [JOB], page: { number: 0, size: 20, totalItems: 1, totalPages: 1 } };
const ROWS = {
  items: [
    { rowNumber: 7, eventId: "hist-ecom-0007", outcome: "UNMATCHED", detail: "Membro non trovato", inboundEventId: "01INB7", currentStatus: "UNMATCHED" },
    { rowNumber: 9, eventId: null, outcome: "INVALID", detail: "data.amount: atteso un numero", inboundEventId: null, currentStatus: null },
  ],
  page: { number: 0, size: 50, totalItems: 2, totalPages: 1 },
};
const SOURCES = [{ code: "ecommerce", name: "E-commerce", enabled: true }];

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}

function route(overrides: (url: string, init?: RequestInit) => Response | undefined = () => undefined) {
  vi.mocked(fetch).mockImplementation(async (input, init) => {
    const u = input.toString();
    const o = overrides(u, init);
    if (o) return o;
    if (u.includes("/v1/sources")) return json(SOURCES);
    if (u.includes("/retry-unmatched")) return json({ retried: 2, accepted: 1, stillUnmatched: 1, rejected: 0 });
    if (u.includes("/rows")) return json(ROWS);
    if (u.includes(`/v1/imports/${JOB.id}`)) return json({ job: JOB, openUnmatched: 2 });
    if (u.includes("/v1/imports")) return json(PAGE);
    return json([]);
  });
}

describe("BO-32 Import", () => {
  beforeEach(() => {
    params = new URLSearchParams();
    replace.mockReset();
    vi.stubGlobal("fetch", vi.fn());
  });
  afterEach(() => vi.unstubAllGlobals());

  it("elenca i lavori con stato, avanzamento ed esiti", async () => {
    route();
    renderWithProviders(<ImportsPage />, "ANALYST");
    expect(await screen.findByText("ordini-ecommerce-ieri.csv")).toBeInTheDocument();
    expect(screen.getByText("Completato")).toBeInTheDocument();
    expect(screen.getByText("9 / 9 righe")).toBeInTheDocument();
    expect(screen.getByRole("progressbar")).toHaveAttribute("aria-valuenow", "100");
    expect(screen.getByText("respinte", { exact: false })).toBeInTheDocument();
  });

  it("vuoto: invito a caricare il primo file", async () => {
    route((u) => (u.includes("/v1/imports") ? json({ items: [], page: { number: 0, size: 20, totalItems: 0, totalPages: 0 } }) : undefined));
    renderWithProviders(<ImportsPage />);
    expect(await screen.findByText("Nessun import")).toBeInTheDocument();
  });

  it("servizio addormentato: stato degraded", async () => {
    route((u) => (u.includes("/v1/imports") ? json({ type: "SERVICE_ASLEEP", service: "ingestion" }, 503) : undefined));
    renderWithProviders(<ImportsPage />);
    expect(await screen.findByText(/si sta svegliando/)).toBeInTheDocument();
  });

  it("errore del servizio: riquadro con il titolo del problema", async () => {
    route((u) =>
      u.includes("/v1/imports") ? json({ title: "Errore interno", detail: "Si è verificato un errore imprevisto", code: "INTERNAL_ERROR" }, 500) : undefined,
    );
    renderWithProviders(<ImportsPage />);
    expect(await screen.findByRole("alert")).toHaveTextContent("Errore interno");
  });

  it("ADMIN carica un file: multipart con Idempotency-Key, senza content-type JSON, poi apre il lavoro", async () => {
    route((u, init) => (u.includes("/v1/imports") && init?.method === "POST" ? json({ ...JOB, status: "QUEUED", rowsDone: 0 }, 202) : undefined));
    renderWithProviders(<ImportsPage />, "ADMIN");
    await screen.findByText("ordini-ecommerce-ieri.csv");
    await screen.findByRole("option", { name: /E-commerce/ });
    fireEvent.change(screen.getByLabelText("Fonte predefinita"), { target: { value: "ecommerce" } });
    const file = new File(["id,type,subject,time\n"], "ordini.csv", { type: "text/csv" });
    fireEvent.change(screen.getByLabelText("File da importare"), { target: { files: [file] } });
    fireEvent.click(screen.getByRole("button", { name: "Carica" }));

    await waitFor(() => expect(replace).toHaveBeenCalledWith(`/backoffice/observe/imports?i=${JOB.id}`, { scroll: false }));
    const post = vi.mocked(fetch).mock.calls.find((c) => c[1]?.method === "POST");
    expect(post![0].toString()).toBe("/api/lh/ingestion/v1/imports");
    const body = post![1]!.body as FormData;
    expect(body).toBeInstanceOf(FormData);
    expect((body.get("file") as File).name).toBe("ordini.csv");
    expect(body.get("source")).toBe("ecommerce");
    const headers = post![1]!.headers as Record<string, string>;
    expect(headers["idempotency-key"]).toMatch(/^bo32-/);
    expect(headers["content-type"]).toBeUndefined();
  });

  it("un file non ammesso è fermato prima dell'invio", async () => {
    route();
    renderWithProviders(<ImportsPage />, "CARE");
    await screen.findByText("ordini-ecommerce-ieri.csv");
    fireEvent.change(screen.getByLabelText("File da importare"), {
      target: { files: [new File(["x"], "ordini.xlsx")] },
    });
    expect(await screen.findByRole("alert")).toHaveTextContent("Formato non ammesso");
    fireEvent.click(screen.getByRole("button", { name: "Carica" }));
    expect(vi.mocked(fetch).mock.calls.some((c) => c[1]?.method === "POST")).toBe(false);
  });

  it("errore 422 del servizio mostrato sotto il modulo", async () => {
    route((u, init) =>
      init?.method === "POST" ? json({ code: "IMPORT_TOO_MANY_ROWS", detail: "Al massimo 10000 righe per import." }, 422) : undefined,
    );
    renderWithProviders(<ImportsPage />, "ADMIN");
    await screen.findByText("ordini-ecommerce-ieri.csv");
    fireEvent.change(screen.getByLabelText("File da importare"), { target: { files: [new File(["id\n"], "grande.csv")] } });
    fireEvent.click(screen.getByRole("button", { name: "Carica" }));
    expect(await screen.findByText("Al massimo 10000 righe per import.")).toBeInTheDocument();
  });

  it("ANALYST vede il caricamento disabilitato", async () => {
    route();
    renderWithProviders(<ImportsPage />, "ANALYST");
    await screen.findByText("ordini-ecommerce-ieri.csv");
    expect(screen.getByRole("button", { name: "Carica" })).toBeDisabled();
  });

  it("dettaglio: esiti, righe non accettate col collegamento al monitor, rapporto CSV e Riprova non abbinati", async () => {
    params = new URLSearchParams(`i=${JOB.id}`);
    route();
    renderWithProviders(<ImportsPage />, "CARE");
    expect(await screen.findByText("data.amount: atteso un numero")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Monitor →" })).toHaveAttribute("href", "/backoffice/observe/inbound?e=01INB7");
    expect(screen.getByRole("link", { name: "Scarica rapporto CSV" })).toHaveAttribute(
      "href",
      `/api/lh/ingestion/v1/imports/${JOB.id}/report.csv`,
    );
    expect(screen.getByText(/2 righe sono ancora da abbinare/)).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Riprova non abbinati" }));
    expect(await screen.findByText("Riprovate 2 righe: 1 accettate, 1 ancora non abbinate.")).toBeInTheDocument();
    const retry = vi.mocked(fetch).mock.calls.find((c) => c[0].toString().includes("/retry-unmatched"));
    expect(retry![1]?.method).toBe("POST");

    fireEvent.click(screen.getByRole("tab", { name: "Non valide" }));
    await waitFor(() =>
      expect(vi.mocked(fetch).mock.calls.some((c) => c[0].toString().includes("/rows?outcome=INVALID"))).toBe(true),
    );
  });
});
