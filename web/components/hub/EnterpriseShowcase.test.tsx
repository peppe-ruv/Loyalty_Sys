import { afterEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen, within } from "@testing-library/react";
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

const KEYS = ["codespace", "ports", "web", "hub", "db", "kafka", "idpOperators", "idpMembers", "testMembers"] as const;
type Key = (typeof KEYS)[number];
type Checks = Record<Key, "ok" | "pending">;

/** Le prime `n` risorse (nell'ordine dell'elenco) pronte, le altre in attesa. */
const firstReady = (n: number): Checks => Object.fromEntries(KEYS.map((k, i) => [k, i < n ? "ok" : "pending"])) as Checks;
const ALL = firstReady(9);

interface Out {
  state?: string;
  ok?: boolean;
  checks?: Checks;
}

function stubApi(handler: (method: string) => Out | "throw") {
  const f = vi.fn(async (_url: string, init?: RequestInit) => {
    const out = handler(init?.method ?? "GET");
    if (out === "throw") throw new TypeError("rete");
    return Response.json({ state: out.state, url: null, ...(out.checks ? { checks: out.checks } : {}) }, { status: out.ok === false ? 502 : 200 });
  });
  vi.stubGlobal("fetch", f);
  return f;
}

const openLink = () => screen.queryByRole("link", { name: new RegExp(b.open) });
const phase = (k: Key) => screen.getByTestId(`check-${k}`).getAttribute("data-phase");
const advance = (ms: number) =>
  act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });

