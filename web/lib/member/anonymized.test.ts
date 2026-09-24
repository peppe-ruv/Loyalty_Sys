import { describe, expect, it } from "vitest";
import {
  ANONYMIZED_LABEL,
  anonymizeErrorMessage,
  canConfirmAnonymize,
  isAnonymized,
  memberDisplayName,
  personalValue,
} from "./anonymized";

describe("membri anonimizzati", () => {
  it("mostra il segnaposto per un anonimizzato, anche se restasse un nome", () => {
    expect(memberDisplayName({ firstName: null, lastName: null, nickname: "Membro anonimo", status: "ANONYMIZED" })).toBe(ANONYMIZED_LABEL);
    expect(memberDisplayName({ firstName: "Giulia", lastName: "Ferri", status: "ANONYMIZED" })).toBe(ANONYMIZED_LABEL);
    expect(memberDisplayName({ firstName: null, lastName: null, nickname: null, status: "ANONYMIZED" })).toBe(ANONYMIZED_LABEL);
  });

  it("per gli altri usa nome e cognome, poi il nickname, poi il ripiego", () => {
    expect(memberDisplayName({ firstName: "Giulia", lastName: "Ferri", status: "ACTIVE" })).toBe("Giulia Ferri");
    expect(memberDisplayName({ firstName: "Giulia", lastName: null, status: "ACTIVE" })).toBe("Giulia");
    expect(memberDisplayName({ firstName: null, lastName: null, nickname: "giu_f", status: "ACTIVE" })).toBe("giu_f");
    expect(memberDisplayName({ status: "ACTIVE" }, "MBR-000003")).toBe("MBR-000003");
  });

  it("i campi personali mostrano il segnaposto dopo l'anonimizzazione", () => {
    expect(personalValue("ANONYMIZED", null)).toBe(ANONYMIZED_LABEL);
    expect(personalValue("ACTIVE", null)).toBe("—");
    expect(personalValue("ACTIVE", "giulia.ferri@example.org")).toBe("giulia.ferri@example.org");
    expect(isAnonymized("ANONYMIZED")).toBe(true);
    expect(isAnonymized("BLOCKED")).toBe(false);
    expect(isAnonymized(undefined)).toBe(false);
  });

  it("conferma solo digitando esattamente l'ID", () => {
    expect(canConfirmAnonymize("MBR-001000", "MBR-001000")).toBe(true);
    expect(canConfirmAnonymize("  MBR-001000 ", "MBR-001000")).toBe(true);
    expect(canConfirmAnonymize("mbr-001000", "MBR-001000")).toBe(false);
    expect(canConfirmAnonymize("MBR-00100", "MBR-001000")).toBe(false);
    expect(canConfirmAnonymize("", "")).toBe(false);
  });

  it("traduce gli errori dell'API", () => {
    expect(anonymizeErrorMessage(null)).toBeNull();
    expect(anonymizeErrorMessage({ code: "CONFIRM_MISMATCH" })).toContain("non corrisponde");
    expect(anonymizeErrorMessage({ code: "MEMBER_ANONYMIZED" })).toContain("già anonimizzato");
    expect(anonymizeErrorMessage({ code: "FORBIDDEN_ROLE" })).toContain("ADMIN");
    expect(anonymizeErrorMessage({ code: "X", asleep: true })).toContain("non risponde");
    expect(anonymizeErrorMessage({ code: "OTHER", detail: "Dettaglio" })).toBe("Dettaglio");
  });
});
