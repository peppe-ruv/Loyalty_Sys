import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { PortalShell } from "./PortalShell";

// V11 (docs/18, ADR-051, regola 6-bis): il pannello demo del portale (cambio membro, azioni simulate) chiama /v1/demo,
// che in enterprise non esiste: con `demo={false}` (profilo enterprise, deciso dal layout) non compare.

vi.mock("next/navigation", () => ({ usePathname: () => "/portal", useRouter: () => ({ push: vi.fn() }) }));

beforeEach(() => vi.stubGlobal("fetch", vi.fn(() => new Promise<Response>(() => {}))));
afterEach(() => vi.unstubAllGlobals());

function renderShell(demo?: boolean) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <PortalShell demo={demo}>
        <p>contenuto</p>
      </PortalShell>
    </QueryClientProvider>,
  );
}

describe("pannello demo del portale", () => {
  it("profilo demo (predefinito): il pulsante «Demo» c'è", () => {
    renderShell();
    expect(screen.getByRole("button", { name: "Demo" })).toBeInTheDocument();
  });

  it("profilo enterprise (demo=false): nessun pulsante «Demo», nessuna chiamata a /v1/demo", () => {
    renderShell(false);
    expect(screen.queryByRole("button", { name: "Demo" })).toBeNull();
    expect(screen.getByText("contenuto")).toBeInTheDocument();
    const urls = (fetch as unknown as { mock: { calls: unknown[][] } }).mock.calls.map((c) => String(c[0]));
    expect(urls.filter((u) => u.includes("/v1/demo"))).toEqual([]);
  });
});
