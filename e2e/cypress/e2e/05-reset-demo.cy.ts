// Reset della demo a fine corsa (docs/08 BO-30, ADR-054 «Conseguenze», Q-710): le prove precedenti scrivono dati
// fittizi nella demo pubblica; «Ripristina tutto» dalla Console demo li riporta ai dati di `seed/`. Solo ADMIN.

// Ogni spec è un modulo: le costanti non si fondono con quelle degli altri file.
export {};

const RESETTABLE = ['ingestion', 'member', 'campaign', 'wallet', 'reward', 'gamification'];

before(() => {
  cy.waitForCoreServices();
});

/** Massimo di tentativi del ripristino dall'interfaccia: un servizio gratuito che dorme fa fallire il suo passo (✗). */
const MAX_ATTEMPTS = 3;

/**
 * «Ripristina tutto» dalla Console demo con la conferma `RESET`. Il log della pagina riporta un passo per servizio
 * (✓ ripristinato, ✗ errore): se un passo fallisce perché il servizio dormiva, si riaccende la demo e si riprova.
 */
function resetFromConsole(attempt: number): void {
  cy.visit('/backoffice/demo/console');
  cy.contains('h1', 'Console demo', { timeout: 60_000 }).should('be.visible');

  cy.contains('button', 'Ripristina tutto').should('be.enabled').click();
  cy.get('[role="alertdialog"][aria-label="Conferma reset"]').within(() => {
    // Senza la parola esatta il pulsante di conferma resta spento (azione irreversibile, docs/08 §3.5).
    cy.contains('button', 'Ripristina tutto').should('be.disabled');
    cy.get('input[aria-label="Digita RESET per confermare"]').type('RESET');
    cy.contains('button', 'Ripristina tutto').should('be.enabled').click();
  });

  // Intestazione del log + un passo per servizio: quando ci sono tutti, il ripristino è finito.
  cy.get('ul.font-mono li', { timeout: 5 * 60_000 }).should('have.length', RESETTABLE.length + 1);
  cy.get('ul.font-mono li').then(($items) => {
    const lines = [...$items].map((li) => li.textContent ?? '');
    const failed = lines.filter((l) => l.startsWith('✗'));
    if (failed.length === 0) {
      for (const service of RESETTABLE) expect(lines, 'log del ripristino').to.include(`✓ ${service}: ripristinato`);
      return;
    }
    if (attempt >= MAX_ATTEMPTS) {
      throw new Error(`Ripristino non riuscito dopo ${MAX_ATTEMPTS} tentativi: ${failed.join(' | ')}`);
    }
    cy.log(`Tentativo ${attempt} con passi falliti (${failed.length}): si riaccende la demo e si riprova`);
    cy.waitForCoreServices();
    resetFromConsole(attempt + 1);
  });
}

describe('Reset della demo', () => {
  it('BO-30 Console demo: «Ripristina tutto» chiede la parola RESET e ripristina i sei servizi', () => {
    cy.asOperator('marta.admin');
    resetFromConsole(1);
  });

  it('Dopo il reset i saldi di Anna, Marco e Giulia sono quelli di seed/ (100, 1.850, 3.240)', () => {
    cy.asOperator('marta.admin');
    cy.waitForPoints('MBR-000001', (b) => b === 100, 'Anna dopo il reset', 60_000);
    cy.waitForPoints('MBR-000002', (b) => b === 1850, 'Marco dopo il reset', 60_000);
    cy.waitForPoints('MBR-000003', (b) => b === 3240, 'Giulia dopo il reset', 60_000);
  });
});
