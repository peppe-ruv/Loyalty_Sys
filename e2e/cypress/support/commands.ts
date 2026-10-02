// Comandi condivisi dei test Cypress sulla demo pubblica (ADR-054, M9.8a).
// Nel profilo `demo` l'identità è simulata dal cookie `lh_persona`, che il proxy traduce in X-LH-Actor (operatori) o
// X-LH-Member (portale): docs/07 §4. Nessuna login, nessuna credenziale.

export type BoUsername = 'marta.admin' | 'luca.marketing' | 'elena.legal' | 'paolo.care' | 'sara.analyst';

/** Servizi con un ripristino ai dati di `seed/` (stesso elenco della Console demo, BO-30). */
const RESETTABLE = ['ingestion', 'member', 'campaign', 'wallet', 'reward', 'gamification'] as const;

interface DemoStatus {
  services: { code: string; state: string }[];
  readyCount: number;
  totalCount: number;
}

declare global {
  // eslint-disable-next-line @typescript-eslint/no-namespace
  namespace Cypress {
    interface Chainable {
      /** Imposta la persona del backoffice (cookie lh_persona) senza passare dall'interfaccia. */
      asOperator(username: BoUsername): Chainable<void>;
      /** Imposta il membro del portale (cookie lh_persona) senza passare dall'interfaccia. */
      asMember(memberId: string): Chainable<void>;
      /**
       * Imposta il membro e apre una pagina del portale senza il pop-up d'ingresso (al più uno per visita, PopupHost):
       * segnare la visita già fatta evita che un pop-up del momento copra i controlli sotto test.
       */
      visitPortalAs(memberId: string, path: string): Chainable<Cypress.AUTWindow>;
      /** Interroga /api/demo/status finché i servizi del nucleo (ingestion, member, campaign, wallet) sono UP. */
      waitForCoreServices(): Chainable<void>;
      /**
       * Sondaggio dell'elenco scenari di ingestion finché contiene `code` (dopo un reset il servizio li ricarica da
       * `seed/` e per qualche istante può rispondere lento o vuoto).
       */
      waitForScenario(code: string, timeoutMs?: number): Chainable<void>;
      /** Saldo PTS attivo del membro, letto da wallet con l'identità di operatore già impostata (asOperator). */
      pointsBalance(memberId: string): Chainable<number>;
      /**
       * Sondaggio del saldo PTS di un membro finché `accept` è vero o scade il tempo (nessuna attesa fissa nel test).
       * Rende il saldo che ha soddisfatto la condizione; fallisce con `what` se il tempo finisce.
       */
      waitForPoints(memberId: string, accept: (balance: number) => boolean, what: string, timeoutMs?: number): Chainable<number>;
      /** Riporta i servizi ai dati di `seed/` con le stesse chiamate della Console demo (serve il ruolo ADMIN). */
      resetDemoData(): Chainable<void>;
    }
  }
}

const CORE = ['ingestion', 'member', 'campaign', 'wallet'];
// Un servizio gratuito si risveglia in 1-3 minuti (docs/07 §8): sondaggio ogni 5 s, tetto 6 minuti.
const WAKE_POLL_MS = 5_000;
const WAKE_MAX_MS = 6 * 60_000;

Cypress.Commands.add('asOperator', (username: BoUsername) => {
  cy.request({ method: 'POST', url: '/api/persona', body: { kind: 'BO', username } })
    .its('status')
    .should('eq', 200);
});

Cypress.Commands.add('asMember', (memberId: string) => {
  cy.request({ method: 'POST', url: '/api/persona', body: { kind: 'MEMBER', memberId } })
    .its('status')
    .should('eq', 200);
});

Cypress.Commands.add('visitPortalAs', (memberId: string, path: string) => {
  cy.asMember(memberId);
  return cy.visit(path, {
    onBeforeLoad(win) {
      win.sessionStorage.setItem(`lh_popup_visit_${memberId}`, '1');
    },
  });
});

/**
 * Letture innocue, una per servizio ripristinabile (le stesse che usano le schermate): rispondono 502/503/504 finché il
 * servizio gratuito dorme, anche quando lo stato di /api/demo/status risulta già UP (la sonda dello stato e il proxy
 * dei dati non hanno la stessa cache).
 */
const PROBES = [
  '/api/lh/ingestion/v1/event-types',
  '/api/lh/member/v1/members?size=1',
  '/api/lh/campaign/v1/campaigns',
  '/api/lh/wallet/v1/editions',
  '/api/lh/reward/v1/reward-categories',
  '/api/lh/gamification/v1/approvals?status=IN_REVIEW',
];

