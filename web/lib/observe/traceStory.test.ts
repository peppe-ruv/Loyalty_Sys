import { describe, expect, it } from "vitest";
import { buildStory, hasFailedStep, detailOf, outcomeChips, statusLabel, storySentence, isTechOnly } from "./traceStory";
import { CHAIN_TRACE, FAILED_TRACE, PURCHASE_TRACE, REPROCESSED_TRACE, TRACE_LIST } from "@/test/fixtures/traces";

const names: Record<string, string> = { PTS: "Punti", STS: "Punti status" };
const currencyName = (c: string) => names[c] ?? c;

describe("buildStory (BO-25, issue #204)", () => {
  const story = buildStory(PURCHASE_TRACE, { currencyName });

  it("divide i 16 nodi del giro purchase.completed in 4 fasi", () => {
    expect(PURCHASE_TRACE.nodes).toHaveLength(16);
    expect(story.phases.map((p) => p.id)).toEqual(["action", "rules", "effects", "facts"]);
    expect(story.phases.every((p) => p.steps.length > 0)).toBe(true);
    expect(story.root?.shortType).toBe("purchase.completed");
  });

  it("racconta l'azione radice con il suo dato chiave", () => {
    const [first] = story.phases[0].steps;
    expect(first.label).toBe("Acquisto completato");
    expect(first.detail).toBe("€ 24,90");
  });

  it("mette campaign.evaluated nelle regole e wallet.* negli effetti, con il nome della valuta", () => {
    expect(story.phases[1].steps.map((s) => s.shortType)).toEqual(["campaign.evaluated"]);
    const effects = story.phases[2].steps;
    expect(effects.find((s) => s.shortType === "points.grant")?.count).toBe(2);
    const earned = effects.find((s) => s.shortType === "wallet.points.earned");
    expect(earned?.count).toBe(2);
    expect(earned?.label).toBe("Punti accreditati");
  });

  it("raggruppa achievement.progressed ×3", () => {
    const progressed = story.phases[3].steps.find((s) => s.shortType === "achievement.progressed");
    expect(progressed?.count).toBe(3);
    expect(progressed?.eventIds).toEqual(["e07", "e08", "e09"]);
    expect(progressed?.label).toBe("Obiettivo avanzato");
  });

  it("annida sotto badge.awarded la nuova azione e la sua valutazione (parentEventId)", () => {
    const badge = story.phases[3].steps.find((s) => s.family === "FACT" && s.shortType === "badge.awarded");
    expect(badge).toBeDefined();
    expect(badge?.triggered.map((s) => `${s.family}:${s.shortType}`)).toEqual(["ACTION:badge.awarded", "FACT:campaign.evaluated"]);
    // La seconda valutazione non compare tra le regole della radice.
    expect(story.phases[1].steps[0].count).toBe(1);
  });

  it("elenca la radice e l'azione derivata per il riquadro «Perché»", () => {
    expect(story.actions.map((a) => a.eventId)).toEqual(["e01", "e13"]);
  });

  it("evidenzia il passo bloccato di un tracciato FAILED nella fase del suo evento", () => {
    const failed = buildStory(FAILED_TRACE, { currencyName });
    expect(failed.failed).toHaveLength(1);
    expect(failed.failed[0].eventIds).toEqual(["dlq-77"]);
    expect(failed.failed[0].detail).toBe("Punti da accreditare");
    expect(failed.phases[2].steps.some((s) => s.failed)).toBe(true);
    expect(failed.phases[3].steps).toHaveLength(0);
  });
});

describe("buildStory: DLQ riprocessata e azioni annidate", () => {
  it("una voce DLQ REPROCESSED è un passo neutro e non va in story.failed", () => {
    const story = buildStory(REPROCESSED_TRACE, { currencyName });
    expect(story.failed).toHaveLength(0);
    const step = story.phases[2].steps.find((s) => s.family === "DLQ");
    expect(step?.failed).toBe(false);
    expect(step?.label).toBe("Passato dalla DLQ e riprocessato");
    expect(step?.detail).toBe("Punti da accreditare");
    expect(hasFailedStep(story.phases[2].steps)).toBe(false);
  });

  it("annida per azione derivata più vicina: le due campaign.evaluated restano in elenchi diversi", () => {
    const story = buildStory(CHAIN_TRACE, { currencyName });
    const badge = story.phases[3].steps.find((s) => s.family === "FACT" && s.shortType === "badge.awarded");
    expect(badge?.eventIds).toEqual(["r02"]);
    // Primo livello: la valutazione r04 e il badge r05 stanno nell'elenco della prima azione derivata.
    expect(badge?.triggered.map((s) => s.eventIds[0])).toEqual(["r03", "r04", "r05"]);
    expect(badge?.triggered.find((s) => s.eventIds[0] === "r04")?.count).toBe(1);
    // Secondo livello: la seconda valutazione r07 pende dal badge r05, nel proprio elenco.
    const inner = badge?.triggered.find((s) => s.eventIds[0] === "r05")?.triggered;
    expect(inner?.map((s) => s.eventIds[0])).toEqual(["r06", "r07"]);
    expect(inner?.every((s) => s.count === 1)).toBe(true);
    // Nessuna valutazione nelle regole della radice, e il «Perché» elenca le tre azioni.
    expect(story.phases[1].steps).toHaveLength(0);
    expect(story.actions.map((a) => a.eventId)).toEqual(["r01", "r03", "r06"]);
  });
});

describe("testi del racconto", () => {
  it("frase di sintesi in italiano con l'importo", () => {
    expect(storySentence(PURCHASE_TRACE.nodes[0])).toBe("ha completato un acquisto da € 24,90");
  });

  it("stato in italiano", () => {
    expect(statusLabel("COMPLETE")).toBe("Completato");
    expect(statusLabel("IN_PROGRESS")).toBe("In corso");
    expect(statusLabel("FAILED")).toBe("Bloccato");
  });

  it("dato chiave senza codici di valuta", () => {
    expect(detailOf(PURCHASE_TRACE.nodes[4], currencyName)).toBe("+100 Punti");
    expect(detailOf(PURCHASE_TRACE.nodes[6], currencyName)).toBe("");
  });

  it("esito dell'elenco in chip leggibili", () => {
    const chip = (c: string) => (c === "PTS" ? "punti" : c === "STS" ? "status" : c);
    expect(outcomeChips({ ...TRACE_LIST[0], outcomeSummary: "+124 PTS · +24 STS · tier GOLD" }, chip).map((c) => c.text)).toEqual([
      "+124 punti",
      "+24 status",
      "livello GOLD",
    ]);
    // Forma del server: importo negativo → «+-180 PTS», un solo segno e tono neutro.
    const neg = outcomeChips({ ...TRACE_LIST[0], outcomeSummary: "+-180 PTS" }, chip);
    expect(neg).toHaveLength(1);
    expect(neg[0].text).toMatch(/^[-−]180 punti$/);
    expect(neg[0].tone).toBe("neutral");
    expect(outcomeChips(TRACE_LIST[2]).map((c) => c.text)).toEqual(["in elaborazione"]);
    expect(outcomeChips(TRACE_LIST[3]).map((c) => c.text)).toEqual(["nessun premio"]);
    expect(isTechOnly(TRACE_LIST[1])).toBe(true);
    expect(isTechOnly(TRACE_LIST[0])).toBe(false);
  });
});
