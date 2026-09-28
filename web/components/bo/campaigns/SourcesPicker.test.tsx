import { beforeEach, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

// Fonti ammesse (docs/08 §BO-06 «2 Quando», Q-208): multi-scelta da ingestion, nessuna scelta = tutte.
const useLhQuery = vi.fn();
vi.mock("@/lib/api/client", async (orig) => ({ ...(await orig<object>()), useLhQuery: (...a: unknown[]) => useLhQuery(...a) }));
const { SourcesPicker } = await import("./SourcesPicker");

const SOURCES = [
  { code: "ecommerce", name: "E-commerce", enabled: true },
  { code: "app", name: "App", enabled: true },
];

beforeEach(() => useLhQuery.mockReset());

it("nessuna scelta = tutte le fonti", () => {
  useLhQuery.mockReturnValue({ isLoading: false, isError: false, data: SOURCES, refetch: vi.fn() });
  render(<SourcesPicker value={[]} onChange={vi.fn()} />);
  expect(useLhQuery).toHaveBeenCalledWith("ingestion", "/v1/sources");
  expect(screen.getByText("Tutte le fonti (nessuna scelta).")).toBeInTheDocument();
});

it("clic su una fonte la aggiunge; clic di nuovo la toglie", () => {
  useLhQuery.mockReturnValue({ isLoading: false, isError: false, data: SOURCES, refetch: vi.fn() });
  const onChange = vi.fn();
  const { rerender } = render(<SourcesPicker value={[]} onChange={onChange} />);
  fireEvent.click(screen.getByRole("button", { name: "App" }));
  expect(onChange).toHaveBeenLastCalledWith(["app"]);
  rerender(<SourcesPicker value={["app"]} onChange={onChange} />);
  expect(screen.getByRole("button", { name: "App" })).toHaveAttribute("aria-pressed", "true");
  fireEvent.click(screen.getByRole("button", { name: "App" }));
  expect(onChange).toHaveBeenLastCalledWith([]);
});

it("avvisa quando una fonte scelta non accetta un trigger (Q-437)", () => {
  useLhQuery.mockReturnValue({
    isLoading: false,
    isError: false,
    data: [
      { code: "app", name: "App", enabled: true, allowedTypes: ["purchase.completed"] },
      { code: "simulator", name: "Simulatore", enabled: true, allowedTypes: [] },
    ],
    refetch: vi.fn(),
  });
  const { rerender } = render(
    <SourcesPicker
      value={["app"]}
      onChange={vi.fn()}
      triggers={["purchase.completed", "store.visited"]}
      actionLabels={{ "store.visited": "Visita in negozio" }}
    />,
  );
  // App accetta ancora gli acquisti: la campagna scatta da App, ma non per le visite.
  expect(screen.getByText("La fonte App non accetta «Visita in negozio»: per questa azione la campagna non scatterà da App.")).toBeInTheDocument();
  expect(screen.queryByText(/non scatterà mai/)).toBeNull();
  expect(screen.queryByText(/non accetta «purchase.completed»/)).toBeNull();
  rerender(<SourcesPicker value={["app"]} onChange={vi.fn()} triggers={["store.visited"]} />);
  expect(screen.getByText("La fonte App non accetta nessuna delle azioni scelte: da App questa campagna non scatterà mai.")).toBeInTheDocument();
  rerender(<SourcesPicker value={["simulator"]} onChange={vi.fn()} triggers={["store.visited"]} />);
  expect(screen.queryByText(/non accetta/)).toBeNull();
});

it("ingestion addormentato → campo di testo con i codici (degraded)", () => {
  useLhQuery.mockReturnValue({ isLoading: false, isError: true, error: new Error("down"), data: undefined, refetch: vi.fn() });
  const onChange = vi.fn();
  render(<SourcesPicker value={[]} onChange={onChange} />);
  fireEvent.change(screen.getByLabelText("Fonti ammesse (codici separati da virgola)"), { target: { value: "ecommerce, app" } });
  expect(onChange).toHaveBeenLastCalledWith(["ecommerce", "app"]);
});
