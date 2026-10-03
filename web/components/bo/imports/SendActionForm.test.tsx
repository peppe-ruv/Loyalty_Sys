import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { PersonaProvider } from "@/components/bo/PersonaContext";
import { it as t } from "@/lib/i18n/it";
import type { ActionContext, ActionResult } from "@/lib/vetrina/azione";
import { SendActionForm } from "./SendActionForm";

// BO-32 (V11) — modulo «Invia un'azione»: visibilità, stati loading / senza programma / errore / degradato, campo
// importo solo per le azioni con valore, invio con CSRF, esito con punti e livello, audit, regione viva (docs/08 BO-32).

const s = t.sendAction;

vi.mock("next/navigation", () => ({ usePathname: () => "/backoffice/observe/imports", useRouter: () => ({ push: vi.fn() }) }));
vi.mock("@/lib/auth/browser", async (orig) => ({
  ...(await orig<typeof import("@/lib/auth/browser")>()),
  csrfHeaders: (method?: string) => ((method ?? "GET").toUpperCase() === "POST" ? { "X-LH-CSRF": "csrf-prova" } : {}),
}));

const CTX: ActionContext = {
  ready: true,
  members: [
    { key: "anna.rossi", name: "Anna Rossi", registered: true, tier: "Base" },
    { key: "marco.bianchi", name: "Marco Bianchi", registered: false, tier: null },
    { key: "giulia.ferri", name: "Giulia Ferri", registered: true, tier: "Silver" },
  ],
  actions: [
    { type: "purchase.completed", label: "Acquisto completato", valued: true },
    { type: "app.login.daily", label: "Accesso giornaliero all'app", valued: false },
  ],
};

function result(over: Partial<ActionResult> = {}): ActionResult {
  return {
    importId: "01J0000000000000000000001", status: "done", final: true, importStatus: "DONE", outcome: "accepted",
    member: { key: "giulia.ferri", name: "Giulia Ferri" }, type: "purchase.completed", label: "Acquisto completato", amount: 150,
    before: { points: 5000, pending: 0, tier: "Silver" }, after: { points: 5150, pending: 0, tier: "Gold" }, pointsDelta: 150, stsDelta: 150,
    pendingPoints: 0, pointsKnown: true, tierChanged: true,
    audit: { state: "verified", actor: "marta.admin", reason: null }, problems: [], startedAt: "2026-10-02T10:00:00Z", ...over,
  };
}

type Handler = (url: string, init?: RequestInit) => { status: number; body: unknown } | Promise<{ status: number; body: unknown }>;
let handler: Handler;
let calls: { url: string; init?: RequestInit }[];

function renderForm(sendAction: boolean | null = true) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <PersonaProvider value={{ username: "marta.admin", displayName: "Marta Villa", role: "ADMIN", mode: "enterprise", sendAction: sendAction ?? undefined }}>
        <SendActionForm />
      </PersonaProvider>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  calls = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string, init?: RequestInit) => {
      calls.push({ url: String(url), init });
      const r = await handler(String(url), init);
      return new Response(JSON.stringify(r.body), { status: r.status, headers: { "content-type": "application/json" } });
    }),
  );
});
afterEach(() => vi.unstubAllGlobals());

const pick = (label: string, value: string) => fireEvent.change(screen.getByLabelText(label), { target: { value } });

describe("visibilità", () => {
  it("senza `sendAction` (demo, ruolo senza accesso, fuori dalla vetrina) non c'è nulla e non parte nessuna richiesta", () => {
    handler = () => ({ status: 200, body: CTX });
    expect(renderForm(false).container).toBeEmptyDOMElement();
    expect(renderForm(null).container).toBeEmptyDOMElement();
    expect(calls).toHaveLength(0);
  });
});

describe("stati", () => {
  it("loading: messaggio di attesa", () => {
    handler = () => new Promise(() => undefined);
    renderForm();
    expect(screen.getByText(s.loading)).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: s.title })).toBeInTheDocument();
    expect(screen.getByText(s.source)).toBeInTheDocument();
  });

  it("senza programma: «Carica prima il programma di esempio» con il collegamento alla Dashboard, nessun modulo", async () => {
    handler = () => ({ status: 200, body: { ...CTX, ready: false } });
    renderForm();
    expect(await screen.findByText(s.needProgramTitle)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: s.needProgramLink })).toHaveAttribute("href", "/backoffice");
    expect(screen.queryByRole("button", { name: s.submit })).toBeNull();
  });

  it("degradato (503): messaggio dedicato con «Riprova» che rilegge", async () => {
    handler = () => ({ status: 503, body: { code: "HUB_UNAVAILABLE" } });
    renderForm();
    expect(await screen.findByTestId("send-degraded")).toHaveTextContent(s.degradedTitle);
    fireEvent.click(screen.getByRole("button", { name: s.retry }));
    await waitFor(() => expect(calls.length).toBe(2));
  });

  it("errore imprevisto (500): messaggio generico con «Riprova»", async () => {
    handler = () => ({ status: 500, body: {} });
    renderForm();
    expect(await screen.findByTestId("send-error")).toHaveTextContent(s.errorTitle);
  });
});

