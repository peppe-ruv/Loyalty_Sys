import { describe, expect, it } from "vitest";
import { memberStatusActions, statusChangeBody, statusChangeErrorMessage } from "./status";

const summary = (status: string) =>
  memberStatusActions(status).map((a) => ({ key: a.key, label: a.label, target: a.target, enabled: a.disabledReason === null }));

describe("azioni di stato del membro (BO-03, F-MBR-04)", () => {
  it("un membro ACTIVE si può bloccare o disattivare", () => {
    expect(summary("ACTIVE")).toEqual([
      { key: "block", label: "Blocca", target: "BLOCKED", enabled: true },
      { key: "deactivate", label: "Disattiva", target: "INACTIVE", enabled: true },
    ]);
  });

  it("un membro BLOCKED si sblocca (torna ACTIVE) o si disattiva", () => {
    expect(summary("BLOCKED")).toEqual([
      { key: "unblock", label: "Sblocca", target: "ACTIVE", enabled: true },
      { key: "deactivate", label: "Disattiva", target: "INACTIVE", enabled: true },
    ]);
  });

  it("un membro INACTIVE non ha cambi di stato dal menu", () => {
    const actions = memberStatusActions("INACTIVE");
    expect(actions.map((a) => a.key)).toEqual(["block", "deactivate"]);
    expect(actions[0].disabledReason).toMatch(/disattivato/);
    expect(actions[1].disabledReason).toBe("Membro già disattivato");
  });

  it("con un membro ANONYMIZED tutte le azioni sono disabilitate", () => {
    const actions = memberStatusActions("ANONYMIZED");
    expect(actions.map((a) => a.label)).toEqual(["Blocca", "Disattiva"]);
    for (const a of actions) expect(a.disabledReason).toBe("Membro anonimizzato: azioni disabilitate");
  });

  it("il corpo della richiesta porta il motivo solo se scritto", () => {
    expect(statusChangeBody("BLOCKED", "  ")).toEqual({ status: "BLOCKED" });
    expect(statusChangeBody("BLOCKED", " frode sospetta ")).toEqual({ status: "BLOCKED", reason: "frode sospetta" });
  });

  it("traduce gli errori del servizio", () => {
    expect(statusChangeErrorMessage(null)).toBeNull();
    expect(statusChangeErrorMessage({ asleep: true })).toMatch(/non risponde/);
    expect(statusChangeErrorMessage({ code: "MEMBER_ANONYMIZED" })).toMatch(/anonimizzato/);
    expect(statusChangeErrorMessage({ code: "FORBIDDEN_ROLE" })).toMatch(/ADMIN o CARE/);
    expect(statusChangeErrorMessage({ code: "BAD_REQUEST", detail: "status deve essere ACTIVE, INACTIVE o BLOCKED" })).toBe(
      "status deve essere ACTIVE, INACTIVE o BLOCKED",
    );
  });
});
