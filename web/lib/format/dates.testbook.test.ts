import { expect, it } from "vitest";
import { rows } from "@/test/testbook";
import { computeRollingExpiry, formatDate, formatDateTime, formatRelative, formatTime } from "./dates";

// Testbook TB-WEB §FMT (date): docs/07 §9 "date `18 set 2026, 10:42`, relative "3 min fa" sotto le 24 h", fuso
// Europe/Rome (docs/07 §2 `format/dates.ts`); BO-08 esempio di scadenza ROLLING_MONTHS (docs/08 §BO-08, docs/03 §4.1).

it.each(
  rows([
    { id: "TB-WEB-FMT-016", desc: "esempio della spec, ora legale (08:42Z) → «18 set 2026, 10:42»", iso: "2026-09-18T08:42:00Z", expected: "18 set 2026, 10:42" },
    { id: "TB-WEB-FMT-017", desc: "un secondo prima della mezzanotte di Roma (21:59:59Z) → «18 set 2026, 23:59»", iso: "2026-09-18T21:59:59Z", expected: "18 set 2026, 23:59" },
    { id: "TB-WEB-FMT-018", desc: "mezzanotte di Roma (22:00Z) → «19 set 2026, 00:00»", iso: "2026-09-18T22:00:00Z", expected: "19 set 2026, 00:00" },
    { id: "TB-WEB-FMT-019", desc: "ora solare, 31 gen 23:59:59 di Roma → «31 gen 2026, 23:59»", iso: "2026-01-31T22:59:59Z", expected: "31 gen 2026, 23:59" },
    { id: "TB-WEB-FMT-020", desc: "fine mese: 1 feb 00:00 di Roma → «1 feb 2026, 00:00»", iso: "2026-01-31T23:00:00Z", expected: "1 feb 2026, 00:00" },
    { id: "TB-WEB-FMT-021", desc: "cambio ora di marzo, prima (00:59:59Z) → «29 mar 2026, 01:59»", iso: "2026-03-29T00:59:59Z", expected: "29 mar 2026, 01:59" },
    { id: "TB-WEB-FMT-022", desc: "cambio ora di marzo, dopo (01:00Z) → «29 mar 2026, 03:00»", iso: "2026-03-29T01:00:00Z", expected: "29 mar 2026, 03:00" },
    { id: "TB-WEB-FMT-023", desc: "cambio ora di ottobre, prima (00:59:59Z) → «25 ott 2026, 02:59»", iso: "2026-10-25T00:59:59Z", expected: "25 ott 2026, 02:59" },
    { id: "TB-WEB-FMT-024", desc: "cambio ora di ottobre, dopo (01:00Z) → «25 ott 2026, 02:00»", iso: "2026-10-25T01:00:00Z", expected: "25 ott 2026, 02:00" },
    { id: "TB-WEB-FMT-025", desc: "29 febbraio (anno bisestile 2028) → «29 feb 2028, 12:00»", iso: "2028-02-29T11:00:00Z", expected: "29 feb 2028, 12:00" },
  ]),
)("[%s] formatDateTime %s", (_id, _desc, { iso, expected }) => {
  expect(formatDateTime(iso)).toBe(expected);
});

it("[TB-WEB-FMT-026] formatDate a cavallo d'anno: 31 dic 23:30Z è già 1 gen a Roma → «1 gen 2027»", () => {
  expect(formatDate("2026-12-31T23:30:00Z")).toBe("1 gen 2027");
});

it("[TB-WEB-FMT-027] formatDate di un valore assente → «—»", () => {
  // TESTBOOK: ambiguo, vedi TB-WEB-FMT-027 — docs/07 non dice cosa mostrare per una data assente.
  expect(formatDate(null)).toBe("—");
});

it("[TB-WEB-FMT-028] formatTime (etichetta «aggiornato alle 10:42», docs/07 §6) → «10:42»", () => {
  expect(formatTime("2026-09-18T08:42:00Z")).toBe("10:42");
});

const NOW = new Date("2026-09-18T10:00:00Z");
const ago = (ms: number) => new Date(NOW.getTime() - ms);
const MIN = 60_000;
const H = 60 * MIN;

