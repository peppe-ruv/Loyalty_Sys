// Generatore di journey casuali con seme (ADR-053 decisione 4, Q-693, M9.7d, F2-QA-01, F2-QA-07).
//
// Codice puro e deterministico: lo stesso seme e le stesse opzioni producono sempre la stessa sequenza di passi, senza
// toccare rete, orologio o ambiente. Nessuna dipendenza: il generatore di numeri casuali è un piccolo mulberry32
// scritto qui. Un passo è un dato JSON (niente funzioni), così un seme che fallisce si salva com'è in
// `journeys/regressioni/` e si rigioca identico anche se questo generatore cambia in seguito.
//
// Le azioni sono solo quelle raggiungibili con l'unico operatore del job `e2e-pr` (marta.admin, ruolo ADMIN) dalle API
// pubbliche di `contracts/api/` attraverso il proxy del BFF: vedi `runner.ts` e Q-719 per ciò che resta fuori.

export const REASONS = ['GOODWILL', 'CORRECTION', 'COMPLAINT', 'TEST'] as const;
export type Reason = (typeof REASONS)[number];

/** Un passo di una journey. I riferimenti (`m1`, `c1`, `r1`) sono nomi locali alla journey, mai identificativi veri. */
export type Step =
  /** Registra un membro di prova dal backoffice (`POST /v1/members`). */
  | { kind: 'registerMember'; ref: string }
  /** Rettifica manuale in accredito (`POST /v1/wallets/{id}/adjustments`, CREDIT). */
  | { kind: 'credit'; member: string; amount: number; reason: Reason }
  /** Rettifica manuale in addebito, mai oltre il saldo previsto dal modello. */
  | { kind: 'debit'; member: string; amount: number; reason: Reason }
  /** Addebito di un punto oltre il saldo: il servizio lo rifiuta (422) e nulla cambia. */
  | { kind: 'overdraw'; member: string }
  /** Legge il portafoglio del membro e lo confronta col modello. */
  | { kind: 'readWallet'; member: string }
  /** Crea una categoria premi (`POST /v1/reward-categories`). */
  | { kind: 'createCategory'; ref: string }
  /** Rinomina la categoria (`PUT /v1/reward-categories/{code}`). */
  | { kind: 'renameCategory'; category: string }
  /** Crea un premio in bozza (con la fascia della journey, creata al bisogno); `stock` null = illimitato. */
  | { kind: 'createReward'; ref: string; category: string | null; stock: number | null }
  /** Cambia lo stock totale del premio in bozza (`PUT /v1/rewards/{code}`). */
  | { kind: 'restockReward'; reward: string; stock: number }
  /** Fine di un ciclo: si verificano tutte le invarianti di M9 osservabili dalle API. */
  | { kind: 'checkpoint' };

export interface Journey {
  seed: number;
  steps: Step[];
}

export interface GenerateOptions {
  /** Numero di passi d'azione (i checkpoint si aggiungono). */
  steps: number;
}

export const MAX_SEED = 0xffffffff;
export const MAX_STEPS = 200;
export const MAX_AMOUNT = 1_000_000;

