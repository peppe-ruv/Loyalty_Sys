import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { it as t } from "@/lib/i18n/it";
import { EnterpriseMemberGate, type AccountIdentity } from "./EnterpriseMemberGate";
import JoinPage from "@/app/portal/join/page";

// PT-16 in enterprise (Laura, ADR-051, Q-673): nome ed e-mail dall'account in sola lettura, codice amico facoltativo,
// termini obbligatori, POST /v1/portal/members senza memberId, attesa del wallet da /me/wallet, poi la Home.

let search = "";
vi.mock("next/navigation", () => ({
  usePathname: () => "/portal/join",
  useRouter: () => ({ replace: vi.fn() }),
  useSearchParams: () => new URLSearchParams(search),
}));
vi.mock("@/components/shared/ThemeContext", () => ({ usePortalTheme: () => ({ programName: "Club Aurora" }) }));

const ACCOUNT: AccountIdentity = { givenName: "Laura", familyName: "Conti", name: "Laura Conti", email: "laura.conti@example.org" };
let calls: { url: string; init?: RequestInit }[];
let walletReady: boolean;

function problem(status: number, code: string, detail = "") {
  return new Response(JSON.stringify({ code, detail }), { status, headers: { "content-type": "application/json" } });
}

function renderJoin(account: AccountIdentity = ACCOUNT) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <EnterpriseMemberGate account={account}>
        <JoinPage />
      </EnterpriseMemberGate>
    </QueryClientProvider>,
  );
}

let post: (init?: RequestInit) => Response;

beforeEach(() => {
  search = "";
  calls = [];
  walletReady = true;
  post = () => Response.json({ memberId: "MBR-000042" }, { status: 201 });
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string, init?: RequestInit) => {
      calls.push({ url, init });
      if (url.endsWith("/v1/portal/me/profile")) return problem(404, "MEMBER_NOT_REGISTERED");
      if (url.endsWith("/v1/portal/members") && init?.method === "POST") return post(init);
      if (url.endsWith("/v1/portal/me/wallet")) return walletReady ? Response.json({ memberId: "MBR-000042" }) : problem(404, "NOT_FOUND");
      return problem(500, "UNEXPECTED");
    }),
  );
  // La navigazione finale non deve far uscire il test da jsdom.
  Object.defineProperty(window, "location", { configurable: true, value: { href: "/portal/join", search: "" } });
});
afterEach(() => vi.unstubAllGlobals());

describe("PT-16 registrazione in enterprise", () => {
  it("nome ed e-mail dall'account, in sola lettura; codice amico, termini e marketing da compilare", async () => {
    renderJoin();
    expect(await screen.findByTestId("join-enterprise")).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("Ciao Laura, completa l'iscrizione");
    const name = screen.getByLabelText(new RegExp(t.portalMember.fullName));
    const email = screen.getByLabelText(new RegExp(t.portalMember.email));
    expect(name).toHaveValue("Laura Conti");
    expect(name).toHaveAttribute("readonly");
    expect(email).toHaveValue("laura.conti@example.org");
    expect(email).toHaveAttribute("readonly");
    expect(screen.getByLabelText(/Codice amico/)).not.toHaveAttribute("readonly");
    expect(screen.getByLabelText(/Accetto il regolamento/)).not.toBeChecked();
    expect(screen.getByLabelText(/Voglio ricevere offerte/)).not.toBeChecked();
    // Nessun campo per scegliere un altro membro.
    expect(document.querySelector('[name="memberId"]')).toBeNull();
  });

  it("senza i termini non invia: errore sul campo e nessuna POST", async () => {
    renderJoin();
    await screen.findByTestId("join-enterprise");
    fireEvent.click(screen.getByRole("button", { name: "Iscriviti" }));
    expect(await screen.findByText(/accetta regolamento e informativa/)).toBeInTheDocument();
    expect(calls.some((c) => c.init?.method === "POST")).toBe(false);
  });

  it("invio: POST /v1/portal/members senza memberId, canale né stato; poi attesa del wallet dal token e Home col benvenuto", async () => {
    search = "ref=ab2cd3ef";
    renderJoin();
    await screen.findByTestId("join-enterprise");
    expect(screen.getByLabelText(/Codice amico/)).toHaveValue("AB2CD3EF");
    fireEvent.click(screen.getByLabelText(/Accetto il regolamento/));
    fireEvent.click(screen.getByLabelText(/Voglio ricevere offerte/));
    fireEvent.click(screen.getByRole("button", { name: "Iscriviti" }));
    await waitFor(() => expect(window.location.href).toBe("/portal?welcome=1"));
    const posts = calls.filter((c) => c.init?.method === "POST");
    expect(posts).toHaveLength(1);
    expect(posts[0].url).toBe("/api/lh/member/v1/portal/members");
    expect(JSON.parse(String(posts[0].init?.body))).toEqual({
      firstName: "Laura",
      lastName: "Conti",
      email: "laura.conti@example.org",
      referralCode: "AB2CD3EF",
      consents: { marketing: true, profiling: false },
    });
    expect(String(posts[0].init?.body)).not.toMatch(/memberId|channel|status/i);
    // Attesa del wallet col percorso del token, nessun cookie persona.
    expect(calls.some((c) => c.url === "/api/lh/wallet/v1/portal/me/wallet")).toBe(true);
    expect(calls.some((c) => c.url.includes("/api/persona"))).toBe(false);
    for (const c of calls) expect(c.url).not.toMatch(/memberId|MBR-/i);
  });

  it("codice amico non valido dal servizio: errore sul campo, si resta sul modulo", async () => {
    post = () => problem(422, "REFERRAL_CODE_INVALID", "codice non valido");
    renderJoin();
    await screen.findByTestId("join-enterprise");
    fireEvent.click(screen.getByLabelText(/Accetto il regolamento/));
    fireEvent.click(screen.getByRole("button", { name: "Iscriviti" }));
    expect(await screen.findByText("Codice amico non valido")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Iscriviti" })).toBeEnabled();
  });

  it("rifiuto del servizio (403 MEMBER_REQUIRED): messaggio in testa, il modulo resta", async () => {
    post = () => problem(403, "MEMBER_REQUIRED", "Serve un account di un membro");
    renderJoin();
    await screen.findByTestId("join-enterprise");
    fireEvent.click(screen.getByLabelText(/Accetto il regolamento/));
    fireEvent.click(screen.getByRole("button", { name: "Iscriviti" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Serve un account di un membro");
  });

  it("account senza e-mail nel token: lo dice e l'invio resta spento (Degraded)", async () => {
    renderJoin({ ...ACCOUNT, email: null });
    await screen.findByTestId("join-enterprise");
    expect(screen.getByRole("alert")).toHaveTextContent(t.portalMember.unavailable);
    expect(screen.getByRole("button", { name: "Iscriviti" })).toBeDisabled();
  });

  it("solo nome completo nel token: diviso al primo spazio", async () => {
    renderJoin({ givenName: null, familyName: null, name: "Laura Conti", email: "laura.conti@example.org" });
    await screen.findByTestId("join-enterprise");
    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("Ciao Laura");
    expect(screen.getByLabelText(new RegExp(t.portalMember.fullName))).toHaveValue("Laura Conti");
  });
});
