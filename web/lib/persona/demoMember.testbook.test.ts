import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { expect, it } from "vitest";
import type { Persona } from "./cookie";
import { MEMBER_ID_PATTERN, demoMemberHeader, demoPortalMember, isMemberId } from "./demoMember";
import { rows } from "@/test/testbook";

// Testbook TB-WEB §PERS (PERS-036…048): membro attivo del profilo demo (docs/07 §4, docs/06 §3.4, ADR-048, Q-555).
// Lo stesso helper dà l'id che il layout del portale mostra e l'`X-LH-Member` del proxy: non possono discordare.

it.each(rows([
  { id: "TB-WEB-PERS-036", desc: "persona MEMBER → il suo id", persona: { kind: "MEMBER", memberId: "MBR-000007" } as Persona | null, expected: "MBR-000007" },
  { id: "TB-WEB-PERS-037", desc: "nessuna persona → MBR-000002", persona: null as Persona | null, expected: "MBR-000002" },
]))("[%s] demoPortalMember: %s", (_id, _desc, { persona, expected }) => {
  expect(demoPortalMember(persona)).toBe(expected);
  expect(demoMemberHeader(persona)).toBe(expected);
});

it("[TB-WEB-PERS-038] persona BO: il portale mostra MBR-000002 (interfaccia) ma nessun X-LH-Member (Q-560)", () => {
  const bo: Persona = { kind: "BO", username: "marta.admin", role: "ADMIN" };
  expect(demoPortalMember(bo)).toBe("MBR-000002");
  expect(demoMemberHeader(bo)).toBeNull();
});

it("[TB-WEB-PERS-039] demoPortalMember non valida l'id del cookie (il portale si comporta come prima), demoMemberHeader sì", () => {
  const odd: Persona = { kind: "MEMBER", memberId: "MBR-7" };
  expect(demoPortalMember(odd)).toBe("MBR-7");
  expect(demoMemberHeader(odd)).toBeNull();
});

it.each(rows([
  { id: "TB-WEB-PERS-040", desc: "meno di sei cifre", value: "MBR-00007" },
  { id: "TB-WEB-PERS-041", desc: "più di sei cifre", value: "MBR-0000007" },
  { id: "TB-WEB-PERS-042", desc: "prefisso in minuscolo", value: "mbr-000007" },
  { id: "TB-WEB-PERS-043", desc: "carattere non numerico", value: "MBR-00000A" },
  { id: "TB-WEB-PERS-044", desc: "spazio davanti o dietro", value: " MBR-000007" },
  { id: "TB-WEB-PERS-045", desc: "a capo in coda", value: "MBR-000007\n" },
  { id: "TB-WEB-PERS-046", desc: "vuoto", value: "" },
]))("[%s] id membro non valido (%s) → nessun X-LH-Member", (_id, _desc, { value }) => {
  expect(isMemberId(value)).toBe(false);
  expect(demoMemberHeader({ kind: "MEMBER", memberId: value })).toBeNull();
});

it("[TB-WEB-PERS-047] forma valida: MBR- e sei cifre, come i contratti evento", () => {
  expect(MEMBER_ID_PATTERN.source).toBe("^MBR-[0-9]{6}$");
  for (const id of ["MBR-000002", "MBR-999999", "MBR-000000"]) expect(isMemberId(id)).toBe(true);
  for (const nope of [undefined, null, 2, {}, ["MBR-000002"]]) expect(isMemberId(nope)).toBe(false);
});

it("[TB-WEB-PERS-048] il layout del portale usa demoPortalMember e non ha un ripiego proprio (layout e proxy non discordano)", () => {
  const layout = readFileSync(resolve(process.cwd(), "app/portal/layout.tsx"), "utf8");
  expect(layout).toMatch(/import\s*\{[^}]*\bdemoPortalMember\b[^}]*\}\s*from\s*"@\/lib\/persona\/demoMember"/);
  expect(layout).toContain("demoPortalMember(");
  expect(layout).not.toContain("DEFAULT_MEMBER_ID");
});
