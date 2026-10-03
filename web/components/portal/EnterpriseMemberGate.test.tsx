import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { it as t } from "@/lib/i18n/it";
import { EnterpriseMemberGate, useAccountIdentity } from "./EnterpriseMemberGate";
import { usePortalApi, useUnregistered } from "./MemberContext";

// Ingresso del portale in enterprise (PT-08, PT-16, F2-SEC-09, ADR-051): il membro viene da GET /v1/portal/me/profile;
// 404 MEMBER_NOT_REGISTERED porta a /portal/join; altri errori hanno «Riprova».

const replace = vi.fn();
let pathname = "/portal";
vi.mock("next/navigation", () => ({ usePathname: () => pathname, useRouter: () => ({ replace }) }));

const ACCOUNT = { givenName: "Laura", familyName: "Conti", name: "Laura Conti", email: "laura.conti@example.org" };
let fetchSpy: ReturnType<typeof vi.fn>;

function problem(status: number, code: string, detail = "") {
  return new Response(JSON.stringify({ code, detail, title: code }), { status, headers: { "content-type": "application/json" } });
}

function Probe() {
  const api = usePortalApi();
  const acc = useAccountIdentity();
  return (
    <p data-testid="probe">
      {api.enterprise ? "ent" : "demo"}|{api.memberId}|{String(useUnregistered())}|{acc.email}|{api.wallet}
    </p>
  );
}

function renderGate() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <EnterpriseMemberGate account={ACCOUNT}>
        <Probe />
      </EnterpriseMemberGate>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  replace.mockReset();
  pathname = "/portal";
  fetchSpy = vi.fn();
  vi.stubGlobal("fetch", fetchSpy);
});
afterEach(() => vi.unstubAllGlobals());

describe("EnterpriseMemberGate", () => {
  it("chiede il profilo dal token, senza memberId in percorso né in query; durante l'attesa mostra lo stato di caricamento", async () => {
    fetchSpy.mockReturnValue(new Promise(() => {}));
    renderGate();
    expect(screen.getByText(t.portalMember.loading)).toBeInTheDocument();
    expect(fetchSpy).toHaveBeenCalledTimes(1);
    expect(fetchSpy.mock.calls[0][0]).toBe("/api/lh/member/v1/portal/me/profile");
  });

  it("200: l'id restituito alimenta il contesto solo per mostrare, i percorsi restano quelli del token", async () => {
    fetchSpy.mockResolvedValue(Response.json({ memberId: "MBR-000042", status: "ACTIVE" }));
    renderGate();
    expect(await screen.findByTestId("probe")).toHaveTextContent("ent|MBR-000042|false|laura.conti@example.org|/v1/portal/me/wallet");
    expect(replace).not.toHaveBeenCalled();
  });

  it("aggiornamento in secondo piano fallito dopo un dato noto (es. dopo il salvataggio del profilo): il portale resta", async () => {
    fetchSpy.mockResolvedValue(Response.json({ memberId: "MBR-000042", status: "ACTIVE" }));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <EnterpriseMemberGate account={ACCOUNT}>
          <Probe />
        </EnterpriseMemberGate>
      </QueryClientProvider>,
    );
    expect(await screen.findByTestId("probe")).toHaveTextContent("MBR-000042");
    fetchSpy.mockResolvedValue(problem(500, "BOOM", "errore"));
    await act(async () => {
      await client.invalidateQueries({ queryKey: ["member"] });
    });
    expect(screen.getByTestId("probe")).toHaveTextContent("MBR-000042");
    expect(screen.queryByRole("alert")).toBeNull();
    // Un 503 «dorme» in secondo piano non fa comparire il degraded.
    fetchSpy.mockResolvedValue(new Response(JSON.stringify({ type: "SERVICE_ASLEEP" }), { status: 503 }));
    await act(async () => {
      await client.invalidateQueries({ queryKey: ["member"] });
    });
    expect(screen.getByTestId("probe")).toBeInTheDocument();
    expect(screen.queryByText(/si sta svegliando/)).toBeNull();
    expect(replace).not.toHaveBeenCalled();
  });

  it("404 MEMBER_NOT_REGISTERED fuori da /portal/join: porta alla registrazione", async () => {
    fetchSpy.mockResolvedValue(problem(404, "MEMBER_NOT_REGISTERED"));
    renderGate();
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/portal/join"));
    expect(screen.getByText(t.portalMember.toJoin)).toBeInTheDocument();
    expect(screen.queryByTestId("probe")).toBeNull();
  });

  it("404 MEMBER_NOT_REGISTERED su /portal/join: mostra la registrazione con le viste dei servizi ferme", async () => {
    pathname = "/portal/join";
    fetchSpy.mockResolvedValue(problem(404, "MEMBER_NOT_REGISTERED"));
    renderGate();
    expect(await screen.findByTestId("probe")).toHaveTextContent("ent||true|laura.conti@example.org");
    expect(replace).not.toHaveBeenCalled();
  });

  it("già registrato su /portal/join: porta alla Home", async () => {
    pathname = "/portal/join";
    fetchSpy.mockResolvedValue(Response.json({ memberId: "MBR-000002", status: "ACTIVE" }));
    renderGate();
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/portal"));
    expect(screen.getByText(t.portalMember.toHome)).toBeInTheDocument();
  });

  it("un altro 404 non è la registrazione: errore con «Riprova»", async () => {
    fetchSpy.mockResolvedValue(problem(404, "NOT_FOUND", "nessuna risorsa"));
    renderGate();
    expect(await screen.findByRole("alert")).toHaveTextContent(t.portalMember.errorTitle);
    expect(replace).not.toHaveBeenCalled();
    fetchSpy.mockResolvedValue(Response.json({ memberId: "MBR-000002", status: "ACTIVE" }));
    screen.getByRole("button", { name: t.portalMember.retry }).click();
    expect(await screen.findByTestId("probe")).toHaveTextContent("MBR-000002");
  });

  it("un guasto (500) mostra il codice dell'errore in piccolo con «Copia il codice» (F2-QA-06, C1)", async () => {
    fetchSpy.mockImplementation(async () =>
      new Response(JSON.stringify({ code: "INTERNAL_ERROR", title: "Errore interno", correlationId: "01JC8QF0B2C4D6E8G0J2K4M6NP" }), { status: 500, headers: { "content-type": "application/problem+json" } }),
    );
    renderGate();
    const alert = await screen.findByRole("alert", {}, { timeout: 5000 });
    expect(alert).toHaveTextContent(t.portalMember.errorTitle);
    expect(alert).toHaveTextContent(t.errorCode.portalHint);
    expect(alert).toHaveTextContent("01JC8QF0B2C4D6E8G0J2K4M6NP");
    expect(alert).toHaveTextContent(t.errorCode.copy);
  });

  it("servizio che dorme (503): riquadro degraded con «Riprova», nessun reindirizzamento", async () => {
    fetchSpy.mockResolvedValue(new Response(JSON.stringify({ type: "SERVICE_ASLEEP" }), { status: 503 }));
    renderGate();
    expect(await screen.findByText(/si sta svegliando/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Riprova" })).toBeInTheDocument();
    expect(replace).not.toHaveBeenCalled();
  });
});
