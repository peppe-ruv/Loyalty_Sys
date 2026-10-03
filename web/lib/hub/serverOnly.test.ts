// @vitest-environment node
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative } from "node:path";
import { describe, expect, it } from "vitest";

// HUB-01, HUB-02 (ADR-051, Q-674, Q-676): i moduli con credenziali pubbliche, seme OTP e token di GitHub restano SOLO
// LATO SERVER. Il pacchetto `server-only` non è tra le dipendenze (nessuna dipendenza nuova senza decisione): questo
// test fa lo stesso lavoro e fallisce se un file `"use client"` importa (non `import type`) uno di quei moduli.

const ROOT = join(__dirname, "..", "..");
const SERVER_ONLY = ["hub/testUsers", "hub/totp", "hub/codespace", "hub/testMode", "vetrina/programma", "vetrina/azione"];

function sources(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const p = join(dir, name);
    if (name === "node_modules" || name === ".next") return [];
    if (statSync(p).isDirectory()) return sources(p);
    return /\.tsx?$/.test(name) && !/\.test\.tsx?$/.test(name) ? [p] : [];
  });
}

// Direttiva su una riga propria (in testa al file, dopo eventuali commenti): niente regex con ripetizioni annidate.
const isClient = (src: string) => /^["']use client["'];?\s*$/m.test(src);

/** Specificatori di importo di valore (non `import type`) di un file. */
function valueImports(src: string): string[] {
  return [...src.matchAll(/^\s*(?:import|export)\s+(?!type\b)[^;]*?from\s+["']([^"']+)["']/gm)].map((m) => m[1]);
}

describe("moduli solo server", () => {
  const files = ["app", "components", "lib"].flatMap((d) => sources(join(ROOT, d)));

  it("l'analisi trova i file client e quelli che importano i moduli server", () => {
    const clients = files.filter((f) => isClient(readFileSync(f, "utf8")));
    expect(clients.length).toBeGreaterThan(10);
    const importers = files.filter((f) => valueImports(readFileSync(f, "utf8")).some((s) => SERVER_ONLY.some((m) => s.endsWith(m) || s === `./${m.split("/")[1]}`)));
    expect(importers.length).toBeGreaterThan(3);
  });

  it("nessun file con \"use client\" importa testUsers, totp, codespace, testMode o vetrina/programma", () => {
    const offenders = files.filter((f) => {
      const src = readFileSync(f, "utf8");
      return isClient(src) && valueImports(src).some((s) => SERVER_ONLY.some((m) => s.endsWith(m) || s === `./${m.split("/")[1]}`));
    });
    expect(offenders.map((f) => relative(ROOT, f))).toEqual([]);
  });
});
