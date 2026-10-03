import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { PersonaProvider } from "@/components/bo/PersonaContext";
import { it as t } from "@/lib/i18n/it";
import type { JobView, PreviewView } from "@/lib/vetrina/programma";
import { SampleProgramBox } from "./SampleProgramBox";

// BO-01 (V10) — riquadro «Carica il programma di esempio»: stati loading / vuoto / completo / errore / degradato,
// anteprima, avvio con CSRF, avanzamento ed esito (docs/08 BO-01, mockup V10 sezioni 1-3).

const s = t.sampleProgram;

vi.mock("next/navigation", () => ({ usePathname: () => "/backoffice", useRouter: () => ({ push: vi.fn() }) }));
// Il cookie `__Host-` non si può impostare in jsdom (http, senza Secure): il token CSRF si simula qui.
vi.mock("@/lib/auth/browser", async (orig) => ({
  ...(await orig<typeof import("@/lib/auth/browser")>()),
  csrfHeaders: (method?: string) => ((method ?? "GET").toUpperCase() === "POST" ? { "X-LH-CSRF": "csrf-prova" } : {}),
}));

const GROUPS = [
  { id: "currency-tiers", label: "Valute e livelli", create: 0, present: 5, skipped: 0, errors: 0 },
  { id: "actions", label: "Tipi di azione e fonte di test", create: 1, present: 12, skipped: 0, errors: 0 },
  { id: "rewards", label: "Premi (in bozza), fasce e coupon", create: 24, present: 0, skipped: 0, errors: 0 },
  { id: "campaigns", label: "Campagne (in bozza)", create: 15, present: 0, skipped: 5, errors: 0 },
];

function preview(over: Partial<PreviewView> = {}, stories: Partial<PreviewView["summary"]["stories"]> = {}): PreviewView {
  return {
    box: "pending",
    job: null,
    lastRun: null,
    summary: {
      actor: "marta.admin",
      groups: GROUPS,
      create: 40,
      present: 17,
      errors: 0,
      rewardsCreate: 14,
      campaignsCreate: 15,
      excluded: [{ what: "Webhook", why: "destinazione di rete in uscita" }],
      stories: { state: "waiting", reasons: ["in attesa delle campagne attive (CMP-PURCHASE-BASE)"], waitingFor: ["CMP-PURCHASE-BASE"], rows: 38, totalRows: 38, jobId: null, incompleteJobIds: [],
        members: [{ username: "anna.rossi", name: "Anna Rossi", rows: 2, state: "ready" }, { username: "marco.bianchi", name: "Marco Bianchi", rows: 14, state: "ready" }, { username: "giulia.ferri", name: "Giulia Ferri", rows: 22, state: "missing" }],
        ...stories },
    },
    ...over,
  };
}

function job(over: Partial<JobView> = {}): JobView {
  return { id: "J1", scope: "program", status: "running", phase: "config", done: 33, total: 60, message: "premi in bozza", actor: "marta.admin", startedAt: "2026-10-02T10:00:00Z", finishedAt: null, result: null, ...over };
}

const result = { created: 60, already: 0, failed: 0, auditVerified: 60, auditTotal: 60, rewardsDraft: 14, campaignsDraft: 15, storyRows: 0, problems: [] as string[] };

type Handler = (url: string, init?: RequestInit) => { status: number; body: unknown } | Promise<{ status: number; body: unknown }>;
let handler: Handler;
let calls: { url: string; init?: RequestInit }[];

function renderBox(sampleProgram: boolean | null = true) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <PersonaProvider value={{ username: "marta.admin", displayName: "Marta Villa", role: "ADMIN", mode: "enterprise", sampleProgram: sampleProgram ?? undefined }}>
        <SampleProgramBox />
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
afterEach(() => {
  vi.unstubAllGlobals();
});

describe("visibilità", () => {
  it("senza `sampleProgram` (non ADMIN, demo, fuori dalla vetrina) non c'è nulla e non parte nessuna richiesta", () => {
    handler = () => ({ status: 200, body: preview() });
    const { container } = renderBox(false);
    expect(container).toBeEmptyDOMElement();
    expect(calls).toHaveLength(0);
    const again = renderBox(null);
    expect(again.container).toBeEmptyDOMElement();
  });
});

