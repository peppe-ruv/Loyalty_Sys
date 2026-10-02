// HUB-01 — Demo Hub della demo pubblica (docs/07 §8, F-DEMO-01). Profilo `demo`, nessuna login (ADR-054, Q-710).
// Prima prova: «Accendi la demo» e attesa del risveglio dei servizi gratuiti, con tempi larghi e sondaggio.

// Ogni spec è un modulo: le costanti non si fondono con quelle degli altri file.
export {};

const WAKE_TIMEOUT = 6 * 60_000;

describe('Demo Hub', () => {
  it('mostra il nome del programma e il pannello dello stato dei servizi', () => {
    cy.visit('/');
    cy.contains('h1', 'Loyalty Hub').should('be.visible');
    cy.contains('h2', 'Stato dei servizi').should('be.visible');
    cy.contains('button', /Accendi la demo|Avvio in corso/).should('be.visible');
  });

  it('«Accendi la demo» sveglia i servizi fino a «pronti 10/10»', () => {
    cy.visit('/');
    cy.contains('button', 'Accendi la demo', { timeout: 30_000 }).should('be.enabled').click();
    // Il pulsante resta «Avvio in corso…» finché tutte le tessere non sono UP; il pannello lo scrive in chiaro.
    cy.contains(/pronti 10\/10/, { timeout: WAKE_TIMEOUT }).should('be.visible');
    cy.contains('button', 'Accendi la demo', { timeout: WAKE_TIMEOUT }).should('be.enabled');
  });

  it('a servizi accesi tutte e 10 le tessere (8 servizi, Kafka, Postgres) risultano attive', () => {
    cy.waitForCoreServices();
    cy.visit('/');
    cy.get('[data-testid^="tile-"]', { timeout: 60_000 }).should('have.length', 10);
    cy.get('[data-testid^="tile-"]').each(($tile) => {
      expect($tile.attr('data-state'), $tile.attr('data-testid')).to.eq('UP');
    });
    cy.get('[data-testid="tile-kafka"]').should('contain.text', 'Kafka');
    cy.get('[data-testid="tile-db"]').should('contain.text', 'Postgres');
  });

  it('la barra di avanzamento riporta tutti i servizi pronti', () => {
    cy.waitForCoreServices();
    cy.visit('/');
    cy.get('[role="progressbar"]', { timeout: 60_000 })
      .should('have.attr', 'aria-valuemax', '10')
      .and('have.attr', 'aria-valuenow', '10');
  });

  it('il backoffice offre le cinque persone con il loro ruolo e sono selezionabili', () => {
    cy.waitForCoreServices();
    cy.visit('/');
    cy.contains('h2', 'Da dove entrare').should('be.visible');
    cy.contains('h3', 'Backoffice')
      .closest('div.mb-1')
      .parent()
      .within(() => {
        for (const [name, role] of [
          ['Marta Villa', 'ADMIN'],
          ['Luca Serra', 'MARKETING'],
          ['Elena Riva', 'LEGAL'],
          ['Paolo Neri', 'CARE'],
          ['Sara Longo', 'ANALYST'],
        ] as const) {
          cy.contains('button', name, { timeout: 30_000 }).should('be.enabled').and('contain.text', role);
        }
      });
  });

  it('il portale elenca i membri della demo con livello e saldo, tra cui Anna, Marco e Giulia', () => {
    cy.waitForCoreServices();
    cy.visit('/');
    cy.contains('h3', 'Portale').should('be.visible');
    cy.contains('button', 'Anna Rossi', { timeout: 60_000 }).should('be.enabled');
    cy.contains('button', 'Marco Bianchi').should('be.enabled');
    cy.contains('button', 'Giulia Ferri').should('be.enabled');
  });

  it('entrare come Luca Serra porta al backoffice con la persona e il ruolo giusti', () => {
    cy.waitForCoreServices();
    cy.visit('/');
    cy.contains('button', 'Luca Serra', { timeout: 30_000 }).should('be.enabled').click();
    cy.location('pathname', { timeout: 30_000 }).should('eq', '/backoffice');
    cy.contains('header', 'Luca Serra').should('contain.text', 'MARKETING');
  });

  it('entrare come Marco Bianchi porta al portale con il suo saluto', () => {
    cy.waitForCoreServices();
    cy.visit('/');
    cy.contains('button', 'Marco Bianchi', { timeout: 60_000 }).should('be.enabled').click();
    cy.location('pathname', { timeout: 30_000 }).should('eq', '/portal');
    cy.contains('h1', /Ciao Marco/, { timeout: 60_000 }).should('be.visible');
  });

  it('il percorso consigliato porta alle schermate della demo', () => {
    cy.visit('/');
    cy.contains('h2', 'Percorso consigliato').should('be.visible');
    cy.contains('h2', 'Percorso consigliato').parent().find('ol > li a').should('have.length.greaterThan', 2);
  });
});
