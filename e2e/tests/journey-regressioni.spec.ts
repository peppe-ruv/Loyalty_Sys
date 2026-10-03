import { expect, test } from '@playwright/test';
import { runJourney } from '../journeys/runner.js';
import { loadRegressions } from '../journeys/regressioni.js';

// Specifiche: ADR-053 decisione 4, Q-693 (un seme che fallisce diventa una journey fissa), F2-QA-01, F2-QA-07.
//
// Rigioca, com'è, ogni file di `journeys/regressioni/*.json` (seme e passi salvati al momento del guasto). Cartella senza
// file JSON = nessun test: la prova passa. I passi non si rigenerano dal seme, quindi la journey resta la stessa anche se
// il generatore cambia. Un file malformato fa fallire il caricamento: non si ignora.

const regressions = loadRegressions();

test.describe(`journey di regressione (${regressions.length})`, () => {
  for (const { journey, file } of regressions) {
    test(`regressione ${file}, seme ${journey.seed}`, async ({ page }) => {
      test.setTimeout(300_000);
      console.log(`[regressione] ${file} seme=${journey.seed} passi=${journey.steps.length}`);
      await page.goto('/backoffice/governance/audit');
      await expect(page.getByRole('heading', { level: 1, name: 'Audit' })).toBeVisible();
      await runJourney(page, journey, { startedAt: new Date(), log: (line) => console.log(line) });
    });
  }
});
