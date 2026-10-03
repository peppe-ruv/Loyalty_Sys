import { defineConfig, devices } from '@playwright/test';
import { WEB_URL, operatorStatePath, serverSpkiPin } from './lib/env.js';
import { parseSeed } from './journeys/generator.js';
import { randomSeed } from './journeys/seed.js';

// Harness Playwright del cancello e2e-pr (ADR-052 livello 1, M9.1, F2-QA-01). Gira contro lo stack di
// `bash scripts/smoke-enterprise.sh up`: compose di riferimento in profilo enterprise, overlay di test del realm,
// proxy TLS di prova su https://web.lh.test:8443 (nomi in /etc/hosts verso 127.0.0.1).
//
// TLS: Chromium non legge NODE_EXTRA_CA_CERTS. Il browser accetta SOLO la chiave del certificato del proxy di prova
// (--ignore-certificate-errors-spki-list con l'impronta SPKI di <LH_CI_DIR>/tls/server.crt): ogni altro certificato
// resta rifiutato e non si disattiva la verifica in generale. Le chiamate HTTP dei test partono dal browser (bff.ts),
// quindi nessuna richiesta di Node deve fidarsi della CA di prova.
// Seme principale delle journey casuali (ADR-053, Q-693): da LH_JOURNEY_SEED oppure scelto qui, nel processo principale,
// e ereditato dai worker tramite l'ambiente. Deve essere uno solo per tutti i processi: i titoli dei test (che
// contengono i semi) devono coincidere tra il processo principale e i worker. Un valore non valido è un errore.
if (process.env.LH_JOURNEY_SEED !== undefined && process.env.LH_JOURNEY_SEED !== '' && parseSeed(process.env.LH_JOURNEY_SEED) === null) {
  throw new Error('LH_JOURNEY_SEED non valido: atteso un intero tra 1 e 4294967295');
}
if (!parseSeed(process.env.LH_JOURNEY_SEED)) process.env.LH_JOURNEY_SEED = String(randomSeed());

const pin = serverSpkiPin();
const statePath = operatorStatePath();

// Un solo worker: i test dopo il login condividono l'operatore, che ha un solo primo accesso (cambio password e
// configurazione dell'OTP) per stack. Nessun retry: un test rosso non si nasconde (ADR-052).
export default defineConfig({
  testDir: './tests',
  outputDir: './test-results',
  globalTeardown: './global-teardown.ts',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  forbidOnly: !!process.env.CI,
  // In CI una pila rotta farebbe attendere ogni journey fino al suo tetto: dopo 10 test falliti la corsa si ferma.
  maxFailures: process.env.CI ? 10 : 0,
  timeout: 90_000,
  expect: { timeout: 15_000 },
  // Il JSON serve al riepilogo del job (quante journey sono girate); sta in test-results/, caricato con le tracce.
  reporter: [['list'], ['html', { outputFolder: 'playwright-report', open: 'never' }], ['json', { outputFile: 'test-results/results.json' }]],
  use: {
    baseURL: WEB_URL,
    locale: 'it-IT',
    ignoreHTTPSErrors: false,
    launchOptions: pin ? { args: [`--ignore-certificate-errors-spki-list=${pin}`] } : {},
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },
  projects: [
    {
      // Login dell'operatore dal browser con la MFA vera. Traccia, video e screenshot spenti: una traccia registra il
      // corpo delle richieste (password) e la pagina di configurazione dell'OTP mostra il segreto (regola 20).
      name: 'setup',
      testMatch: /.*\.setup\.ts/,
      use: { ...devices['Desktop Chrome'], trace: 'off', video: 'off', screenshot: 'off' },
    },
    {
      name: 'desktop-chromium',
      testIgnore: /.*\.setup\.ts/,
      dependencies: ['setup'],
      use: { ...devices['Desktop Chrome'], storageState: statePath },
    },
  ],
});
