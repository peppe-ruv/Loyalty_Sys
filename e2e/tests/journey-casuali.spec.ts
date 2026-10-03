import { expect, test } from '@playwright/test';
import { generateJourney, deriveSeed, parsePositiveInt, parseSeed } from '../journeys/generator.js';
import { runJourney } from '../journeys/runner.js';
import { saveRegression } from '../journeys/regressioni.js';

// Specifiche: ADR-053 decisione 4, Q-693 (20 journey a ogni PR, 500 nella notte), Q-694 (se non stanno nel tempo si
// riducono i passi, mai si salta il controllo), Q-718 (tetto di tempo della corsa notturna), F2-QA-01, F2-QA-07.
//
// Sequenze casuali di azioni fatte con le API vere dal BFF (registrare membri, rettificare saldi, creare categorie e premi,
// cambiare stock), con le invarianti di M9 verificate dopo ogni ciclo. Il seme principale viene da `LH_JOURNEY_SEED`
// (o è scelto da playwright.config.ts, una sola volta per tutti i processi) e ogni journey ha un seme derivato: seme e
// passi sono sempre stampati. Un seme che fallisce si salva in `journeys/regressioni/<seme>.json` (seme e passi): per
// rigiocarlo basta quel file, che `tests/journey-regressioni.spec.ts` esegue com'è; committato diventa una journey fissa
// (journeys/regressioni/README.md). Rilanciare con lo stesso `LH_JOURNEY_SEED` ripete l'intera corsa.
//
// Variabili: LH_JOURNEY_SEED (seme principale), LH_JOURNEY_COUNT (predefinito 20), LH_JOURNEY_STEPS (azioni per journey,
// predefinito 8), LH_JOURNEY_BUDGET_MIN (tetto di minuti della sequenza, solo notte: le journey che non partono entro il
// tetto risultano saltate e annotate, Q-718).

const master = parseSeed(process.env.LH_JOURNEY_SEED);
if (master === null) throw new Error('LH_JOURNEY_SEED non valido o assente (lo imposta playwright.config.ts): attesi 1…4294967295');
const count = parsePositiveInt(process.env.LH_JOURNEY_COUNT, 20, 5000, 'LH_JOURNEY_COUNT');
const steps = parsePositiveInt(process.env.LH_JOURNEY_STEPS, 8, 200, 'LH_JOURNEY_STEPS');
const budgetMin = process.env.LH_JOURNEY_BUDGET_MIN ? parsePositiveInt(process.env.LH_JOURNEY_BUDGET_MIN, 1, 600, 'LH_JOURNEY_BUDGET_MIN') : null;
const firstStart = Date.now();

test.describe(`journey casuali (seme principale ${master}, ${count} journey da ${steps} azioni)`, () => {
  for (let i = 0; i < count; i++) {
    const seed = deriveSeed(master, i);
    test(`journey ${i + 1}/${count}, seme ${seed}`, async ({ page }, testInfo) => {
      test.setTimeout(300_000);
      if (budgetMin !== null && Date.now() - firstStart > budgetMin * 60_000) {
        testInfo.annotations.push({ type: 'tetto', description: `oltre ${budgetMin} minuti (Q-718)` });
        test.skip(true, `tetto di tempo di ${budgetMin} minuti raggiunto (Q-718)`);
      }
      const journey = generateJourney(seed, { steps });
      const log = (line: string): void => console.log(line);
      log(`[journey] seme principale=${master} indice=${i + 1}/${count} seme=${seed}`);
      await testInfo.attach('journey.json', { body: JSON.stringify({ seed, steps: journey.steps }, null, 2), contentType: 'application/json' });

      // La pagina deve stare sull'origine del web: le chiamate partono dal suo contesto (cookie e CSRF, bff.ts).
      await page.goto('/backoffice/governance/audit');
      await expect(page.getByRole('heading', { level: 1, name: 'Audit' })).toBeVisible();

      const startedAt = new Date();
      const t0 = Date.now();
      try {
        await runJourney(page, journey, { startedAt, log });
      } catch (e) {
        const message = e instanceof Error ? e.message : 'errore';
        const file = saveRegression(journey, message);
        console.log(`[journey] FALLITA seme=${seed}: ${message}`);
        console.log(`[journey] salvata come regressione: ${file.split('/').slice(-3).join('/')}`);
        await testInfo.attach('seme-fallito.txt', { body: `seme=${seed}\nseme principale=${master}\n${message}\n`, contentType: 'text/plain' });
        throw e;
      } finally {
        console.log(`[journey] seme=${seed} durata=${Math.round((Date.now() - t0) / 1000)} s`);
      }
    });
  }
});
