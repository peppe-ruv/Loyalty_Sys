import { describe, expect, it } from "vitest";
import { describeAchievement, periodPhrase, streakDots } from "./achievements";

describe("frase generata dell'obiettivo", () => {
  it("conteggio mensile ripetibile", () => {
    expect(
      describeAchievement({ metric: "COUNT", actionTypes: ["purchase.completed"], target: 3, period: "MONTH", repeatable: true }),
    ).toBe("Completa **3** volte **Acquisto completato** **nello stesso mese**; si può ripetere ogni periodo.");
  });
  it("primo acquisto, una volta sola", () => {
    expect(
      describeAchievement({ metric: "COUNT", actionTypes: ["purchase.completed"], target: 1, period: "EVER", repeatable: false }),
    ).toBe("Fai **Acquisto completato** per la prima volta; una volta sola.");
  });
  it("somma, tipi distinti e serie", () => {
    expect(
      describeAchievement({ metric: "SUM", sumField: "data.amount", actionTypes: ["purchase.completed"], target: 1000, period: "EDITION", repeatable: false }),
    ).toContain("sommando **amount**");
    expect(
      describeAchievement({ metric: "DISTINCT_TYPES", actionTypes: ["ebill.activated", "directdebit.activated"], target: 2, period: "EVER", repeatable: false }),
    ).toContain("**Bolletta digitale attivata** o **Domiciliazione attivata**");
    expect(
      describeAchievement({ metric: "STREAK", streakUnit: "DAY", actionTypes: ["app.login.daily"], target: 7, period: "EVER", repeatable: false }),
    ).toContain("**7 giorni di fila**");
  });
});

describe("portale", () => {
  it("periodi e pallini", () => {
    expect(periodPhrase("MONTH")).toBe("questo mese");
    expect(periodPhrase("EVER")).toBeNull();
    expect(streakDots(5, 7)).toEqual([true, true, true, true, true, false, false]);
  });
});
