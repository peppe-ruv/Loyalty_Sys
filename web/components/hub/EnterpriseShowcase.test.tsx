import { afterEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen } from "@testing-library/react";
import { it as t } from "@/lib/i18n/it";
import { VETRINA_GUIDE_URL } from "@/lib/hub/links";
import { EnterpriseShowcase } from "./EnterpriseShowcase";
import { EnterpriseBox, GRACE_MS, MAX_STARTING_MS, POLL_MS } from "./EnterpriseBox";

// HUB-01, F2-DIST-09, ADR-049, Q-674: il riquadro «Modalità Enterprise» (sostituisce il vecchio pulsante in testata).

const URL_OK = "https://showcase.example.org";
const CONF = { LH_HUB_ENTERPRISE_URL: URL_OK, LH_VETRINA_CODESPACE: "verbose-space-abc123", LH_VETRINA_GITHUB_TOKEN: "github_pat_x" };
const b = t.enterpriseBox;

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe("EnterpriseShowcase (server)", () => {
  it("senza URL il riquadro non c'è", () => {
    const { container } = render(<EnterpriseShowcase env={{}} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("con un URL non valido il riquadro non c'è", () => {
    const { container } = render(<EnterpriseShowcase env={{ ...CONF, LH_HUB_ENTERPRISE_URL: "http://showcase.example.org" }} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("nel profilo enterprise (anche sconosciuto) è nascosto con un URL valido", () => {
    for (const LH_PROFILE of ["enterprise", "boh"]) {
      const { container } = render(<EnterpriseShowcase env={{ ...CONF, LH_PROFILE }} />);
      expect(container).toBeEmptyDOMElement();
    }
  });

  it("senza token o codespace: titolo, passi, nota «disponibile su richiesta» tratteggiata, nessuna chiamata", () => {
    const f = vi.fn();
    vi.stubGlobal("fetch", f);
    render(<EnterpriseShowcase env={{ LH_HUB_ENTERPRISE_URL: URL_OK }} />);
    expect(screen.getByRole("heading", { name: b.title })).toBeInTheDocument();
    expect(screen.getByTestId("enterprise-pill")).toHaveTextContent(b.pill.notConfigured);
    expect(screen.getByTestId("enterprise-not-configured")).toHaveTextContent(b.notConfigured);
    expect(screen.getByTestId("enterprise-not-configured").className).toMatch(/border-dashed/);
    expect(screen.queryByRole("button")).toBeNull();
    expect(screen.getByRole("list", { name: b.stepsLabel }).querySelectorAll("li")).toHaveLength(3);
    expect(f).not.toHaveBeenCalled();
  });

  it("il collegamento alla guida di Mintlify esiste in ogni caso e apre una nuova scheda", () => {
    render(<EnterpriseShowcase env={{ LH_HUB_ENTERPRISE_URL: URL_OK }} />);
    const link = screen.getByRole("link", { name: new RegExp(b.guide) });
    expect(link).toHaveAttribute("href", VETRINA_GUIDE_URL);
    expect(link).toHaveAttribute("target", "_blank");
    expect(link.getAttribute("rel")).toContain("noopener");
  });

  it("configurato: monta il riquadro con lo scheletro di caricamento", () => {
    vi.stubGlobal("fetch", vi.fn(() => new Promise<Response>(() => {})));
    render(<EnterpriseShowcase env={CONF} />);
    expect(screen.getByTestId("enterprise-skeleton")).toBeInTheDocument();
    expect(screen.getByTestId("enterprise-pill")).toHaveTextContent(b.pill.loading);
  });
});

function stubApi(handler: (method: string) => { state?: string; ok?: boolean } | "throw") {
  const f = vi.fn(async (_url: string, init?: RequestInit) => {
    const out = handler(init?.method ?? "GET");
    if (out === "throw") throw new TypeError("rete");
    return Response.json({ state: out.state, url: null }, { status: out.ok === false ? 502 : 200 });
  });
  vi.stubGlobal("fetch", f);
  return f;
}

describe("EnterpriseBox (client)", () => {
  it("spenta: «Accendi…» con la nota; il clic manda un solo POST e passa a «in accensione» con il pulsante disabilitato", async () => {
    const f = stubApi((m) => (m === "POST" ? { state: "starting" } : { state: "shutdown" }));
    render(<EnterpriseBox url={URL_OK} />);
    const start = await screen.findByRole("button", { name: new RegExp(b.start) });
    expect(screen.getByTestId("enterprise-pill")).toHaveTextContent(b.pill.off);
    fireEvent.click(start);
    const busy = await screen.findByRole("button", { name: /Accensione in corso/ });
    expect(busy).toBeDisabled();
    expect(screen.getByTestId("enterprise-pill")).toHaveTextContent(b.pill.starting);
    expect(screen.getByRole("progressbar")).toBeInTheDocument();
    expect(f.mock.calls.filter((c) => (c[1] as RequestInit).method === "POST")).toHaveLength(1);
    expect(f.mock.calls[0][0]).toBe("/api/vetrina/codespace");
  });

  it("accesa: «Apri la vetrina» verso LH_HUB_ENTERPRISE_URL, nessun timer di polling", async () => {
    const f = stubApi(() => ({ state: "available" }));
    render(<EnterpriseBox url={URL_OK} />);
    const link = await screen.findByRole("link", { name: new RegExp(b.open) });
    expect(link).toHaveAttribute("href", URL_OK);
    expect(link).toHaveAttribute("rel", "noopener");
    expect(screen.getByTestId("enterprise-pill")).toHaveTextContent(b.pill.on);
    expect(f).toHaveBeenCalledTimes(1);
  });

  it("polling ogni 10 s solo mentre si accende; quando diventa disponibile il pulsante porta alla vetrina e il polling si ferma", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    let state = "starting";
    const f = stubApi(() => ({ state }));
    render(<EnterpriseBox url={URL_OK} />);
    await screen.findByRole("button", { name: /Accensione in corso/ });
    expect(f).toHaveBeenCalledTimes(1);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(POLL_MS);
    });
    expect(f).toHaveBeenCalledTimes(2);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(POLL_MS);
    });
    expect(f).toHaveBeenCalledTimes(3);

    state = "available";
    await act(async () => {
      await vi.advanceTimersByTimeAsync(POLL_MS);
    });
    expect(await screen.findByRole("link", { name: new RegExp(b.open) })).toBeInTheDocument();
    const calls = f.mock.calls.length;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(POLL_MS * 3);
    });
    expect(f).toHaveBeenCalledTimes(calls);
  });

  it("mostra il tempo trascorso mentre si accende", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    stubApi((m) => (m === "POST" ? { state: "starting" } : { state: "shutdown" }));
    render(<EnterpriseBox url={URL_OK} />);
    fireEvent.click(await screen.findByRole("button", { name: new RegExp(b.start) }));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(3000);
    });
    expect(screen.getByRole("button", { name: /Accensione in corso… 0:0[2-4]/ })).toBeDisabled();
  });

  it("errore di rete in lettura: messaggio con role=alert e «Riprova» che rilegge lo stato", async () => {
    let fail = true;
    stubApi(() => (fail ? "throw" : { state: "shutdown" }));
    render(<EnterpriseBox url={URL_OK} />);
    expect(await screen.findByRole("alert")).toHaveTextContent(b.errors.read);
    expect(screen.getByTestId("enterprise-pill")).toHaveTextContent(b.pill.error);
    fail = false;
    fireEvent.click(screen.getByRole("button", { name: new RegExp(b.retry) }));
    expect(await screen.findByRole("button", { name: new RegExp(b.start) })).toBeInTheDocument();
  });

  it("errore (502) all'avvio: torna l'errore con «Riprova»", async () => {
    stubApi((m) => (m === "POST" ? { ok: false } : { state: "shutdown" }));
    render(<EnterpriseBox url={URL_OK} />);
    fireEvent.click(await screen.findByRole("button", { name: new RegExp(b.start) }));
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent(b.errors.start);
    expect(alert.textContent).not.toMatch(/non si è accesa/);
    expect(screen.getByRole("button", { name: new RegExp(b.retry) })).toBeInTheDocument();
  });

  it("dopo il proprio avvio «spento» di GitHub resta «in accensione» per 90 s e il polling continua; poi torna «spenta»", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const f = stubApi((m) => (m === "POST" ? { state: "starting" } : { state: "shutdown" }));
    render(<EnterpriseBox url={URL_OK} />);
    fireEvent.click(await screen.findByRole("button", { name: new RegExp(b.start) }));
    await screen.findByRole("button", { name: /Accensione in corso/ });
    const before = f.mock.calls.length;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(POLL_MS * 3);
    });
    expect(f.mock.calls.length).toBeGreaterThan(before);
    expect(screen.getByRole("button", { name: /Accensione in corso/ })).toBeDisabled();
    expect(screen.getByTestId("enterprise-pill")).toHaveTextContent(b.pill.starting);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(GRACE_MS);
    });
    expect(await screen.findByRole("button", { name: new RegExp(b.start) })).toBeEnabled();
    expect(screen.getByTestId("enterprise-pill")).toHaveTextContent(b.pill.off);
  });

  it("senza un proprio avvio (pagina aperta a vetrina spenta) «spento» resta «spenta»", async () => {
    stubApi(() => ({ state: "shutdown" }));
    render(<EnterpriseBox url={URL_OK} />);
    expect(await screen.findByRole("button", { name: new RegExp(b.start) })).toBeEnabled();
  });

  it("tetto del polling: dopo 10 minuti ancora «in accensione» ⇒ errore con «Riprova» e nessuna lettura in più", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const f = stubApi(() => ({ state: "starting" }));
    render(<EnterpriseBox url={URL_OK} />);
    await screen.findByRole("button", { name: /Accensione in corso/ });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(MAX_STARTING_MS + 2000);
    });
    expect(screen.getByRole("alert")).toHaveTextContent(b.errors.timeout);
    expect(screen.getByRole("button", { name: new RegExp(b.retry) })).toBeInTheDocument();
    const calls = f.mock.calls.length;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(POLL_MS * 3);
    });
    expect(f).toHaveBeenCalledTimes(calls);
  });

  it("stato non riconosciuto: errore dedicato", async () => {
    stubApi(() => ({ state: "unknown" }));
    render(<EnterpriseBox url={URL_OK} />);
    expect(await screen.findByRole("alert")).toHaveTextContent(b.errors.unknown);
  });

  it("l'area d'azione è una regione aria-live educata", async () => {
    stubApi(() => ({ state: "shutdown" }));
    render(<EnterpriseBox url={URL_OK} />);
    await screen.findByRole("button", { name: new RegExp(b.start) });
    expect(screen.getByTestId("enterprise-action")).toHaveAttribute("aria-live", "polite");
  });
});
