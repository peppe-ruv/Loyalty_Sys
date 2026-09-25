import { beforeEach, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { rows } from "@/test/testbook";
import type { WalletView } from "@/lib/api/types";
import PortalHome from "./page";

// Testbook TB-WEB §HOME: PT-01 (docs/09 §PT-01, §2 regole trasversali; docs/03 §4.3 "Progresso mostrato al membro").
// I dati arrivano dai servizi: qui le query sono simulate con lo stato voluto (dati, caricamento, errore).

type Fake = { data?: unknown; isLoading?: boolean; isError?: boolean; error?: unknown; isFetched?: boolean };
let queries: Record<string, Fake>;

vi.mock("@/lib/api/client", () => ({
  useLhQuery: (service: string, path: string) => {
    const q = queries[`${service} ${path}`] ?? { data: undefined };
    return { isLoading: false, isError: false, error: null, isFetched: true, refetch: vi.fn(), ...q };
  },
}));
vi.mock("@/components/portal/MemberContext", () => ({ useActiveMember: () => "MBR-000002" }));
const markPending = vi.fn();
vi.mock("@/components/portal/PendingContext", () => ({ usePending: () => ({ pending: false, markPending }) }));
vi.mock("@/components/portal/ContentSlot", () => ({ ContentSlot: () => null }));
vi.mock("@/components/portal/PopupHost", () => ({ PopupHost: () => null }));
vi.mock("@/components/portal/parts", () => ({ PendingBanner: () => null, ActivityRow: () => null }));

function wallet(over: Partial<WalletView["tier"]> = {}, extra: Partial<WalletView> & { tierExtra?: Record<string, unknown> } = {}): WalletView {
  const { tierExtra, ...rest } = extra;
  return {
    memberId: "MBR-000002",
    balances: { PTS: { active: 1850, pending: 0, lifetimeEarned: 5000, lifetimeSpent: 0 } },
    tier: { code: "SILVER", name: "Silver", since: null, periodSts: 2880, multiplier: 1.25, progressPct: 57, next: { code: "GOLD", threshold: 3000, missing: 120 }, ...over, ...(tierExtra ?? {}) },
    ...rest,
  } as WalletView;
}

function setup(w: Fake, status = "ACTIVE") {
  queries = {
    "wallet /v1/portal/wallets/MBR-000002": w,
    "member /v1/members/MBR-000002": { data: { id: "MBR-000002", firstName: "Giulia", lastName: "Neri", status } },
    "wallet /v1/portal/wallets/MBR-000002/activity": { data: [] },
  };
  return render(<PortalHome />);
}

beforeEach(() => {
  queries = {};
  markPending.mockReset();
  window.history.replaceState(null, "", "/portal");
});

it("[TB-WEB-HOME-001] SILVER, mancano 120 STS a GOLD → «Ti mancano 120 punti status per GOLD»", () => {
  setup({ data: wallet() });
  expect(document.body.textContent).toContain("Ti mancano 120 punti status per GOLD");
});

it("[TB-WEB-HOME-002] manca 1 punto status (soglia − 1) → «Ti manca 1 punto status per GOLD»", () => {
  setup({ data: wallet({ periodSts: 2999, next: { code: "GOLD", threshold: 3000, missing: 1 } }) });
  expect(document.body.textContent).toContain("Ti manca 1 punto status per GOLD");
});

it("[TB-WEB-HOME-003] mancano 2.350 → separatore delle migliaia", () => {
  setup({ data: wallet({ code: "BASE", periodSts: 650, next: { code: "GOLD", threshold: 3000, missing: 2350 } }) });
  expect(document.body.textContent).toContain("Ti mancano 2.350 punti status per GOLD");
});

it("[TB-WEB-HOME-004] livello massimo (PLATINUM, nessun successivo) → «Hai raggiunto il livello più alto»", () => {
  setup({ data: wallet({ code: "PLATINUM", next: null, progressPct: 100 }) });
  expect(document.body.textContent).toContain("Hai raggiunto il livello più alto");
  expect(document.body.textContent).not.toContain("Ti mancano");
});

it.each(
  rows([
    { id: "TB-WEB-HOME-005", desc: "appena salito (periodSts = soglia, progresso 0 %) → barra vuota", pct: 0 },
    { id: "TB-WEB-HOME-006", desc: "a metà (57 %) → barra al 57 %", pct: 57 },
    { id: "TB-WEB-HOME-007", desc: "livello massimo (100 %) → barra piena", pct: 100 },
  ]),
)("[%s] barra verso il prossimo livello: %s", (_id, _desc, { pct }) => {
  const { container } = setup({ data: wallet({ progressPct: pct }) });
  const bars = [...container.querySelectorAll<HTMLElement>("[style]")].filter((e) => e.style.width.endsWith("%"));
  expect(bars.map((b) => b.style.width)).toContain(`${pct}%`);
});

it("[TB-WEB-HOME-008] keepWarning GOLD, mancano 2.350 → «Per mantenere GOLD servono ancora 2.350 punti status…»", () => {
  setup({ data: wallet({ code: "GOLD", next: { code: "PLATINUM", threshold: 7000, missing: 6350 } }, { tierExtra: { keepWarning: { tier: "GOLD", missing: 2350 } } }) });
  // La data ("entro il 31 dic") è la fine dell'edizione, che keepWarning {tier, missing} non porta: si verifica il resto.
  expect(document.body.textContent).toContain("Per mantenere GOLD servono ancora 2.350 punti status");
});

it("[TB-WEB-HOME-009] 1.900 punti in scadenza il 31 ott → avviso «1.900 punti scadono il 31 ott — usali» verso i premi", () => {
  setup({ data: wallet({}, { expiringSoon: { amount: 1900, within30d: true, nextExpiryAt: "2026-10-31T22:59:59Z" } }) });
  expect(document.body.textContent).toMatch(/1\.900 punti scadono il 31 ott.*usali/);
  expect(screen.getByRole("link", { name: /scadono/ })).toHaveAttribute("href", "/portal/rewards");
});

it.each(
  rows([
    { id: "TB-WEB-HOME-010", desc: "importo in scadenza 0 → nessun avviso", exp: { amount: 0, within30d: false, nextExpiryAt: null } as WalletView["expiringSoon"] },
    { id: "TB-WEB-HOME-011", desc: "dato di scadenza assente → nessun avviso", exp: undefined },
  ]),
)("[%s] %s", (_id, _desc, { exp }) => {
  setup({ data: wallet({}, { expiringSoon: exp }) });
  expect(document.body.textContent).not.toContain("scadono");
});

it("[TB-WEB-HOME-012] importo > 0 senza data di scadenza → nessun avviso", () => {
  // TESTBOOK: ambiguo, vedi TB-WEB-HOME-012 — PT-01 cita la data nell'avviso ma non dice cosa fare se manca.
  setup({ data: wallet({}, { expiringSoon: { amount: 500, within30d: true, nextExpiryAt: null } }) });
  expect(document.body.textContent).not.toContain("scadono");
});

it.each(
  rows([
    { id: "TB-WEB-HOME-013", desc: "membro BLOCKED → banda «Il tuo profilo è sospeso…»", status: "BLOCKED" },
    { id: "TB-WEB-HOME-014", desc: "membro INACTIVE → banda «Il tuo profilo è sospeso…»", status: "INACTIVE" },
  ]),
)("[%s] %s", (_id, _desc, { status }) => {
  setup({ data: wallet() }, status);
  expect(document.body.textContent).toContain("Il tuo profilo è sospeso: puoi consultare ma non accumulare o richiedere premi");
});

it("[TB-WEB-HOME-015] membro ACTIVE → nessuna banda di sospensione", () => {
  setup({ data: wallet() }, "ACTIVE");
  expect(document.body.textContent).not.toContain("sospeso");
});

it("[TB-WEB-HOME-016] membro ANONYMIZED → avviso di profilo anonimizzato, saluto senza nome", () => {
  // TESTBOOK: ambiguo, vedi TB-WEB-HOME-016 — docs/09 §2 esclude ANONYMIZED dalle persone selezionabili, non dice cosa
  // mostrare se ci si arriva comunque (cookie precedente).
  setup({ data: wallet() }, "ANONYMIZED");
  expect(document.body.textContent).toContain("Questo profilo è stato anonimizzato");
  expect(document.body.textContent).not.toContain("Ciao Giulia");
});

it("[TB-WEB-HOME-017] wallet addormentato al primo caricamento → riquadro degraded, il resto della pagina resta", () => {
  setup({ isError: true, error: { asleep: true, code: "SERVICE_ASLEEP", detail: "" } });
  expect(document.body.textContent).toMatch(/wallet/);
  expect(screen.getByText("Ultimi movimenti")).toBeInTheDocument();
});

it("[TB-WEB-HOME-018] wallet addormentato con saldo già noto → tessera con l'ultimo saldo e «aggiornato alle …»", () => {
  setup({ data: wallet(), isError: true, error: { asleep: true, code: "SERVICE_ASLEEP", detail: "" } });
  expect(document.body.textContent).toContain("1.850");
  expect(document.body.textContent).toMatch(/aggiornato alle \d{2}:\d{2}/);
});

it("[TB-WEB-HOME-019] saluto col nome del membro", () => {
  setup({ data: wallet() });
  expect(document.body.textContent).toContain("Ciao Giulia");
});

it("[TB-WEB-HOME-020] dopo l'iscrizione (?welcome=1) con saldo ancora 0 → riga «+100 punti di benvenuto in arrivo…»", () => {
  window.history.replaceState(null, "", "/portal?welcome=1");
  setup({ data: wallet({}, { balances: { PTS: { active: 0, pending: 0, lifetimeEarned: 0, lifetimeSpent: 0 } } }) });
  expect(markPending).toHaveBeenCalledWith(["+100 punti di benvenuto in arrivo…"]);
});

it("[TB-WEB-HOME-021] dopo l'iscrizione con i punti di benvenuto già sul saldo → nessuna riga «in arrivo»", () => {
  window.history.replaceState(null, "", "/portal?welcome=1");
  setup({ data: wallet() });
  expect(markPending).not.toHaveBeenCalled();
});
