import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import { MAX_SEED, deriveSeed, generateJourney, journeyTag, mulberry32, parsePositiveInt, parseSeed, validateJourney } from './generator.ts';
import { loadRegressions, saveRegression } from './regressioni.ts';

// Prove del generatore senza stack (ADR-053 decisione 4, Q-693): `pnpm test:unit`. Determinismo, validità delle
// sequenze, lettura dei semi e formato dei file di regressione.

const SEEDS = [1, 2, 7, 42, 1234, 99999, 0xdeadbeef, MAX_SEED];

test('lo stesso seme dà gli stessi passi, semi diversi sequenze diverse', () => {
  for (const seed of SEEDS) {
    assert.deepEqual(generateJourney(seed, { steps: 12 }), generateJourney(seed, { steps: 12 }));
  }
  const all = new Set(SEEDS.map((s) => JSON.stringify(generateJourney(s, { steps: 12 }).steps)));
  assert.equal(all.size, SEEDS.length);
});

test('mulberry32 è deterministico e resta in [0, 1)', () => {
  const a = mulberry32(5);
  const b = mulberry32(5);
  for (let i = 0; i < 1000; i++) {
    const x = a();
    assert.equal(x, b());
    assert.ok(x >= 0 && x < 1);
  }
});

test('le journey sono valide: riferimenti noti, debiti entro il saldo, checkpoint finale', () => {
  for (let i = 0; i < 300; i++) {
    const seed = deriveSeed(20261003, i);
    const { steps } = generateJourney(seed, { steps: 8 });
    assert.equal(steps[0]!.kind, 'registerMember');
    assert.equal(steps.at(-1)!.kind, 'checkpoint');
    assert.equal(steps.filter((s) => s.kind !== 'checkpoint').length, 8);
    const balances = new Map<string, number>();
    const cats = new Set<string>();
    const rewards = new Set<string>();
    for (const s of steps) {
      switch (s.kind) {
        case 'registerMember':
          assert.ok(!balances.has(s.ref));
          balances.set(s.ref, 0);
          break;
        case 'credit':
          assert.ok(balances.has(s.member));
          balances.set(s.member, balances.get(s.member)! + s.amount);
          break;
        case 'debit':
          assert.ok(balances.has(s.member));
          assert.ok(s.amount >= 1 && s.amount <= balances.get(s.member)!, `debito ${s.amount} oltre il saldo (seme ${seed})`);
          balances.set(s.member, balances.get(s.member)! - s.amount);
          break;
        case 'overdraw':
        case 'readWallet':
          assert.ok(balances.has(s.member));
          break;
        case 'createCategory':
          cats.add(s.ref);
          break;
        case 'renameCategory':
          assert.ok(cats.has(s.category));
          break;
        case 'createReward':
          assert.ok(s.category === null || cats.has(s.category));
          rewards.add(s.ref);
          break;
        case 'restockReward':
          assert.ok(rewards.has(s.reward));
          break;
        case 'checkpoint':
          break;
      }
    }
    // Uscita da JSON e validazione: la journey salvata è quella eseguita.
    assert.deepEqual(validateJourney(JSON.parse(JSON.stringify({ seed, steps }))), { seed, steps });
  }
});

test('su molti semi compaiono tutti i tipi di passo', () => {
  const kinds = new Set<string>();
  for (let i = 0; i < 300; i++) for (const s of generateJourney(deriveSeed(1, i), { steps: 12 }).steps) kinds.add(s.kind);
  assert.deepEqual(
    [...kinds].sort(),
    ['checkpoint', 'createCategory', 'createReward', 'credit', 'debit', 'overdraw', 'readWallet', 'registerMember', 'renameCategory', 'restockReward'],
  );
});

test('i semi derivati sono in intervallo e senza collisioni su 5000 journey', () => {
  const seen = new Set<number>();
  for (let i = 0; i < 5000; i++) {
    const s = deriveSeed(123456789, i);
    assert.ok(Number.isInteger(s) && s >= 1 && s <= MAX_SEED);
    seen.add(s);
  }
  assert.equal(seen.size, 5000);
});

test('parseSeed e parsePositiveInt rifiutano valori non validi', () => {
  assert.equal(parseSeed('42'), 42);
  assert.equal(parseSeed(' 4294967295 '), MAX_SEED);
  for (const bad of [undefined, '', '0', '-1', '4294967296', '1.5', '1e3', '0x10', 'abc', '1; rm -rf /', '$(id)']) assert.equal(parseSeed(bad), null, String(bad));
  assert.equal(parsePositiveInt(undefined, 20, 5000, 'X'), 20);
  assert.equal(parsePositiveInt('500', 20, 5000, 'X'), 500);
  for (const bad of ['0', '5001', 'x', '1 2', '-3']) assert.throws(() => parsePositiveInt(bad, 20, 5000, 'X'), /X/);
});

test('validateJourney rifiuta passi sconosciuti o fuori limite', () => {
  const ok = { seed: 3, steps: [{ kind: 'registerMember', ref: 'm1' }, { kind: 'checkpoint' }] };
  assert.doesNotThrow(() => validateJourney(ok));
  const bads: unknown[] = [
    null,
    { seed: 0, steps: ok.steps },
    { seed: 3, steps: [] },
    { seed: 3, steps: [{ kind: 'exec', cmd: 'ls' }] },
    { seed: 3, steps: [{ kind: 'credit', member: 'm1', amount: 0, reason: 'TEST' }] },
    { seed: 3, steps: [{ kind: 'credit', member: 'm1', amount: 5, reason: 'ALTRO' }] },
    { seed: 3, steps: [{ kind: 'credit', member: '../x', amount: 5, reason: 'TEST' }] },
    { seed: 3, steps: [{ kind: 'createReward', ref: 'r1', category: 'm1', stock: 3 }] },
    { seed: 3, steps: [{ kind: 'restockReward', reward: 'r1', stock: -1 }] },
  ];
  for (const b of bads) assert.throws(() => validateJourney(b), /journey/);
});

test('journeyTag è alfanumerico maiuscolo', () => {
  assert.match(journeyTag(MAX_SEED), /^J[0-9A-Z]+$/);
});

test('regressioni: cartella vuota = nessun test; salva e rilegge un seme; file malformato = errore', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'lh-regr-'));
  try {
    assert.deepEqual(loadRegressions(dir), []);
    assert.deepEqual(loadRegressions(path.join(dir, 'non-esiste')), []);
    const journey = generateJourney(777, { steps: 6 });
    const saved = saveRegression(journey, 'saldo   letto 1,\natteso 2', dir);
    assert.equal(path.basename(saved), '777.json');
    const body = JSON.parse(fs.readFileSync(saved, 'utf8')) as { seed: number; failure: string };
    assert.equal(body.seed, 777);
    assert.equal(body.failure, 'saldo letto 1, atteso 2');
    assert.deepEqual(loadRegressions(dir), [{ file: '777.json', journey }]);
    assert.equal(saveRegression(journey, 'altro', dir), saved);
    fs.writeFileSync(path.join(dir, 'rotto.json'), '{');
    assert.throws(() => loadRegressions(dir), /rotto\.json/);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('la cartella delle regressioni del repository carica senza errori', () => {
  assert.ok(Array.isArray(loadRegressions()));
});
