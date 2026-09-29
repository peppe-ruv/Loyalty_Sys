import { expect, it } from "vitest";
import { demoIdentity, upstreamHeaders, withoutMember } from "./proxyHeaders";
import type { Persona } from "@/lib/persona/cookie";
import { rows } from "@/test/testbook";

// Testbook TB-WEB §PRX (PRX-037…043): identità del proxy verso i servizi (docs/07 §3, ADR-048, Q-555).
// La parte che passa dal route handler è in `app/api/lh/[service]/[...path]/route.member.testbook.test.ts`.
// SPEC-GAP: Q-560 - PRX-039 fotografa il ripiego MBR-000002 per la persona BO (Q-555); se Giuseppe sceglie (A) di Q-560
// l'atteso diventa il solo `x-lh-actor`.

const MEMBER: Persona = { kind: "MEMBER", memberId: "MBR-000005" };
const BO: Persona = { kind: "BO", username: "paolo.care", role: "CARE" };

it.each(rows([
  { id: "TB-WEB-PRX-037", desc: "persona MEMBER", persona: MEMBER as Persona | null, expected: { "x-lh-actor": "ANALYST:anonymous", "x-lh-member": "MBR-000005" } },
  { id: "TB-WEB-PRX-038", desc: "nessuna persona → membro di default", persona: null as Persona | null, expected: { "x-lh-actor": "ANALYST:anonymous", "x-lh-member": "MBR-000002" } },
  { id: "TB-WEB-PRX-039", desc: "persona BO → attore BO e membro di default", persona: BO as Persona | null, expected: { "x-lh-actor": "CARE:paolo.care", "x-lh-member": "MBR-000002" } },
]))("[%s] demoIdentity su /v1/portal/**: %s", (_id, _desc, { persona, expected }) => {
  expect(demoIdentity(persona, ["v1", "portal", "campaigns"])).toEqual(expected);
});

it("[TB-WEB-PRX-040] demoIdentity: percorsi che non sono API del portale → solo x-lh-actor", () => {
  const notPortal = [
    [],
    ["v1"],
    ["v1", "portal"], // radice senza sottopercorso: non è `/v1/portal/**`
    ["v1", "portalx", "tiers"],
    ["v1", "members", "portal"],
    ["v2", "portal", "tiers"],
    ["portal", "v1", "tiers"],
    ["health"],
  ];
  for (const path of notPortal) expect(demoIdentity(MEMBER, path), path.join("/")).toEqual({ "x-lh-actor": "ANALYST:anonymous" });
});

it("[TB-WEB-PRX-041] demoIdentity: segmenti fuori da [A-Za-z0-9._~-], vuoti, `.` o `..` → non è portale, nessun x-lh-member", () => {
  // Next decodifica i segmenti (`%2F`, `%2e%2e`) e `new URL()` normalizza `..` e `\`: `/v1/portal/../members` arriverebbe
  // a un endpoint di gestione. Vale la stessa lista di segmenti del profilo enterprise (`safeSegments`).
  const odd = [
    ["v1", "portal", "..", "members", "MBR-000005"],
    ["v1", "portal", ".", "tiers"],
    ["v1", "portal", "", "tiers"],
    ["v1", "portal", "wallets", ""],
    ["v1", "portal", "wallets", "MBR-000005", ".."],
    ["v1", "portal", "../members", "MBR-000009"], // `..%2Fmembers` decodificato da Next
    ["v1", "portal", "%2e%2e", "members"], // doppia codifica
    ["v1", "portal", ".%2E", "members"],
    ["v1", "portal", "x\\..\\members"], // `x\..\members`: `new URL` normalizza il `\` in `/`
    ["v1", "portal", "a;b"], // parametro di matrice
  ];
  for (const path of odd) expect(demoIdentity(MEMBER, path), path.join("/")).toEqual({ "x-lh-actor": "ANALYST:anonymous" });
});

it("[TB-WEB-PRX-042] upstreamHeaders: X-LH-Member tra gli header del browser (qualunque grafia) non passa; vale l'identità", () => {
  const incoming = new Headers({ "content-type": "application/json", "X-LH-Member": "MBR-000009", "x-lh-actor": "ADMIN:evil" });
  incoming.append("x-LH-member", "MBR-000008");

  const withoutIdentityMember = upstreamHeaders(incoming, { "x-lh-actor": "ANALYST:anonymous" }, "corr-1");
  expect(withoutIdentityMember.has("x-lh-member")).toBe(false);
  expect(withoutIdentityMember.get("x-lh-actor")).toBe("ANALYST:anonymous");

  const withIdentityMember = upstreamHeaders(incoming, demoIdentity(MEMBER, ["v1", "portal", "tiers"]), "corr-1");
  expect(withIdentityMember.get("x-lh-member")).toBe("MBR-000005");
  expect(JSON.stringify([...withIdentityMember])).not.toMatch(/MBR-00000[89]/);

  const enterprise = upstreamHeaders(incoming, { authorization: "Bearer AT" }, "corr-1");
  expect(enterprise.has("x-lh-member")).toBe(false);
  expect(enterprise.has("x-lh-actor")).toBe(false);
});

it("[TB-WEB-PRX-043] withoutMember: toglie solo x-lh-member; l'identità enterprise resta com'è", () => {
  expect(withoutMember({ "x-lh-actor": "ANALYST:anonymous", "x-lh-member": "MBR-000005" })).toEqual({ "x-lh-actor": "ANALYST:anonymous" });
  expect(withoutMember({ "x-lh-actor": "CARE:paolo.care" })).toEqual({ "x-lh-actor": "CARE:paolo.care" });
  expect(withoutMember({ authorization: "Bearer AT" })).toEqual({ authorization: "Bearer AT" });
});
