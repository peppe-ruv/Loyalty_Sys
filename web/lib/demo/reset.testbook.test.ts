import { expect, it } from "vitest";
import { rows } from "@/test/testbook";
import { isResetConfirmed } from "./reset";
import { canConfirmAnonymize } from "@/lib/member/anonymized";

// Testbook TB-WEB §CONF: conferme delle azioni irreversibili (docs/08 §3.5 "digitazione del codice dell'oggetto";
// BO-30 *Ripristina tutto* "conferma digitando RESET"; BO-03 *Anonimizza* "conferma con digitazione dell'ID").

it.each(
  rows([
    { id: "TB-WEB-CONF-001", desc: "«RESET» → confermato", typed: "RESET" as string | null | undefined, expected: true },
    { id: "TB-WEB-CONF-002", desc: "«reset» (minuscolo) → no", typed: "reset", expected: false },
    { id: "TB-WEB-CONF-003", desc: "«RESE» (una lettera in meno) → no", typed: "RESE", expected: false },
    { id: "TB-WEB-CONF-004", desc: "«RESETT» (una lettera in più) → no", typed: "RESETT", expected: false },
    { id: "TB-WEB-CONF-005", desc: "vuoto → no", typed: "", expected: false },
    { id: "TB-WEB-CONF-006", desc: "null → no", typed: null, expected: false },
    { id: "TB-WEB-CONF-007", desc: "assente → no", typed: undefined, expected: false },
    { id: "TB-WEB-CONF-009", desc: "«R E S E T» → no", typed: "R E S E T", expected: false },
  ]),
)("[%s] BO-30 reset: %s", (_id, _desc, { typed, expected }) => {
  expect(isResetConfirmed(typed)).toBe(expected);
});

it("[TB-WEB-CONF-008] «  RESET  » (spazi ai bordi) → confermato", () => {
  // TESTBOOK: ambiguo, vedi TB-WEB-CONF-008 — la spec dice "digitando RESET"; gli spazi ai bordi sono tollerati.
  expect(isResetConfirmed("  RESET  ")).toBe(true);
});

it.each(
  rows([
    { id: "TB-WEB-CONF-010", desc: "ID esatto → confermato", typed: "MBR-000007", memberId: "MBR-000007", expected: true },
    { id: "TB-WEB-CONF-011", desc: "ID in minuscolo → no", typed: "mbr-000007", memberId: "MBR-000007", expected: false },
    { id: "TB-WEB-CONF-012", desc: "ID di un altro membro → no", typed: "MBR-000008", memberId: "MBR-000007", expected: false },
    { id: "TB-WEB-CONF-014", desc: "ID membro vuoto e testo vuoto → no", typed: "", memberId: "", expected: false },
  ]),
)("[%s] BO-03 anonimizza: %s", (_id, _desc, { typed, memberId, expected }) => {
  expect(canConfirmAnonymize(typed, memberId)).toBe(expected);
});

it("[TB-WEB-CONF-013] ID con spazi ai bordi → confermato", () => {
  // TESTBOOK: ambiguo, vedi TB-WEB-CONF-013 — come CONF-008.
  expect(canConfirmAnonymize("  MBR-000007 ", "MBR-000007")).toBe(true);
});
