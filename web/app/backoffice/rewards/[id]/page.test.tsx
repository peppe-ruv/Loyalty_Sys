import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, screen, waitFor } from "@testing-library/react";
import RewardEditorPage from "./page";
import { renderWithProviders } from "../../../../test/test-utils";

vi.mock("next/navigation", () => ({
  useParams: () => ({ id: "1" }),
  useRouter: () => ({}),
}));

const mockReward = {
    id: "1",
    version: 1,
    status: "DRAFT",
    name: "Tazza",
    code: "TAZZA",
    type: "PHYSICAL",
    category: "C1",
    band: "B1",
    fulfilment: "MANUAL",
    stockTotal: 10,
    perMemberLimit: 1,
    eligibleTiers: [],
    eligibleSegments: [],
    validFrom: null,
    validTo: null,
    description: "Una bella tazza",
    terms: "Nessuno",
    imageUrl: "http://example.com/tazza.jpg"
};

describe("RewardEditorPage", () => {
    beforeEach(() => {
        vi.stubGlobal("fetch", vi.fn());
    });

    afterEach(() => {
        vi.unstubAllGlobals();
    });

    it("saves sends version and handles 409 VERSION_CONFLICT", async () => {
        const fetchMock = vi.mocked(fetch);

        fetchMock.mockImplementation(async (url, init) => {
            const urlStr = url.toString();
            if (urlStr.endsWith("/v1/rewards/1")) {
                if (init?.method === "PUT") {
                    return new Response(JSON.stringify({ code: "VERSION_CONFLICT" }), { status: 409, headers: { "content-type": "application/json" } });
                }
                return new Response(JSON.stringify(mockReward), { status: 200, headers: { "content-type": "application/json" } });
            }
            if (urlStr.endsWith("/v1/reward-bands")) return new Response("[]");
            if (urlStr.endsWith("/v1/reward-categories")) return new Response("[]");
            if (urlStr.endsWith("/v1/tiers")) return new Response("[]");
            if (urlStr.endsWith("/v1/coupon-pools")) return new Response("[]");
            if (urlStr.endsWith("/v1/approval-policies")) return new Response("{}");

            return new Response("Not found", {status: 404});
        });

        renderWithProviders(<RewardEditorPage />, "MARKETING");

        await screen.findByDisplayValue("Tazza");

        fireEvent.change(screen.getByLabelText("Nome"), { target: { value: "Tazza Nuova" } });

        fireEvent.click(screen.getByRole("button", { name: "Salva modifiche" }));

        await waitFor(() => {
             const putCall = fetchMock.mock.calls.find(c => c[0].toString().endsWith("/v1/rewards/1") && c[1]?.method === "PUT");
             expect(putCall).toBeDefined();
             expect(JSON.parse(putCall![1]!.body as string)).toEqual(expect.objectContaining({ name: "Tazza Nuova", version: 1 }));
        });

        await screen.findByRole("alert");
        expect(screen.getByText(/Qualcun altro ha modificato il premio nel frattempo./)).toBeInTheDocument();

        fetchMock.mockClear();
        fetchMock.mockResolvedValueOnce(new Response(JSON.stringify({...mockReward, name: "Tazza Nuova", version: 2}), { status: 200, headers: { "content-type": "application/json" } }));
        fireEvent.click(screen.getByRole("button", { name: "Sovrascrivi" }));

        await waitFor(() => {
             const putCall = fetchMock.mock.calls.find(c => c[0].toString().endsWith("/v1/rewards/1") && c[1]?.method === "PUT");
             expect(putCall).toBeDefined();
             const body = JSON.parse(putCall![1]!.body as string);
             expect(body).toEqual(expect.objectContaining({ name: "Tazza Nuova" }));
             expect(body).not.toHaveProperty("version");
        });
    });
});
