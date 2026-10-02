// Scenari del menu Demo e riscatto di un premio sulla demo pubblica (docs/08 BO-29, docs/09 PT-03/PT-14, docs/10 §8,
// Q-710). Prova che la pipeline eventi è viva: azione → punti sul saldo entro 15 s (SCN-SMOKE), richiesta di un premio →
// saldo scalato. Dati fittizi; la corsa si chiude col reset (05-reset-demo.cy.ts). Si parte dai dati di `seed/`.

// Ogni spec è un modulo: le costanti non si fondono con quelle degli altri file.
export {};

const MARCO = 'MBR-000002';
const ANNA = 'MBR-000001';
/** Tetto dell'effetto di una azione sul saldo (docs/12 §4, `scripts/smoke.sh`: ≤ 15 s a servizi svegli). */
const EFFECT_WITHIN_MS = 15_000;

before(() => {
  cy.waitForCoreServices();
  cy.resetDemoData();
  cy.waitForPoints(MARCO, (b) => b === 1850, 'Dopo il reset Marco ha il saldo del seed');
  cy.waitForPoints(ANNA, (b) => b === 100, 'Dopo il reset Anna ha il saldo del seed');
  cy.waitForScenario('SCN-SMOKE');
});

describe('Scenari e riscatto', () => {
  it('BO-29 SCN-SMOKE «Prova di vita»: l’accesso giornaliero di Marco fa crescere il saldo di 5 punti entro 15 s', () => {
    cy.asOperator('marta.admin');
    cy.pointsBalance(MARCO).then((before) => {
      expect(before, 'saldo di Marco prima dello scenario').to.eq(1850);
      cy.visit('/backoffice/demo/scenarios');
      cy.contains('div.rounded-lg', 'SCN-SMOKE', { timeout: 60_000 }).within(() => {
        cy.contains('p', 'Prova di vita').should('be.visible');
        cy.contains('button', 'Esegui').should('be.enabled').click();
      });
      // Il pannello di esecuzione passa da «in attesa» a «elaborato» e conta i passi.
      cy.contains('h2', 'Prova di vita', { timeout: 60_000 }).should('be.visible');
      cy.contains('ACCEPTED', { timeout: 60_000 }).should('be.visible');
      cy.contains('Esecuzione completata', { timeout: 60_000 }).should('be.visible');
      cy.waitForPoints(MARCO, (b) => b >= before + 5, 'SCN-SMOKE: +5 punti', EFFECT_WITHIN_MS).should('eq', before + 5);
    });
  });

  it('PT-07 Dopo SCN-SMOKE l’attività di Marco nel portale mostra il movimento arrivato', () => {
    cy.visitPortalAs(MARCO, '/portal/activity');
    cy.contains('h1', 'La mia attività').should('be.visible');
    cy.contains('+5 punti', { timeout: 60_000 }).should('be.visible');
    cy.contains('Nessun movimento ancora').should('not.exist');
  });

  it('BO-28 Simulatore eventi: «Accesso quotidiano» per Anna è ACCEPTED e le fa guadagnare punti entro 15 s', () => {
    cy.asOperator('marta.admin');
    cy.visit('/backoffice/demo/simulator');
    cy.contains('h1', 'Simulatore eventi', { timeout: 60_000 }).should('be.visible');
    cy.contains('label', 'Membro').find('input').clear().type(ANNA);
    cy.contains('button', 'Accesso quotidiano').click();
    cy.contains('button', 'Invia').should('be.enabled').click();
    cy.contains('h3', 'Esiti').parent().contains('ACCEPTED', { timeout: 60_000 }).should('be.visible');
    cy.waitForPoints(ANNA, (b) => b > 100, 'L’accesso quotidiano di Anna fa crescere il saldo', EFFECT_WITHIN_MS);
  });

  it('PT-03 Anna con meno di 500 punti non può richiedere «Pianta un albero»: mancano punti e non c’è il pulsante', () => {
    cy.visitPortalAs(ANNA, '/portal/rewards/RWD-DONATION-TREE');
    cy.contains('h1', 'Pianta un albero', { timeout: 60_000 }).should('be.visible');
    cy.contains(/Ti mancano \d+(\.\d{3})* punti/, { timeout: 30_000 }).should('be.visible');
    cy.contains('button', /Richiedi per/).should('not.exist');
  });

  it('PT-03 Riscatto: Marco richiede «Pianta un albero» e il saldo scende di 500 punti', () => {
    cy.asOperator('marta.admin');
    cy.pointsBalance(MARCO).then((before) => {
      cy.visitPortalAs(MARCO, '/portal/rewards/RWD-DONATION-TREE');
      cy.contains('h1', 'Pianta un albero', { timeout: 60_000 }).should('be.visible');
      cy.contains('button', 'Richiedi per 500 punti').should('be.enabled').click();
      cy.get('[role="dialog"]').within(() => {
        cy.contains('button', 'Conferma · 500 punti').should('be.enabled').click();
      });
      cy.contains('Fatto! Pianta un albero è tuo', { timeout: 60_000 }).should('be.visible');
      cy.asOperator('marta.admin');
      cy.waitForPoints(MARCO, (b) => b === before - 500, 'Il riscatto scala 500 punti dal saldo di Marco', 30_000);
    });
  });
});