describe("EnterpriseBox (client)", () => {
  it("spenta: «Accendi…» con la nota; il clic manda un solo POST e passa a «in accensione» con elenco e pulsante disabilitato", async () => {
    const f = stubApi((m) => (m === "POST" ? { state: "starting" } : { state: "shutdown" }));
    render(<EnterpriseBox url={URL_OK} />);
    const start = await screen.findByRole("button", { name: new RegExp(b.start) });
    expect(screen.getByTestId("enterprise-pill")).toHaveTextContent(b.pill.off);
    fireEvent.click(start);
    const busy = await screen.findByRole("button", { name: /Accensione in corso/ });
    expect(busy).toBeDisabled();
    expect(screen.getByTestId("enterprise-pill")).toHaveTextContent(b.pill.starting);
    const bar = screen.getByRole("progressbar", { name: b.progressLabel });
    expect(bar).toHaveAttribute("aria-valuemax", "9");
    expect(bar).toHaveAttribute("aria-valuenow", "0");
    expect(within(screen.getByTestId("enterprise-checks")).getAllByRole("listitem")).toHaveLength(9);
    expect(f.mock.calls.filter((c) => (c[1] as RequestInit).method === "POST")).toHaveLength(1);
    expect(f.mock.calls[0][0]).toBe("/api/vetrina/codespace");
  });

  it("le nove risorse hanno le etichette del mockup, nell'ordine", async () => {
    stubApi((m) => (m === "POST" ? { state: "starting" } : { state: "shutdown" }));
    render(<EnterpriseBox url={URL_OK} />);
    fireEvent.click(await screen.findByRole("button", { name: new RegExp(b.start) }));
    await screen.findByTestId("enterprise-checks");
    const labels = within(screen.getByTestId("enterprise-checks"))
      .getAllByRole("listitem")
      .map((li) => li.children[1].firstChild?.textContent);
    expect(labels).toEqual([
      "Codespace acceso",
      "Porte pubbliche 8000 e 8001",
      "Web",
      "Servizi (hub)",
      "Postgres",
      "Kafka",
      "Accesso: realm operatori",
      "Accesso: realm membri",
      "Membri di test registrati",
    ]);
  });

  it("in accensione (codespace): solo «Codespace acceso» è in corso, le altre in attesa (frame 2)", async () => {
    stubApi((m) => (m === "POST" ? { state: "starting" } : { state: "starting" }));
    render(<EnterpriseBox url={URL_OK} />);
    await screen.findByRole("button", { name: /Accensione in corso/ });
    expect(phase("codespace")).toBe("wait");
    for (const k of KEYS.slice(1)) expect(phase(k)).toBe("todo");
    expect(screen.getByText(b.startingNote)).toBeInTheDocument();
    expect(openLink()).toBeNull();
  });

  it("già accesa con tutte le risorse pronte: «Apri la vetrina» subito, pillola «pronta», nessun timer di polling", async () => {
    const f = stubApi(() => ({ state: "available", checks: ALL }));
    render(<EnterpriseBox url={URL_OK} />);
    const link = await screen.findByRole("link", { name: new RegExp(b.open) });
    expect(link).toHaveAttribute("href", URL_OK);
    expect(link).toHaveAttribute("rel", "noopener");
    expect(screen.getByTestId("enterprise-pill")).toHaveTextContent(b.pill.on);
    expect(screen.getByText(b.allReady(9))).toBeInTheDocument();
    expect(screen.getByText(b.openNote)).toBeInTheDocument();
    expect(f).toHaveBeenCalledTimes(1);
  });

  it("«Già accesa» (pagina riaperta): prima «Controllo le risorse…» con spinner, poi il pulsante", async () => {
    let release: (r: Response) => void = () => undefined;
    vi.stubGlobal("fetch", vi.fn(() => new Promise<Response>((r) => (release = r))));
    render(<EnterpriseBox url={URL_OK} />);
    expect(screen.getByTestId("enterprise-pill")).toHaveTextContent(b.pill.loading);
    const busy = screen.getByRole("button", { name: new RegExp(b.checking) });
    expect(busy).toBeDisabled();
    expect(screen.getByTestId("enterprise-skeleton")).toBeInTheDocument();
    expect(screen.getByText(b.checkingNote)).toBeInTheDocument();
    expect(openLink()).toBeNull();
    release(Response.json({ state: "available", url: null, checks: ALL }));
    expect(await screen.findByRole("link", { name: new RegExp(b.open) })).toBeInTheDocument();
  });

  it("già accesa ma con risorse mancanti: l'elenco parte dal codespace pronto e il pulsante NON compare (8 su 9)", async () => {
    stubApi(() => ({ state: "available", checks: firstReady(8) }));
    render(<EnterpriseBox url={URL_OK} />);
    await screen.findByRole("button", { name: /Accensione in corso/ });
    expect(openLink()).toBeNull();
    expect(phase("codespace")).toBe("ok");
    expect(phase("kafka")).toBe("ok");
    expect(phase("idpMembers")).toBe("ok");
    expect(phase("testMembers")).toBe("wait");
    expect(screen.getByRole("progressbar")).toHaveAttribute("aria-valuenow", "8");
    expect(screen.getByTestId("enterprise-pill")).toHaveTextContent(b.pill.starting);
    expect(screen.getByText(b.resourcesNote)).toBeInTheDocument();
  });

  it("senza `checks` nella risposta (route vecchia o errore) il pulsante non compare mai", async () => {
    stubApi(() => ({ state: "available" }));
    render(<EnterpriseBox url={URL_OK} />);
    await screen.findByRole("button", { name: /Accensione in corso/ });
    expect(openLink()).toBeNull();
    expect(phase("codespace")).toBe("ok");
    expect(phase("ports")).toBe("wait");
  });

  it("avanzamento (frame 3): sei pronte, i due accessi in corso, i membri in attesa; poi tutto pronto e il polling si ferma", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    let checks: Checks = firstReady(1);
    const f = stubApi(() => ({ state: "available", checks }));
    render(<EnterpriseBox url={URL_OK} />);
    await screen.findByRole("button", { name: /Accensione in corso/ });
    expect(openLink()).toBeNull();

    checks = firstReady(6);
    await advance(POLL_MS);
    expect(phase("kafka")).toBe("ok");
    expect(phase("idpOperators")).toBe("wait");
    expect(phase("idpMembers")).toBe("wait");
    expect(phase("testMembers")).toBe("todo");
    expect(screen.getByRole("progressbar")).toHaveAttribute("aria-valuenow", "6");
    expect(screen.getByTestId("enterprise-status")).toHaveTextContent(b.progressStatus(6, 9));
    expect(openLink()).toBeNull();

    checks = ALL;
    await advance(POLL_MS);
    expect(await screen.findByRole("link", { name: new RegExp(b.open) })).toBeInTheDocument();
    expect(screen.getByTestId("enterprise-status")).toHaveTextContent(b.readyStatus);
    const calls = f.mock.calls.length;
    await advance(POLL_MS * 3);
    expect(f).toHaveBeenCalledTimes(calls);
  });

  it("polling ogni 10 s solo mentre si accende; quando il codespace è disponibile e tutto pronto il pulsante porta alla vetrina", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    let state: Out = { state: "starting" };
    const f = stubApi(() => state);
    render(<EnterpriseBox url={URL_OK} />);
    await screen.findByRole("button", { name: /Accensione in corso/ });
    expect(f).toHaveBeenCalledTimes(1);
    await advance(POLL_MS);
    expect(f).toHaveBeenCalledTimes(2);
    await advance(POLL_MS);
    expect(f).toHaveBeenCalledTimes(3);
    state = { state: "available", checks: ALL };
    await advance(POLL_MS);
    expect(await screen.findByRole("link", { name: new RegExp(b.open) })).toBeInTheDocument();
    const calls = f.mock.calls.length;
    await advance(POLL_MS * 3);
    expect(f).toHaveBeenCalledTimes(calls);
  });

  it("dopo il proprio avvio la tempistica compare accanto a ogni risorsa pronta e il totale in «Pronta»", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    let state: Out = { state: "shutdown" };
    stubApi((m) => (m === "POST" ? { state: "starting" } : state));
    render(<EnterpriseBox url={URL_OK} />);
    fireEvent.click(await screen.findByRole("button", { name: new RegExp(b.start) }));
    await screen.findByRole("button", { name: /Accensione in corso/ });
    await advance(70_000);
    state = { state: "available", checks: firstReady(6) };
    await advance(POLL_MS);
    expect(within(screen.getByTestId("check-codespace")).getByText(/^1:\d\d$/)).toBeInTheDocument();
    state = { state: "available", checks: ALL };
    await advance(POLL_MS);
    expect(await screen.findByRole("link", { name: new RegExp(b.open) })).toBeInTheDocument();
    expect(within(screen.getByTestId("enterprise-checks")).getByText(/^1:\d\d$/)).toBeInTheDocument();
  });

  it("mostra il tempo trascorso mentre si accende", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    stubApi((m) => (m === "POST" ? { state: "starting" } : { state: "shutdown" }));
    render(<EnterpriseBox url={URL_OK} />);
    fireEvent.click(await screen.findByRole("button", { name: new RegExp(b.start) }));
    await advance(3000);
    expect(screen.getByRole("button", { name: /Accensione in corso… 0:0[2-4]/ })).toBeDisabled();
  });

  it("tetto di 15 minuti (frame 5): «Non pronta» con la risorsa bloccata, «Controlla di nuovo» e nessuna lettura in più", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const stuck: Checks = { ...firstReady(6), idpOperators: "pending", idpMembers: "pending", testMembers: "pending" };
    const f = stubApi(() => ({ state: "available", checks: stuck }));
    render(<EnterpriseBox url={URL_OK} />);
    await screen.findByRole("button", { name: /Accensione in corso/ });
    await advance(MAX_STARTING_MS - 5_000);
    expect(screen.getByTestId("enterprise-pill")).toHaveTextContent(b.pill.starting);
    await advance(7_000);
    const alert = screen.getByRole("alert");
    expect(screen.getByTestId("enterprise-pill")).toHaveTextContent(b.pill.stuck);
    expect(alert).toHaveTextContent(b.stuckText.idpOperators);
    expect(within(alert).getByTestId("check-idpOperators")).toHaveTextContent(b.stuckState.idpOperators);
    expect(within(alert).getByTestId("check-testMembers")).toBeInTheDocument();
    expect(within(alert).getByText(/^Codespace, porte, web, servizi, Postgres, Kafka$/)).toBeInTheDocument();
    expect(within(alert).getByRole("button", { name: new RegExp(b.recheck) })).toBeInTheDocument();
    expect(alert).toHaveTextContent(b.stuckHint);
    expect(openLink()).toBeNull();
    const calls = f.mock.calls.length;
    await advance(POLL_MS * 3);
    expect(f).toHaveBeenCalledTimes(calls);
  });

  it("15 minuti con il codespace che GitHub non dice mai acceso: il blocco è il codespace", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    stubApi(() => ({ state: "starting" }));
    render(<EnterpriseBox url={URL_OK} />);
    await screen.findByRole("button", { name: /Accensione in corso/ });
    await advance(MAX_STARTING_MS + 2000);
    expect(screen.getByRole("alert")).toHaveTextContent(b.stuckText.codespace);
  });

  it("«Controlla di nuovo»: una lettura, non riaccende niente (nessun POST); se ora è tutto pronto compare il pulsante", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    let checks: Checks = { ...ALL, idpOperators: "pending" };
    const f = stubApi(() => ({ state: "available", checks }));
    render(<EnterpriseBox url={URL_OK} />);
    await screen.findByRole("button", { name: /Accensione in corso/ });
    await advance(MAX_STARTING_MS + 2000);
    expect(screen.getByRole("alert")).toBeInTheDocument();
    checks = ALL;
    fireEvent.click(screen.getByRole("button", { name: new RegExp(b.recheck) }));
    expect(await screen.findByRole("link", { name: new RegExp(b.open) })).toBeInTheDocument();
    expect(f.mock.calls.filter((c) => (c[1] as RequestInit).method === "POST")).toHaveLength(0);
  });

  it("«Controlla di nuovo» con la risorsa ancora ferma: torna «in accensione» e ricomincia il tetto di 15 minuti", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    stubApi(() => ({ state: "available", checks: { ...ALL, testMembers: "pending" } }));
    render(<EnterpriseBox url={URL_OK} />);
    await screen.findByRole("button", { name: /Accensione in corso/ });
    await advance(MAX_STARTING_MS + 2000);
    fireEvent.click(screen.getByRole("button", { name: new RegExp(b.recheck) }));
    await screen.findByRole("button", { name: /Accensione in corso/ });
    expect(screen.queryByRole("alert")).toBeNull();
    await advance(MAX_STARTING_MS - 20_000);
    expect(screen.queryByRole("alert")).toBeNull();
    await advance(25_000);
    expect(screen.getByRole("alert")).toHaveTextContent(b.stuckText.testMembers);
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
    expect(screen.getByRole("button", { name: new RegExp(b.retry) })).toBeInTheDocument();
  });

  it("POST a vetrina già accesa (senza `checks`): subito una lettura, che porta le risorse", async () => {
    const f = stubApi((m) => (m === "POST" ? { state: "available" } : { state: "shutdown" }));
    render(<EnterpriseBox url={URL_OK} />);
    const start = await screen.findByRole("button", { name: new RegExp(b.start) });
    f.mockImplementation(async (_u: string, init?: RequestInit) =>
      Response.json({ state: "available", url: null, ...(init?.method === "POST" ? {} : { checks: ALL }) }),
    );
    fireEvent.click(start);
    expect(await screen.findByRole("link", { name: new RegExp(b.open) })).toBeInTheDocument();
  });

  it("dopo il proprio avvio «spento» di GitHub resta «in accensione» per 90 s e il polling continua; poi torna «spenta»", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const f = stubApi((m) => (m === "POST" ? { state: "starting" } : { state: "shutdown" }));
    render(<EnterpriseBox url={URL_OK} />);
    fireEvent.click(await screen.findByRole("button", { name: new RegExp(b.start) }));
    await screen.findByRole("button", { name: /Accensione in corso/ });
    const before = f.mock.calls.length;
    await advance(POLL_MS * 3);
    expect(f.mock.calls.length).toBeGreaterThan(before);
    expect(screen.getByRole("button", { name: /Accensione in corso/ })).toBeDisabled();
    expect(screen.getByTestId("enterprise-pill")).toHaveTextContent(b.pill.starting);
    await advance(GRACE_MS);
    expect(await screen.findByRole("button", { name: new RegExp(b.start) })).toBeEnabled();
    expect(screen.getByTestId("enterprise-pill")).toHaveTextContent(b.pill.off);
  });

  it("senza un proprio avvio (pagina aperta a vetrina spenta) «spento» resta «spenta»", async () => {
    stubApi(() => ({ state: "shutdown" }));
    render(<EnterpriseBox url={URL_OK} />);
    expect(await screen.findByRole("button", { name: new RegExp(b.start) })).toBeEnabled();
  });

  it("stato non riconosciuto: errore dedicato", async () => {
    stubApi(() => ({ state: "unknown" }));
    render(<EnterpriseBox url={URL_OK} />);
    expect(await screen.findByRole("alert")).toHaveTextContent(b.errors.unknown);
  });

  it("accessibilità: stato annunciato in una regione role=status aria-live educata; l'orologio non è in una regione live", async () => {
    stubApi(() => ({ state: "available", checks: firstReady(3) }));
    render(<EnterpriseBox url={URL_OK} />);
    await screen.findByRole("button", { name: /Accensione in corso/ });
    const live = screen.getByRole("status");
    expect(live).toHaveAttribute("aria-live", "polite");
    expect(live).toHaveTextContent(b.progressStatus(3, 9));
    expect(screen.getByTestId("enterprise-action").closest("[aria-live]")).toBeNull();
    // Ogni risorsa dice il proprio stato anche a chi non vede le icone.
    expect(screen.getByTestId("check-web")).toHaveTextContent(`${b.checks.web}: ${b.item.ok}`);
    expect(screen.getByTestId("check-kafka")).toHaveTextContent(`${b.checks.kafka}: ${b.item.wait}`);
    expect(screen.getByTestId("check-testMembers")).toHaveTextContent(`${b.checks.testMembers}: ${b.item.todo}`);
  });
});