describe("stati", () => {
  it("loading: messaggio di attesa", () => {
    handler = () => new Promise(() => undefined);
    renderBox();
    expect(screen.getByText(s.loading)).toBeInTheDocument();
    expect(screen.getByText(s.badge)).toBeInTheDocument();
  });

  it("programma vuoto: titolo, spiegazione e pulsante «Carica il programma di esempio»", async () => {
    handler = () => ({ status: 200, body: preview() });
    renderBox();
    expect(await screen.findByRole("heading", { name: s.emptyTitle })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: s.load })).toBeInTheDocument();
    expect(calls[0].url).toBe("/api/vetrina/programma");
  });

  it("completo: «Programma di esempio caricato» con chi e quando, e «Controlla di nuovo» che rifà l'anteprima", async () => {
    handler = () => ({ status: 200, body: preview({ box: "complete", lastRun: { finishedAt: "2026-10-02T10:00:00Z", by: "marta.admin", scope: "program" } }) });
    renderBox();
    expect(await screen.findByRole("heading", { name: s.completeTitle })).toBeInTheDocument();
    expect(screen.getByText(/Caricato il .* da marta\.admin/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: s.load })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: s.recheck }));
    await waitFor(() => expect(calls.filter((c) => c.url === "/api/vetrina/programma").length).toBe(2));
  });

  it("degradato (servizi non raggiungibili, 503): messaggio dedicato con «Riprova»", async () => {
    handler = () => ({ status: 503, body: { code: "HUB_UNAVAILABLE" } });
    renderBox();
    expect(await screen.findByTestId("sample-degraded")).toHaveTextContent(s.degradedTitle);
    handler = () => ({ status: 200, body: preview() });
    fireEvent.click(screen.getByRole("button", { name: s.retry }));
    expect(await screen.findByRole("heading", { name: s.emptyTitle })).toBeInTheDocument();
  });

  it("errore imprevisto: messaggio e «Riprova»", async () => {
    handler = () => ({ status: 500, body: { code: "X" } });
    renderBox();
    expect(await screen.findByTestId("sample-error")).toHaveTextContent(s.errorTitle);
    expect(screen.getByRole("button", { name: s.retry })).toBeInTheDocument();
  });

  it("storie in attesa delle campagne: elenco dei motivi e collegamento alle Approvazioni", async () => {
    handler = () => ({ status: 200, body: preview({ box: "stories-waiting" }) });
    renderBox();
    expect(await screen.findByRole("heading", { name: s.storiesWaitingTitle })).toBeInTheDocument();
    expect(screen.getByText(/in attesa delle campagne attive \(CMP-PURCHASE-BASE\)/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: s.linkApprovals })).toHaveAttribute("href", "/backoffice/governance/approvals");
    expect(screen.queryByRole("button", { name: s.loadStories })).toBeNull();
  });

  it("campagne attive: l'unico pulsante è «Carica le storie»", async () => {
    handler = () => ({ status: 200, body: preview({ box: "stories-ready" }, { state: "ready", reasons: [], waitingFor: [] }) });
    renderBox();
    expect(await screen.findByRole("button", { name: s.loadStories })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: s.load })).toBeNull();
  });
});

