// File di supporto di Cypress (specPattern `cypress/e2e/**/*.cy.ts`, ADR-054, Q-713). Gira prima di ogni spec.
import './commands';

// Un errore dell'applicazione (React, rete) non deve spegnere il test per un'eccezione che la pagina gestisce da sé:
// la demo gratuita risponde a volte con servizi che dormono. Gli esiti si verificano con asserzioni, non con le
// eccezioni non catturate del browser. Si ignorano solo quelle di idratazione/rete del sito, mai quelle dei test.
Cypress.on('uncaught:exception', (err) => {
  return !/Hydration|NetworkError|Failed to fetch|Load failed|ResizeObserver/i.test(err.message);
});
