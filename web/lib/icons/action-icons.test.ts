import { describe, expect, it } from "vitest";
import * as lucide from "lucide-react";
import {
  ACTION_ICONS,
  actionIcon,
  actionIconLabel,
  FALLBACK_ACTION_ICON,
  isKnownActionIcon,
  searchActionIcons,
  SEED_ACTION_ICONS,
} from "./action-icons";
import seedTypes from "../../../seed/event-types.json";

// Elenco chiuso delle icone delle azioni (BO-09, BO-06; Q-431). Ogni nome deve esistere nella versione installata di
// lucide-react e corrispondere al componente importato; ogni icona del seed deve restare nell'elenco.

const pascal = (kebab: string) =>
  kebab
    .split("-")
    .map((p) => p.charAt(0).toUpperCase() + p.slice(1))
    .join("");

describe("icone delle azioni", () => {
  it("ogni nome esiste in lucide-react ed è il componente importato", () => {
    const exports = lucide as unknown as Record<string, unknown>;
    for (const choice of ACTION_ICONS) {
      const component = exports[pascal(choice.name)];
      expect(component, choice.name).toBeDefined();
      expect(component, choice.name).toBe(choice.Icon);
    }
  });

  it("nomi ed etichette sono unici e ogni etichetta è in italiano (non vuota)", () => {
    const names = ACTION_ICONS.map((c) => c.name);
    expect(new Set(names).size).toBe(names.length);
    for (const c of ACTION_ICONS) {
      expect(c.name).toMatch(/^[a-z0-9]+(-[a-z0-9]+)*$/);
      expect(c.label.trim().length).toBeGreaterThan(0);
    }
    expect(new Set(ACTION_ICONS.map((c) => c.label)).size).toBe(ACTION_ICONS.length);
  });

  it("contiene tutte le icone del seed e le icone curate della proposta", () => {
    const seedIcons = new Set((seedTypes as { icon?: string }[]).map((t) => t.icon).filter(Boolean) as string[]);
    for (const icon of seedIcons) expect(isKnownActionIcon(icon), icon).toBe(true);
    expect([...SEED_ACTION_ICONS].sort()).toEqual([...seedIcons].sort());
    for (const icon of ["map-pin", "store", "calendar", "ticket", "qr-code", "handshake", "heart", "thumbs-up", "message-square", "camera", "leaf", "recycle", "log-in"]) {
      expect(isKnownActionIcon(icon), icon).toBe(true);
    }
  });

  it("un valore fuori elenco o assente usa ⚡", () => {
    expect(actionIcon("inesistente")).toBe(FALLBACK_ACTION_ICON);
    expect(actionIcon(null)).toBe(FALLBACK_ACTION_ICON);
    expect(isKnownActionIcon("inesistente")).toBe(false);
    expect(actionIconLabel("gauge")).toBe("Contatore");
    expect(actionIconLabel("inesistente")).toBeNull();
  });

  it("la ricerca usa etichette, nomi e parole chiave senza accenti", () => {
    expect(searchActionIcons("negozio").map((c) => c.name)).toEqual(expect.arrayContaining(["store", "map-pin"]));
    expect(searchActionIcons("sostenibilita").map((c) => c.name)).toContain("leaf");
    expect(searchActionIcons("  ")).toBe(ACTION_ICONS);
    expect(searchActionIcons("zzzz")).toEqual([]);
  });
});
