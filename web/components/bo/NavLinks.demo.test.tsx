import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { PersonaProvider, type BoPersona } from "./PersonaContext";
import { NavLinks } from "./NavLinks";

// V11 (docs/18, ADR-051): le voci «Demo» (Simulatore eventi, Scenari, Console demo) chiamano /v1/demo, che in
// enterprise non esiste: nel profilo enterprise non compaiono né nella sidebar né nel cassetto mobile (stessa NavLinks).

vi.mock("next/navigation", () => ({ usePathname: () => "/backoffice", useRouter: () => ({ push: vi.fn() }) }));

beforeEach(() => vi.stubGlobal("fetch", vi.fn(() => new Promise<Response>(() => {}))));
afterEach(() => vi.unstubAllGlobals());

function renderAs(persona: Partial<BoPersona>) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <PersonaProvider value={{ username: "u", displayName: "U", role: "ADMIN", ...persona }}>
        <NavLinks />
      </PersonaProvider>
    </QueryClientProvider>,
  );
}

const DEMO_LABELS = ["Simulatore eventi", "Scenari", "Console demo"];

describe("menu Demo del backoffice", () => {
  it("profilo demo: gruppo e tre voci presenti", () => {
    renderAs({ mode: "demo" });
    expect(screen.getByText("Demo")).toBeInTheDocument();
    for (const l of DEMO_LABELS) expect(screen.getByRole("link", { name: l })).toBeInTheDocument();
  });

  it("senza profilo dichiarato (valore predefinito: demo): presente", () => {
    renderAs({});
    expect(screen.getByRole("link", { name: "Console demo" })).toBeInTheDocument();
  });

  it.each(["ADMIN", "CARE", "MARKETING", "LEGAL", "ANALYST"] as const)("enterprise, ruolo %s: nessuna voce Demo e nessun collegamento a /backoffice/demo", (role) => {
    renderAs({ mode: "enterprise", role });
    expect(screen.queryByText("Demo")).toBeNull();
    for (const l of DEMO_LABELS) expect(screen.queryByRole("link", { name: l })).toBeNull();
    expect(screen.getAllByRole("link").filter((a) => (a.getAttribute("href") ?? "").startsWith("/backoffice/demo"))).toEqual([]);
    // Le altre voci ci sono ancora.
    expect(screen.getByRole("link", { name: "Import" })).toBeInTheDocument();
  });
});
