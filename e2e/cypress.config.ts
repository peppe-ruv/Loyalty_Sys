/// <reference types="node" />
import { defineConfig } from 'cypress';

// Cypress sulla demo pubblica in profilo `demo` (ADR-054, Q-710…Q-713, M9.8a, F2-QA-08). Accanto a Playwright, che
// verifica il profilo `enterprise` nelle PR (ADR-052): qui nessun login, l'identità è simulata (X-LH-Actor, memberId).
// Gira ogni cinque giorni e a richiesta (.github/workflows/cypress-demo.yml), mai nelle PR.
//
// La record key di Cypress Cloud NON sta qui (regola 20, Q-712): arriva solo dalla variabile d'ambiente
// CYPRESS_RECORD_KEY, impostata dal workflow dal segreto del repository, e solo se il segreto esiste.
export default defineConfig({
  projectId: 'v1g3bz',
  e2e: {
    baseUrl: process.env.LH_DEMO_URL || 'https://loyalty-hub-web.vercel.app',
    specPattern: 'cypress/e2e/**/*.cy.ts',
    supportFile: 'cypress/support/e2e.ts',
    // Una corsa è registrata sul piano gratuito: nessun retry automatico (Q-711), un test rosso non si nasconde.
    retries: 0,
    video: false,
    screenshotOnRunFailure: true,
    screenshotsFolder: 'cypress/screenshots',
    videosFolder: 'cypress/videos',
    downloadsFolder: 'cypress/downloads',
    viewportWidth: 1440,
    viewportHeight: 900,
    // I servizi gratuiti si addormentano: tempi larghi, mai attese fisse.
    defaultCommandTimeout: 15_000,
    pageLoadTimeout: 90_000,
    requestTimeout: 60_000,
    responseTimeout: 60_000,
    testIsolation: true,
  },
});
