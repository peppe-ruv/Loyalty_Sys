import { expect, it } from "vitest";
import { can, isReadOnly, type Capability } from "./permissions";
import type { Role } from "./personas";
import { rows } from "@/test/testbook";

// Testbook TB-WEB §PERM (docs/testbook/TB-WEB-interfaccia.md): matrice ruolo × capacità.
// Oracolo: la tabella di docs/08 §2 trascritta qui sotto (NON la MATRIX di permissions.ts) + Q-52 per `coupon.use`.
// Una riga del testbook = un caso eseguito.

const ROLES: Role[] = ["ADMIN", "MARKETING", "LEGAL", "CARE", "ANALYST"];

// docs/08 §2, colonne ADMIN · MARKETING · LEGAL · CARE · ANALYST (✓ = true). `instants.view` per MARKETING è
// "solo istogramma": la tabella degli istanti resta negata. `edition.close` ha due colonne (anteprima / applica).
type SpecCapability = Capability | "edition.close:preview" | "edition.close:apply";
const SPEC: [SpecCapability, boolean[]][] = [
  ["instants.view", [true, false, true, false, false]],
  ["member.write", [true, false, false, true, false]],
  ["member.anonymize", [true, false, false, false, false]],
  ["points.adjust", [true, false, false, true, false]],
  ["segment.write", [true, true, false, false, false]],
  ["object.edit", [true, true, false, false, false]],
  ["object.approve", [true, false, true, false, false]],
  ["content.write", [true, true, false, false, false]],
  ["program.config", [true, false, false, false, false]],
  ["actiontype.custom", [true, true, false, false, false]],
  ["edition.close:preview", [true, true, true, true, true]],
  ["edition.close:apply", [true, false, false, false, false]],
  ["redemption.handle", [true, false, false, true, false]],
  ["delivery.handle", [true, false, false, true, false]],
  ["coupon.void", [true, false, false, true, false]],
  ["inbound.handle", [true, false, false, true, false]],
  ["webhook.write", [true, false, false, false, false]],
  ["dlq.handle", [true, false, false, false, false]],
  ["demo.simulate", [true, true, true, true, false]],
  ["demo.admin", [true, false, false, false, false]],
  // Q-52: uso alla cassa a ogni ruolo operativo, mai ANALYST.
  ["coupon.use", [true, true, true, true, false]],
];

/**
 * Decisione della UI per una capacità della specifica. `edition.close` non è una capability di permissions.ts: in BO-08
 * *Anteprima chiusura* non ha gate e *Applica chiusura* è dentro `<Can capability="program.config">`
 * (app/backoffice/program/currencies/page.tsx, pannello chiusura edizione).
 */
function uiAllows(role: Role, cap: SpecCapability): boolean {
  if (cap === "edition.close:preview") return true;
  if (cap === "edition.close:apply") return can(role, "program.config");
  return can(role, cap);
}

const matrix = SPEC.flatMap(([cap, expected], ci) =>
  ROLES.map((role, ri) => ({
    id: `TB-WEB-PERM-${String(ci * ROLES.length + ri + 1).padStart(3, "0")}`,
    desc: `${role} × ${cap} → ${expected[ri]}`,
    role,
    cap,
    expected: expected[ri],
  })),
);

it.each(rows(matrix))("[%s] %s", (_id, _desc, { role, cap, expected }) => {
  expect(uiAllows(role, cap)).toBe(expected);
});

it("[TB-WEB-PERM-106] ruolo sconosciuto (SUPERUSER) × object.edit → false", () => {
  expect(can("SUPERUSER" as Role, "object.edit")).toBe(false);
});

// docs/08 §2: "Il backend rifiuta inoltre ogni scrittura di ANALYST" → la UI tratta ANALYST come sola lettura.
it.each(
  rows([
    { id: "TB-WEB-PERM-107", desc: "sola lettura per ADMIN → false", role: "ADMIN" as Role, expected: false },
    { id: "TB-WEB-PERM-108", desc: "sola lettura per MARKETING → false", role: "MARKETING" as Role, expected: false },
    { id: "TB-WEB-PERM-109", desc: "sola lettura per LEGAL → false", role: "LEGAL" as Role, expected: false },
    { id: "TB-WEB-PERM-110", desc: "sola lettura per CARE → false", role: "CARE" as Role, expected: false },
    { id: "TB-WEB-PERM-111", desc: "sola lettura per ANALYST → true", role: "ANALYST" as Role, expected: true },
  ]),
)("[%s] %s", (_id, _desc, { role, expected }) => {
  expect(isReadOnly(role)).toBe(expected);
});