Cypress.Commands.add('waitForCoreServices', () => {
  const started = Date.now();
  const poll = (): void => {
    cy.request({ url: '/api/demo/status', failOnStatusCode: false, timeout: 60_000 }).then((res) => {
      const body = res.body as Partial<DemoStatus> | undefined;
      const up = new Set((body?.services ?? []).filter((s) => s.state === 'UP').map((s) => s.code));
      const statusUp = res.status === 200 && CORE.every((c) => up.has(c));
      // Le sei letture si fanno una dopo l'altra; basta una risposta «servizio addormentato» per riprovare.
      const probes = PROBES.map((url) => () => cy.request({ url, failOnStatusCode: false, timeout: 60_000 }));
      const results: number[] = [];
      probes.forEach((probe) => probe().then((r) => results.push(r.status)));
      cy.then(() => {
        const asleep = results.some((st) => [502, 503, 504].includes(st));
        if (statusUp && !asleep) return;
        if (Date.now() - started > WAKE_MAX_MS) {
          throw new Error(`I servizi non sono diventati attivi entro ${WAKE_MAX_MS / 1000} s (stato ${res.status}, letture ${results.join(',')})`);
        }
        // Risveglio: la demo spegne i servizi gratuiti; una richiesta li sveglia (come «Accendi la demo»).
        cy.request({ method: 'POST', url: '/api/demo/wake', failOnStatusCode: false });
        cy.wait(WAKE_POLL_MS); // cadenza del sondaggio, non un tempo fisso del test
        poll();
      });
    });
  };
  poll();
});

Cypress.Commands.add('waitForScenario', (code: string, timeoutMs = 3 * 60_000) => {
  const started = Date.now();
  const poll = (): void => {
    cy.request({ url: '/api/lh/ingestion/v1/demo/scenarios', failOnStatusCode: false, timeout: 60_000 }).then((res) => {
      const list = Array.isArray(res.body) ? (res.body as { code?: string }[]) : [];
      if (res.status === 200 && list.some((sc) => sc.code === code)) return;
      if (Date.now() - started > timeoutMs) {
        throw new Error(`Lo scenario ${code} non compare nell'elenco di ingestion entro ${timeoutMs / 1000} s (HTTP ${res.status})`);
      }
      cy.wait(2_000); // cadenza del sondaggio, non un tempo fisso del test
      poll();
    });
  };
  poll();
});

Cypress.Commands.add('pointsBalance', (memberId: string) =>
  cy
    .request({ url: `/api/lh/wallet/v1/wallets/${memberId}`, timeout: 60_000 })
    .then((res) => (res.body as { balances: { PTS?: { active: number } } }).balances.PTS?.active ?? 0),
);

Cypress.Commands.add('waitForPoints', (memberId: string, accept: (balance: number) => boolean, what: string, timeoutMs = 60_000) => {
  const started = Date.now();
  const poll = (): Cypress.Chainable<number> =>
    cy.pointsBalance(memberId).then((balance) => {
      if (accept(balance)) return cy.wrap(balance);
      if (Date.now() - started > timeoutMs) {
        throw new Error(`${what}: saldo di ${memberId} ancora ${balance} dopo ${timeoutMs / 1000} s`);
      }
      cy.wait(2_000); // cadenza del sondaggio, non un tempo fisso del test
      return poll();
    });
  return poll();
});

/**
 * POST di ripristino di un servizio, con nuovi tentativi finché il servizio gratuito non è sveglio (il proxy risponde
 * 503 SERVICE_ASLEEP): ogni tentativo sveglia i servizi come «Accendi la demo». Tetto di tempo, nessuna attesa fissa.
 */
function resetService(service: string, started = Date.now()): void {
  cy.request({ method: 'POST', url: `/api/lh/${service}/v1/demo/reset`, timeout: 120_000, failOnStatusCode: false }).then((res) => {
    if (res.status >= 200 && res.status < 300) return;
    if ([502, 503, 504].includes(res.status) && Date.now() - started < WAKE_MAX_MS) {
      cy.request({ method: 'POST', url: '/api/demo/wake', failOnStatusCode: false });
      cy.wait(WAKE_POLL_MS); // cadenza del sondaggio, non un tempo fisso del test
      resetService(service, started);
      return;
    }
    throw new Error(`Ripristino di ${service} non riuscito: HTTP ${res.status}`);
  });
}

Cypress.Commands.add('resetDemoData', () => {
  cy.asOperator('marta.admin');
  for (const service of RESETTABLE) resetService(service);
});

export {};
