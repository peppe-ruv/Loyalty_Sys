import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import type { ActionType } from "@/lib/actiontypes/types";
import { ActionTypeDetail } from "@/components/bo/actiontypes/ActionTypeDetail";
import { EventRail } from "@/components/observe/EventRail";

// V11 (ADR-051, docs/18 M8.14): i collegamenti al simulatore (BO-28) puntano a /v1/demo, che in enterprise non esiste:
// nel profilo enterprise «Prova» (BO-09), il collegamento nel feed vuoto (BO-24) non compaiono. In demo ci sono.
// (Il link «Prova nel simulatore» di TriggerPicker ha il suo test in TriggerPicker.test.tsx.)

vi.mock("next/navigation", () => ({ usePathname: () => "/backoffice", useRouter: () => ({ push: vi.fn() }), useSearchParams: () => new URLSearchParams() }));
vi.mock("@/lib/realtime/useLiveEvents", () => ({
  useLiveEvents: () => ({ events: [], state: "live", paused: false, pendingCount: 0, pause: vi.fn(), resume: vi.fn(), clear: vi.fn() }),
}));

beforeEach(() => {
  vi.stubGlobal("fetch", vi.fn(async () => new Response("[]", { status: 200, headers: { "content-type": "application/json" } })));
});
afterEach(() => vi.unstubAllGlobals());

const TYPE: ActionType = {
  code: "purchase.completed", name: "Acquisto completato", description: null, origin: "SYSTEM", category: "TRANSACTION", icon: "shopping-cart",
  enabled: true, dataSchema: { type: "object", properties: {} }, sampleData: null,
};

const simulatorLinks = () => screen.queryAllByRole("link").filter((a) => (a.getAttribute("href") ?? "").startsWith("/backoffice/demo"));

describe("BO-09 dettaglio azione: «Prova»", () => {
  it("demo: il collegamento al simulatore c'è", () => {
    renderWithProviders(<ActionTypeDetail type={TYPE} sources={[]} campaigns={[]} onEdit={() => undefined} />, "ADMIN", "demo");
    expect(simulatorLinks().map((a) => a.getAttribute("href"))).toEqual(["/backoffice/demo/simulator?type=purchase.completed"]);
  });

  it("enterprise: nessun collegamento al simulatore", () => {
    renderWithProviders(<ActionTypeDetail type={TYPE} sources={[]} campaigns={[]} onEdit={() => undefined} />, "ADMIN", "enterprise");
    expect(simulatorLinks()).toEqual([]);
  });
});

describe("BO-24 flusso live vuoto", () => {
  it("demo: invita a usare il simulatore, con il collegamento", () => {
    renderWithProviders(<EventRail />, "ADMIN", "demo");
    expect(screen.getByRole("link", { name: "simulatore" })).toHaveAttribute("href", "/backoffice/demo/simulator");
  });

  it("enterprise: nessun collegamento al simulatore, il testo rimanda al portale e all'Import", () => {
    renderWithProviders(<EventRail />, "ADMIN", "enterprise");
    expect(simulatorLinks()).toEqual([]);
    expect(screen.getByText(/dal portale o dalla pagina Import/)).toBeInTheDocument();
  });
});
