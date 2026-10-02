// Backoffice della demo pubblica, per ogni ruolo simulato (docs/08 §1-§2, Q-710). L'identità è il cookie `lh_persona`
// che il proxy traduce in X-LH-Actor: nessuna login.
//
// Nota sul menu: la matrice permessi dice che TUTTE le persone LEGGONO tutto (docs/08 §2), quindi la barra laterale è
// uguale per ogni ruolo. Quello che cambia con il ruolo sono le azioni: la UI le disabilita con `<Can>`.
import type { BoUsername } from '../support/commands';

interface Persona {
  username: BoUsername;
  name: string;
  role: 'ADMIN' | 'MARKETING' | 'LEGAL' | 'CARE' | 'ANALYST';
  /** Capacità (docs/08 §2) abilitate: object.edit, demo.simulate, demo.admin, member.write, member.anonymize, points.adjust. */
  can: { edit: boolean; simulate: boolean; admin: boolean; memberWrite: boolean; anonymize: boolean; adjust: boolean };
}

const PERSONAS: Persona[] = [
  { username: 'marta.admin', name: 'Marta Villa', role: 'ADMIN', can: { edit: true, simulate: true, admin: true, memberWrite: true, anonymize: true, adjust: true } },
  { username: 'luca.marketing', name: 'Luca Serra', role: 'MARKETING', can: { edit: true, simulate: true, admin: false, memberWrite: false, anonymize: false, adjust: false } },
  { username: 'elena.legal', name: 'Elena Riva', role: 'LEGAL', can: { edit: false, simulate: true, admin: false, memberWrite: false, anonymize: false, adjust: false } },
  { username: 'paolo.care', name: 'Paolo Neri', role: 'CARE', can: { edit: false, simulate: true, admin: false, memberWrite: true, anonymize: false, adjust: true } },
  { username: 'sara.analyst', name: 'Sara Longo', role: 'ANALYST', can: { edit: false, simulate: false, admin: false, memberWrite: false, anonymize: false, adjust: false } },
];

const NAV_LINKS = ['Dashboard', 'Membri', 'Campagne', 'Catalogo', 'Richieste premio', 'Audit', 'Scenari', 'Console demo'];

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function expectEnabled(subject: Cypress.Chainable<JQuery<any>>, enabled: boolean): void {
  subject.should(enabled ? 'be.enabled' : 'be.disabled');
}

before(() => {
  cy.waitForCoreServices();
});

describe('Backoffice: guscio e menu per ruolo', () => {
  for (const p of PERSONAS) {
    it(`${p.role}: ${p.name} entra nella dashboard e vede il menu completo`, () => {
      cy.asOperator(p.username);
      cy.visit('/backoffice');
      cy.contains('header', p.name).should('contain.text', p.role);
      cy.contains('h1', 'Dashboard', { timeout: 30_000 }).should('be.visible');
      cy.get('aside nav').within(() => {
        for (const label of NAV_LINKS) {
          cy.contains('a', label).should('be.visible');
        }
        cy.contains('a', 'Dashboard').should('have.attr', 'href', '/backoffice');
      });
    });
  }
});

describe('Backoffice: azioni abilitate o disabilitate per ruolo (docs/08 §2)', () => {
  for (const p of PERSONAS) {
    it(`${p.role}: premi, scenari e console demo rispettano la matrice permessi`, () => {
      cy.asOperator(p.username);

      cy.visit('/backoffice/rewards');
      cy.contains('h1', 'Catalogo premi', { timeout: 30_000 }).should('be.visible');
      expectEnabled(cy.contains('button', 'Nuovo premio'), p.can.edit);

      cy.visit('/backoffice/demo/scenarios');
      cy.contains('p', 'Il weekend di Anna', { timeout: 30_000 }).should('be.visible');
      expectEnabled(cy.contains('button', 'Esegui'), p.can.simulate);

      cy.visit('/backoffice/demo/console');
      cy.contains('h1', 'Console demo', { timeout: 30_000 }).should('be.visible');
      expectEnabled(cy.contains('button', 'Ripristina tutto'), p.can.admin);
    });

    it(`${p.role}: la scheda di Anna Rossi abilita rettifica punti e anonimizzazione solo ai ruoli giusti`, () => {
      cy.asOperator(p.username);
      cy.visit('/backoffice/members/MBR-000001');
      cy.contains('Anna', { timeout: 60_000 }).should('be.visible');
      expectEnabled(cy.contains('button', 'Rettifica punti'), p.can.adjust);
      cy.get('button[aria-label="Altre azioni"]').click();
      cy.get('[role="menu"]').within(() => {
        expectEnabled(cy.contains('button', 'Anonimizza'), p.can.anonymize);
      });
    });
  }
});

