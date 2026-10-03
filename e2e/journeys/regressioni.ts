import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { validateJourney, type Journey } from './generator.ts';

// Cartella delle journey di regressione (ADR-053 decisione 4, Q-693): un file JSON per seme che ha fallito, con seme e
// passi, rigiocato da `tests/journey-regressioni.spec.ts`. Formato in `regressioni/README.md`.

export const REGRESSIONS_DIR = path.join(path.dirname(fileURLToPath(import.meta.url)), 'regressioni');

export interface RegressionFile {
  journey: Journey;
  file: string;
}

/** Legge e valida ogni `*.json` della cartella, in ordine alfabetico. Un file malformato è un errore, non si ignora. */
export function loadRegressions(dir: string = REGRESSIONS_DIR): RegressionFile[] {
  if (!fs.existsSync(dir)) return [];
  return fs
    .readdirSync(dir)
    .filter((f) => f.endsWith('.json'))
    .sort()
    .map((f) => {
      let raw: unknown;
      try {
        raw = JSON.parse(fs.readFileSync(path.join(dir, f), 'utf8'));
      } catch {
        throw new Error(`regressione ${f}: JSON non valido`);
      }
      try {
        return { journey: validateJourney(raw), file: f };
      } catch (e) {
        throw new Error(`regressione ${f}: ${e instanceof Error ? e.message : 'non valida'}`);
      }
    });
}

/**
 * Salva una journey fallita come file di regressione (`<seme>.json`). Contiene solo seme, passi e un breve motivo
 * scritto dall'harness (mai corpi di risposta, credenziali o dati personali). Se il file esiste già non lo sovrascrive.
 */
export function saveRegression(journey: Journey, failure: string, dir: string = REGRESSIONS_DIR): string {
  fs.mkdirSync(dir, { recursive: true });
  const file = path.join(dir, `${journey.seed}.json`);
  if (fs.existsSync(file)) return file;
  const body = {
    seed: journey.seed,
    failure: failure.replace(/\s+/g, ' ').slice(0, 300),
    steps: journey.steps,
  };
  fs.writeFileSync(file, `${JSON.stringify(body, null, 2)}\n`, { flag: 'wx' });
  return file;
}
