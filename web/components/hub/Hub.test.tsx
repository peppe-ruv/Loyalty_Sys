import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { DemoStatus, ServiceState } from "@/lib/api/status";
import { Entrances } from "./Entrances";
import { StatusPanel } from "./StatusPanel";

// HUB-01 con le API simulate: /api/demo/status, /api/demo/wake, /api/persona, member /v1/demo/personas.

const ALL = ["ingestion", "member", "campaign", "wallet", "reward", "gamification", "engagement", "insight"];

let upCodes: string[] = [];
let kafka: ServiceState = "UP";
let checkedAt = "2026-09-24T10:00:00.000Z";
let personas: unknown[] = [];
let membersAsleep = false;
let calls: { url: string; method: string; body?: string }[] = [];

function demoStatus(): DemoStatus {
  return {
    services: ALL.map((code) => ({
      code, name: code[0].toUpperCase() + code.slice(1), state: upCodes.includes(code) ? "UP" : "SLEEPING",
      latencyMs: upCodes.includes(code) ? 42 : null,
    })),
    kafka: { state: kafka },
    db: { state: "UP" },
    readyCount: upCodes.length + (kafka === "UP" ? 1 : 0) + 1,
    totalCount: 10,
    checkedAt,
  };
}

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}

beforeEach(() => {
  upCodes = [];
  kafka = "UP";
  checkedAt = "2026-09-24T10:00:00.000Z";
  membersAsleep = false;
  personas = [
    { memberId: "MBR-000002", name: "Giulia Ferri", tier: "SILVER", story: "Vicina al GOLD", avatarSeed: "g", balancePts: 1850 },
    { memberId: "MBR-000003", name: "Marco Neri", tier: "SILVER", story: null, avatarSeed: "m", balancePts: 3240 },
  ];
  calls = [];
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    calls.push({ url, method: init?.method ?? "GET", body: init?.body as string | undefined });
    if (url.startsWith("/api/demo/status")) return json(demoStatus());
    if (url.startsWith("/api/demo/wake")) return json({ woken: ALL }, 202);
    if (url.startsWith("/api/persona")) return json({ ok: true });
    if (url.startsWith("/api/lh/member/v1/demo/personas")) {
      return membersAsleep ? json({ type: "SERVICE_ASLEEP", service: "member" }, 503) : json(personas);
    }
    return json({}, 404);
  }));
  Object.defineProperty(window, "location", { configurable: true, value: { ...window.location, assign: vi.fn() } });
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function renderWith(ui: React.ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>);
}

describe("HUB-01 ingressi", () => {
  it("con i servizi core non tutti UP le schede persona sono disabilitate e i membri non si caricano", async () => {
    upCodes = ["ingestion", "member", "campaign"]; // manca wallet
    renderWith(<Entrances />);
    await waitFor(() => expect(calls.some((c) => c.url.startsWith("/api/demo/status"))).toBe(true));
    const marta = await screen.findByRole("button", { name: /Marta Villa/ });
    expect(marta).toBeDisabled();
    expect(screen.getAllByText(/Gli ingressi si attivano/).length).toBeGreaterThan(0);
    expect(calls.some((c) => c.url.includes("/demo/personas"))).toBe(false);
  });

  it("con ingestion, member, campaign e wallet UP: 5 persone e le schede membro con tier, saldo e storia", async () => {
    upCodes = ["ingestion", "member", "campaign", "wallet"];
    renderWith(<Entrances />);
    await waitFor(() => expect(screen.getByRole("button", { name: /Marta Villa/ })).toBeEnabled());
    expect(screen.getAllByRole("button", { name: /ADMIN|MARKETING|LEGAL|CARE|ANALYST/ })).toHaveLength(5);
    const giulia = await screen.findByRole("button", { name: /Giulia Ferri/ });
    expect(giulia).toHaveTextContent("SILVER");
    expect(giulia).toHaveTextContent("1.850 punti");
    expect(giulia).toHaveTextContent("Vicina al GOLD");
  });

  it("scegliere una persona scrive il cookie e apre l'area", async () => {
    upCodes = ["ingestion", "member", "campaign", "wallet"];
    renderWith(<Entrances />);
    const elena = await screen.findByRole("button", { name: /Elena Riva/ });
    await waitFor(() => expect(elena).toBeEnabled());
    fireEvent.click(elena);
    await waitFor(() => expect(window.location.assign).toHaveBeenCalledWith("/backoffice"));
    const post = calls.find((c) => c.url === "/api/persona");
    expect(JSON.parse(post!.body!)).toEqual({ kind: "BO", username: "elena.legal" });

    fireEvent.click(await screen.findByRole("button", { name: /Marco Neri/ }));
    await waitFor(() => expect(window.location.assign).toHaveBeenCalledWith("/portal"));
    expect(JSON.parse(calls.filter((c) => c.url === "/api/persona")[1].body!)).toEqual({ kind: "MEMBER", memberId: "MBR-000003" });
  });

  it("stati vuoto e degradato dell'elenco membri", async () => {
    upCodes = ["ingestion", "member", "campaign", "wallet"];
    personas = [];
    const { unmount } = renderWith(<Entrances />);
    expect(await screen.findByText("Nessun membro selezionabile")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Console demo/ })).toHaveAttribute("href", "/backoffice/demo/console");
    unmount();

    membersAsleep = true;
    renderWith(<Entrances />);
    expect(await screen.findByText(/Servizio «member» non raggiungibile/)).toBeInTheDocument();
  });
});

describe("HUB-01 pannello stato", () => {
  it("mostra le 10 tessere, 'pronti N/10' e i tempi di risposta", async () => {
    upCodes = ["ingestion", "member", "campaign", "wallet"];
    renderWith(<StatusPanel />);
    expect(await screen.findByText("pronti 6/10")).toBeInTheDocument();
    expect(document.querySelectorAll("[data-testid^='tile-']")).toHaveLength(10);
    expect(screen.getByTestId("tile-reward")).toHaveAttribute("data-state", "SLEEPING");
    expect(screen.getByTestId("tile-wallet")).toHaveTextContent("42 ms");
  });

  it("'Accendi la demo' chiama wake, le tessere non UP diventano WAKING e compare il tempo trascorso", async () => {
    renderWith(<StatusPanel />);
    fireEvent.click(await screen.findByRole("button", { name: /Accendi la demo/ }));
    await waitFor(() => expect(calls.some((c) => c.url === "/api/demo/wake" && c.method === "POST")).toBe(true));
    await waitFor(() => expect(screen.getByTestId("tile-reward")).toHaveAttribute("data-state", "WAKING"));
    expect(screen.getByTestId("elapsed")).toHaveTextContent(/tempo trascorso 0:0\d/);
    expect(screen.getByText(/Il primo avvio richiede 1–3 minuti/)).toBeInTheDocument();
  });

  it("errore nel leggere lo stato: riquadro con Riprova", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => json({}, 500)));
    renderWith(<StatusPanel />);
    expect(await screen.findByText("Impossibile leggere lo stato dei servizi.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Riprova/ })).toBeInTheDocument();
  });
});
