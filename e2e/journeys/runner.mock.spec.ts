import { expect, test, type Page } from '@playwright/test';
import { deriveSeed, generateJourney } from './generator.js';
import { JourneyFailure, runJourney } from './runner.js';

// Prove del runner e delle invarianti contro uno stack SIMULATO (nessun browser, nessun servizio): `pnpm test:mock`
// (playwright.mock.config.ts). Non sostituiscono le journey sullo stack vero: verificano la logica dell'harness
// (modello dei saldi, conteggio delle voci di audit, riconoscimento di ogni invariante violata) senza Docker.
// La simulazione implementa le API come descritte da contracts/api/ e dai controller: se uno stack vero si comporta
// diversamente, a dirlo è la corsa sullo stack (e2e-pr), non questa prova.

interface Fault {
  /** Somma dei lotti diversa dal saldo. */
  lotsMismatch?: boolean;
  /** Due voci di audit per ogni rettifica. */
  duplicateAudit?: boolean;
  /** Nessuna voce di audit per le categorie. */
  dropCategoryAudit?: boolean;
  /** Residuo di stock oltre il totale. */
  stockOverflow?: boolean;
  /** Mesi di scadenza che non sommano al totale in circolazione. */
  liabilityMismatch?: boolean;
  /** Un messaggio doppio in inbox. */
  inboxDuplicate?: boolean;
  /** Una voce in DLQ comparsa durante la journey. */
  dlq?: boolean;
  /** Attore dell'audit diverso dall'operatore. */
  wrongActor?: boolean;
  /** Il portafoglio non nasce mai. */
  noWallet?: boolean;
}

interface Req {
  path: string;
  method: string;
  body: unknown;
}