describe("modulo", () => {
  it("campi con etichetta; membri con nome e livello, quello non registrato disattivato; azioni in italiano", async () => {
    handler = () => ({ status: 200, body: CTX });
    renderForm();
    const member = await screen.findByLabelText(s.member);
    expect(screen.getByRole("option", { name: "Giulia Ferri · Silver" })).toBeEnabled();
    expect(screen.getByRole("option", { name: `Marco Bianchi · ${s.memberMissing}` })).toBeDisabled();
    expect(member).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "Acquisto completato" })).toBeInTheDocument();
    expect(screen.getByText(s.note("marta.admin"))).toBeInTheDocument();
  });

  it("l'importo compare solo per le azioni con valore; il pulsante resta spento finché manca qualcosa", async () => {
    handler = () => ({ status: 200, body: CTX });
    renderForm();
    await screen.findByLabelText(s.member);
    const submit = screen.getByRole("button", { name: s.submit });
    expect(screen.queryByLabelText(s.amount)).toBeNull();
    expect(submit).toBeDisabled();
    pick(s.member, "giulia.ferri");
    pick(s.action, "app.login.daily");
    expect(screen.queryByLabelText(s.amount)).toBeNull();
    expect(submit).toBeEnabled();
    pick(s.action, "purchase.completed");
    expect(screen.getByLabelText(s.amount)).toBeInTheDocument();
    expect(submit).toBeDisabled();
    fireEvent.change(screen.getByLabelText(s.amount), { target: { value: "150" } });
    expect(submit).toBeEnabled();
  });

  it("invio: POST con CSRF e solo nome utente, tipo e importo (mai un id di membro); poi l'esito con punti, livello e audit", async () => {
    handler = (url, init) => {
      if (init?.method === "POST") return { status: 202, body: { importId: "01J0000000000000000000001" } };
      if (url.includes("?import=")) return { status: 200, body: result() };
      return { status: 200, body: CTX };
    };
    renderForm();
    await screen.findByLabelText(s.member);
    pick(s.member, "giulia.ferri");
    pick(s.action, "purchase.completed");
    fireEvent.change(screen.getByLabelText(s.amount), { target: { value: "150" } });
    fireEvent.click(screen.getByRole("button", { name: s.submit }));

    const post = await waitFor(() => {
      const c = calls.find((x) => x.init?.method === "POST");
      expect(c).toBeDefined();
      return c!;
    });
    expect(post.url).toBe("/api/vetrina/azione");
    expect((post.init?.headers as Record<string, string>)["X-LH-CSRF"]).toBe("csrf-prova");
    expect(JSON.parse(String(post.init?.body))).toEqual({ username: "giulia.ferri", type: "purchase.completed", amount: "150" });

    const out = await screen.findByTestId("send-result");
    expect(out).toHaveTextContent("Giulia Ferri · Acquisto completato");
    expect(out).toHaveTextContent("accettata");
    expect(out).toHaveTextContent("+150 punti");
    expect(out).toHaveTextContent("nuovo livello Gold");
    expect(out).toHaveTextContent(s.auditVerified("marta.admin"));
    expect(screen.getByRole("link", { name: s.openImport })).toHaveAttribute("href", "/backoffice/observe/imports?i=01J0000000000000000000001");
    expect(screen.getByRole("link", { name: s.openMembers })).toHaveAttribute("href", "/backoffice/members");
    // Regione viva per i lettori di schermo.
    expect(screen.getByTestId("send-last")).toHaveAttribute("aria-live", "polite");
    expect(screen.getByTestId("send-last")).toHaveAttribute("role", "status");
  });

  it("azione senza importo: il corpo non lo contiene", async () => {
    handler = (url, init) => {
      if (init?.method === "POST") return { status: 202, body: { importId: "01J1" } };
      if (url.includes("?import=")) return { status: 200, body: result({ type: "app.login.daily", label: "Accesso giornaliero all'app", amount: null, pointsDelta: 5, tierChanged: false }) };
      return { status: 200, body: CTX };
    };
    renderForm();
    await screen.findByLabelText(s.member);
    pick(s.member, "anna.rossi");
    pick(s.action, "app.login.daily");
    fireEvent.click(screen.getByRole("button", { name: s.submit }));
    await screen.findByTestId("send-result");
    const post = calls.find((x) => x.init?.method === "POST")!;
    expect(JSON.parse(String(post.init?.body))).toEqual({ username: "anna.rossi", type: "app.login.daily" });
  });

  it("errori dell'invio: programma mancante (409), membro non registrato (422), importo (400), generico", async () => {
    const cases: [number, string, string][] = [
      [409, "PROGRAM_MISSING", s.programMissing],
      [422, "MEMBER_NOT_REGISTERED", s.memberNotRegistered],
      [400, "INVALID_AMOUNT", s.badAmount],
      [400, "INVALID_MEMBER", s.badMember],
      [400, "INVALID_TYPE", s.badType],
      [400, "BAD_REQUEST", s.sendFailed],
      [409, "SEND_RUNNING", s.sendRunning],
      [503, "HUB_UNAVAILABLE", s.sendFailed],
    ];
    for (const [status, code, text] of cases) {
      handler = (_, init) => (init?.method === "POST" ? { status, body: { code } } : { status: 200, body: CTX });
      const { unmount } = renderForm();
      await screen.findByLabelText(s.member);
      pick(s.member, "giulia.ferri");
      pick(s.action, "purchase.completed");
      fireEvent.change(screen.getByLabelText(s.amount), { target: { value: "10" } });
      fireEvent.click(screen.getByRole("button", { name: s.submit }));
      expect(await screen.findByTestId("send-form-error")).toHaveTextContent(text);
      unmount();
    }
  });
});

