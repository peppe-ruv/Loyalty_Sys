#!/usr/bin/env node
// Riepilogo Markdown delle journey casuali per il riepilogo del job (`$GITHUB_STEP_SUMMARY`), usato da `e2e-pr` (ci.yml)
// e da `e2e-nightly.yml` (ADR-053 decisione 4, Q-693, Q-718). Legge il JSON del reporter di Playwright e la cartella
// delle regressioni; stampa su stdout seme principale, numero di journey, esito e i semi falliti.
//
// Uso: node journeys/riepilogo.mjs [results.json] [cartella-regressioni] >> "$GITHUB_STEP_SUMMARY"
// Ambiente (solo lettura): LH_JOURNEY_SEED, LH_JOURNEY_COUNT, LH_JOURNEY_STEPS, LH_JOURNEY_BUDGET_MIN. Nessun segreto: i
// titoli dei test contengono solo i semi.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const resultsFile = process.argv[2] ?? path.join(here, '..', 'test-results', 'results.json');
const regressionsDir = process.argv[3] ?? path.join(here, 'regressioni');

const safe = (v) => (/^[0-9]{1,10}$/.test(String(v ?? '')) ? String(v) : 'n/d');

/** Percorre le suite del JSON di Playwright e raccoglie i test dei file indicati. */
export function collect(report, fileMatcher) {
  const out = [];
  const walk = (suite) => {
    for (const spec of suite.specs ?? []) {
      if (!fileMatcher.test(spec.file ?? suite.file ?? '')) continue;
      for (const t of spec.tests ?? []) out.push({ title: spec.title, status: t.status });
    }
    for (const s of suite.suites ?? []) walk(s);
  };
  for (const s of report.suites ?? []) walk(s);
  return out;
}

export function summarize(report, env, regressionFiles) {
  const tests = collect(report, /journey-casuali\.spec\.ts$/);
  const count = (st) => tests.filter((t) => t.status === st).length;
  const failed = tests.filter((t) => t.status === 'unexpected');
  const seedOf = (title) => /seme ([0-9]{1,10})$/.exec(title)?.[1] ?? null;
  const lines = [];
  lines.push('## Journey casuali (ADR-053, Q-693)', '');
  lines.push('| Voce | Valore |', '| --- | --- |');
  lines.push(`| Seme principale | \`${safe(env.LH_JOURNEY_SEED)}\` |`);
  lines.push(`| Journey richieste | ${safe(env.LH_JOURNEY_COUNT)} |`);
  lines.push(`| Azioni per journey | ${safe(env.LH_JOURNEY_STEPS)} |`);
  if (env.LH_JOURNEY_BUDGET_MIN) lines.push(`| Tetto di tempo | ${safe(env.LH_JOURNEY_BUDGET_MIN)} minuti (Q-718) |`);
  lines.push(`| Eseguite | ${tests.length - count('skipped')} |`);
  lines.push(`| Riuscite | ${count('expected')} |`);
  lines.push(`| Fallite | ${failed.length} |`);
  lines.push(`| Non eseguite (tetto di tempo o corsa interrotta) | ${count('skipped')} |`);
  if (failed.length > 0) {
    lines.push('', '**Semi falliti** (rigiocarli: `journeys/regressioni/<seme>.json` nell\'artefatto del job):', '');
    for (const t of failed) lines.push(`- \`${seedOf(t.title) ?? 'n/d'}\``);
  }
  if (regressionFiles.length > 0) {
    lines.push('', `File in \`journeys/regressioni/\`: ${regressionFiles.length}.`);
  }
  lines.push('', 'Per ripetere questa corsa: `LH_JOURNEY_SEED=' + safe(env.LH_JOURNEY_SEED) + '` (stesso numero di journey e di azioni).');
  return lines.join('\n') + '\n';
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  let report = { suites: [] };
  try {
    report = JSON.parse(fs.readFileSync(resultsFile, 'utf8'));
  } catch {
    console.log('## Journey casuali (ADR-053, Q-693)\n\n> Il JSON dei risultati non c\'è: la corsa si è interrotta prima del termine.\n');
    console.log(`Seme principale: \`${safe(process.env.LH_JOURNEY_SEED)}\`.`);
    process.exit(0);
  }
  const files = fs.existsSync(regressionsDir) ? fs.readdirSync(regressionsDir).filter((f) => f.endsWith('.json')) : [];
  process.stdout.write(summarize(report, process.env, files));
}
