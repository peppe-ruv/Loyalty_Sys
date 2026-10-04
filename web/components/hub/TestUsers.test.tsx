import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen, within } from "@testing-library/react";
import { execSync } from "node:child_process";
import { it as t } from "@/lib/i18n/it";
import {
  KEYCLOAK_ADMIN_PASSWORD,
  MEMBERS_PASSWORD,
  OPERATORS_PASSWORD,
  OPERATORS_TOTP_SEED,
  MEMBER_PORTAL_READY,
  TEST_MEMBERS,
  TEST_OPERATORS,
} from "@/lib/hub/testUsers";
import { EnterpriseHub } from "./EnterpriseHub";
import { loginHref, TestEnvironmentBanner, TestUsers } from "./TestUsers";
import { CopyValue } from "./CopyButton";
import { OtpCard } from "./OtpCard";

// HUB-02 — schede degli utenti di test (ADR-051, Q-673, Q-676, Q-677) nell'ambiente di test dichiarato.

vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: vi.fn() }) }));

const tu = t.testUsers;
const MODE = {
  operators: { realm: "loyaltyhub", url: "https://idp.lh.test/admin/loyaltyhub/console/" },
  members: { realm: "loyaltyhub-members", url: "https://idp2.lh.test/admin/loyaltyhub-members/console/" },
};