describe("esito", () => {
  async function submitWith(res: ActionResult | { status: number; body: unknown }) {
    handler = (url, init) => {
      if (init?.method === "POST") return { status: 202, body: { importId: "01J1" } };
      if (url.includes("?import=")) return "importId" in res ? { status: 200, body: res } : res;
      return { status: 200, body: CTX };
    };
    renderForm();
    await screen.findByLabelText(s.member);
    pick(s.member, "anna.rossi");
    pick(s.action, "app.login.daily");
    fireEvent.click(screen.getByRole("button", { name: s.submit }));
  }

  it("in elaborazione: «In elaborazione…» e il risultato si aggiorna da solo", async () => {
    await submitWith(result({ status: "running", final: false, outcome: "pending", after: null, pointsDelta: null, tierChanged: false }));
    expect(await screen.findByText(new RegExp(s.processing))).toBeInTheDocument();
  });

  it("punti non ancora arrivati: lo dice, senza inventare numeri", async () => {
    await submitWith(result({ final: false, pointsDelta: null, stsDelta: null, tierChanged: false, after: { points: 5000, pending: 0, tier: "Silver" }, audit: { state: "pending", actor: "marta.admin", reason: null } }));
    const out = await screen.findByTestId("send-result");
    expect(out).toHaveTextContent(s.pointsPending);
    expect(out).toHaveTextContent(s.auditPending);
    expect(out).not.toHaveTextContent(/\+\d/);
  });

  it("finale senza variazione: «nessuna variazione dei punti»", async () => {
    await submitWith(result({ pointsDelta: null, tierChanged: false }));
    expect(await screen.findByText(s.pointsNone)).toBeInTheDocument();
  });

  it("righe non accettate e audit mancante: errori espliciti con il rinvio al dettaglio", async () => {
    await submitWith(result({
      status: "error", outcome: "invalid", after: null, pointsDelta: null, tierChanged: false,
      audit: { state: "missing", actor: "marta.admin", reason: "voce di audit mancante" },
      problems: ["La riga non è stata accettata: apri il dettaglio dell'import per il rapporto."],
    }));
    const out = await screen.findByTestId("send-result");
    expect(out).toHaveTextContent(s.outcomeInvalid);
    expect(out).toHaveTextContent("apri il dettaglio dell'import");
    expect(out).toHaveTextContent(s.auditMissing("voce di audit mancante"));
    expect(screen.getByRole("link", { name: s.openImport })).toBeInTheDocument();
  });

  it("libro mastro non leggibile: «punti sconosciuti», non «nessuna variazione»", async () => {
    await submitWith(result({ pointsDelta: null, stsDelta: null, pointsKnown: false, tierChanged: false, before: null, after: null }));
    const out = await screen.findByTestId("send-result");
    expect(out).toHaveTextContent(s.pointsUnknown);
    expect(out).not.toHaveTextContent(s.pointsNone);
  });

  it("variazione negativa: senza il colore dell'accredito; punti di livello e quota in attesa mostrati", async () => {
    await submitWith(result({ pointsDelta: -40, stsDelta: null, tierChanged: false }));
    const neg = (await screen.findByTestId("send-result")).querySelector("strong") as HTMLElement;
    expect(neg).toHaveTextContent("-40 punti");
    expect(neg.className).not.toContain("state-up");
  });

  it("accredito con punti di livello e parte in attesa", async () => {
    await submitWith(result({ pointsDelta: 150, stsDelta: 150, pendingPoints: 150, tierChanged: false }));
    const out = await screen.findByTestId("send-result");
    expect(out.querySelector("strong")?.className).toContain("state-up");
    expect(out).toHaveTextContent("+150 punti di livello");
    expect(out).toHaveTextContent(s.pointsPendingPart(150));
    expect(out).not.toHaveTextContent(s.pointsNone);
  });

  it("niente role=alert dentro la regione role=status", async () => {
    await submitWith({ status: 500, body: {} });
    await screen.findByText(s.readError);
    expect(screen.getByTestId("send-last").querySelector('[role="alert"]')).toBeNull();
  });

  it("prima della prima lettura non c'è un riepilogo vuoto: «Invio in corso…»", async () => {
    handler = (url, init) => {
      if (init?.method === "POST") return { status: 202, body: { importId: "01J1" } };
      if (url.includes("?import=")) return new Promise(() => undefined);
      return { status: 200, body: CTX };
    };
    renderForm();
    await screen.findByLabelText(s.member);
    pick(s.member, "anna.rossi");
    pick(s.action, "app.login.daily");
    fireEvent.click(screen.getByRole("button", { name: s.submit }));
    const last = await screen.findByTestId("send-last");
    await waitFor(() => expect(last).toHaveTextContent(s.sending));
    expect(last.textContent?.trim()).not.toBe(s.lastTitle);
  });

  it("l'invio resta spento finché l'esito non è definitivo; a esito definitivo si può inviare ancora e il menu dei membri si rilegge", async () => {
    let final = false;
    handler = (url, init) => {
      if (init?.method === "POST") return { status: 202, body: { importId: "01J1" } };
      if (url.includes("?import=")) return { status: 200, body: result({ final, status: final ? "done" : "running", outcome: final ? "accepted" : "pending" }) };
      return { status: 200, body: CTX };
    };
    renderForm();
    await screen.findByLabelText(s.member);
    pick(s.member, "anna.rossi");
    pick(s.action, "app.login.daily");
    fireEvent.click(screen.getByRole("button", { name: s.submit }));
    await screen.findByTestId("send-last");
    const btn = () => screen.getByRole("button", { name: new RegExp(`${s.sending}|${s.submit}`) });
    await waitFor(() => expect(btn()).toBeDisabled());
    const contextReads = () => calls.filter((c) => c.url === "/api/vetrina/azione").length;
    const before = contextReads();
    final = true;
    await waitFor(() => expect(btn()).toBeEnabled(), { timeout: 4000 });
    expect(btn()).toHaveTextContent(s.submit);
    await waitFor(() => expect(contextReads()).toBeGreaterThan(before));
  });

  it("l'azione «reso» mostra che non storna i punti", async () => {
    handler = () => ({ status: 200, body: { ...CTX, actions: [...CTX.actions, { type: "purchase.returned", label: "Reso di un acquisto (non storna punti)", valued: true }] } });
    renderForm();
    await screen.findByLabelText(s.member);
    expect(screen.queryByText(s.returnHelp)).toBeNull();
    pick(s.action, "purchase.returned");
    expect(screen.getByText(s.returnHelp)).toBeInTheDocument();
  });

  it("lettura dell'esito non riuscita: messaggio con «Riprova» e il collegamento agli import", async () => {
    await submitWith({ status: 500, body: {} });
    expect(await screen.findByText(s.readError)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: s.retry })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: s.openImport })).toBeInTheDocument();
  });

  it("invio non più noto (404): lo dice, senza «Riprova»", async () => {
    await submitWith({ status: 404, body: { code: "IMPORT_NOT_FOUND" } });
    expect(await screen.findByText(s.lost)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: s.retry })).toBeNull();
  });
});
