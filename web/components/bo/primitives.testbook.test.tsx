import { expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { rows } from "@/test/testbook";
import { PointsAmount, StatusPill } from "./primitives";

// Testbook TB-WEB §PILL: stati oggetto come pill colorate (docs/07 §5.2) e §FMT per PointsAmount (docs/08 §3.6:
// "PointsAmount (segno, colore semantico, valuta)"; docs/07 §9 punti "1.850").

const pillClass = (status: string) => {
  const { container } = render(<StatusPill status={status} />);
  return container.firstElementChild?.className ?? "";
};

// docs/07 §5.2: DRAFT grigio, IN_REVIEW ambra, APPROVED blu, SCHEDULED indaco, LIVE verde, PAUSED arancio, ENDED slate,
// REJECTED rosso, ARCHIVED grigio chiaro. Famiglie Tailwind corrispondenti.
it.each(
  rows([
    { id: "TB-WEB-PILL-001", desc: "DRAFT → grigio", status: "DRAFT", family: /\b(bg|text)-(gray|slate|zinc|neutral)-/ },
    { id: "TB-WEB-PILL-002", desc: "IN_REVIEW → ambra", status: "IN_REVIEW", family: /\bbg-amber-/ },
    { id: "TB-WEB-PILL-003", desc: "APPROVED → blu", status: "APPROVED", family: /\bbg-(blue|sky)-/ },
    { id: "TB-WEB-PILL-004", desc: "SCHEDULED → indaco", status: "SCHEDULED", family: /\bbg-indigo-/ },
    { id: "TB-WEB-PILL-005", desc: "LIVE → verde", status: "LIVE", family: /\bbg-(green|emerald)-/ },
    { id: "TB-WEB-PILL-006", desc: "PAUSED → arancio", status: "PAUSED", family: /\bbg-orange-/ },
    { id: "TB-WEB-PILL-007", desc: "ENDED → slate", status: "ENDED", family: /\bbg-slate-/ },
    { id: "TB-WEB-PILL-008", desc: "REJECTED → rosso", status: "REJECTED", family: /\bbg-red-/ },
    { id: "TB-WEB-PILL-009", desc: "ARCHIVED → grigio chiaro", status: "ARCHIVED", family: /\bbg-(gray|slate|zinc|neutral)-/ },
  ]),
)("[%s] pill %s", (_id, _desc, { status, family }) => {
  expect(pillClass(status)).toMatch(family);
});

it("[TB-WEB-PILL-010] ARCHIVED (grigio chiaro) si distingue da ENDED (slate)", () => {
  expect(pillClass("ARCHIVED")).not.toBe(pillClass("ENDED"));
});

it("[TB-WEB-PILL-011] PAUSED (arancio) si distingue da IN_REVIEW (ambra)", () => {
  expect(pillClass("PAUSED")).not.toBe(pillClass("IN_REVIEW"));
});

it("[TB-WEB-PILL-012] la pill riporta il codice di stato come testo", () => {
  render(<StatusPill status="IN_REVIEW" />);
  expect(screen.getByText("IN_REVIEW")).toBeInTheDocument();
});

it.each(
  rows([
    { id: "TB-WEB-FMT-040", desc: "PointsAmount +1850 PTS → «+1.850 PTS»", value: 1850, currency: "PTS", text: "+1.850 PTS" },
    { id: "TB-WEB-FMT-041", desc: "PointsAmount −1850 → «-1.850» (segno meno, nessun +)", value: -1850, currency: undefined, text: "-1.850" },
    { id: "TB-WEB-FMT-042", desc: "PointsAmount 0 → «0» senza segno", value: 0, currency: undefined, text: "0" },
    { id: "TB-WEB-FMT-043", desc: "PointsAmount +1 STS → «+1 STS»", value: 1, currency: "STS", text: "+1 STS" },
  ]),
)("[%s] %s", (_id, _desc, { value, currency, text }) => {
  const { container } = render(<PointsAmount value={value} currency={currency} />);
  expect(container.textContent).toBe(text);
});
