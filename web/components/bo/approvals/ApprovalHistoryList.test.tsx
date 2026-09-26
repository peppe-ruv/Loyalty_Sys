import { beforeEach, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import type { ApprovalHistoryRow } from "@/lib/approvals/types";

// Storico delle transizioni (docs/03 §3.6, F-APR-01): stesso componente per BO-21 e per l'editor dei contenuti (BO-18).
const useLhQuery = vi.fn();
vi.mock("@/lib/api/client", async (orig) => ({ ...(await orig<object>()), useLhQuery: (...a: unknown[]) => useLhQuery(...a) }));

const { ApprovalHistoryList } = await import("./ApprovalHistoryList");

const ROWS: ApprovalHistoryRow[] = [
  { id: "h2", entityType: "CONTENT", entityId: "c1", fromStatus: "LIVE", toStatus: "PAUSED", action: "PAUSE",
    actor: "MARKETING:giulia", comment: null, createdAt: "2026-09-26T08:30:00Z" },
  { id: "h1", entityType: "CONTENT", entityId: "c1", fromStatus: "DRAFT", toStatus: "LIVE", action: "PUBLISH",
    actor: "MARKETING:giulia", comment: "Via al banner d'autunno", createdAt: "2026-09-25T09:00:00Z" },
];

beforeEach(() => useLhQuery.mockReset());

it("contenuto (BO-18): legge lo storico da engagement /v1/contents/{id}/approval-history", () => {
  useLhQuery.mockReturnValue({ isLoading: false, isError: false, data: ROWS, refetch: vi.fn() });
  render(<ApprovalHistoryList entityType="CONTENT" id="c1" />);
  expect(useLhQuery).toHaveBeenCalledWith("engagement", "/v1/contents/c1/approval-history");
  expect(screen.getByText("Messo in pausa")).toBeInTheDocument();
  expect(screen.getByText("Pubblicato")).toBeInTheDocument();
  expect(screen.getByText("LIVE → PAUSED")).toBeInTheDocument();
  expect(screen.getByText("«Via al banner d'autunno»")).toBeInTheDocument();
});

it("contenuto senza transizioni → stato vuoto con la frase", () => {
  useLhQuery.mockReturnValue({ isLoading: false, isError: false, data: [], refetch: vi.fn() });
  render(<ApprovalHistoryList entityType="CONTENT" id="c2" />);
  expect(screen.getByText("Nessuna transizione registrata")).toBeInTheDocument();
});

it("campagna (BO-21): stessa lista, dal servizio proprietario", () => {
  useLhQuery.mockReturnValue({ isLoading: false, isError: false, data: [], refetch: vi.fn() });
  render(<ApprovalHistoryList entityType="CAMPAIGN" id="k9" />);
  expect(useLhQuery).toHaveBeenCalledWith("campaign", "/v1/campaigns/k9/approval-history");
});