describe("anteprima e avvio", () => {
  it("anteprima: tabella da creare / già presenti, esclusi con il motivo, scritture a nome dell'operatore; Annulla non scrive", async () => {
    handler = () => ({ status: 200, body: preview() });
    renderBox();
    fireEvent.click(await screen.findByRole("button", { name: s.load }));
    const dialog = await screen.findByRole("dialog", { name: s.previewTitle });
    const table = within(dialog).getByTestId("preview-table");
    expect(within(table).getByText("Campagne (in bozza)")).toBeInTheDocument();
    expect(within(within(table).getByText("Premi (in bozza), fasce e coupon").closest("tr") as HTMLElement).getByText("24")).toBeInTheDocument();
    expect(within(dialog).getByText(s.writes(40, "marta.admin"))).toBeInTheDocument();
    expect(within(dialog).getByText(s.excludedTitle(1))).toBeInTheDocument();
    expect(within(dialog).getByText(/in attesa delle campagne attive/)).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole("button", { name: s.cancel }));
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(calls.every((c) => (c.init?.method ?? "GET") === "GET")).toBe(true);
  });

  it("conferma: POST con CSRF e scope «program», poi avanzamento «33 di 60», poi esito con premi e campagne da approvare", async () => {
    let polls = 0;
    handler = (url, init) => {
      if (init?.method === "POST") return { status: 202, body: { jobId: "J1" } };
      if (url.includes("?job=")) return { status: 200, body: ++polls < 3 ? job() : job({ status: "done", phase: "done", done: 60, finishedAt: "2026-10-02T10:01:00Z", message: "Fatto", result }) };
      return { status: 200, body: preview() };
    };
    renderBox();
    fireEvent.click(await screen.findByRole("button", { name: s.load }));
    fireEvent.click(await screen.findByRole("button", { name: s.confirm(40) }));
    const post = await waitFor(() => {
      const p = calls.find((c) => c.init?.method === "POST");
      expect(p).toBeDefined();
      return p!;
    });
    expect(post.url).toBe("/api/vetrina/programma");
    expect(post.init?.body).toBe(JSON.stringify({ scope: "program" }));
    expect(new Headers(post.init?.headers).get("x-lh-csrf")).toBe("csrf-prova");
    expect(await screen.findByTestId("sample-running")).toHaveTextContent("33 di 60 · premi in bozza");
    expect(screen.getByRole("progressbar")).toHaveAttribute("aria-valuenow", "55");
    const done = await screen.findByTestId("sample-done", undefined, { timeout: 6000 });
    expect(done).toHaveTextContent(s.doneSummary(60, 60, 60, "marta.admin"));
    expect(done).toHaveTextContent(s.approvalNote(14, 15));
    expect(within(done).getByRole("link", { name: s.linkApprovals })).toHaveAttribute("href", "/backoffice/governance/approvals");
    expect(within(done).getByRole("link", { name: s.linkCatalogue })).toHaveAttribute("href", "/backoffice/rewards");
    expect(within(done).getByRole("link", { name: s.linkImports })).toHaveAttribute("href", "/backoffice/observe/imports");
  }, 15000);

  it("errore a metà: «Caricati 33 di 60. Riprova per completare», con l'elenco dei problemi e «Riprova» che riapre l'anteprima", async () => {
    handler = (url, init) => {
      if (init?.method === "POST") return { status: 202, body: { jobId: "J1" } };
      if (url.includes("?job=")) {
        return { status: 200, body: job({ status: "error", phase: "done", message: "Caricati 33 di 60. Riprova per completare.", result: { ...result, created: 33, failed: 27, problems: ["reward-bands F1: HTTP 500"] } }) };
      }
      return { status: 200, body: preview() };
    };
    renderBox();
    fireEvent.click(await screen.findByRole("button", { name: s.load }));
    fireEvent.click(await screen.findByRole("button", { name: s.confirm(40) }));
    const err = await screen.findByTestId("sample-job-error");
    expect(err).toHaveTextContent("Caricati 33 di 60. Riprova per completare.");
    expect(err).toHaveTextContent("reward-bands F1: HTTP 500");
    fireEvent.click(within(err).getByRole("button", { name: s.retry }));
    expect(await screen.findByRole("dialog", { name: s.previewTitle })).toBeInTheDocument();
  });

  it("409 «già in corso» (anche di un altro operatore): il pannello si chiude e il riquadro mostra «in corso» senza leggere il lavoro altrui", async () => {
    let running = false;
    handler = (url, init) => {
      if (init?.method === "POST") {
        running = true;
        return { status: 409, body: { code: "JOB_RUNNING", jobId: "J9" } };
      }
      if (url.includes("?job=")) return { status: 404, body: { code: "JOB_NOT_FOUND" } };
      return { status: 200, body: running ? preview({ box: "running", job: job({ id: "J9", actor: "altro.admin" }) }) : preview() };
    };
    renderBox();
    fireEvent.click(await screen.findByRole("button", { name: s.load }));
    fireEvent.click(await screen.findByRole("button", { name: s.confirm(40) }));
    expect(await screen.findByText(s.runningTitle)).toBeInTheDocument();
    expect(calls.some((c) => c.url.includes("?job="))).toBe(false);
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("errore nella lettura dell'avanzamento: avviso con «Controlla di nuovo» e nessuna raffica di richieste", async () => {
    let jobReads = 0;
    handler = (url, init) => {
      if (init?.method === "POST") return { status: 202, body: { jobId: "J3" } };
      if (url.includes("?job=")) {
        jobReads++;
        return { status: 500, body: { code: "X" } };
      }
      return { status: 200, body: preview() };
    };
    renderBox();
    fireEvent.click(await screen.findByRole("button", { name: s.load }));
    fireEvent.click(await screen.findByRole("button", { name: s.confirm(40) }));
    const notice = await screen.findByTestId("sample-job-read-error");
    expect(within(notice).getByRole("button", { name: s.retry })).toBeInTheDocument();
    const reads = jobReads;
    await new Promise((r) => setTimeout(r, 1800));
    expect(jobReads).toBe(reads);
  });

  it("anteprima: il focus entra nel pannello, Esc chiude e il focus torna al pulsante", async () => {
    handler = () => ({ status: 200, body: preview() });
    renderBox();
    const button = await screen.findByRole("button", { name: s.load });
    button.focus();
    fireEvent.click(button);
    const dialog = await screen.findByRole("dialog", { name: s.previewTitle });
    await waitFor(() => expect(dialog).toHaveFocus());
    fireEvent.keyDown(document, { key: "Escape" });
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(button).toHaveFocus();
  });

  it("storie in attesa: il testo spiega invia-approva-pubblica e il link porta alle campagne", async () => {
    handler = () => ({ status: 200, body: preview({ box: "stories-waiting" }) });
    renderBox();
    expect(await screen.findByText(/invia in approvazione da Campagne e Catalogo/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: s.linkCampaigns })).toHaveAttribute("href", "/backoffice/campaigns");
  });

  it("storie: anteprima con i membri (uno non registrato) e import da avviare con scope «stories»", async () => {
    handler = (url, init) => {
      if (init?.method === "POST") return { status: 202, body: { jobId: "J2" } };
      if (url.includes("?job=")) return { status: 200, body: job({ id: "J2", scope: "stories", phase: "import", done: 10, total: 16, message: "Carico le storie dalla fonte di test" }) };
      return { status: 200, body: preview({ box: "stories-ready" }, { state: "ready", reasons: [], waitingFor: [], rows: 16 }) };
    };
    renderBox();
    fireEvent.click(await screen.findByRole("button", { name: s.loadStories }));
    const dialog = await screen.findByRole("dialog", { name: s.previewTitleStories });
    expect(within(dialog).getByText(s.memberMissing)).toBeInTheDocument();
    expect(within(dialog).getByText(s.writesStories(16, "marta.admin"))).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole("button", { name: s.confirmStories }));
    await waitFor(() => expect(calls.find((c) => c.init?.method === "POST")?.init?.body).toBe(JSON.stringify({ scope: "stories" })));
    expect(await screen.findByTestId("sample-running")).toHaveTextContent("10 di 16");
  });

  it("riprende un lavoro già in corso dopo un ricaricamento della pagina", async () => {
    handler = (url) => (url.includes("?job=") ? { status: 200, body: job({ done: 5, total: 60 }) } : { status: 200, body: preview({ box: "running", job: job() }) });
    renderBox();
    expect(await screen.findByTestId("sample-running")).toHaveTextContent("5 di 60");
  });

  it("lavoro non più noto (server riavviato): messaggio e «Controlla di nuovo»", async () => {
    handler = (url, init) => {
      if (init?.method === "POST") return { status: 202, body: { jobId: "J3" } };
      if (url.includes("?job=")) return { status: 404, body: { code: "JOB_NOT_FOUND" } };
      return { status: 200, body: preview() };
    };
    renderBox();
    fireEvent.click(await screen.findByRole("button", { name: s.load }));
    fireEvent.click(await screen.findByRole("button", { name: s.confirm(40) }));
    expect(await screen.findByText(s.jobLost)).toBeInTheDocument();
  });
});
