import { readdirSync, readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { eventLabel } from "./eventLabels";

// Ogni tipo evento dei contratti (contracts/events/) e dei seed (seed/event-types.json) ha un'etichetta italiana:
// un tipo nuovo senza etichetta fa fallire questo test (issue #204).

const ROOT = resolve(process.cwd(), "..");
const typesIn = (folder: string) =>
  readdirSync(resolve(ROOT, "contracts/events", folder))
    .filter((f) => f.endsWith(".schema.json"))
    .map((f) => f.replace(/\.schema\.json$/, ""));

describe("etichette dei tipi evento (BO-25)", () => {
  it.each([
    ["ACTION", "action"],
    ["FACT", "fact"],
    ["EFFECT", "effect"],
  ])("ogni tipo %s dei contratti ha un'etichetta", (family, folder) => {
    const types = typesIn(folder);
    expect(types.length).toBeGreaterThan(0);
    for (const t of types) expect(eventLabel(family, t), `${family} ${t}`).not.toBe(t);
  });

  it("ogni azione dei seed ha un'etichetta", () => {
    const seed = JSON.parse(readFileSync(resolve(ROOT, "seed/event-types.json"), "utf8")) as { code: string }[];
    for (const { code } of seed) expect(eventLabel("ACTION", code), code).not.toBe(code);
  });

  it("le etichette non contengono codici", () => {
    expect(eventLabel("FACT", "achievement.progressed")).toBe("Obiettivo avanzato");
    expect(eventLabel("ACTION", "purchase.completed")).toBe("Acquisto completato");
    expect(eventLabel("EFFECT", "points.grant")).toBe("Punti da accreditare");
    expect(eventLabel("DLQ", "points.grant")).toBe("Elaborazione non riuscita");
  });
});