/** mulberry32: 32 bit di stato, periodo 2^32, ampiamente sufficiente per sequenze di poche decine di passi. */
export function mulberry32(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** Seme della i-esima journey a partire dal seme principale (mescolamento di interi a 32 bit). */
export function deriveSeed(master: number, index: number): number {
  let h = (master ^ Math.imul(index + 1, 0x9e3779b1)) >>> 0;
  h = Math.imul(h ^ (h >>> 16), 0x85ebca6b) >>> 0;
  h = Math.imul(h ^ (h >>> 13), 0xc2b2ae35) >>> 0;
  h = (h ^ (h >>> 16)) >>> 0;
  return h === 0 ? 1 : h;
}

/** Legge un seme da una stringa: solo cifre decimali, da 1 a 2^32-1. Altro valore: `null`. */
export function parseSeed(raw: string | undefined | null): number | null {
  const s = (raw ?? '').trim();
  if (!/^[0-9]{1,10}$/.test(s)) return null;
  const n = Number(s);
  return Number.isSafeInteger(n) && n >= 1 && n <= MAX_SEED ? n : null;
}

/** Intero positivo da una variabile d'ambiente, con valore predefinito e tetto; un valore non valido è un errore. */
export function parsePositiveInt(raw: string | undefined, fallback: number, max: number, name: string): number {
  if (raw === undefined || raw.trim() === '') return fallback;
  if (!/^[0-9]{1,6}$/.test(raw.trim())) throw new Error(`${name} deve essere un intero positivo (ricevuto un valore non numerico)`);
  const n = Number(raw.trim());
  if (n < 1 || n > max) throw new Error(`${name} deve essere tra 1 e ${max}`);
  return n;
}

interface Weighted<T> {
  weight: number;
  value: T;
}

function pick<T>(rnd: () => number, options: Weighted<T>[]): T {
  const total = options.reduce((s, o) => s + o.weight, 0);
  let x = rnd() * total;
  for (const o of options) {
    x -= o.weight;
    if (x < 0) return o.value;
  }
  return options[options.length - 1]!.value;
}

const int = (rnd: () => number, min: number, max: number): number => min + Math.floor(rnd() * (max - min + 1));

type Action = 'registerMember' | 'credit' | 'debit' | 'overdraw' | 'readWallet' | 'createCategory' | 'renameCategory' | 'createReward' | 'restockReward';

/**
 * Genera una journey: sequenza di `opts.steps` azioni valide rispetto a un modello minimo (un debito non supera mai il
 * saldo, un riferimento esiste prima di essere usato), con un checkpoint ogni 3-5 azioni e uno finale. Il primo passo è
 * sempre la registrazione di un membro, così ogni journey tocca portafoglio, audit e bus.
 */
export function generateJourney(seed: number, opts: GenerateOptions): Journey {
  if (!Number.isInteger(seed) || seed < 1 || seed > MAX_SEED) throw new Error('seme fuori intervallo');
  if (!Number.isInteger(opts.steps) || opts.steps < 1 || opts.steps > MAX_STEPS) throw new Error(`steps deve essere tra 1 e ${MAX_STEPS}`);
  const rnd = mulberry32(seed);
  const steps: Step[] = [];
  const balances = new Map<string, number>();
  const categories: string[] = [];
  const rewards: string[] = [];
  let sinceCheckpoint = 0;
  let nextCheckpoint = int(rnd, 3, 5);

  const memberRefs = (): string[] => [...balances.keys()];
  const anyMember = (): string => memberRefs()[int(rnd, 0, balances.size - 1)]!;

  for (let i = 0; i < opts.steps; i++) {
    const options: Weighted<Action>[] = [];
    if (i === 0) {
      options.push({ weight: 1, value: 'registerMember' });
    } else {
      if (balances.size < 3) options.push({ weight: 2, value: 'registerMember' });
      if (balances.size > 0) {
        options.push({ weight: 5, value: 'credit' }, { weight: 1, value: 'overdraw' }, { weight: 2, value: 'readWallet' });
        if ([...balances.values()].some((b) => b > 0)) options.push({ weight: 3, value: 'debit' });
      }
      if (categories.length < 3) options.push({ weight: 2, value: 'createCategory' });
      if (categories.length > 0) options.push({ weight: 1, value: 'renameCategory' });
      if (rewards.length < 3) options.push({ weight: 2, value: 'createReward' });
      if (rewards.length > 0) options.push({ weight: 1, value: 'restockReward' });
    }
    switch (pick(rnd, options)) {
      case 'registerMember': {
        const ref = `m${balances.size + 1}`;
        balances.set(ref, 0);
        steps.push({ kind: 'registerMember', ref });
        break;
      }
      case 'credit': {
        const member = anyMember();
        const amount = int(rnd, 1, 500);
        balances.set(member, balances.get(member)! + amount);
        steps.push({ kind: 'credit', member, amount, reason: REASONS[int(rnd, 0, REASONS.length - 1)]! });
        break;
      }
      case 'debit': {
        const withBalance = memberRefs().filter((m) => balances.get(m)! > 0);
        const member = withBalance[int(rnd, 0, withBalance.length - 1)]!;
        const amount = int(rnd, 1, balances.get(member)!);
        balances.set(member, balances.get(member)! - amount);
        steps.push({ kind: 'debit', member, amount, reason: REASONS[int(rnd, 0, REASONS.length - 1)]! });
        break;
      }
      case 'overdraw':
        steps.push({ kind: 'overdraw', member: anyMember() });
        break;
      case 'readWallet':
        steps.push({ kind: 'readWallet', member: anyMember() });
        break;
      case 'createCategory': {
        const ref = `c${categories.length + 1}`;
        categories.push(ref);
        steps.push({ kind: 'createCategory', ref });
        break;
      }
      case 'renameCategory':
        steps.push({ kind: 'renameCategory', category: categories[int(rnd, 0, categories.length - 1)]! });
        break;
      case 'createReward': {
        const ref = `r${rewards.length + 1}`;
        rewards.push(ref);
        const category = categories.length > 0 && rnd() < 0.5 ? categories[int(rnd, 0, categories.length - 1)]! : null;
        steps.push({ kind: 'createReward', ref, category, stock: rnd() < 0.2 ? null : int(rnd, 1, 50) });
        break;
      }
      case 'restockReward':
        steps.push({ kind: 'restockReward', reward: rewards[int(rnd, 0, rewards.length - 1)]!, stock: int(rnd, 1, 100) });
        break;
    }
    sinceCheckpoint++;
    if (sinceCheckpoint >= nextCheckpoint && i < opts.steps - 1) {
      steps.push({ kind: 'checkpoint' });
      sinceCheckpoint = 0;
      nextCheckpoint = int(rnd, 3, 5);
    }
  }
  steps.push({ kind: 'checkpoint' });
  return { seed, steps };
}

const REF = /^[mcr][0-9]{1,3}$/;

const isInt = (v: unknown, min: number, max: number): v is number => typeof v === 'number' && Number.isInteger(v) && v >= min && v <= max;

/**
 * Valida una journey letta da un file (cartella `regressioni/`): forma, tipi e limiti. Rifiuta ciò che non è un passo
 * noto, così un file di regressione non può far eseguire altro che le azioni di questo harness.
 */
export function validateJourney(raw: unknown): Journey {
  if (typeof raw !== 'object' || raw === null) throw new Error('journey: oggetto atteso');
  const o = raw as Record<string, unknown>;
  if (!isInt(o.seed, 1, MAX_SEED)) throw new Error('journey: seme non valido');
  if (!Array.isArray(o.steps) || o.steps.length < 1 || o.steps.length > MAX_STEPS * 2) throw new Error('journey: elenco dei passi non valido');
  const steps: Step[] = o.steps.map((s, i): Step => {
    const bad = (why: string): never => {
      throw new Error(`journey: passo ${i + 1} non valido (${why})`);
    };
    if (typeof s !== 'object' || s === null) return bad('oggetto atteso');
    const p = s as Record<string, unknown>;
    const ref = (v: unknown, prefix: string): string => (typeof v === 'string' && REF.test(v) && v.startsWith(prefix) ? v : bad(`riferimento ${prefix}…`));
    const reason = (v: unknown): Reason => ((REASONS as readonly unknown[]).includes(v) ? (v as Reason) : bad('motivo'));
    switch (p.kind) {
      case 'registerMember':
        return { kind: 'registerMember', ref: ref(p.ref, 'm') };
      case 'credit':
      case 'debit':
        return isInt(p.amount, 1, MAX_AMOUNT)
          ? { kind: p.kind, member: ref(p.member, 'm'), amount: p.amount, reason: reason(p.reason) }
          : bad('importo');
      case 'overdraw':
      case 'readWallet':
        return { kind: p.kind, member: ref(p.member, 'm') };
      case 'createCategory':
        return { kind: 'createCategory', ref: ref(p.ref, 'c') };
      case 'renameCategory':
        return { kind: 'renameCategory', category: ref(p.category, 'c') };
      case 'createReward': {
        const stock = p.stock === null ? null : isInt(p.stock, 0, 100_000) ? p.stock : bad('stock');
        return { kind: 'createReward', ref: ref(p.ref, 'r'), category: p.category === null ? null : ref(p.category, 'c'), stock };
      }
      case 'restockReward':
        return isInt(p.stock, 0, 100_000) ? { kind: 'restockReward', reward: ref(p.reward, 'r'), stock: p.stock } : bad('stock');
      case 'checkpoint':
        return { kind: 'checkpoint' };
      default:
        return bad('tipo sconosciuto');
    }
  });
  return { seed: o.seed, steps };
}

/** Riga di testo di un passo per il registro: solo tipo, riferimenti locali e numeri (nessun dato personale). */
export function describeStep(s: Step): string {
  switch (s.kind) {
    case 'registerMember':
      return `registerMember ${s.ref}`;
    case 'credit':
    case 'debit':
      return `${s.kind} ${s.member} ${s.amount} ${s.reason}`;
    case 'overdraw':
    case 'readWallet':
      return `${s.kind} ${s.member}`;
    case 'createCategory':
      return `createCategory ${s.ref}`;
    case 'renameCategory':
      return `renameCategory ${s.category}`;
    case 'createReward':
      return `createReward ${s.ref} categoria=${s.category ?? '-'} stock=${s.stock ?? 'illimitato'}`;
    case 'restockReward':
      return `restockReward ${s.reward} stock=${s.stock}`;
    case 'checkpoint':
      return 'checkpoint (invarianti)';
  }
}

/** Suffisso unico per i codici creati dalla journey (alfanumerico maiuscolo): `J<seme in base 36>`. */
export function journeyTag(seed: number): string {
  return `J${seed.toString(36).toUpperCase()}`;
}
