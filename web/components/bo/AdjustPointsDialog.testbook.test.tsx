import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { fireEvent, screen } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { rows } from "@/test/testbook";
import { AdjustPointsDialog } from "./AdjustPointsDialog";

// Testbook TB-WEB §ADJ: rettifica punti di BO-03 (docs/08 §BO-03: valuta, direzione, quantità, motivo GOODWILL /
// CORRECTION / COMPLAINT / TEST, nota ≥ 10 caratteri, anteprima "saldo dopo", errori INSUFFICIENT_BALANCE e
// NOTE_TOO_SHORT sul campo; docs/03 §4.2 "Un addebito non può portare il saldo sotto zero"; Q-45, Q-46).

let fetchMock: ReturnType<typeof vi.fn>;
beforeEach(() => {
  fetchMock = vi.fn();
  vi.stubGlobal("fetch", fetchMock);
});
afterEach(() => vi.unstubAllGlobals());

const BALANCE = 100;
function fill({ direction = "CREDIT", amount = "", note = "" }: { direction?: "CREDIT" | "DEBIT"; amount?: string; note?: string }) {
  renderWithProviders(<AdjustPointsDialog memberId="MBR-000002" balance={BALANCE} onClose={vi.fn()} />, "CARE");
  if (direction === "DEBIT") fireEvent.click(screen.getByRole("button", { name: "Addebito" }));
  fireEvent.change(screen.getByRole("spinbutton"), { target: { value: amount } });
  fireEvent.change(screen.getByRole("textbox"), { target: { value: note } });
  return screen.getByRole("button", { name: "Conferma rettifica" });
}
const NOTE10 = "1234567890";

it("[TB-WEB-ADJ-001] motivi proposti: GOODWILL, CORRECTION, COMPLAINT, TEST", () => {
  fill({});
  const options = [...(screen.getAllByRole("combobox")[1] as HTMLSelectElement).options].map((o) => o.value);
  expect(options).toEqual(["GOODWILL", "CORRECTION", "COMPLAINT", "TEST"]);
});

it("[TB-WEB-ADJ-002] valuta: solo PTS (gli STS non si rettificano, Q-46)", () => {
  fill({});
  const currency = screen.getAllByRole("combobox")[0] as HTMLSelectElement;
  expect([currency.disabled, [...currency.options].map((o) => o.textContent)]).toEqual([true, ["PTS — Punti"]]);
});

it.each(
  rows([
    { id: "TB-WEB-ADJ-003", desc: "quantità 0 → conferma disabilitata", direction: "CREDIT" as const, amount: "0", note: NOTE10, enabled: false },
    { id: "TB-WEB-ADJ-004", desc: "quantità 1 e nota di 10 caratteri → conferma abilitata", direction: "CREDIT" as const, amount: "1", note: NOTE10, enabled: true },
    { id: "TB-WEB-ADJ-005", desc: "nota di 9 caratteri → conferma disabilitata", direction: "CREDIT" as const, amount: "5", note: "123456789", enabled: false },
    { id: "TB-WEB-ADJ-006", desc: "nota di 11 caratteri → conferma abilitata", direction: "CREDIT" as const, amount: "5", note: "12345678901", enabled: true },
    { id: "TB-WEB-ADJ-007", desc: "nota di 9 caratteri con spazi ai bordi → conferma disabilitata", direction: "CREDIT" as const, amount: "5", note: "  123456789  ", enabled: false },
    { id: "TB-WEB-ADJ-008", desc: "addebito pari al saldo (100) → abilitata, saldo dopo 0", direction: "DEBIT" as const, amount: "100", note: NOTE10, enabled: true },
    { id: "TB-WEB-ADJ-009", desc: "addebito oltre il saldo (101) → disabilitata, «saldo insufficiente»", direction: "DEBIT" as const, amount: "101", note: NOTE10, enabled: false },
    { id: "TB-WEB-ADJ-010", desc: "quantità negativa (−5) → disabilitata", direction: "CREDIT" as const, amount: "-5", note: NOTE10, enabled: false },
    { id: "TB-WEB-ADJ-011", desc: "quantità vuota → disabilitata", direction: "CREDIT" as const, amount: "", note: NOTE10, enabled: false },
  ]),
)("[%s] %s", (_id, _desc, { direction, amount, note, enabled }) => {
  expect(fill({ direction, amount, note })).toHaveProperty("disabled", !enabled);
});

it("[TB-WEB-ADJ-012] anteprima «saldo dopo» di un accredito di 1.000 su 100 → «Saldo dopo: 1.100 PTS»", () => {
  fill({ amount: "1000", note: NOTE10 });
  expect(screen.getByText(/Saldo dopo: 1\.100 PTS/)).toBeInTheDocument();
});

it("[TB-WEB-ADJ-013] addebito oltre il saldo → anteprima negativa con «saldo insufficiente»", () => {
  fill({ direction: "DEBIT", amount: "101", note: NOTE10 });
  expect(screen.getByText(/Saldo dopo: -1 PTS — saldo insufficiente/)).toBeInTheDocument();
});

it.each(
  rows([
    { id: "TB-WEB-ADJ-014", desc: "rifiuto NOTE_TOO_SHORT dal wallet → errore sotto la nota", code: "NOTE_TOO_SHORT", near: "textbox" as const },
    { id: "TB-WEB-ADJ-015", desc: "rifiuto INSUFFICIENT_BALANCE dal wallet → errore sotto la quantità", code: "INSUFFICIENT_BALANCE", near: "spinbutton" as const },
  ]),
)("[%s] %s", async (_id, _desc, { code, near }) => {
  fetchMock.mockResolvedValue(new Response(JSON.stringify({ code, detail: `dettaglio ${code}` }), { status: 422, headers: { "content-type": "application/json" } }));
  fireEvent.click(fill({ amount: "5", note: NOTE10 }));
  const msg = await screen.findByText(`dettaglio ${code}`);
  expect(msg.closest("label")).toBe(screen.getByRole(near).closest("label"));
});

it("[TB-WEB-ADJ-016] invio: POST /v1/wallets/{id}/adjustments con currency PTS, direzione, quantità intera, motivo e nota ripulita", async () => {
  fetchMock.mockResolvedValue(new Response(JSON.stringify({ ledgerEntryId: "L1", balanceAfter: 105 }), { status: 200, headers: { "content-type": "application/json" } }));
  fireEvent.click(fill({ amount: "5", note: `  ${NOTE10}  ` }));
  await screen.findByText(/Rettifica registrata/);
  const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
  expect(url).toBe("/api/lh/wallet/v1/wallets/MBR-000002/adjustments");
  expect(JSON.parse(String(init.body))).toEqual({ currency: "PTS", direction: "CREDIT", amount: 5, reason: "GOODWILL", note: NOTE10 });
});