describe('Backoffice: schermate principali (ADMIN)', () => {
  beforeEach(() => {
    cy.asOperator('marta.admin');
  });

  it('BO-01 Dashboard: KPI, grafico dei punti, azioni per fonte e passività', () => {
    cy.visit('/backoffice');
    cy.contains('h1', 'Dashboard').should('be.visible');
    for (const kpi of ['Membri attivi', 'Azioni ricevute', 'PTS emessi', 'PTS spesi']) {
      cy.contains(kpi, { timeout: 60_000 }).should('be.visible');
    }
    cy.contains('h2', 'Punti per giorno').should('be.visible');
    cy.contains('h2', 'Azioni per fonte').should('be.visible');
    cy.contains('h2', 'Passività per mese di scadenza').should('be.visible');
    cy.contains('a', 'Richieste da evadere').should('have.attr', 'href', '/backoffice/rewards/redemptions');
  });

  it('BO-02 Membri: elenco con i 12 membri della demo, tra cui Anna, Marco e Giulia', () => {
    cy.visit('/backoffice/members');
    cy.contains('h1', 'Membri').should('be.visible');
    cy.contains('12 membri', { timeout: 60_000 }).should('be.visible');
    cy.contains('td', 'MBR-000001').should('be.visible');
    cy.contains('td', 'MBR-000002').should('be.visible');
    cy.contains('td', 'MBR-000003').should('be.visible');
    cy.contains('th', 'Saldo PTS').should('be.visible');
  });

  it('BO-02 Membri: la ricerca per nome restringe l’elenco a Marco', () => {
    cy.visit('/backoffice/members');
    cy.contains('td', 'MBR-000001', { timeout: 60_000 }).should('be.visible');
    cy.get('input[placeholder="Cerca per nome, e-mail, ID…"]').type('Marco');
    cy.contains('td', 'MBR-000002').should('be.visible');
    cy.contains('td', 'MBR-000001').should('not.exist');
  });

  it('BO-03 Scheda membro: dal clic su Anna si apre la sua scheda con saldo e livello', () => {
    cy.visit('/backoffice/members');
    cy.contains('td', 'MBR-000001', { timeout: 60_000 }).click();
    cy.location('pathname').should('eq', '/backoffice/members/MBR-000001');
    cy.contains('Anna', { timeout: 60_000 }).should('be.visible');
    cy.contains('MBR-000001').should('be.visible');
    cy.contains('button', 'Rettifica punti').should('be.visible');
  });

  it('BO-05 Campagne: elenco del motore regole con le campagne di benvenuto e accesso giornaliero', () => {
    cy.visit('/backoffice/campaigns');
    cy.contains('h1', 'Campagne').should('be.visible');
    cy.contains('button', 'Nuova campagna').should('be.visible');
    cy.contains('td', 'Bonus di benvenuto', { timeout: 60_000 }).should('be.visible');
    cy.contains('td', 'CMP-APP-DAILY').should('be.visible');
    cy.contains('th', 'Punti erogati').should('be.visible');
  });

  it('BO-10 Catalogo premi: i premi della demo, con il pulsante per crearne uno nuovo', () => {
    cy.visit('/backoffice/rewards');
    cy.contains('h1', 'Catalogo premi').should('be.visible');
    cy.contains('button', 'Nuovo premio').should('be.enabled');
    cy.contains('Buono colazione 5 €', { timeout: 60_000 }).should('be.visible');
    cy.contains('Pianta un albero').should('be.visible');
    cy.get('[role="group"][aria-label="Vista"]').should('be.visible');
  });

  it('BO-22 Audit: filtri per attore, servizio e azione e area di dettaglio', () => {
    cy.visit('/backoffice/governance/audit');
    cy.contains('h1', 'Audit').should('be.visible');
    cy.contains('Ogni scrittura da backoffice e job').should('be.visible');
    cy.get('input[placeholder="nome o ruolo…"]').should('be.visible');
    cy.contains('label, span, div', 'Servizio').should('exist');
    cy.contains('Seleziona una voce per vederne il dettaglio e il diff.', { timeout: 60_000 }).should('be.visible');
  });

  it('BO-07 Livelli: la scala dei livelli del programma', () => {
    cy.visit('/backoffice/program/tiers');
    cy.contains('h1', 'Livelli').should('be.visible');
    for (const tier of ['Base', 'Silver', 'Gold', 'Platinum']) {
      cy.contains(tier, { timeout: 60_000 }).should('be.visible');
    }
  });
});
