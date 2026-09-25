import { expect, it } from "vitest";
import { rows } from "@/test/testbook";
import { formatEuro, formatPoints } from "./points";

// Testbook TB-WEB §FMT (numeri): docs/07 §9 "Formati: punti `1.850`, valute `€ 129,90`" (Intl it-IT).

const norm = (s: string) => s.replace(/[  ]/g, " ");

it.each(
  rows([
    { id: "TB-WEB-FMT-001", desc: "0 → «0»", value: 0, expected: "0" },
    { id: "TB-WEB-FMT-002", desc: "1 → «1»", value: 1, expected: "1" },
    { id: "TB-WEB-FMT-003", desc: "999 (ultimo senza separatore) → «999»", value: 999, expected: "999" },
    { id: "TB-WEB-FMT-004", desc: "1000 (primo con separatore) → «1.000»", value: 1000, expected: "1.000" },
    { id: "TB-WEB-FMT-005", desc: "1850 (esempio della spec) → «1.850»", value: 1850, expected: "1.850" },
    { id: "TB-WEB-FMT-006", desc: "999999 → «999.999»", value: 999_999, expected: "999.999" },
    { id: "TB-WEB-FMT-007", desc: "1000000 → «1.000.000»", value: 1_000_000, expected: "1.000.000" },
    { id: "TB-WEB-FMT-008", desc: "−1 → «-1»", value: -1, expected: "-1" },
    { id: "TB-WEB-FMT-009", desc: "−1850 → «-1.850»", value: -1850, expected: "-1.850" },
    { id: "TB-WEB-FMT-010", desc: "−0 (zero negativo) → «0»", value: -0, expected: "0" },
  ]),
)("[%s] formatPoints %s", (_id, _desc, { value, expected }) => {
  expect(formatPoints(value)).toBe(expected);
});

// TESTBOOK: ambiguo, vedi TB-WEB-FMT-011/012 — i punti sono interi; nessuna fonte dice come mostrare un valore decimale
// (oggi arrotondamento it-IT "half-expand" a 0 decimali).
it.each(
  rows([
    { id: "TB-WEB-FMT-011", desc: "1,5 → «2» (arrotondato)", value: 1.5, expected: "2" },
    { id: "TB-WEB-FMT-012", desc: "1,4 → «1» (arrotondato)", value: 1.4, expected: "1" },
  ]),
)("[%s] formatPoints %s", (_id, _desc, { value, expected }) => {
  expect(formatPoints(value)).toBe(expected);
});

it.each(
  rows([
    { id: "TB-WEB-FMT-013", desc: "129,9 → «€ 129,90» (esempio della spec)", value: 129.9, expected: "€ 129,90" },
    { id: "TB-WEB-FMT-014", desc: "1234,5 → «€ 1.234,50»", value: 1234.5, expected: "€ 1.234,50" },
    { id: "TB-WEB-FMT-015", desc: "0 → «€ 0,00»", value: 0, expected: "€ 0,00" },
  ]),
)("[%s] formatEuro %s", (_id, _desc, { value, expected }) => {
  expect(norm(formatEuro(value))).toBe(expected);
});
