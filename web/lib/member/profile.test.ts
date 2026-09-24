import { describe, expect, it } from "vitest";
import { completenessPct, joinErrorFromApi, missingFieldsSentence, normalizeCode, validateJoin } from "./profile";

const OK = { firstName: "Lia", lastName: "Nuova", email: "lia@example.org", referralCode: "", terms: true, marketing: false };

describe("profilo e registrazione", () => {
  it("descrive i campi mancanti", () => {
    expect(missingFieldsSentence([])).toBe("Profilo completo");
    expect(missingFieldsSentence(["city"])).toBe("Manca città");
    expect(missingFieldsSentence(["phone", "birthDate", "city"])).toBe("Mancano telefono, data di nascita e città");
    expect(completenessPct(["city"])).toBe(83);
  });

  it("valida il form minimo di iscrizione", () => {
    expect(validateJoin(OK)).toEqual({});
    expect(validateJoin({ ...OK, email: "x", terms: false })).toEqual({
      email: "Serve un indirizzo e-mail valido",
      terms: "Per iscriverti accetta regolamento e informativa",
    });
    expect(validateJoin({ ...OK, referralCode: "abc" }).referralCode).toBeDefined();
    expect(validateJoin({ ...OK, referralCode: " ab23 cd45 " })).toEqual({});
    expect(normalizeCode(" ab23 cd45 ")).toBe("AB23CD45");
  });

  it("porta sul campo gli errori del member-service", () => {
    expect(joinErrorFromApi("REFERRAL_CODE_INVALID", "")).toEqual({ referralCode: "Codice amico non valido" });
    expect(joinErrorFromApi("EMAIL_TAKEN", "")).toEqual({ email: "Questa e-mail è già iscritta al Club" });
  });
});