function createStack(fault: Fault = {}) {
  let n = 0;
  const members = new Map<string, { balance: number; lots: Array<{ remaining: number }> }>();
  const categories = new Set<string>();
  const bands: Array<Record<string, unknown>> = [];
  const rewards = new Map<string, Record<string, unknown>>();
  const audit: Array<Record<string, unknown>> = [];
  const tm = process.env.LH_E2E_OPERATOR ?? 'marta.admin';
  const record = (entityType: string, entityId: string, action: string): void => {
    const copies = fault.duplicateAudit && action === 'ADJUST' ? 2 : 1;
    if (fault.dropCategoryAudit && entityType === 'REWARD_CATEGORY') return;
    for (let i = 0; i < copies; i++) audit.push({ entityType, entityId, action, actorName: fault.wrongActor ? 'altro' : tm, at: new Date().toISOString() });
  };
  const ok = (json: unknown, status = 200) => ({ status, code: null, json });
  const err = (status: number, code: string) => ({ status, code, json: { code } });

  function handle({ path, method, body }: Req) {
    const url = new URL(path, 'https://web.lh.test');
    const p = url.pathname.replace('/api/lh/', '');
    const b = (body ?? {}) as Record<string, any>;
    if (p === 'member/v1/members' && method === 'POST') {
      const id = `MBR-${String(++n).padStart(6, '0')}`;
      members.set(id, { balance: 0, lots: [] });
      record('MEMBER', id, 'CREATE');
      return ok({ id }, 201);
    }
    let m = /^wallet\/v1\/wallets\/([^/]+)(\/lots|\/adjustments)?$/.exec(p);
    if (m) {
      const mem = members.get(m[1]!);
      if (!mem || fault.noWallet) return err(404, 'NOT_FOUND');
      if (m[2] === '/lots') {
        const lots = fault.lotsMismatch && mem.balance > 0 ? [{ remaining: mem.balance - 1, status: 'ACTIVE', currency: 'PTS' }] : mem.lots.map((l) => ({ ...l, status: 'ACTIVE', currency: 'PTS' }));
        return ok(lots);
      }
      if (m[2] === '/adjustments') {
        if (b.direction === 'CREDIT') {
          mem.balance += b.amount;
          mem.lots.push({ remaining: b.amount });
        } else {
          if (b.amount > mem.balance) return err(422, 'INSUFFICIENT_BALANCE');
          mem.balance -= b.amount;
          let left = b.amount;
          for (const l of mem.lots) {
            const take = Math.min(left, l.remaining);
            l.remaining -= take;
            left -= take;
          }
        }
        record('wallet', `${m[1]}:PTS`, 'ADJUST');
        return ok({ ledgerEntryId: 'L', balanceAfter: mem.balance });
      }
      return ok({ memberId: m[1], balances: { PTS: { active: mem.balance, pending: 0, lifetimeEarned: 0, lifetimeSpent: 0 }, STS: { active: 0, pending: 0 } } });
    }
    if (p === 'wallet/v1/liability') {
      const outstanding = [...members.values()].reduce((s, x) => s + x.balance, 0);
      return ok({ currency: 'PTS', outstanding, pending: 0, byExpiryMonth: [{ month: '2027-10', amount: fault.liabilityMismatch ? outstanding + 1 : outstanding }] });
    }
    if (p === 'reward/v1/reward-categories' && method === 'POST') {
      categories.add(b.code);
      record('REWARD_CATEGORY', b.code, 'CREATE');
      return ok(b, 201);
    }
    m = /^reward\/v1\/reward-categories\/([^/]+)$/.exec(p);
    if (m && method === 'PUT') {
      if (!categories.has(m[1]!)) return err(404, 'NOT_FOUND');
      record('REWARD_CATEGORY', m[1]!, 'UPDATE');
      return ok(b);
    }
    if (p === 'reward/v1/reward-bands') {
      if (method === 'GET') return ok(bands);
      if (bands.some((x) => x.pointsThreshold === b.pointsThreshold)) return err(422, 'BAND_THRESHOLD_DUPLICATE');
      bands.push(b);
      record('REWARD_BAND', b.code, 'CREATE');
      return ok(b, 201);
    }
    if (p === 'reward/v1/rewards' && method === 'POST') {
      const r = { code: b.code, status: 'DRAFT', stockTotal: b.stockTotal ?? null, stockRemaining: b.stockTotal ?? null, version: 0 };
      rewards.set(b.code, r);
      record('REWARD', b.code, 'CREATE');
      return ok(r, 201);
    }
    if (p === 'reward/v1/rewards') return ok([...rewards.values()].map((r) => (fault.stockOverflow && r.stockTotal != null ? { ...r, stockRemaining: (r.stockTotal as number) + 1 } : r)));
    m = /^reward\/v1\/rewards\/([^/]+)$/.exec(p);
    if (m) {
      const r = rewards.get(m[1]!);
      if (!r) return err(404, 'NOT_FOUND');
      if (method === 'GET') return ok(r);
      if (b.version !== r.version) return err(409, 'VERSION_CONFLICT');
      r.stockTotal = b.stockTotal;
      r.stockRemaining = b.stockTotal;
      r.version = (r.version as number) + 1;
      record('REWARD', m[1]!, 'UPDATE');
      return ok(r);
    }
    if (p === 'engagement/v1/messages') {
      const id = url.searchParams.get('memberId');
      const msg = { memberId: id, sourceEventId: 'EVT-1', templateCode: 'WELCOME' };
      return ok({ items: fault.inboxDuplicate ? [msg, msg] : [msg], page: { number: 0, size: 100, totalItems: 1, totalPages: 1 } });
    }
    if (p === 'insight/v1/audit') {
      const page = Number(url.searchParams.get('page') ?? 0);
      const size = Number(url.searchParams.get('size') ?? 20);
      return ok({ items: audit.slice(page * size, page * size + size), page: { number: page, size, totalItems: audit.length, totalPages: Math.max(1, Math.ceil(audit.length / size)) } });
    }
    if (p === 'insight/v1/dlq') {
      const items = fault.dlq ? [{ firstSeenAt: new Date().toISOString(), consumer: 'wallet', originalType: 'io.loyaltyhub.fact.x', errorCode: 'BOOM' }] : [];
      return ok({ items, page: { number: 0, size: 100, totalItems: items.length, totalPages: 1 } });
    }
    return err(404, 'NOT_FOUND');
  }

  // Finto `Page`: bffRequest chiama `page.evaluate(fn, args)`; qui non serve eseguire fn, basta rispondere ai suoi argomenti.
  const page = {
    evaluate: async (_fn: unknown, args: { path: string; method: string; body?: unknown }) => handle({ path: args.path, method: args.method, body: args.body }),
  } as unknown as Page;
  return { page, audit, members };
}

