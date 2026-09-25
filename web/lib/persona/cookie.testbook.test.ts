import { expect, it } from "vitest";
import {
  DEFAULT_BO_PERSONA,
  DEFAULT_MEMBER_PERSONA,
  PERSONA_COOKIE,
  PERSONA_MAX_AGE,
  actorHeader,
  backofficePersonaFromUsername,
  parsePersona,
  serializePersona,
  type Persona,
} from "./cookie";
import { BACKOFFICE_PERSONAS } from "./personas";
import { rows } from "@/test/testbook";

// Testbook TB-WEB §PERS: identità simulata (docs/07 §4 cookie `lh_persona`; docs/06 §3 header `X-LH-Actor`).

const enc = (v: unknown) => encodeURIComponent(JSON.stringify(v));

it.each(rows([
  { id: "TB-WEB-PERS-001", desc: "BO valido (codificato)", raw: enc({ kind: "BO", username: "luca.marketing", role: "MARKETING" }), expected: { kind: "BO", username: "luca.marketing", role: "MARKETING" } },
  { id: "TB-WEB-PERS-002", desc: "BO valido (JSON non codificato)", raw: JSON.stringify({ kind: "BO", username: "paolo.care", role: "CARE" }), expected: { kind: "BO", username: "paolo.care", role: "CARE" } },
  { id: "TB-WEB-PERS-003", desc: "MEMBER valido", raw: enc({ kind: "MEMBER", memberId: "MBR-000007" }), expected: { kind: "MEMBER", memberId: "MBR-000007" } },
  { id: "TB-WEB-PERS-004", desc: "cookie assente (undefined)", raw: undefined, expected: null },
  { id: "TB-WEB-PERS-005", desc: "cookie null", raw: null, expected: null },
  { id: "TB-WEB-PERS-006", desc: "cookie vuoto", raw: "", expected: null },
  { id: "TB-WEB-PERS-007", desc: "testo non JSON", raw: "marta.admin", expected: null },
  { id: "TB-WEB-PERS-008", desc: "codifica percentuale rotta", raw: "%E0%A4%A", expected: null },
  { id: "TB-WEB-PERS-009", desc: "JSON elenco", raw: enc([{ kind: "BO" }]), expected: null },
  { id: "TB-WEB-PERS-010", desc: "JSON null", raw: "null", expected: null },
  { id: "TB-WEB-PERS-011", desc: "kind sconosciuto", raw: enc({ kind: "ADMIN", username: "x", role: "ADMIN" }), expected: null },
  { id: "TB-WEB-PERS-012", desc: "BO senza role", raw: enc({ kind: "BO", username: "marta.admin" }), expected: null },
  { id: "TB-WEB-PERS-013", desc: "BO con role non testo", raw: enc({ kind: "BO", username: "marta.admin", role: 1 }), expected: null },
  { id: "TB-WEB-PERS-014", desc: "BO senza username", raw: enc({ kind: "BO", role: "ADMIN" }), expected: null },
  { id: "TB-WEB-PERS-016", desc: "MEMBER senza memberId", raw: enc({ kind: "MEMBER" }), expected: null },
  { id: "TB-WEB-PERS-017", desc: "MEMBER con memberId numerico", raw: enc({ kind: "MEMBER", memberId: 2 }), expected: null },
  { id: "TB-WEB-PERS-018", desc: "campi in più ignorati", raw: enc({ kind: "BO", username: "elena.legal", role: "LEGAL", admin: true }), expected: { kind: "BO", username: "elena.legal", role: "LEGAL" } },
]))("[%s] parsePersona: %s", (_id, _desc, { raw, expected }) => {
  expect(parsePersona(raw)).toEqual(expected);
});

it("[TB-WEB-PERS-015] BO con ruolo fuori dai 5 (ROOT) → ANALYST, nell'interfaccia e in X-LH-Actor", () => {
  // Q-186 DECISA: ruolo fuori elenco trattato come ANALYST (sola lettura) sia nell'interfaccia sia verso i servizi.
  const p = parsePersona(enc({ kind: "BO", username: "marta.admin", role: "ROOT" }));
  expect(p).toEqual({ kind: "BO", username: "marta.admin", role: "ANALYST" });
  expect(actorHeader(p)).toBe("ANALYST:marta.admin");
  expect(parsePersona(enc({ kind: "BO", username: "x", role: "admin" }))).toEqual({ kind: "BO", username: "x", role: "ANALYST" });
});

it.each(rows([
  { id: "TB-WEB-PERS-019", desc: "BO", p: { kind: "BO", username: "sara.analyst", role: "ANALYST" } as Persona },
  { id: "TB-WEB-PERS-020", desc: "MEMBER", p: { kind: "MEMBER", memberId: "MBR-000012" } as Persona },
]))("[%s] serializePersona → parsePersona restituisce la stessa persona (%s)", (_id, _desc, { p }) => {
  expect(parsePersona(serializePersona(p))).toEqual(p);
});

it.each(rows([
  { id: "TB-WEB-PERS-021", desc: "persona BO → MARKETING:luca.marketing", p: { kind: "BO", username: "luca.marketing", role: "MARKETING" } as Persona | null, expected: "MARKETING:luca.marketing" },
  { id: "TB-WEB-PERS-022", desc: "nessuna persona (header assente) → ANALYST:anonymous", p: null, expected: "ANALYST:anonymous" },
  { id: "TB-WEB-PERS-023", desc: "persona MEMBER (endpoint portale, header non richiesto) → ANALYST:anonymous", p: { kind: "MEMBER", memberId: "MBR-000002" } as Persona | null, expected: "ANALYST:anonymous" },
]))("[%s] X-LH-Actor per %s", (_id, _desc, { p, expected }) => {
  expect(actorHeader(p)).toBe(expected);
});

it("[TB-WEB-PERS-024] persona di default del backoffice: marta.admin (ADMIN)", () => {
  expect(DEFAULT_BO_PERSONA).toEqual({ kind: "BO", username: "marta.admin", role: "ADMIN" });
});

it("[TB-WEB-PERS-025] persona di default del portale: MBR-000002", () => {
  expect(DEFAULT_MEMBER_PERSONA).toEqual({ kind: "MEMBER", memberId: "MBR-000002" });
});

it("[TB-WEB-PERS-026] cookie lh_persona valido 30 giorni", () => {
  expect(PERSONA_COOKIE).toBe("lh_persona");
  expect(PERSONA_MAX_AGE).toBe(30 * 24 * 60 * 60);
});

it("[TB-WEB-PERS-027] username noto (elena.legal) → ruolo dall'elenco delle personas (LEGAL)", () => {
  expect(backofficePersonaFromUsername("elena.legal")).toEqual({ kind: "BO", username: "elena.legal", role: "LEGAL" });
});

it("[TB-WEB-PERS-028] username sconosciuto → ANALYST (sola lettura)", () => {
  // TESTBOOK: ambiguo, vedi TB-WEB-PERS-028 — nessuna fonte dice il ruolo di uno username fuori dalle 5 personas.
  expect(backofficePersonaFromUsername("mario.rossi")).toEqual({ kind: "BO", username: "mario.rossi", role: "ANALYST" });
});

it("[TB-WEB-PERS-029] 5 personas del backoffice, una per ruolo", () => {
  expect(BACKOFFICE_PERSONAS.map((p) => p.role).sort()).toEqual(["ADMIN", "ANALYST", "CARE", "LEGAL", "MARKETING"]);
  expect(new Set(BACKOFFICE_PERSONAS.map((p) => p.username)).size).toBe(5);
});
