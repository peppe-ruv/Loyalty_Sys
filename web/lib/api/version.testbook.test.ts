import { expect, it } from "vitest";
import { rows } from "@/test/testbook";
import { isVersionConflict, withVersion, withoutVersion, type VersionedError } from "./version";

// Testbook TB-WEB §VER: conflitto di versione negli editor (docs/08 §3.2 "`409` di versione → dialogo «Qualcun altro ha
// modificato: ricarica / sovrascrivi»"; Q-112: il PUT porta la `version` letta, 409 VERSION_CONFLICT se superata).

it.each(
  rows([
    { id: "TB-WEB-VER-001", desc: "409 VERSION_CONFLICT → conflitto", e: { status: 409, code: "VERSION_CONFLICT" } as VersionedError | null | undefined, expected: true },
    { id: "TB-WEB-VER-002", desc: "409 con altro codice (APPROVAL_REQUIRED) → non è un conflitto di versione", e: { status: 409, code: "APPROVAL_REQUIRED" }, expected: false },
    { id: "TB-WEB-VER-003", desc: "422 con codice VERSION_CONFLICT → non è il 409 atteso", e: { status: 422, code: "VERSION_CONFLICT" }, expected: false },
    { id: "TB-WEB-VER-004", desc: "nessun errore (null) → no", e: null, expected: false },
    { id: "TB-WEB-VER-005", desc: "nessun errore (undefined) → no", e: undefined, expected: false },
  ]),
)("[%s] isVersionConflict: %s", (_id, _desc, { e, expected }) => {
  expect(isVersionConflict(e)).toBe(expected);
});

const body = { name: "Doppio weekend", priority: 10 };

it.each(
  rows([
    { id: "TB-WEB-VER-006", desc: "versione 3 → aggiunta al corpo del PUT", v: 3 as number | null | undefined, expected: { ...body, version: 3 } as object },
    { id: "TB-WEB-VER-007", desc: "versione 0 (prima versione) → aggiunta, non scambiata per assente", v: 0, expected: { ...body, version: 0 } },
    { id: "TB-WEB-VER-008", desc: "versione null → corpo invariato", v: null, expected: body },
    { id: "TB-WEB-VER-009", desc: "versione assente → corpo invariato", v: undefined, expected: body },
  ]),
)("[%s] withVersion: %s", (_id, _desc, { v, expected }) => {
  expect(withVersion(body, v)).toEqual(expected);
});

it("[TB-WEB-VER-010] Sovrascrivi → stesse modifiche senza versione (nessun controllo)", () => {
  const input = { ...body, version: 7 };
  expect(withoutVersion(input)).toEqual(body);
  expect(input.version).toBe(7);
});
