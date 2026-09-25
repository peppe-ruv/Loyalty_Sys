import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, screen, waitFor } from "@testing-library/react";
import ActionsPage from "./page";
import { renderWithProviders } from "../../../../test/test-utils";

// BO-09 scheda `sources` (docs/08, F-ING-05): interruttore della fonte → ingestion PUT /v1/sources/{code} {enabled}.
vi.mock("next/navigation", () => ({
  useSearchParams: () => new URLSearchParams("tab=sources"),
  usePathname: () => "/backoffice/program/actions",
  useRouter: () => ({}),
}));

const SOURCES = [
  { code: "ecommerce", name: "E-commerce", kind: "HTTP", enabled: true, allowedTypes: ["purchase.completed"], description: null },
  { code: "partner", name: "Partner", kind: "HTTP", enabled: false, allowedTypes: [], description: null },
];

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}

describe("BO-09 fonti", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("ADMIN spegne una fonte accesa con PUT {enabled:false}", async () => {
    const fetchMock = vi.mocked(fetch);
    fetchMock.mockImplementation(async (url, init) => {
      const u = url.toString();
      if (u.includes("/v1/sources/ecommerce") && init?.method === "PUT") return json({ ...SOURCES[0], enabled: false });
      if (u.includes("/v1/sources")) return json(SOURCES);
      return json([]);
    });
    renderWithProviders(<ActionsPage />, "ADMIN");

    fireEvent.click(await screen.findByRole("button", { name: "Accesa" }));
    await waitFor(() => {
      const put = fetchMock.mock.calls.find((c) => c[0].toString().includes("/v1/sources/ecommerce") && c[1]?.method === "PUT");
      expect(put).toBeDefined();
      expect(JSON.parse(put![1]!.body as string)).toEqual({ enabled: false });
    });
  });

  it("MARKETING vede l'interruttore disabilitato con il ruolo richiesto", async () => {
    vi.mocked(fetch).mockImplementation(async () => json(SOURCES));
    renderWithProviders(<ActionsPage />, "MARKETING");
    const button = await screen.findByRole("button", { name: "Accesa" });
    expect(button.closest("span[title]")?.getAttribute("title")).toMatch(/ADMIN/);
  });

  it("errore del servizio mostrato sopra la tabella", async () => {
    vi.mocked(fetch).mockImplementation(async (url, init) =>
      init?.method === "PUT" ? json({ code: "SOURCE_INVALID", detail: "Indicare enabled e/o allowedTypes." }, 422) : json(SOURCES),
    );
    renderWithProviders(<ActionsPage />, "ADMIN");
    fireEvent.click(await screen.findByRole("button", { name: "Spenta" }));
    expect(await screen.findByText("Indicare enabled e/o allowedTypes.")).toBeInTheDocument();
  });
});