it("[TB-WEB-FMT-029] formatRelative 3 min fa (esempio della spec) → «3 min fa»", () => {
  expect(formatRelative(ago(3 * MIN), NOW)).toBe("3 min fa");
});

it("[TB-WEB-FMT-030] formatRelative 59 min fa → «59 min fa»", () => {
  expect(formatRelative(ago(59 * MIN), NOW)).toBe("59 min fa");
});

// TESTBOOK: ambiguo, vedi TB-WEB-FMT-031…033 e 036 — la spec dà solo "3 min fa": non fissa la resa sotto il minuto,
// delle ore né degli istanti futuri.
it.each(
  rows([
    { id: "TB-WEB-FMT-031", desc: "adesso (0 s) → «ora»", at: ago(0), expected: "ora" },
    { id: "TB-WEB-FMT-032", desc: "60 min fa → «1 h fa»", at: ago(60 * MIN), expected: "1 h fa" },
    { id: "TB-WEB-FMT-033", desc: "23 h fa → «23 h fa»", at: ago(23 * H), expected: "23 h fa" },
    { id: "TB-WEB-FMT-036", desc: "tra 5 minuti (futuro) → «ora»", at: new Date(NOW.getTime() + 5 * MIN), expected: "ora" },
  ]),
)("[%s] formatRelative %s", (_id, _desc, { at, expected }) => {
  expect(formatRelative(at, NOW)).toBe(expected);
});

it("[TB-WEB-FMT-034] formatRelative 23 h 40 min fa (sotto le 24 h) → ancora relativa", () => {
  expect(formatRelative(ago(23 * H + 40 * MIN), NOW)).toMatch(/fa$/);
});

it("[TB-WEB-FMT-035] formatRelative esattamente 24 h fa → data estesa «17 set 2026, 12:00»", () => {
  expect(formatRelative(ago(24 * H), NOW)).toBe("17 set 2026, 12:00");
});

// computeRollingExpiry lavora sul calendario locale del browser (in Italia = Europe/Rome): ingressi costruiti in ora locale.
const ymd = (d: Date) => [d.getFullYear(), d.getMonth() + 1, d.getDate()];
it.each(
  rows([
    { id: "TB-WEB-FMT-037", desc: "12 mesi dal 18 set 2026 → 30 set 2027 (esempio di BO-08)", months: 12, from: new Date(2026, 8, 18, 10), expected: [2027, 9, 30] },
    { id: "TB-WEB-FMT-038", desc: "1 mese dal 31 gen 2026 → 28 feb 2026 (non marzo)", months: 1, from: new Date(2026, 0, 31, 10), expected: [2026, 2, 28] },
    { id: "TB-WEB-FMT-039", desc: "13 mesi dal 31 gen 2027 → 29 feb 2028 (bisestile)", months: 13, from: new Date(2027, 0, 31, 10), expected: [2028, 2, 29] },
    { id: "TB-WEB-FMT-044", desc: "12 mesi dal 29 feb 2028 → 28 feb 2029", months: 12, from: new Date(2028, 1, 29, 10), expected: [2029, 2, 28] },
    { id: "TB-WEB-FMT-045", desc: "0 mesi → fine del mese corrente (30 set 2026)", months: 0, from: new Date(2026, 8, 18, 10), expected: [2026, 9, 30] },
    { id: "TB-WEB-FMT-046", desc: "12 mesi dal 30 set 2026 23:59 → 30 set 2027", months: 12, from: new Date(2026, 8, 30, 23, 59, 59), expected: [2027, 9, 30] },
    { id: "TB-WEB-FMT-047", desc: "12 mesi dal 1 ott 2026 00:00 → 31 ott 2027", months: 12, from: new Date(2026, 9, 1, 0, 0, 0), expected: [2027, 10, 31] },
    { id: "TB-WEB-FMT-048", desc: "1 mese dal 31 dic 2026 → 31 gen 2027 (cambio d'anno)", months: 1, from: new Date(2026, 11, 31, 10), expected: [2027, 1, 31] },
  ]),
)("[%s] computeRollingExpiry %s", (_id, _desc, { months, from, expected }) => {
  expect(ymd(computeRollingExpiry(months, from))).toEqual(expected);
});
