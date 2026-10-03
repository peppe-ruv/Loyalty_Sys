import type { Page } from '@playwright/test';
import { bffRequest, type ApiResult } from '../lib/bff.js';
import { OPERATOR } from '../lib/env.js';
import { describeStep, journeyTag, type Journey, type Step } from './generator.js';

// Esecuzione di una journey contro lo stack enterprise del job `e2e-pr` (ADR-053 decisione 4, Q-693, M9.7d, F2-QA-01,
// F2-QA-07) e verifica delle invarianti di M9 dopo ogni ciclo (ogni `checkpoint`).
//
// Tutto passa dal proxy del BFF, dal contesto della pagina dell'operatore già autenticato (come `bff.ts`): il token non
// esce mai dal server (regola 20). Nessuna chiamata diretta ai servizi e nessun endpoint nuovo: solo le API di
// `contracts/api/`. Gli errori portano solo stato HTTP e codice breve dell'errore, mai corpi di risposta (che potrebbero
// riportare dati dei membri di prova).
//
// Azioni raggiungibili oggi (un solo operatore ADMIN, nessun token di membro): registrare un membro, rettificare il
// saldo, leggere il portafoglio, creare e rinominare categorie premi, creare premi in bozza e cambiarne lo stock.
// Il resto (accrediti da campagna, riscatti, concorsi) richiede un secondo operatore o il token di un membro:
// SPEC-GAP: Q-719 (azioni non ancora raggiungibili, con la conservazione dello stock sui riscatti e `contest.won`).

const API = {
  members: '/api/lh/member/v1/members',
  wallets: '/api/lh/wallet/v1/wallets',
  liability: '/api/lh/wallet/v1/liability',
  categories: '/api/lh/reward/v1/reward-categories',
  bands: '/api/lh/reward/v1/reward-bands',
  rewards: '/api/lh/reward/v1/rewards',
  messages: '/api/lh/engagement/v1/messages',
  audit: '/api/lh/insight/v1/audit',
  dlq: '/api/lh/insight/v1/dlq',
};

const WALLET_WAIT_MS = 90_000;
const AUDIT_WAIT_MS = 90_000;
const POLL_MS = [500, 1000, 2000, 3000];

export class JourneyFailure extends Error {
  constructor(
    message: string,
    readonly stepIndex: number,
  ) {
    super(message);
    this.name = 'JourneyFailure';
  }
}

interface MemberState {
  id: string;
  walletReady: boolean;
  /** Saldo attivo atteso (somma delle rettifiche riuscite). */
  balance: number;
}

interface RewardState {
  code: string;
  /** Stock totale atteso (`null` = illimitato). Senza riscatti il residuo è sempre uguale al totale. */
  total: number | null;
}

interface CategoryState {
  code: string;
  renames: number;
}

const sleep = (ms: number): Promise<void> => new Promise((r) => setTimeout(r, ms));

/** Messaggio sicuro di una risposta inattesa: solo cosa si chiedeva, lo stato e il codice breve. */
function unexpected(what: string, res: ApiResult, wanted: number[]): never {
  throw new Error(`${what}: atteso HTTP ${wanted.join('/')}, ricevuto ${res.status}${res.code ? ` (${res.code})` : ''}`);
}

function expectStatus(what: string, res: ApiResult, ...wanted: number[]): void {
  if (!wanted.includes(res.status)) unexpected(what, res, wanted);
}

const obj = (v: unknown): Record<string, unknown> => (v && typeof v === 'object' ? (v as Record<string, unknown>) : {});
const arr = (v: unknown): unknown[] => (Array.isArray(v) ? v : []);
const num = (v: unknown): number => (typeof v === 'number' ? v : Number.NaN);
const str = (v: unknown): string => (typeof v === 'string' ? v : '');

async function poll<T>(fn: () => Promise<T | null>, timeoutMs: number): Promise<T | null> {
  const deadline = Date.now() + timeoutMs;
  for (let i = 0; ; i++) {
    const v = await fn();
    if (v !== null) return v;
    if (Date.now() >= deadline) return null;
    await sleep(POLL_MS[Math.min(i, POLL_MS.length - 1)]!);
  }
}

export interface RunOptions {
  /** Istante di inizio della journey: delimita audit e DLQ da considerare. */
  startedAt: Date;
  log?: (line: string) => void;
  /** Attese massime in millisecondi (portafoglio dopo la registrazione, voci di audit dal bus). Solo per le prove con simulazione. */
  waitMs?: { wallet?: number; audit?: number };
}

