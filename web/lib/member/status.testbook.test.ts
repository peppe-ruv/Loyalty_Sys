import { expect, it } from "vitest";
import { rows } from "@/test/testbook";
import { memberStatusActions, statusChangeBody, statusChangeErrorMessage } from "./status";
import { memberDisplayName, personalValue } from "./anonymized";

// Testbook TB-WEB §MBR: menu di stato della scheda 360° (docs/08 §BO-03 "menu: Blocca/Sblocca, Disattiva, Anonimizza ●";
// docs/03 §2 stati ACTIVE, INACTIVE, BLOCKED, ANONYMIZED irreversibile; F-MBR-04/05; Q-120, Q-137, Q-138).

const view = (status: string | null) => memberStatusActions(status).map((a) => `${a.label}${a.disabledReason ? " (disabilitato)" : ""}→${a.target}`);

it.each(
  rows([
    { id: "TB-WEB-MBR-001", desc: "ACTIVE → Blocca, Disattiva utilizzabili", status: "ACTIVE" as string | null, expected: ["Blocca→BLOCKED", "Disattiva→INACTIVE"] },
    { id: "TB-WEB-MBR-002", desc: "BLOCKED → Sblocca (torna ACTIVE), Disattiva", status: "BLOCKED", expected: ["Sblocca→ACTIVE", "Disattiva→INACTIVE"] },
    { id: "TB-WEB-MBR-003", desc: "INACTIVE → Blocca e Disattiva disabilitati (Q-137: niente Riattiva)", status: "INACTIVE", expected: ["Blocca (disabilitato)→BLOCKED", "Disattiva (disabilitato)→INACTIVE"] },
    { id: "TB-WEB-MBR-004", desc: "ANONYMIZED → tutte le azioni disabilitate (irreversibile)", status: "ANONYMIZED", expected: ["Blocca (disabilitato)→BLOCKED", "Disattiva (disabilitato)→INACTIVE"] },
  ]),
)("[%s] %s", (_id, _desc, { status, expected }) => {
  expect(view(status)).toEqual(expected);
});

it("[TB-WEB-MBR-005] stato non noto (null) → come ACTIVE", () => {
  // TESTBOOK: ambiguo, vedi TB-WEB-MBR-005 — stato non ancora caricato: nessuna fonte.
  expect(view(null)).toEqual(["Blocca→BLOCKED", "Disattiva→INACTIVE"]);
});

it.each(
  rows([
    { id: "TB-WEB-MBR-006", desc: "motivo vuoto o di soli spazi → non inviato (Q-138)", reason: "   ", expected: { status: "BLOCKED" } as object },
    { id: "TB-WEB-MBR-007", desc: "motivo compilato → inviato senza spazi ai bordi", reason: "  frode sospetta ", expected: { status: "BLOCKED", reason: "frode sospetta" } },
  ]),
)("[%s] corpo del cambio stato: %s", (_id, _desc, { reason, expected }) => {
  expect(statusChangeBody("BLOCKED", reason)).toEqual(expected);
});

// TESTBOOK: ambiguo, vedi TB-WEB-MBR-008/009 — le parole dei messaggi d'errore non sono nella spec (docs/07 §6 chiede
// solo il riquadro degraded o il problema).
it("[TB-WEB-MBR-008] errore «servizio addormentato» → messaggio che invita a riprovare a demo accesa", () => {
  expect(statusChangeErrorMessage({ asleep: true })).toMatch(/non risponde/);
});

it("[TB-WEB-MBR-009] errore con codice non previsto → detail del problema", () => {
  expect(statusChangeErrorMessage({ code: "X", detail: "Dettaglio dal servizio" })).toBe("Dettaglio dal servizio");
});

it.each(
  rows([
    { id: "TB-WEB-MBR-010", desc: "ANONYMIZED → «Membro anonimo» (Q-120)", m: { firstName: null, lastName: null, nickname: "Membro anonimo", status: "ANONYMIZED" }, expected: "Membro anonimo" },
    { id: "TB-WEB-MBR-011", desc: "nome e cognome → «Giulia Neri»", m: { firstName: "Giulia", lastName: "Neri", status: "ACTIVE" }, expected: "Giulia Neri" },
    { id: "TB-WEB-MBR-012", desc: "senza nome → nickname", m: { firstName: " ", lastName: null, nickname: "giuly", status: "ACTIVE" }, expected: "giuly" },
  ]),
)("[%s] nome mostrato: %s", (_id, _desc, { m, expected }) => {
  expect(memberDisplayName(m)).toBe(expected);
});

it.each(
  rows([
    { id: "TB-WEB-MBR-013", desc: "campo personale di un anonimizzato → «Membro anonimo»", status: "ANONYMIZED", value: "giulia@example.test" as string | null, expected: "Membro anonimo" },
    { id: "TB-WEB-MBR-014", desc: "campo personale vuoto → «—»", status: "ACTIVE", value: "", expected: "—" },
    { id: "TB-WEB-MBR-015", desc: "campo personale valorizzato → il valore", status: "ACTIVE", value: "Torino", expected: "Torino" },
  ]),
)("[%s] %s", (_id, _desc, { status, value, expected }) => {
  expect(personalValue(status, value)).toBe(expected);
});
