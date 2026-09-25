import { expect, it } from "vitest";
import { CONTENT_ACTIONS } from "./manage";
import { rows } from "@/test/testbook";

// Testbook TB-WEB §LIFE (contenuti): docs/06 §7 — CONTENT "approvazione: mai (pubblicazione diretta)"; docs/08 §BO-18
// "I contenuti non richiedono approvazione: Pubblica diretto"; stati e transizioni di docs/03 §3.6.

it.each(
  rows([
    { id: "TB-WEB-LIFE-056", desc: "contenuto DRAFT → Pubblica, Archivia (mai Invia in revisione)", status: "DRAFT" as const, expected: ["PUBLISH", "ARCHIVE"] },
    { id: "TB-WEB-LIFE-057", desc: "contenuto LIVE → Metti in pausa, Termina", status: "LIVE" as const, expected: ["PAUSE", "END"] },
    { id: "TB-WEB-LIFE-058", desc: "contenuto PAUSED → Riprendi, Termina", status: "PAUSED" as const, expected: ["RESUME", "END"] },
    { id: "TB-WEB-LIFE-059", desc: "contenuto ENDED → Archivia", status: "ENDED" as const, expected: ["ARCHIVE"] },
    { id: "TB-WEB-LIFE-060", desc: "contenuto ARCHIVED → nessuna transizione", status: "ARCHIVED" as const, expected: [] as string[] },
  ]),
)("[%s] %s", (_id, _desc, { status, expected }) => {
  expect(CONTENT_ACTIONS[status].map((a) => a.action)).toEqual(expected);
});
