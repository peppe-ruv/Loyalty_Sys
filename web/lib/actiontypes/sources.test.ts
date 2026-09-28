import { describe, expect, it } from "vitest";
import {
  externalSourcesFor,
  planAllowedTypes,
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
  it("segnala le fonti scelte che non accettano un trigger", () => {
    const out = unreachableTriggers(["app", "ecommerce"], ["purchase.completed", "review.submitted"], SOURCES);
    expect(out.map((o) => `${o.source.code}:${o.trigger}`)).toEqual(["app:review.submitted"]);
  });

  it("nessuna fonte scelta = tutte, nessun avviso; fonti sconosciute ignorate", () => {
    expect(unreachableTriggers([], ["store.visited"], SOURCES)).toEqual([]);
    expect(unreachableTriggers(["sparita"], ["store.visited"], SOURCES)).toEqual([]);
    expect(unreachableTriggers(["simulator"], ["store.visited"], SOURCES)).toEqual([]);
  });
});
