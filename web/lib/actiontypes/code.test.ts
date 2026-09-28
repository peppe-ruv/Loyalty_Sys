import { describe, expect, it } from "vitest";
import {
  codeChecks,
  codeWords,
  dedupe,
  integratorTypes,
  isAcceptableCode,
  isReservedCode,
  suggestCode,
  systemNamespaceClash,
  VERB_CHOICES,
} from "./code";

// Codice tecnico proposto dal nome (BO-09 sezione 2; Q-429, Q-430). La regola del servizio non cambia: la UI la rende
// solo più severa.

describe("codice proposto dal nome", () => {
  it("minuscolo, senza accenti, senza articoli e preposizioni, parole unite da un punto", () => {
    expect(suggestCode("Visita in negozio")).toEqual({ code: "visita.negozio", needsVerb: false });
    expect(suggestCode("Città del caffè")).toEqual({ code: "citta.caffe", needsVerb: false });
    expect(suggestCode("Partecipazione all'evento dell'anno")).toEqual({ code: "partecipazione.evento.anno", needsVerb: false });
    expect(suggestCode("  Iscrizione — corso   di   yoga!  ")).toEqual({ code: "iscrizione.corso.yoga", needsVerb: false });
  });

  it("al massimo 4 parole", () => {
    expect(suggestCode("alfa beta gamma delta epsilon").code).toBe("alfa.beta.gamma.delta");
    expect(suggestCode("uno due tre quattro cinque").code).toBe("due.tre.quattro.cinque"); // «uno» è un articolo
  });

  it("entro 60 caratteri, tagliando solo tra una parola e l'altra", () => {
    const long = "precipitevolissimevolmente straordinariamente inimmaginabilmente incomprensibilmente";
    const code = suggestCode(long).code!;
    expect(code.length).toBeLessThanOrEqual(60);
    expect(code).toBe("precipitevolissimevolmente.straordinariamente");
    expect(isAcceptableCode(code)).toBe(true);
    // Una sola parola più lunga di 60: si accorcia a 40 e resta spazio per la seconda.
    const huge = "a".repeat(70) + " b";
    expect(suggestCode(huge).code).toBe("a".repeat(40) + ".b");
  });

  it("toglie le cifre iniziali di una parola e le parole fatte solo di cifre", () => {
    expect(codeWords("2024 Visita 3negozi")).toEqual(["visita", "negozi"]);
    expect(suggestCode("Evento 2026 speciale").code).toBe("evento.speciale");
  });

  it("un nome di una sola parola chiede «Che cosa è successo?» e usa il participio inglese", () => {
    expect(suggestCode("Iscrizione")).toEqual({ code: null, needsVerb: true });
    expect(suggestCode("Iscrizione", [], "completed")).toEqual({ code: "iscrizione.completed", needsVerb: false });
    expect(VERB_CHOICES.map((v) => v.participle)).toContain("submitted");
    for (const v of VERB_CHOICES) expect(v.participle).toMatch(/^[a-z]+$/);
  });

  it("un nome vuoto o fatto di sole parole scartate non propone nulla", () => {
    expect(suggestCode("")).toEqual({ code: null, needsVerb: false });
    expect(suggestCode("di la del")).toEqual({ code: null, needsVerb: false });
  });

  it("un codice già usato riceve un numero sull'ultima parte", () => {
    expect(suggestCode("Visita in negozio", ["visita.negozio"]).code).toBe("visita.negozio2");
    expect(suggestCode("Visita in negozio", ["visita.negozio", "visita.negozio2"]).code).toBe("visita.negozio3");
    const long = ["a".repeat(30), "b".repeat(29)];
    const taken = new Set([long.join(".")]);
    const deduped = dedupe(long, taken);
    expect(deduped.length).toBeLessThanOrEqual(60);
    expect(deduped.endsWith("2")).toBe(true);
    expect(taken.has(deduped)).toBe(false);
  });

  it("salta una prima parola riservata (io, loyaltyhub)", () => {
    expect(suggestCode("Loyaltyhub effect premio").code).toBe("effect.premio");
    expect(suggestCode("io loyaltyhub effect x").code).toBe("effect.x");
  });
});

describe("controlli del codice", () => {
  const byKey = (code: string, taken: string[] = []) => Object.fromEntries(codeChecks(code, taken).map((c) => [c.key, c.ok]));

  it("elenco dal vivo: caratteri, parti, lunghezza, unicità, prefisso riservato", () => {
    expect(byKey("visita.negozio")).toEqual({ chars: true, parts: true, length: true, unique: true, reserved: true });
    expect(byKey("Visita.negozio").chars).toBe(false);
    expect(byKey("visita-negozio").chars).toBe(false);
    expect(byKey("visita.2negozio").chars).toBe(false);
    expect(byKey("visita").parts).toBe(false);
    expect(byKey("a.b.c.d.e").parts).toBe(false);
    expect(byKey("visita..negozio").parts).toBe(false);
    expect(byKey("a." + "b".repeat(60)).length).toBe(false);
    expect(byKey("purchase.completed", ["purchase.completed"]).unique).toBe(false);
    expect(byKey("io.loyaltyhub.effect.x").reserved).toBe(false);
    expect(byKey("loyaltyhub.x").reserved).toBe(false);
  });

  it("accettabile solo se rispetta la regola del servizio e quelle della UI", () => {
    expect(isAcceptableCode("meter.reading.sent")).toBe(true);
    expect(isAcceptableCode("io.loyaltyhub.effect.x")).toBe(false);
    expect(isAcceptableCode("store.visited", ["store.visited"])).toBe(false);
    expect(isAcceptableCode("")).toBe(false);
    expect(isReservedCode("io.x")).toBe(true);
    expect(isReservedCode("iot.x")).toBe(false);
  });

  it("avvisa quando riusa lo spazio di nomi di un'azione di sistema", () => {
    const types = [
      { code: "purchase.completed", origin: "SYSTEM" },
      { code: "store.visited", origin: "CUSTOM" },
    ];
    expect(systemNamespaceClash("purchase.partner", types)).toBe("purchase.completed");
    expect(systemNamespaceClash("store.opened", types)).toBeNull();
    expect(systemNamespaceClash("visita.negozio", types)).toBeNull();
  });

  it("anteprima per l'integratore: forma breve o completa", () => {
    expect(integratorTypes("store.visited")).toEqual({ short: "store.visited", full: "io.loyaltyhub.action.store.visited" });
  });
});
