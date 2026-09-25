import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, screen, waitFor } from "@testing-library/react";
import { LifecycleBar } from "./LifecycleBar";
import { renderWithProviders } from "../../test/test-utils";

describe("LifecycleBar", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("MARKETING on DRAFT with approvalRequired sees 'Invia in revisione' and not 'Pubblica'", () => {
    renderWithProviders(
      <LifecycleBar service="campaign" transitionsPath="/v1/campaigns/1/transitions" status="DRAFT" approvalRequired={true} onChanged={vi.fn()} />,
      "MARKETING"
    );
    expect(screen.getByRole("button", { name: "Invia in revisione" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Pubblica" })).toBeNull();
  });

  it("LEGAL on IN_REVIEW can approve and reject with comment, shows 422 if comment missing", async () => {
    const fetchMock = vi.mocked(fetch);
    fetchMock.mockResolvedValueOnce(new Response(JSON.stringify({ code: "REJECT_COMMENT_REQUIRED", detail: "Comment is required" }), { status: 422, headers: { "content-type": "application/json" } }));

    renderWithProviders(
      <LifecycleBar service="campaign" transitionsPath="/v1/campaigns/1/transitions" status="IN_REVIEW" onChanged={vi.fn()} />,
      "LEGAL"
    );

    expect(screen.getByRole("button", { name: "Approva" })).toBeInTheDocument();

    const rejectBtn = screen.getByRole("button", { name: /Rifiuta/ });
    fireEvent.click(rejectBtn);

    const commentInput = screen.getByLabelText("Motivo del rifiuto");
    expect(commentInput).toBeInTheDocument();

    const submitRejectBtn = screen.getByRole("button", { name: "Rifiuta" });
    expect(submitRejectBtn).toBeDisabled();

    fireEvent.change(commentInput, { target: { value: "not good enough" } });
    expect(submitRejectBtn).not.toBeDisabled();

    fireEvent.click(submitRejectBtn);

    await waitFor(() => {
      expect(screen.getByRole("alert")).toHaveTextContent("Comment is required");
    });

    expect(fetchMock).toHaveBeenCalledWith(
        expect.stringContaining("/v1/campaigns/1/transitions"),
        expect.objectContaining({
            method: "POST",
            body: JSON.stringify({ action: "REJECT", comment: "not good enough" })
        })
    );
  });

  it("ADMIN override works on IN_REVIEW", () => {
      renderWithProviders(
          <LifecycleBar service="campaign" transitionsPath="/v1/campaigns/1/transitions" status="IN_REVIEW" onChanged={vi.fn()} />,
          "ADMIN"
      );
      expect(screen.getByRole("button", { name: "Approva" })).toBeInTheDocument();
      expect(screen.getByRole("button", { name: /Rifiuta/ })).toBeInTheDocument();
  });

  it("renders 409 APPROVAL_REQUIRED", async () => {
      const fetchMock = vi.mocked(fetch);
      fetchMock.mockResolvedValueOnce(new Response(JSON.stringify({ code: "APPROVAL_REQUIRED" }), { status: 409, headers: { "content-type": "application/json" } }));

      renderWithProviders(
        <LifecycleBar service="campaign" transitionsPath="/v1/campaigns/1/transitions" status="DRAFT" onChanged={vi.fn()} />,
        "MARKETING"
      );

      fireEvent.click(screen.getByRole("button", { name: "Pubblica" }));
      fireEvent.click(screen.getByRole("button", { name: "Conferma" }));
      await waitFor(() => {
          expect(screen.getByRole("alert")).toHaveTextContent("Serve l'approvazione: usa «Invia in revisione».");
      });
  });

  it("buttons hidden/disabled per role (MARKETING on IN_REVIEW)", () => {
      renderWithProviders(
          <LifecycleBar service="campaign" transitionsPath="/v1/campaigns/1/transitions" status="IN_REVIEW" onChanged={vi.fn()} />,
          "MARKETING"
      );

      const approveBtn = screen.getByText("Approva");
      const wrapper = approveBtn.closest('span[title]');
      expect(wrapper).toBeInTheDocument();
      expect(wrapper).toHaveAttribute('title', expect.stringContaining('ADMIN, LEGAL'));
      expect(wrapper).toHaveClass('cursor-not-allowed');
  });

});
