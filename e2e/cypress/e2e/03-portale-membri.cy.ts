// Portale dei membri della demo pubblica (docs/09, Q-710): Anna, Marco e Giulia (MBR-000001…3, docs/10 §3).
// Identità simulata dal cookie `lh_persona`, nessuna login. Si parte dai dati di `seed/` (reset in `before`), così i
// saldi attesi sono quelli del seed e non quelli lasciati da una corsa precedente.

// Ogni spec è un modulo: le costanti non si fondono con quelle degli altri file.
export {};

interface Member {
  id: string;
  first: string;
  full: string;
  tier: string;
  points: string; // formato it-IT
  nextTier: string;
  missingSts: string;
}

const ANNA: Member = { id: 'MBR-000001', first: 'Anna', full: 'Anna Rossi', tier: 'Base', points: '100', nextTier: 'SILVER', missingSts: '1.000' };
const MARCO: Member = { id: 'MBR-000002', first: 'Marco', full: 'Marco Bianchi', tier: 'Silver', points: '1.850', nextTier: 'GOLD', missingSts: '1.580' };
const GIULIA: Member = { id: 'MBR-000003', first: 'Giulia', full: 'Giulia Ferri', tier: 'Silver', points: '3.240', nextTier: 'GOLD', missingSts: '120' };

before(() => {
  cy.waitForCoreServices();
  cy.resetDemoData();
  cy.waitForPoints('MBR-000001', (b) => b === 100, 'Dopo il reset Anna ha il saldo del seed');
  cy.waitForPoints('MBR-000002', (b) => b === 1850, 'Dopo il reset Marco ha il saldo del seed');
  cy.waitForPoints('MBR-000003', (b) => b === 3240, 'Dopo il reset Giulia ha il saldo del seed');
});

describe('Portale: home, attività e catalogo premi per membro', () => {
  for (const m of [ANNA, MARCO, GIULIA]) {
    it(`PT-01 Home di ${m.first}: saluto, tessera ${m.tier}, saldo ${m.points} punti e livello successivo`, () => {
      cy.visitPortalAs(m.id, '/portal');
      cy.contains('h1', `Ciao ${m.first}`, { timeout: 60_000 }).should('be.visible');
      cy.contains('Saldo punti', { timeout: 60_000 }).closest('.rounded-2xl').should('contain.text', m.points).and('contain.text', m.id);
      cy.contains('span', m.tier).should('be.visible');
      cy.contains(new RegExp(`Ti mancano\\s*${m.missingSts.replace('.', '\\.')}\\s*punti status per ${m.nextTier}`)).should('be.visible');
      cy.contains('a', 'Premi').should('be.visible');
    });

    it(`PT-03 Catalogo premi di ${m.first}: fasce in ordine di soglia e stato rispetto al saldo`, () => {
      cy.asMember(m.id);
      cy.visit('/portal/rewards');
      cy.contains('h1', 'Premi').should('be.visible');
      cy.contains(`Hai ${m.points} punti`, { timeout: 60_000 }).should('be.visible');
      cy.contains('h2', 'Fascia 500 punti', { timeout: 60_000 }).should('be.visible');
      cy.contains('h2', 'Fascia 12.000 punti').should('be.visible');
      cy.contains('Pianta un albero').should('be.visible');
    });
  }

  it('PT-03 Catalogo: Anna con 100 punti non ha raggiunto la prima fascia, Giulia le prime tre', () => {
    cy.asMember(ANNA.id);
    cy.visit('/portal/rewards');
    cy.contains('h2', 'Fascia 500 punti', { timeout: 60_000 }).parent().should('contain.text', 'ti mancano 400 punti');
    cy.asMember(GIULIA.id);
    cy.visit('/portal/rewards');
    cy.contains('h2', 'Fascia 3.000 punti', { timeout: 60_000 }).parent().should('contain.text', 'raggiunta');
    cy.contains('h2', 'Fascia 6.000 punti').parent().should('contain.text', 'ti mancano');
  });

  it('PT-07 Attività di Giulia: i movimenti storici della demo, divisi per giorno', () => {
    cy.asMember(GIULIA.id);
    cy.visit('/portal/activity');
    cy.contains('h1', 'La mia attività').should('be.visible');
    cy.contains('Premio richiesto', { timeout: 60_000 }).should('be.visible');
    cy.contains('-500').should('be.visible');
    cy.contains('button', 'Punti status').click();
    cy.contains('Premio richiesto').should('not.exist');
  });

  it('PT-07 Attività di Anna e Marco: ancora nessun movimento a demo appena accesa', () => {
    for (const m of [ANNA, MARCO]) {
      cy.asMember(m.id);
      cy.visit('/portal/activity');
      cy.contains('Nessun movimento ancora', { timeout: 60_000 }).should('be.visible');
    }
  });

  it('PT-14 Pannello demo: dal tray si passa da Anna a Marco e il portale lo saluta', () => {
    cy.visitPortalAs(ANNA.id, '/portal');
    cy.contains('h1', 'Ciao Anna', { timeout: 60_000 }).should('be.visible');
    cy.contains('button', 'Demo').click();
    cy.contains('Pannello demo').should('be.visible');
    cy.contains('button', `${MARCO.full} · SILVER`, { timeout: 60_000 }).click();
    cy.contains('h1', 'Ciao Marco', { timeout: 60_000 }).should('be.visible');
  });
});
