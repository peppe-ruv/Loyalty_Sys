import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { PersonaProvider } from "./PersonaContext";
import { NavLinks } from "./NavLinks";
import { MembersList } from "./MembersList";
import { canSeeMemberUsers, NAV, visibleNav } from "@/lib/nav";
import { it as t } from "@/lib/i18n/it";

// ADR-051 decisione 7, docs/08 §1: voce esterna «Utenti membri» (console Keycloak del realm dei membri) per ADMIN e CARE.

vi.mock("next/navigation", () => ({ usePathname: () => "/backoffice/members", useRouter: () => ({ push: vi.fn() }) }));

const URL_CONSOLE = "https://idp2.lh.test/admin/loyaltyhub-members/console/";

beforeEach(() => {
  vi.stubGlobal("fetch", vi.fn(() => new Promise<Response>(() => {})));
});
afterEach(() => vi.unstubAllGlobals());

function renderAs(ui: React.ReactNode, memberUsersUrl: string | null | undefined) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <PersonaProvider value={{ username: "u", displayName: "U", role: "ADMIN", mode: "enterprise", memberUsersUrl }}>{ui}</PersonaProvider>
    </QueryClientProvider>,
  );
}

describe("regola di visibilità", () => {
  it("solo ADMIN e CARE", () => {
    expect((["ADMIN", "CARE", "MARKETING", "LEGAL", "ANALYST"] as const).filter(canSeeMemberUsers)).toEqual(["ADMIN", "CARE"]);
  });
  it("non è una voce di NAV (nessun BO-xx nuovo, nessuna milestone): la regola «niente pagine in arrivo» resta intatta", () => {
    expect(NAV.flatMap((g) => g.items).some((i) => i.label === "Utenti membri")).toBe(false);
    expect(visibleNav().find((g) => g.label === "Clienti")?.items.map((i) => i.id)).toEqual(["BO-02", "BO-04"]);
  });
});

describe("NavLinks", () => {
  it("con l'indirizzo: «Utenti membri ↗» subito dopo «Membri», link esterno in nuova scheda con rel noopener", () => {
    renderAs(<NavLinks />, URL_CONSOLE);
    const link = screen.getByRole("link", { name: new RegExp(t.memberUsers.navLabel) });
    expect(link).toHaveAttribute("href", URL_CONSOLE);
    expect(link).toHaveAttribute("target", "_blank");
    expect(link.getAttribute("rel")).toContain("noopener");
    const labels = screen.getAllByRole("link").map((l) => l.textContent ?? "");
    const i = labels.findIndex((x) => x.startsWith("Membri"));
    expect(labels[i + 1]).toContain(t.memberUsers.navLabel);
    expect(labels[i + 2]).toContain("Segmenti");
    expect(screen.getAllByRole("link")).toHaveLength(30);
  });

  it.each([null, undefined])("senza indirizzo (%s): le 29 voci di sempre", (url) => {
    renderAs(<NavLinks />, url);
    expect(screen.queryByRole("link", { name: /Utenti membri/ })).toBeNull();
    expect(screen.getAllByRole("link")).toHaveLength(29);
  });
});

describe("BO-02 Membri: riquadro «Utenti membri»", () => {
  it("con l'indirizzo: riquadro sopra la tabella con «Apri gli utenti membri ↗» in nuova scheda", () => {
    renderAs(<MembersList />, URL_CONSOLE);
    const card = screen.getByTestId("member-users-card");
    expect(within(card).getByText(t.memberUsers.cardTitle)).toBeInTheDocument();
    const link = within(card).getByRole("link", { name: new RegExp(t.memberUsers.cardOpen) });
    expect(link).toHaveAttribute("href", URL_CONSOLE);
    expect(link).toHaveAttribute("target", "_blank");
    expect(link.getAttribute("rel")).toContain("noopener");
  });

  it("senza indirizzo: nessun riquadro", () => {
    renderAs(<MembersList />, null);
    expect(screen.queryByTestId("member-users-card")).toBeNull();
  });
});
