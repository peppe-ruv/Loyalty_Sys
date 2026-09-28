import { describe, expect, it } from "vitest";
import {
  externalSourcesFor,
  isEditableSource,
  isProgramGenerated,
  planAllowedTypes,
  sourcesToEnable,
  reachOf,
  sourceAccepts,
  unreachableTriggers,
  withAllowedType,
  withoutAllowedType,
  type SourceRow,
} from "./sources";
import seedSources from "../../../seed/sources.json";

// Azioni ammesse dalle fonti (BO-09, BO-06; Q-433, Q-437). `PUT /v1/sources/{code}` sostituisce l'elenco: la UI manda
// sempre l'elenco completo e non trasforma mai un elenco pieno in `[]` («tutte le azioni») senza una scelta esplicita.

const SOURCES = seedSources as SourceRow[];
const src = (code: string) => SOURCES.find((s) => s.code === code)!;

describe("da dove può arrivare un'azione", () => {
  it("una nuova azione personalizzata arriva solo dal simulatore e dal ponte interno", () => {
    const reach = Object.fromEntries(reachOf("store.visited", SOURCES).map((r) => [r.source.code, r.status]));
    expect(reach).toEqual({
      app: "NOT_ALLOWED",
      billing: "NOT_ALLOWED",
      crm: "NOT_ALLOWED",
      ecommerce: "NOT_ALLOWED",
      partner: "NOT_ALLOWED",
      internal: "ALL",
      simulator: "ALL",
    });
    expect(externalSourcesFor("store.visited", SOURCES)).toEqual([]);
  });

  it("le fonti esterne vengono prima; una fonte spenta è «OFF»", () => {
    const off = SOURCES.map((s) => (s.code === "ecommerce" ? { ...s, enabled: false } : s));
    const reach = reachOf("purchase.completed", off);
    expect(reach.slice(0, 5).every((r) => r.source.kind === "HTTP")).toBe(true);
    expect(reach.find((r) => r.source.code === "ecommerce")!.status).toBe("OFF");
    expect(reach.find((r) => r.source.code === "app")!.status).toBe("ACCEPTS");
    expect(externalSourcesFor("purchase.completed", off).map((s) => s.code)).toEqual(["app"]);
  });

  it("un elenco vuoto accetta tutto", () => {
    expect(sourceAccepts({ allowedTypes: [] }, "qualunque.cosa")).toBe(true);
    expect(sourceAccepts(src("crm"), "purchase.completed")).toBe(false);
  });
});

describe("aggiornare le azioni ammesse", () => {
  it("aggiungere manda l'elenco completo", () => {
    expect(withAllowedType(src("app"), "store.visited")).toEqual([
      "purchase.completed",
      "selfreading.submitted",
      "app.login.daily",
      "store.visited",
    ]);
    expect(withAllowedType(src("app"), "purchase.completed")).toBeNull();
    expect(withAllowedType(src("simulator"), "store.visited")).toBeNull();
  });

  it("togliere manda l'elenco completo meno l'azione, mai []", () => {
    expect(withoutAllowedType(src("ecommerce"), "review.submitted")).toEqual({ allowedTypes: ["purchase.completed", "purchase.returned"] });
    expect(withoutAllowedType(src("crm"), "newsletter.subscribed")).toEqual({ blocked: "LAST_TYPE" });
    expect(withoutAllowedType(src("simulator"), "x.y")).toEqual({ blocked: "ALL_TYPES" });
    expect(withoutAllowedType(src("crm"), "x.y")).toEqual({ blocked: "NOT_PRESENT" });
  });

  it("[] solo con la scelta esplicita «Tutte le azioni»", () => {
    expect(planAllowedTypes("ALL", ["a.b"])).toEqual({ ok: true, allowedTypes: [] });
    expect(planAllowedTypes("LIST", [])).toEqual({ ok: false, reason: "EMPTY_LIST" });
    expect(planAllowedTypes("LIST", ["", "a.b", "a.b", "c.d"])).toEqual({ ok: true, allowedTypes: ["a.b", "c.d"] });
  });
});

describe("fonti ammesse di una campagna (Q-437)", () => {
  it("segnala per fonte i trigger che non accetta, e se non ne accetta nessuno", () => {
    const out = unreachableTriggers(["app", "ecommerce"], ["purchase.completed", "review.submitted"], SOURCES);
    expect(out.map((o) => `${o.source.code}:${o.triggers.join("+")}:${o.none}`)).toEqual(["app:review.submitted:false"]);
    const none = unreachableTriggers(["app"], ["review.submitted", "store.visited"], SOURCES);
    expect(none).toEqual([expect.objectContaining({ triggers: ["review.submitted", "store.visited"], none: true })]);
  });

  it("nessuna fonte scelta = tutte, nessun avviso; fonti sconosciute ignorate", () => {
    expect(unreachableTriggers([], ["store.visited"], SOURCES)).toEqual([]);
    expect(unreachableTriggers(["sparita"], ["store.visited"], SOURCES)).toEqual([]);
    expect(unreachableTriggers(["simulator"], ["store.visited"], SOURCES)).toEqual([]);
  });
});

describe("abilitazione subito dalla sezione 5 (Q-433)", () => {
  const opts = { canConfigSources: true, isNew: true, code: "store.visited", category: "ENGAGEMENT" };

  it("ADMIN: solo le fonti esterne spuntate che non la accettano già", () => {
    const out = sourcesToEnable(["app", "simulator", "ecommerce"], SOURCES, opts);
    expect(out.map((s) => s.code)).toEqual(["app", "ecommerce"]);
    expect(sourcesToEnable(["app"], SOURCES, { ...opts, code: "purchase.completed" })).toEqual([]);
  });

  it("MARKETING non scrive mai sulle fonti, anche con caselle spuntate", () => {
    expect(sourcesToEnable(["app", "ecommerce"], SOURCES, { ...opts, canConfigSources: false })).toEqual([]);
  });

  it("niente da abilitare per un'azione esistente o generata dal programma", () => {
    expect(sourcesToEnable(["app"], SOURCES, { ...opts, isNew: false })).toEqual([]);
    expect(sourcesToEnable(["app"], SOURCES, { ...opts, category: "INTERNAL" })).toEqual([]);
    expect(isProgramGenerated({ category: "INTERNAL" })).toBe(true);
    expect(isProgramGenerated({ category: "ENGAGEMENT" })).toBe(false);
  });

  it("solo le fonti esterne si modificano dalla UI", () => {
    expect(isEditableSource(src("app"))).toBe(true);
    expect(isEditableSource(src("internal"))).toBe(false);
    expect(isEditableSource(src("simulator"))).toBe(false);
  });
});
