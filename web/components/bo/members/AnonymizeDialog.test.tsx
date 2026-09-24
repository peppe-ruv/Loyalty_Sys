import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, screen, waitFor } from "@testing-library/react";
import { AnonymizeDialog } from "./AnonymizeDialog";
import { renderWithProviders } from "../../../test/test-utils";
import type { MemberView } from "@/lib/api/types";

const mockMember: MemberView = {
    id: "MBR-123456",
    version: 1,
    firstName: "Mario",
    lastName: "Rossi",
    email: "mario@example.com",
    status: "ACTIVE" as any,
    tier: "BASE" as any,
    externalId: null,
    nickname: null,
    balancePts: 0,
    periodSts: 0,
    registeredAt: "2023-01-01T00:00:00Z"
};

describe("AnonymizeDialog", () => {
    beforeEach(() => {
        vi.stubGlobal("fetch", vi.fn());
    });

    afterEach(() => {
        vi.unstubAllGlobals();
    });

    it("confirm disabled until the exact member ID is typed, then sends {confirm}", async () => {
        const fetchMock = vi.mocked(fetch);
        fetchMock.mockResolvedValueOnce(new Response(JSON.stringify({ ...mockMember, status: "ANONYMIZED", firstName: null, lastName: null, email: null }), { status: 200, headers: { "content-type": "application/json" } }));

        const onClose = vi.fn();
        const onDone = vi.fn();

        renderWithProviders(<AnonymizeDialog member={mockMember} onClose={onClose} onDone={onDone} />, "ADMIN");

        const anonymizeBtn = screen.getByRole("button", { name: "Anonimizza" });
        expect(anonymizeBtn).toBeDisabled();

        const input = screen.getByLabelText(/confermare/);

        fireEvent.change(input, { target: { value: "MBR-999" } });
        expect(anonymizeBtn).toBeDisabled();

        fireEvent.change(input, { target: { value: "MBR-123456" } });
        expect(anonymizeBtn).not.toBeDisabled();

        fireEvent.click(anonymizeBtn);

        await waitFor(() => {
            const call = fetchMock.mock.calls.find(c => c[0].toString().endsWith("/v1/members/MBR-123456/anonymize"));
            expect(call).toBeDefined();
            expect(JSON.parse(call![1]!.body as string)).toEqual({ confirm: "MBR-123456" });
        });

        expect(onDone).toHaveBeenCalled();
    });

    it("shows error states", async () => {
        const fetchMock = vi.mocked(fetch);
        fetchMock.mockResolvedValueOnce(new Response(JSON.stringify({ code: "ANONYMIZE_FAILED", detail: "Internal error" }), { status: 500, headers: { "content-type": "application/json" } }));

        renderWithProviders(<AnonymizeDialog member={mockMember} onClose={vi.fn()} onDone={vi.fn()} />, "ADMIN");

        const input = screen.getByLabelText(/confermare/);
        fireEvent.change(input, { target: { value: "MBR-123456" } });

        const anonymizeBtn = screen.getByRole("button", { name: "Anonimizza" });
        fireEvent.click(anonymizeBtn);

        await waitFor(() => {
            expect(screen.getByRole("alert")).toHaveTextContent("Internal error");
        });
    });
});
