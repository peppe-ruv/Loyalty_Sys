import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, screen, waitFor } from "@testing-library/react";
import { DuplicateButton } from "./DuplicateButton";
import { renderWithProviders } from "../../test/test-utils";

const mockPush = vi.fn();

vi.mock("next/navigation", () => ({
  useRouter: () => ({
    push: mockPush,
  }),
}));

describe("DuplicateButton", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
    mockPush.mockClear();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("POST to the given path and navigation to the copy", async () => {
    const fetchMock = vi.mocked(fetch);
    fetchMock.mockResolvedValueOnce(new Response(JSON.stringify({ id: "NEW-123" }), { status: 200, headers: { "content-type": "application/json" } }));

    renderWithProviders(
      <DuplicateButton service="campaign" path="/v1/campaigns/1/duplicate" hrefFor={(copy) => `/campaigns/${copy.id}`} />,
      "MARKETING"
    );

    fireEvent.click(screen.getByRole("button", { name: /Duplica/ }));

    await waitFor(() => {
        expect(mockPush).toHaveBeenCalledWith("/campaigns/NEW-123");
    });

    expect(fetchMock).toHaveBeenCalledWith(
        expect.stringContaining("/v1/campaigns/1/duplicate"),
        expect.objectContaining({
            method: "POST"
        })
    );
  });

  it("hidden/disabled for ANALYST", () => {
    renderWithProviders(
        <DuplicateButton service="campaign" path="/v1/campaigns/1/duplicate" hrefFor={(copy) => `/campaigns/${copy.id}`} />,
        "ANALYST"
      );

      const duplicateBtn = screen.getByText(/Duplica/);
      const wrapper = duplicateBtn.closest('span[title]');
      expect(wrapper).toBeInTheDocument();
      expect(wrapper).toHaveAttribute('title', expect.stringContaining('ADMIN, MARKETING'));
      expect(wrapper).toHaveClass('cursor-not-allowed');
  });

  it("shows error if duplication fails", async () => {
    const fetchMock = vi.mocked(fetch);
    fetchMock.mockResolvedValueOnce(new Response(JSON.stringify({ code: "DUPLICATE_FAILED", detail: "Duplication not allowed" }), { status: 400, headers: { "content-type": "application/json" } }));

    renderWithProviders(
      <DuplicateButton service="campaign" path="/v1/campaigns/1/duplicate" hrefFor={(copy) => `/campaigns/${copy.id}`} />,
      "MARKETING"
    );

    fireEvent.click(screen.getByRole("button", { name: /Duplica/ }));

    await waitFor(() => {
        expect(screen.getByRole("alert")).toHaveTextContent("Duplication not allowed");
    });
  });

});