const fast = { waitMs: { wallet: 1500, audit: 1500 } } as const;

test('journey casuali sullo stack simulato: nessuna violazione, 60 semi', async () => {
  for (let i = 0; i < 60; i++) {
    const stack = createStack();
    const journey = generateJourney(deriveSeed(20261003, i), { steps: 10 });
    await runJourney(stack.page, journey, { startedAt: new Date(), ...fast });
    // Una voce di audit per ogni scrittura: membri + rettifiche + categorie + fasce + premi.
    const writes = journey.steps.filter((s) => ['registerMember', 'credit', 'debit', 'createCategory', 'renameCategory', 'createReward', 'restockReward'].includes(s.kind)).length;
    const bands = journey.steps.some((s) => s.kind === 'createReward') ? 1 : 0;
    expect(stack.audit.length).toBe(writes + bands);
  }
});

test('ogni invariante violata fa fallire la journey con il suo messaggio', async () => {
  const seed = 31337;
  const journey = generateJourney(seed, { steps: 24 });
  const kinds = new Set(journey.steps.map((s) => s.kind));
  for (const k of ['credit', 'createCategory', 'createReward'] as const) expect(kinds.has(k), `la journey del seme ${seed} deve usare ${k}`).toBe(true);
  const cases: Array<[string, Fault, RegExp]> = [
    ['Σ lotti', { lotsMismatch: true }, /Σ lotti attivi/],
    ['audit doppio', { duplicateAudit: true }, /audit: \d+ voci per WALLET/],
    ['audit mancante', { dropCategoryAudit: true }, /audit: voce mancante REWARD_CATEGORY/],
    ['stock oltre il totale', { stockOverflow: true }, /residuo .* fuori da 0/],
    ['mesi di scadenza', { liabilityMismatch: true }, /Σ mesi di scadenza/],
    ['doppione in inbox', { inboxDuplicate: true }, /doppione in inbox/],
    ['DLQ', { dlq: true }, /DLQ:/],
    ['attore diverso', { wrongActor: true }, /attore «altro»/],
    ['portafoglio mai creato', { noWallet: true }, /portafoglio del membro non è comparso/],
  ];
  for (const [name, fault, expected] of cases) {
    const stack = createStack(fault);
    const err = await runJourney(stack.page, journey, { startedAt: new Date(), ...fast }).then(
      () => null,
      (e: unknown) => e,
    );
    expect(err, `${name}: la journey doveva fallire`).toBeInstanceOf(JourneyFailure);
    expect((err as JourneyFailure).message, name).toMatch(expected);
  }
});

test('un passo che il servizio rifiuta fa fallire con stato e codice, senza corpi di risposta', async () => {
  const stack = createStack();
  const journey = { seed: 5, steps: [{ kind: 'credit' as const, member: 'm1', amount: 5, reason: 'TEST' as const }, { kind: 'checkpoint' as const }] };
  const err = await runJourney(stack.page, journey, { startedAt: new Date(), ...fast }).catch((e: unknown) => e);
  expect(err).toBeInstanceOf(JourneyFailure);
  expect((err as Error).message).toMatch(/membro m1 non registrato/);
});