beforeEach(() => {
  vi.stubGlobal(
    "fetch",
    vi.fn(async () => Response.json({ code: "482913", remainingSeconds: 18 })),
  );
});
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe("modulo dei dati di test", () => {
  it("valori esatti, cinque operatori e quattro membri", () => {
    expect(OPERATORS_PASSWORD).toBe("Aurora-Operatori-26!");
    expect(MEMBERS_PASSWORD).toBe("Aurora-Membri-26!");
    expect(KEYCLOAK_ADMIN_PASSWORD).toBe("Aurora-Admin-26!");
    expect(OPERATORS_TOTP_SEED).toBe("KZSXI4TJNZQUC5LSN5ZGCMRQGI3EY2BB");
    expect(TEST_OPERATORS.map((o) => `${o.username}:${o.role}`)).toEqual([
      "marta.admin:ADMIN",
      "luca.marketing:MARKETING",
      "elena.legal:LEGAL",
      "paolo.care:CARE",
      "sara.analyst:ANALYST",
    ]);
    expect(TEST_MEMBERS.map((m) => m.username)).toEqual(["anna.rossi", "marco.bianchi", "giulia.ferri", "laura.conti"]);
  });

  it("il portale dei membri funziona dal token (V9b): il flag vale true", () => {
    expect(MEMBER_PORTAL_READY).toBe(true);
  });

  it("nessun modulo client importa le credenziali: solo server component e route handler", () => {
    const out = execSync(`grep -rlE "hub/testUsers|from \\"./testUsers\\"" --include=*.ts --include=*.tsx app components lib || true`, {
      encoding: "utf8",
      cwd: __dirname + "/../..",
    });
    const importers = out.split("\n").filter((f) => f && !f.endsWith(".test.ts") && !f.endsWith(".test.tsx"));
    expect(importers.length).toBeGreaterThan(0);
    for (const file of importers) {
      const head = execSync(`head -c 200 "${file}"`, { encoding: "utf8", cwd: __dirname + "/../.." });
      expect(head, file).not.toMatch(/["']use client["']/);
    }
  });
});

describe("TestUsers", () => {
  it("banner dell'ambiente di test con role=note", () => {
    render(<TestEnvironmentBanner />);
    expect(screen.getByTestId("test-banner")).toHaveAttribute("role", "note");
    expect(screen.getByText(tu.banner)).toBeInTheDocument();
  });

  it("operatori: password condivisa, cinque schede con ruolo e «Entra come» verso il login con login_hint", async () => {
    render(<TestUsers mode={MODE} user={null} />);
    await screen.findByTestId("otp-code");
    const ops = screen.getByRole("region", { name: tu.operators });
    expect(within(ops).getByText(OPERATORS_PASSWORD)).toBeInTheDocument();
    expect(within(ops).getByRole("button", { name: tu.copyLabel(tu.operatorsPassword) })).toBeInTheDocument();
    for (const op of TEST_OPERATORS) {
      const card = screen.getByTestId(`op-${op.username}`);
      expect(within(card).getByText(op.role)).toBeInTheDocument();
      expect(within(card).getByText(op.username)).toBeInTheDocument();
      expect(within(card).getByText(op.summary)).toBeInTheDocument();
      const link = within(card).getByRole("link", { name: tu.enterAs(op.name) });
      expect(link).toHaveAttribute("href", `/api/auth/login?realm=operators&returnTo=%2Fbackoffice&login_hint=${op.username}`);
    }
  });

  it("l'URL di ingresso non contiene password", () => {
    expect(loginHref("members", "/portal", "anna.rossi")).toBe("/api/auth/login?realm=members&returnTo=%2Fportal&login_hint=anna.rossi");
    expect(loginHref("operators", "/backoffice", "marta.admin")).not.toMatch(/Aurora/);
  });

  it("nota col seme abbreviato (mai intero) e rimando alla guida", async () => {
    render(<TestUsers mode={MODE} user={null} />);
    await screen.findByTestId("otp-code");
    expect(screen.getByText(/KZSXI4TJ….*GI3EY2BB/)).toBeInTheDocument();
    expect(document.body.textContent).not.toContain(OPERATORS_TOTP_SEED);
  });

  it("sessione aperta: la scheda dell'utente dice «Sei dentro come…» con «Apri il backoffice»; le altre offrono l'uscita al posto dell'ingresso", async () => {
    render(<TestUsers mode={MODE} user={{ username: "luca.marketing", name: "Luca Serra" }} />);
    await screen.findByTestId("otp-code");
    const luca = screen.getByTestId("op-luca.marketing");
    expect(within(luca).getByText(tu.signedInAs("Luca Serra"))).toBeInTheDocument();
    expect(within(luca).getByRole("link", { name: new RegExp(tu.openBackoffice) })).toHaveAttribute("href", "/backoffice");
    expect(within(luca).queryByRole("link", { name: /Entra come/ })).toBeNull();
    // Keycloak non autentica un altro utente sopra la sessione SSO: nelle altre schede c'è l'uscita (modulo CSRF di LogoutButton).
    for (const op of TEST_OPERATORS.filter((o) => o.username !== "luca.marketing")) {
      const card = screen.getByTestId(`op-${op.username}`);
      expect(within(card).getByText(tu.signedInAs("Luca Serra"))).toBeInTheDocument();
      expect(within(card).queryByRole("link")).toBeNull();
      const out = within(card).getByRole("button", { name: tu.logoutToEnterAs(op.name.split(" ")[0]) });
      const form = out.closest("form");
      expect(form).toHaveAttribute("method", "post");
      expect(form).toHaveAttribute("action", "/api/auth/logout");
      expect(form?.querySelector('input[name="csrf"]')).not.toBeNull();
    }
  });

  it("membri: tre registrati e Laura tratteggiata «da registrare»; schede attive con il collegamento di ingresso al realm dei membri", async () => {
    render(<TestUsers mode={MODE} user={null} />);
    await screen.findByTestId("otp-code");
    const members = screen.getByRole("region", { name: tu.members });
    expect(within(members).getByText(MEMBERS_PASSWORD)).toBeInTheDocument();
    expect(within(screen.getByTestId("member-anna.rossi")).getByText("registrata")).toBeInTheDocument();
    expect(within(screen.getByTestId("member-marco.bianchi")).getByText("registrato")).toBeInTheDocument();
    expect(within(screen.getByTestId("member-giulia.ferri")).getByText("registrata")).toBeInTheDocument();
    const laura = screen.getByTestId("member-laura.conti");
    expect(within(laura).getByText(tu.toRegister)).toBeInTheDocument();
    expect(laura.className).toMatch(/border-dashed/);
    for (const m of TEST_MEMBERS) {
      const card = screen.getByTestId(`member-${m.username}`);
      expect(within(card).queryByRole("button")).toBeNull();
      const first = m.name.split(" ")[0];
      const link = within(card).getByRole("link", { name: m.registered ? tu.enterAs(first) : tu.registerAs(first) });
      expect(link).toHaveAttribute("href", `/api/auth/login?realm=members&returnTo=%2Fportal&login_hint=${m.username}`);
      expect(within(card).queryByText(tu.portalNotReady)).toBeNull();
      expect(within(card).getByText(m.story)).toBeInTheDocument();
    }
    expect(screen.getByText(tu.membersNote)).toBeInTheDocument();
    // La scheda di Marta (ADMIN) porta al programma di esempio; le altre no.
    expect(within(screen.getByTestId("op-marta.admin")).getByRole("link", { name: tu.loadSample })).toHaveAttribute("href", "/backoffice");
    expect(within(screen.getByTestId("op-luca.marketing")).queryByRole("link", { name: tu.loadSample })).toBeNull();
  });

  it("sessione da membro: la sua scheda dice «Sei dentro come…» con «Apri il portale»; le altre offrono l'uscita dalla sessione del MEMBRO", async () => {
    render(<TestUsers mode={MODE} user={null} memberUser={{ username: "anna.rossi", name: "Anna Rossi" }} />);
    await screen.findByTestId("otp-code");
    const anna = screen.getByTestId("member-anna.rossi");
    expect(within(anna).getByText(tu.signedInAs("Anna Rossi"))).toBeInTheDocument();
    expect(within(anna).getByRole("link", { name: new RegExp(tu.openPortal) })).toHaveAttribute("href", "/portal");
    expect(within(anna).queryByRole("link", { name: /Entra come/ })).toBeNull();
    for (const m of TEST_MEMBERS.filter((x) => x.username !== "anna.rossi")) {
      const card = screen.getByTestId(`member-${m.username}`);
      expect(within(card).queryByRole("link")).toBeNull();
      const out = within(card).getByRole("button", { name: tu.logoutToEnterAs(m.name.split(" ")[0]) });
      const form = out.closest("form");
      expect(form).toHaveAttribute("method", "post");
      // Chiude la sola sessione del membro (realm=members), non quella dell'operatore.
      expect(form).toHaveAttribute("action", "/api/auth/logout?realm=members");
      expect(form?.querySelector('input[name="csrf"]')).not.toBeNull();
    }
    // Le schede degli operatori non cambiano: nessuna sessione da operatore, ingresso normale.
    for (const op of TEST_OPERATORS) {
      expect(within(screen.getByTestId(`op-${op.username}`)).getByRole("link", { name: tu.enterAs(op.name) })).toBeInTheDocument();
    }
  });

  it("console di Keycloak: due schede con utente e password copiabili e link in nuova scheda alla console del realm", async () => {
    render(<TestUsers mode={MODE} user={null} />);
    await screen.findByTestId("otp-code");
    const ops = screen.getByTestId("console-loyaltyhub");
    expect(within(ops).getByText("vetrina.admin")).toBeInTheDocument();
    expect(within(ops).getByText(KEYCLOAK_ADMIN_PASSWORD)).toBeInTheDocument();
    const opsLink = within(ops).getByRole("link", { name: new RegExp(tu.consoleOpen) });
    expect(opsLink).toHaveAttribute("href", "https://idp.lh.test/admin/loyaltyhub/console/");
    expect(opsLink).toHaveAttribute("target", "_blank");
    expect(opsLink.getAttribute("rel")).toContain("noopener");
    const mem = screen.getByTestId("console-loyaltyhub-members");
    expect(within(mem).getByText("membri.admin")).toBeInTheDocument();
    expect(within(mem).getByRole("link", { name: new RegExp(tu.consoleOpen) })).toHaveAttribute(
      "href",
      "https://idp2.lh.test/admin/loyaltyhub-members/console/",
    );
    expect(screen.queryByText(/La console master non è pubblica/)).toBeNull();
    expect(screen.getByText(/M8\.12, Q-677/)).toBeInTheDocument();
  });

  it("senza realm dei membri configurato la scheda della console dei membri non c'è", async () => {
    render(<TestUsers mode={{ operators: MODE.operators, members: null }} user={null} />);
    await screen.findByTestId("otp-code");
    expect(screen.getByTestId("console-loyaltyhub")).toBeInTheDocument();
    expect(screen.queryByTestId("console-loyaltyhub-members")).toBeNull();
  });
});

describe("EnterpriseHub con l'ambiente di test", () => {
  const load = async () => ({
    tiles: [
      { key: "hub", group: "role", state: "UP", latencyMs: 1 },
      { key: "web", group: "role", state: "UP", latencyMs: null },
      { key: "idp", group: "role", state: "UP", latencyMs: 1 },
      { key: "cms", group: "role", state: "NOT_INSTALLED", latencyMs: null },
      { key: "db", group: "infra", state: "UP", latencyMs: null },
      { key: "kafka", group: "infra", state: "UP", latencyMs: null },
    ],
    checkedAt: "2026-10-01T10:00:00.000Z",
  }) as never;

  it("con testMode: due banner (vetrina + credenziali pubbliche) e le schede al posto dei due ingressi", async () => {
    render(<EnterpriseHub demoUrl={null} user={null} load={load} testMode={MODE} />);
    expect(screen.getByTestId("showcase-banner")).toBeInTheDocument();
    expect(screen.getByTestId("test-banner")).toBeInTheDocument();
    expect(await screen.findByTestId("test-users")).toBeInTheDocument();
    expect(screen.queryByTestId("portal-closed")).toBeNull();
  });

  it("senza testMode: HUB-02 resta quello di oggi (solo login, nessuna scheda né credenziale)", async () => {
    render(<EnterpriseHub demoUrl={null} user={null} load={load} />);
    expect(screen.queryByTestId("test-banner")).toBeNull();
    expect(screen.queryByTestId("test-users")).toBeNull();
    expect(screen.getByTestId("portal-closed")).toBeInTheDocument();
    expect(document.body.textContent).not.toContain(OPERATORS_PASSWORD);
  });
});

describe("CopyValue", () => {
  it("copia dentro il gestore del clic e annuncia «Copiato» in una regione aria-live", async () => {
    const writeText = vi.fn(async () => undefined);
    vi.stubGlobal("navigator", { clipboard: { writeText } });
    render(<CopyValue value="Aurora-Operatori-26!" what="la password" />);
    fireEvent.click(screen.getByRole("button", { name: tu.copyLabel("la password") }));
    expect(writeText).toHaveBeenCalledWith("Aurora-Operatori-26!");
    await vi.waitFor(() => expect(screen.getByRole("status")).toHaveTextContent(tu.copied));
    expect(screen.getByRole("status")).toHaveAttribute("aria-live", "polite");
  });

  it("senza clipboard o con il rifiuto del browser: seleziona il testo e lo dice", async () => {
    vi.stubGlobal("navigator", { clipboard: { writeText: vi.fn(async () => { throw new Error("negato"); }) } });
    render(<CopyValue value="abc123" what="il valore" />);
    fireEvent.click(screen.getByRole("button", { name: tu.copyLabel("il valore") }));
    await vi.waitFor(() => expect(screen.getByRole("status")).toHaveTextContent(tu.copyFailed));
    expect(window.getSelection()?.toString()).toBe("abc123");
  });

  it("navigator.clipboard assente: stesso ripiego", async () => {
    vi.stubGlobal("navigator", {});
    render(<CopyValue value="xyz" what="il valore" />);
    fireEvent.click(screen.getByRole("button", { name: tu.copyLabel("il valore") }));
    await vi.waitFor(() => expect(screen.getByRole("status")).toHaveTextContent(tu.copyFailed));
  });
});

describe("OtpCard", () => {
  it("scheletro, poi codice a 6 cifre con spazio, secondi restanti locali e una sola richiesta per periodo", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const f = vi
      .fn()
      .mockResolvedValueOnce(Response.json({ code: "482913", remainingSeconds: 18 }))
      .mockResolvedValue(Response.json({ code: "111222", remainingSeconds: 30 }));
    vi.stubGlobal("fetch", f);
    render(<OtpCard seedHint="KZSXI4TJ…GI3EY2BB" />);
    expect(screen.getByTestId("otp-skeleton")).toBeInTheDocument();
    expect(await screen.findByTestId("otp-code")).toHaveTextContent("482 913");
    expect(screen.getByTestId("otp-remaining")).toHaveTextContent(tu.otpChanges(18));
    expect(screen.getByRole("progressbar")).toHaveAttribute("aria-valuenow", "18");

    // 10 secondi: il conto scende in locale, nessuna nuova richiesta.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(10_000);
    });
    expect(f).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId("otp-remaining")).toHaveTextContent(tu.otpChanges(8));

    // Al cambio di periodo una sola richiesta.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(8_500);
    });
    expect(f).toHaveBeenCalledTimes(2);
    expect(await screen.findByTestId("otp-code")).toHaveTextContent("111 222");
    expect(f.mock.calls[0][0]).toBe("/api/vetrina/totp");
  });

  it("errore: messaggio con «Riprova»", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response("{}", { status: 404 })));
    render(<OtpCard seedHint="x" />);
    expect(await screen.findByRole("alert")).toHaveTextContent(tu.otpError);
    expect(screen.getByRole("button", { name: tu.otpRetry })).toBeInTheDocument();
  });

  it("all'uscita annulla la richiesta in volo e il timer del prossimo codice", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const f = vi.fn(async (_url: string, init?: RequestInit) => {
      void init;
      return Response.json({ code: "482913", remainingSeconds: 18 });
    });
    vi.stubGlobal("fetch", f);
    const { unmount } = render(<OtpCard seedHint="x" />);
    await screen.findByTestId("otp-code");
    const signal = f.mock.calls[0][1]?.signal as AbortSignal;
    expect(signal.aborted).toBe(false);
    unmount();
    expect(signal.aborted).toBe(true);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(60_000);
    });
    expect(f).toHaveBeenCalledTimes(1);
  });

  // ADR-055, Q-727: scheda «Realm master», solo lo stato e il collegamento.
  const MASTER_URL = "https://idp.lh.test/admin/master/console/";

  it("realm master aperto: pill «Aperta», link attivo alla console master in nuova scheda", async () => {
    render(<TestUsers mode={MODE} user={null} masterConsole={{ state: "open", url: MASTER_URL }} />);
    await screen.findByTestId("otp-code");
    const card = screen.getByTestId("console-master");
    expect(card).toHaveAttribute("data-state", "open");
    expect(within(card).getByRole("heading", { name: tu.consoleMaster })).toBeInTheDocument();
    expect(within(card).getByText(tu.consoleMasterOpen)).toBeInTheDocument();
    expect(within(card).getByText(tu.consoleMasterText)).toBeInTheDocument();
    expect(within(card).getByText(tu.consoleMasterInfoOpen)).toBeInTheDocument();
    const link = within(card).getByRole("link", { name: new RegExp(tu.consoleOpen) });
    expect(link).toHaveAttribute("href", MASTER_URL);
    expect(link).toHaveAttribute("target", "_blank");
    expect(link.getAttribute("rel")).toContain("noopener");
    expect(within(card).queryByRole("button")).toBeNull();
  });

  it("realm master chiuso: pill «Chiusa», pulsante disabilitato e testo col nome del segreto", async () => {
    render(<TestUsers mode={MODE} user={null} masterConsole={{ state: "closed", url: MASTER_URL }} />);
    await screen.findByTestId("otp-code");
    const card = screen.getByTestId("console-master");
    expect(card).toHaveAttribute("data-state", "closed");
    expect(within(card).getByText(tu.consoleMasterClosed)).toBeInTheDocument();
    expect(within(card).getByText(tu.consoleMasterInfoClosed)).toBeInTheDocument();
    expect(card.textContent).toContain("LH_VETRINA_MASTER_ADMIN_PASSWORD");
    expect(within(card).getByRole("button", { name: new RegExp(tu.consoleOpen) })).toBeDisabled();
    expect(within(card).queryByRole("link")).toBeNull();
  });

  it("senza stato (file assente) la scheda master non c'è", async () => {
    render(<TestUsers mode={MODE} user={null} masterConsole={null} />);
    await screen.findByTestId("otp-code");
    expect(screen.queryByTestId("console-master")).toBeNull();
    expect(screen.getByTestId("console-loyaltyhub")).toBeInTheDocument();
  });

  it("il markup della scheda master non contiene mai credenziali", async () => {
    for (const state of ["open", "closed"] as const) {
      const { unmount } = render(<TestUsers mode={MODE} user={null} masterConsole={{ state, url: MASTER_URL }} />);
      await screen.findByTestId("otp-code");
      const html = screen.getByTestId("console-master").outerHTML;
      expect(html).not.toContain("KEYCLOAK_ADMIN_PASSWORD");
      expect(html).not.toContain(KEYCLOAK_ADMIN_PASSWORD);
      expect(html).not.toContain(OPERATORS_PASSWORD);
      expect(html).not.toContain(MEMBERS_PASSWORD);
      // «Password» come parola (il nome del segreto del codespace è tutto maiuscolo e compare solo nel testo di «Chiusa»).
      expect(html).not.toMatch(/Password/);
      expect(html).not.toContain(tu.copy);
      unmount();
    }
  });
});
