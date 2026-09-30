import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { GeneratedSentence, GeneratedText } from "./GeneratedSentence";
import { useLhQuery } from "@/lib/api/client";
import { describeCampaign } from "@/lib/campaign/describe";

vi.mock("@/lib/api/client", () => ({
  useLhQuery: vi.fn(),
}));

vi.mock("@/lib/campaign/describe", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/campaign/describe")>();
  return {
    ...actual,
    describeCampaign: vi.fn(),
  };
});

describe("GeneratedText", () => {
  it("renders text with bold markers as <strong> tags", () => {
    render(<GeneratedText text="Questo è **molto** importante e **bello**." />);

    const strongs = screen.getAllByText(/molto|bello/);
    expect(strongs).toHaveLength(2);
    expect(strongs[0].tagName).toBe("STRONG");
    expect(strongs[0]).toHaveClass("font-semibold text-[var(--color-bo-accent)]");
    expect(strongs[1].tagName).toBe("STRONG");
    expect(screen.getByText("Questo è", { exact: false })).toBeInTheDocument();
    expect(screen.getByText("importante e", { exact: false })).toBeInTheDocument();
  });

  it("renders text without bold markers normally", () => {
    render(<GeneratedText text="Solo testo normale." />);
    expect(screen.getByText("Solo testo normale.")).toBeInTheDocument();
    expect(screen.queryByRole("strong")).toBeNull();
  });

  it("handles empty text gracefully", () => {
    const { container } = render(<GeneratedText text="" />);
    expect(container).toHaveTextContent("");
  });
});

describe("GeneratedSentence", () => {
  beforeEach(() => {
    vi.mocked(useLhQuery).mockReturnValue({
      data: undefined,
      isLoading: false,
      error: null,
    } as any);
    vi.mocked(describeCampaign).mockReturnValue("Mocked sentence");
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it("calls describeCampaign and renders the result", () => {
    const draft = { effects: [{ type: "GRANT_POINTS", value: 10 }] };

    render(<GeneratedSentence draft={draft} />);

    expect(describeCampaign).toHaveBeenCalledWith(
      expect.objectContaining({ effects: draft.effects, contestNames: {} })
    );
    expect(useLhQuery).toHaveBeenCalledWith(
      "gamification",
      "/v1/contests",
      undefined,
      { enabled: false }
    );
    expect(screen.getByText("Mocked sentence")).toBeInTheDocument();
  });

  it("fetches contests when needsContests is true", () => {
    const draft = { effects: [{ type: "GRANT_PLAYS", contestCode: "RUOTA1" }] };
    vi.mocked(useLhQuery).mockReturnValue({
      data: [{ code: "RUOTA1", name: "Ruota Autunno" }, { code: "RUOTA2", name: "Ruota Inverno" }],
      isLoading: false,
      error: null,
    } as any);

    render(<GeneratedSentence draft={draft} />);

    expect(useLhQuery).toHaveBeenCalledWith(
      "gamification",
      "/v1/contests",
      undefined,
      { enabled: true }
    );
    expect(describeCampaign).toHaveBeenCalledWith(
      expect.objectContaining({
        effects: draft.effects,
        contestNames: { "RUOTA1": "Ruota Autunno", "RUOTA2": "Ruota Inverno" }
      })
    );
  });

  it("uses provided draft.contestNames overriding fetched data", () => {
    const draft = {
      effects: [{ type: "GRANT_PLAYS", contestCode: "RUOTA1" }],
      contestNames: { "RUOTA1": "Ruota Custom" }
    };

    vi.mocked(useLhQuery).mockReturnValue({
      data: [{ code: "RUOTA1", name: "Ruota Autunno" }],
      isLoading: false,
      error: null,
    } as any);

    render(<GeneratedSentence draft={draft} />);

    expect(describeCampaign).toHaveBeenCalledWith(
      expect.objectContaining({
        effects: draft.effects,
        contestNames: { "RUOTA1": "Ruota Custom" }
      })
    );
  });
});