/**
 * Esegue la journey passo per passo. Al primo passo che si discosta dal modello o alla prima invariante violata lancia
 * un {@link JourneyFailure} con l'indice del passo.
 */
export async function runJourney(page: Page, journey: Journey, opts: RunOptions): Promise<void> {
  const tag = journeyTag(journey.seed);
  const log = opts.log ?? (() => undefined);
  const walletWaitMs = opts.waitMs?.wallet ?? WALLET_WAIT_MS;
  const auditWaitMs = opts.waitMs?.audit ?? AUDIT_WAIT_MS;
  const members = new Map<string, MemberState>();
  const categories = new Map<string, CategoryState>();
  const rewards = new Map<string, RewardState>();
  /** Voci di audit attese: `ENTITY_TYPE|entityId|ACTION` → quante. Una per ogni scrittura riuscita (regola 21). */
  const expectedAudit = new Map<string, number>();
  let bandCode: string | null = null;
  const sinceMs = opts.startedAt.getTime() - 5_000;
  const since = new Date(sinceMs).toISOString();

  const audited = (entityType: string, entityId: string, action: string): void => {
    const key = `${entityType.toUpperCase()}|${entityId}|${action.toUpperCase()}`;
    expectedAudit.set(key, (expectedAudit.get(key) ?? 0) + 1);
  };
  const member = (ref: string): MemberState => members.get(ref) ?? failWith(`membro ${ref} non registrato`);
  const category = (ref: string): CategoryState => categories.get(ref) ?? failWith(`categoria ${ref} non creata`);
  const reward = (ref: string): RewardState => rewards.get(ref) ?? failWith(`premio ${ref} non creato`);
  let current = -1;
  const failWith = (message: string): never => {
    throw new JourneyFailure(message, current);
  };

  const ensureWallet = async (m: MemberState): Promise<void> => {
    if (m.walletReady) return;
    // Il portafoglio nasce al fatto `member.registered` sul bus: si attende senza pause fisse.
    const ready = await poll(async () => ((await bffRequest(page, `${API.wallets}/${encodeURIComponent(m.id)}`)).status === 200 ? true : null), walletWaitMs);
    if (!ready) failWith(`il portafoglio del membro non è comparso entro ${walletWaitMs / 1000} s dalla registrazione`);
    m.walletReady = true;
  };

  const adjust = async (m: MemberState, direction: 'CREDIT' | 'DEBIT', amount: number, reason: string): Promise<void> => {
    await ensureWallet(m);
    const res = await bffRequest(page, `${API.wallets}/${encodeURIComponent(m.id)}/adjustments`, {
      method: 'POST',
      body: { currency: 'PTS', direction, amount, reason, note: `Journey casuale, seme ${journey.seed}` },
    });
    expectStatus(`rettifica ${direction}`, res, 200);
    m.balance += direction === 'CREDIT' ? amount : -amount;
    const after = num(obj(res.json).balanceAfter);
    if (after !== m.balance) failWith(`rettifica ${direction} di ${amount}: saldo restituito ${after}, atteso ${m.balance}`);
    audited('wallet', `${m.id}:PTS`, 'ADJUST');
  };

  const ensureBand = async (): Promise<string> => {
    if (bandCode) return bandCode;
    const code = `${tag}B`;
    const list = await bffRequest(page, API.bands);
    expectStatus('elenco fasce', list, 200);
    const existing = arr(list.json).map(obj);
    // Soglie uniche e crescenti con l'ordine delle fasce: la nuova sta sopra tutte le esistenti.
    const threshold = Math.max(0, ...existing.map((b) => num(b.pointsThreshold)).filter(Number.isFinite)) + 1000;
    const sortOrder = Math.max(0, ...existing.map((b) => num(b.sortOrder)).filter(Number.isFinite)) + 1;
    const res = await bffRequest(page, API.bands, { method: 'POST', body: { code, name: `Fascia ${code}`, pointsThreshold: threshold, sortOrder } });
    expectStatus('crea fascia', res, 201);
    audited('REWARD_BAND', code, 'CREATE');
    bandCode = code;
    return code;
  };

  const readReward = async (code: string): Promise<Record<string, unknown>> => {
    const res = await bffRequest(page, `${API.rewards}/${encodeURIComponent(code)}`);
    expectStatus('legge il premio', res, 200);
    return obj(res.json);
  };

  const runStep = async (step: Step): Promise<void> => {
    switch (step.kind) {
      case 'registerMember': {
        const email = `${tag.toLowerCase()}-${step.ref}@example.test`;
        const res = await bffRequest(page, API.members, {
          method: 'POST',
          body: { firstName: 'Prova', lastName: `${tag}${step.ref.toUpperCase()}`, email },
        });
        expectStatus('registra membro', res, 201, 200);
        const id = str(obj(res.json).id);
        if (!id) failWith('registra membro: la risposta non porta l\'id');
        members.set(step.ref, { id, walletReady: false, balance: 0 });
        audited('MEMBER', id, 'CREATE');
        break;
      }
      case 'credit':
        await adjust(member(step.member), 'CREDIT', step.amount, step.reason);
        break;
      case 'debit':
        await adjust(member(step.member), 'DEBIT', step.amount, step.reason);
        break;
      case 'overdraw': {
        const m = member(step.member);
        await ensureWallet(m);
        const res = await bffRequest(page, `${API.wallets}/${encodeURIComponent(m.id)}/adjustments`, {
          method: 'POST',
          body: { currency: 'PTS', direction: 'DEBIT', amount: m.balance + 1, reason: 'TEST', note: `Journey casuale, seme ${journey.seed}` },
        });
        expectStatus('addebito oltre il saldo', res, 422);
        break;
      }
      case 'readWallet': {
        const m = member(step.member);
        await ensureWallet(m);
        const res = await bffRequest(page, `${API.wallets}/${encodeURIComponent(m.id)}`);
        expectStatus('legge il portafoglio', res, 200);
        const active = num(obj(obj(obj(res.json).balances).PTS).active);
        if (active !== m.balance) failWith(`saldo letto ${active}, atteso ${m.balance}`);
        break;
      }
      case 'createCategory': {
        const code = `${tag}C${step.ref.slice(1)}`;
        const res = await bffRequest(page, API.categories, { method: 'POST', body: { code, name: `Categoria ${code}`, icon: 'sparkles', sortOrder: 90 + categories.size } });
        expectStatus('crea categoria', res, 201);
        categories.set(step.ref, { code, renames: 0 });
        audited('REWARD_CATEGORY', code, 'CREATE');
        break;
      }
      case 'renameCategory': {
        const c = category(step.category);
        c.renames++;
        const res = await bffRequest(page, `${API.categories}/${encodeURIComponent(c.code)}`, {
          method: 'PUT',
          body: { code: c.code, name: `Categoria ${c.code} v${c.renames}`, icon: 'sparkles', sortOrder: 90 },
        });
        expectStatus('rinomina categoria', res, 200);
        audited('REWARD_CATEGORY', c.code, 'UPDATE');
        break;
      }
      case 'createReward': {
        const band = await ensureBand();
        const code = `${tag}R${step.ref.slice(1)}`;
        const res = await bffRequest(page, API.rewards, {
          method: 'POST',
          body: {
            code,
            name: `Premio ${code}`,
            type: 'DIGITAL',
            fulfilment: 'MANUAL',
            band,
            category: step.category ? category(step.category).code : undefined,
            stockTotal: step.stock ?? undefined,
          },
        });
        expectStatus('crea premio', res, 201);
        const created = obj(res.json);
        if (str(created.status) !== 'DRAFT') failWith(`premio creato in stato ${str(created.status)}, atteso DRAFT`);
        rewards.set(step.ref, { code, total: step.stock });
        audited('REWARD', code, 'CREATE');
        break;
      }
      case 'restockReward': {
        const r = reward(step.reward);
        const before = await readReward(r.code);
        const res = await bffRequest(page, `${API.rewards}/${encodeURIComponent(r.code)}`, {
          method: 'PUT',
          body: { version: before.version, stockTotal: step.stock },
        });
        expectStatus('cambia lo stock', res, 200);
        r.total = step.stock;
        audited('REWARD', r.code, 'UPDATE');
        break;
      }
      case 'checkpoint': {
        const violations = await checkInvariants();
        if (violations.length > 0) failWith(`invarianti violate dopo il ciclo: ${violations.join(' | ')}`);
        break;
      }
    }
  };

  // ---------- invarianti di M9 (docs/18, Accettazione) osservabili dalle API ----------
  // SPEC-GAP: Q-717 — non verificabili con le API di oggi, e quindi non controllate qui: (1) ogni `contest.won` con
  // tracciato completo (nessuna scheda definisce «completo» per `GET /v1/traces/{correlationId}`); (2) Σ lotti = in
  // circolazione su TUTTI i wallet (nessun elenco globale dei lotti: sotto si verifica solo che il totale copra i saldi
  // dei membri della journey); (3) nessun doppione nell'inbox del membro (`/v1/portal/inbox` vuole il token del membro:
  // si controlla il registro `GET /v1/messages`).

  const checkInvariants = async (): Promise<string[]> => {
    const v: string[] = [];
    const ready = [...members.values()].filter((m) => m.walletReady);

    // Σ lotti = saldo, per ogni membro con portafoglio; e il saldo coincide col modello della journey.
    for (const m of ready) {
      const [w, l] = await Promise.all([
        bffRequest(page, `${API.wallets}/${encodeURIComponent(m.id)}`),
        bffRequest(page, `${API.wallets}/${encodeURIComponent(m.id)}/lots`),
      ]);
      if (w.status !== 200 || l.status !== 200) {
        v.push(`lotti e saldo illeggibili (HTTP ${w.status}/${l.status})`);
        continue;
      }
      const pts = obj(obj(obj(w.json).balances).PTS);
      let active = 0;
      let pending = 0;
      for (const lot of arr(l.json).map(obj)) {
        if (str(lot.currency) !== 'PTS') continue;
        if (str(lot.status) === 'ACTIVE') active += num(lot.remaining);
        else if (str(lot.status) === 'PENDING') pending += num(lot.remaining);
      }
      if (active !== num(pts.active)) v.push(`Σ lotti attivi ${active} ≠ saldo attivo ${num(pts.active)}`);
      if (pending !== num(pts.pending)) v.push(`Σ lotti in attesa ${pending} ≠ saldo in attesa ${num(pts.pending)}`);
      if (num(pts.active) !== m.balance) v.push(`saldo attivo ${num(pts.active)} ≠ modello ${m.balance}`);
    }

    // Σ mesi di scadenza = in circolazione (passività, F-WAL-09); e il totale copre almeno i membri della journey.
    if (ready.length > 0) {
      const res = await bffRequest(page, `${API.liability}?currency=PTS`);
      if (res.status !== 200) {
        v.push(`passività illeggibile (HTTP ${res.status})`);
      } else {
        const j = obj(res.json);
        const byMonth = arr(j.byExpiryMonth).reduce<number>((s, x) => s + num(obj(x).amount), 0);
        if (byMonth !== num(j.outstanding)) v.push(`Σ mesi di scadenza ${byMonth} ≠ in circolazione ${num(j.outstanding)}`);
        const mine = ready.reduce((s, m) => s + m.balance, 0);
        if (num(j.outstanding) < mine) v.push(`in circolazione ${num(j.outstanding)} < saldi dei membri della journey ${mine}`);
      }
    }

    // Stock ≤ totale: su tutto il catalogo (nessun premio con residuo oltre il totale o negativo) e, per i premi della
    // journey, il totale è quello impostato e, senza riscatti, il residuo è uguale al totale.
    if (rewards.size > 0) {
      const res = await bffRequest(page, API.rewards);
      if (res.status !== 200) {
        v.push(`catalogo premi illeggibile (HTTP ${res.status})`);
      } else {
        const byCode = new Map(arr(res.json).map(obj).map((r) => [str(r.code), r] as const));
        for (const r of byCode.values()) {
          if (r.stockTotal == null) continue;
          const total = num(r.stockTotal);
          const remaining = num(r.stockRemaining);
          if (!(remaining >= 0 && remaining <= total)) v.push(`stock del premio ${str(r.code)}: residuo ${remaining} fuori da 0…${total}`);
        }
        for (const r of rewards.values()) {
          const got = byCode.get(r.code);
          if (!got) {
            v.push(`premio ${r.code} assente dal catalogo`);
            continue;
          }
          if (r.total === null) {
            if (got.stockTotal != null) v.push(`premio ${r.code}: stock illimitato atteso, totale ${num(got.stockTotal)}`);
          } else if (num(got.stockTotal) !== r.total || num(got.stockRemaining) !== r.total) {
            v.push(`premio ${r.code}: stock ${num(got.stockRemaining)}/${num(got.stockTotal)}, atteso ${r.total}/${r.total}`);
          }
        }
      }
    }

    // Nessun doppione in inbox: (membro, evento di origine, modello) è univoco.
    for (const m of ready) {
      const res = await bffRequest(page, `${API.messages}?memberId=${encodeURIComponent(m.id)}&size=100`);
      if (res.status !== 200) {
        v.push(`inbox illeggibile (HTTP ${res.status})`);
        continue;
      }
      const seen = new Set<string>();
      for (const msg of arr(obj(res.json).items).map(obj)) {
        if (!str(msg.sourceEventId)) continue;
        const key = `${str(msg.memberId)}|${str(msg.sourceEventId)}|${str(msg.templateCode)}`;
        if (seen.has(key)) v.push(`doppione in inbox per l'evento ${str(msg.sourceEventId)} (${str(msg.templateCode)})`);
        seen.add(key);
      }
    }

    // Una voce di audit per ogni scrittura riuscita, con l'attore reale (regola 21, ADR-043).
    v.push(...(await checkAudit()));

    // Nessuna voce nella DLQ comparsa durante la journey.
    const dlq = await bffRequest(page, `${API.dlq}?size=100`);
    if (dlq.status !== 200) {
      v.push(`DLQ illeggibile (HTTP ${dlq.status})`);
    } else {
      const fresh = arr(obj(dlq.json).items)
        .map(obj)
        .filter((e) => Date.parse(str(e.firstSeenAt)) >= sinceMs);
      for (const e of fresh) v.push(`DLQ: ${str(e.originalType) || 'evento'} per ${str(e.consumer) || 'consumer'} (${str(e.errorCode) || 'errore'})`);
    }
    return v;
  };

  /** Raccoglie le voci di audit della journey (da `since`), per chiave `tipo|id|azione`, con gli attori. */
  const fetchAudit = async (): Promise<{ counts: Map<string, number>; actors: Map<string, Set<string>>; error?: string }> => {
    const counts = new Map<string, number>();
    const actors = new Map<string, Set<string>>();
    for (let p = 0; p < 50; p++) {
      const res = await bffRequest(page, `${API.audit}?from=${encodeURIComponent(since)}&page=${p}&size=100`);
      if (res.status !== 200) return { counts, actors, error: `HTTP ${res.status}` };
      const body = obj(res.json);
      for (const e of arr(body.items).map(obj)) {
        const key = `${str(e.entityType).toUpperCase()}|${str(e.entityId)}|${str(e.action).toUpperCase()}`;
        // Solo le scritture della journey: le voci di altri tipi sulle stesse entità (job, proiezioni) non si contano.
        if (!expectedAudit.has(key)) continue;
        counts.set(key, (counts.get(key) ?? 0) + 1);
        (actors.get(key) ?? actors.set(key, new Set()).get(key)!).add(str(e.actorName));
      }
      const info = obj(body.page);
      if (!(p + 1 < num(info.totalPages))) break;
    }
    return { counts, actors };
  };
  const checkAudit = async (): Promise<string[]> => {
    const missing = (counts: Map<string, number>): string[] => [...expectedAudit].filter(([k, n]) => (counts.get(k) ?? 0) < n).map(([k]) => k);
    // Le voci arrivano dal bus: si attende che compaiano tutte (mai una pausa fissa); se ne compare una di troppo non serve aspettare.
    let last: Awaited<ReturnType<typeof fetchAudit>> = { counts: new Map(), actors: new Map() };
    const settled = await poll(async () => {
      last = await fetchAudit();
      return last.error || missing(last.counts).length === 0 ? true : null;
    }, auditWaitMs);
    const v: string[] = [];
    if (last.error) return [`audit illeggibile (${last.error})`];
    if (!settled) for (const k of missing(last.counts)) v.push(`audit: voce mancante ${k.replace(/\|/g, ' ')}`);
    for (const [k, n] of last.counts) {
      const want = expectedAudit.get(k) ?? 0;
      if (n > want) v.push(`audit: ${n} voci per ${k.replace(/\|/g, ' ')}, attesa ${want}`);
      for (const actor of last.actors.get(k) ?? []) {
        if (actor !== OPERATOR) v.push(`audit: attore «${actor || 'assente'}» per ${k.replace(/\|/g, ' ')}, atteso ${OPERATOR}`);
      }
    }
    return v;
  };

  log(`journey seme=${journey.seed} passi=${journey.steps.length}`);
  for (let i = 0; i < journey.steps.length; i++) {
    current = i;
    const step = journey.steps[i]!;
    log(`  ${String(i + 1).padStart(2, '0')} ${describeStep(step)}`);
    try {
      await runStep(step);
    } catch (e) {
      if (e instanceof JourneyFailure) throw e;
      const msg = e instanceof Error ? e.message : 'errore';
      throw new JourneyFailure(`passo ${i + 1} (${describeStep(step)}): ${msg.slice(0, 300)}`, i);
    }
  }
}
