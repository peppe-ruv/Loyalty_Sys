import { describe, expect, it } from "vitest";
import { ACTION_TEMPLATES, applyTemplate, duplicateFrom } from "./templates";
import { isAcceptableCode } from "./code";
import { rowErrors, rowsToSchema } from "./schema";
import { isKnownActionIcon } from "@/lib/icons/action-icons";
import type { ActionType } from "./types";
import { readSeed } from "@/test/seed";

// «Parti da un modello» e «Duplica da un'azione esistente» (BO-09 sezione 3; Q-434).

const SEED = readSeed<ActionType[]>("event-types.json");
const template = (key: string) => ACTION_TEMPLATES.find((t) => t.key === key)!;

describe("modelli", () => {
  it("propongono prima l'azione di sistema che esiste già", () => {
    for (const key of ["purchase", "review", "survey"]) {
      const choice = applyTemplate(template(key), SEED);
      expect(choice.kind).toBe("existing");
      if (choice.kind === "existing") expect(choice.internal).toBe(false);
    }
    const referral = applyTemplate(template("referral"), SEED);
    expect(referral).toMatchObject({ kind: "existing", internal: true });
  });

  it("i modelli nuovi hanno un codice inglese valido, campi validi, icona in elenco e nessun dato personale", () => {
    for (const t of ACTION_TEMPLATES.filter((x) => x.code)) {
      const choice = applyTemplate(t, SEED);
      expect(choice.kind).toBe("draft");
      if (choice.kind !== "draft") continue;
      expect(isAcceptableCode(choice.code, SEED.map((s) => s.code))).toBe(true);
      expect(choice.rows.length).toBeGreaterThan(0);
      expect(rowErrors(choice.rows)).toEqual({});
      expect(choice.rows.some((r) => r.required)).toBe(true);
      expect(isKnownActionIcon(choice.icon)).toBe(true);
      const props = rowsToSchema(choice.rows).properties as Record<string, Record<string, unknown>>;
      for (const p of Object.values(props)) expect(p["x-lh-pii"]).toBe(false);
    }
  });

  it("«Visita in negozio» usa il campo standard storeId", () => {
    const choice = applyTemplate(template("storeVisit"), SEED);
    expect(choice).toMatchObject({ kind: "draft", code: "store.visited", category: "ENGAGEMENT", icon: "map-pin" });
    if (choice.kind === "draft") expect(choice.rows.map((r) => r.name)).toEqual(["storeId", "visitDate"]);
  });

  it("se il codice del modello esiste già (azione creata prima) propone quella", () => {
    const created = { ...SEED[0], code: "store.visited", name: "Visita", origin: "CUSTOM" as const, category: "ENGAGEMENT" };
    expect(applyTemplate(template("storeVisit"), [...SEED, created])).toMatchObject({ kind: "existing", type: { code: "store.visited" } });
  });

  it("senza l'azione di sistema (dati ridotti) si parte da una bozza vuota", () => {
    expect(applyTemplate(template("purchase"), [])).toMatchObject({ kind: "draft", code: "", rows: [] });
  });
});

describe("duplica", () => {
  it("copia campi, categoria, icona e descrizione", () => {
    const selfreading = SEED.find((s) => s.code === "selfreading.submitted")!;
    const copy = duplicateFrom(selfreading);
    expect(copy.rows.map((r) => [r.name, r.kind, r.required])).toEqual([
      ["meterId", "string", true],
      ["reading", "number", true],
    ]);
    expect(copy).toMatchObject({ category: "SERVICE", icon: "gauge", skipped: [] });
    expect(copy.description).toBe(selfreading.description);
  });

  it("salta elenchi e oggetti e trasforma un'azione interna in «Coinvolgimento»", () => {
    const purchase = duplicateFrom(SEED.find((s) => s.code === "purchase.completed")!);
    expect(purchase.skipped).toEqual(["items"]);
    expect(purchase.rows.map((r) => r.name)).toEqual(["orderId", "amount", "currency", "channel"]);
    expect(duplicateFrom(SEED.find((s) => s.code === "tier.upgraded")!).category).toBe("ENGAGEMENT");
  });
});
