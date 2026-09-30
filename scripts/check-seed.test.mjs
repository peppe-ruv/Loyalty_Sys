import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, cpSync, rmSync, symlinkSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { createSeedValidator, formatAjvError } from './seed-schema.mjs';

const here = dirname(fileURLToPath(import.meta.url));

// Schema 2020-12 con annotazioni x-lh-* e prefixItems, come i contratti del repo (docs/05 §9).
const membersSchema = {
  $schema: 'https://json-schema.org/draft/2020-12/schema',
  type: 'array',
  items: {
    type: 'object',
    required: ['id', 'email'],
    properties: {
      id: { type: 'string', pattern: '^MBR-\\d+$' },
      email: { type: 'string', format: 'email', 'x-lh-pii': true },
      point: { type: 'array', prefixItems: [{ type: 'number' }, { type: 'number' }], items: false },
    },
    additionalProperties: false,
  },
};

test('seed valido: nessun errore, anche con schema 2020-12 e x-lh-pii', async () => {
  const v = await createSeedValidator({ 'members.schema.json': membersSchema });
  assert.deepEqual(v.validate('members.schema.json', [{ id: 'MBR-1', email: 'a@example.org', point: [1, 2] }]), []);
});

test('seed non valido: il messaggio contiene il percorso (instancePath)', async () => {
  const v = await createSeedValidator({ 'members.schema.json': membersSchema });
  const problems = v.validate('members.schema.json', [{ id: 'MBR-1', email: 'non-una-email' }, { id: 'X' }]);
  assert.ok(problems.some((p) => p.startsWith('/0/email ')), problems.join('\n'));
  assert.ok(problems.some((p) => p.startsWith('/1/id ')), problems.join('\n'));
  assert.ok(problems.some((p) => p.includes('email')), 'required mancante segnalato');
});

test('errore sulla radice: percorso "/" invece di vuoto', async () => {
  const v = await createSeedValidator({ 'members.schema.json': membersSchema });
  const problems = v.validate('members.schema.json', { not: 'an array' });
  assert.equal(problems.length, 1);
  assert.match(problems[0], /^\/ must be array/);
  assert.equal(formatAjvError({ instancePath: '', message: 'm', params: {} }), '/ m');
});

test('schema non compilabile: validate lancia', async () => {
  const v = await createSeedValidator({ 'bad.schema.json': { type: 'non-un-tipo' } });
  assert.throws(() => v.validate('bad.schema.json', []));
});

test('$ref tra file di _schemas/ si risolve', async () => {
  const v = await createSeedValidator({
    'a.schema.json': { $schema: 'https://json-schema.org/draft/2020-12/schema', type: 'array', items: { $ref: 'b.schema.json' } },
    'b.schema.json': { type: 'string', minLength: 2 },
  });
  assert.deepEqual(v.validate('a.schema.json', ['ok']), []);
  assert.equal(v.validate('a.schema.json', ['x']).length, 1);
});

// Esecuzione dello script su una copia di scripts/ + seed/ minimale: senza schemi e con schemi.
function runCheck(setup) {
  const root = mkdtempSync(join(tmpdir(), 'check-seed-'));
  try {
    mkdirSync(join(root, 'scripts'));
    for (const f of ['check-seed.mjs', 'seed-schema.mjs']) cpSync(join(here, f), join(root, 'scripts', f));
    symlinkSync(join(here, 'node_modules'), join(root, 'scripts', 'node_modules'), 'dir');
    writeFileSync(join(root, 'scripts', 'package.json'), '{"type":"module"}');
    mkdirSync(join(root, 'seed', '_schemas'), { recursive: true });
    setup(root);
    const r = spawnSync(process.execPath, [join(root, 'scripts', 'check-seed.mjs')], { encoding: 'utf8' });
    return { code: r.status, out: r.stdout + r.stderr };
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
}

test('script: senza schemi i seed passano senza caricare ajv', () => {
  const r = runCheck((root) => writeFileSync(join(root, 'seed', 'members.json'), '[{"id":"MBR-1"}]'));
  assert.equal(r.code, 0, r.out);
});

test('script: seed non conforme allo schema fallisce con il percorso; conforme passa', () => {
  const setup = (data) => (root) => {
    writeFileSync(join(root, 'seed', 'members.json'), JSON.stringify(data));
    writeFileSync(join(root, 'seed', '_schemas', 'members.schema.json'), JSON.stringify(membersSchema));
  };
  const ko = runCheck(setup([{ id: 'MBR-1', email: 'x' }]));
  assert.equal(ko.code, 1, ko.out);
  assert.match(ko.out, /members\.json: errore schema — \/0\/email /);
  const ok = runCheck(setup([{ id: 'MBR-1', email: 'a@example.org' }]));
  assert.equal(ok.code, 0, ok.out);
});

test('script: schema JSON malformato e schema non compilabile sono errori', () => {
  const malformed = runCheck((root) => {
    writeFileSync(join(root, 'seed', 'members.json'), '[]');
    writeFileSync(join(root, 'seed', '_schemas', 'members.schema.json'), '{ non json');
  });
  assert.equal(malformed.code, 1, malformed.out);
  assert.match(malformed.out, /schema non valido/);
  const uncompilable = runCheck((root) => {
    writeFileSync(join(root, 'seed', 'members.json'), '[]');
    writeFileSync(join(root, 'seed', '_schemas', 'members.schema.json'), '{"type":"non-un-tipo"}');
  });
  assert.equal(uncompilable.code, 1, uncompilable.out);
  assert.match(uncompilable.out, /errore di compilazione schema/);
});
