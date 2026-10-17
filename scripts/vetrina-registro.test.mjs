// Test di scripts/vetrina-registro.mjs (F2-DIST-09, ADR-050, Q-729): estratto, redazione dei segreti e corpo del
// commento. Marcatori PEM e token d'esempio si compongono a runtime: nessuna stringa letterale che somigli a un segreto.
// Uso: node --test scripts/vetrina-registro.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { corpo, estratto, redigi, MAX_CARATTERI, MAX_RIGHE } from './vetrina-registro.mjs';

const SCRIPT = path.join(path.dirname(new URL(import.meta.url).pathname), 'vetrina-registro.mjs');

test('estratto: solo l\'ultima esecuzione', () => {
  const log = ['== avvio della vetrina 1', 'vecchio', '== avvio della vetrina 2', 'nuovo', 'fine'].join('\n');
  assert.equal(estratto(log), '== avvio della vetrina 2\nnuovo\nfine');
  assert.equal(estratto('senza intestazione\nriga'), 'senza intestazione\nriga');
});

test('estratto: i log dei container sono omessi, il resto della diagnosi resta', () => {
  const log = [
    '== avvio della vetrina x', 'prima',
    '== diagnosi dei container della vetrina y', 'idp\tUp',
    '-- ultime righe di lh-idp', 'riga 1 del container', 'riga 2 del container',
    '-- ultime righe di lh-proxy', 'riga del proxy',
    '== fine della diagnosi', 'dopo',
  ].join('\n');
  const e = estratto(log);
  assert.match(e, /\[log del container lh-idp omesso\]\n\[log del container lh-proxy omesso\]\n== fine della diagnosi\ndopo$/);
  assert.doesNotMatch(e, /riga 1 del container|riga del proxy/);
  assert.match(e, /idp\tUp/);
});

test('estratto: al massimo le ultime 200 righe', () => {
  const log = ['== avvio della vetrina x', ...Array.from({ length: 500 }, (_, i) => `riga ${i}`)].join('\n');
  const righe = estratto(log).split('\n');
  assert.equal(righe.length, MAX_RIGHE);
  assert.equal(righe.at(-1), 'riga 499');
  assert.equal(righe[0], 'riga 300');
});

test('redigi: password=, secret=, token=, key= e la forma JSON, senza distinguere le maiuscole', () => {
  const t = redigi('Password=abc12 passwd=x1 SECRET=s3 token=t4 api_key=k5 {"Client_Secret": "v6", "password":"v7"} user=anna', {});
  assert.equal(t, `Password=[omesso] passwd=[omesso] SECRET=[omesso] token=[omesso] api_key=[omesso] {"Client_Secret": "[omesso]", "password":"[omesso]"} user=anna`);
});

test('redigi: intestazioni Authorization e Cookie', () => {
  const t = redigi('Authorization: Bearer abc\ncookie: sid=1; b=2\ncurl -H "Authorization: Basic xyz" url', {});
  assert.equal(t, 'Authorization: [omesso]\ncookie: [omesso]\ncurl -H "Authorization: [omesso]" url');
});

test('redigi: token GitHub di ogni tipo', () => {
  const token = {
    ghp: 'gh' + 'p_' + 'x'.repeat(36),
    gho: 'gh' + 'o_' + 'x'.repeat(36),
    ghs: 'gh' + 's_' + 'x'.repeat(36),
    ghu: 'gh' + 'u_' + 'x'.repeat(36),
    pat: 'github' + '_pat_' + 'x'.repeat(30),
  };
  for (const [tipo, tok] of Object.entries(token)) assert.equal(redigi(`t ${tok} f`, {}), 't [omesso] f', tipo);
});

test('redigi: JWT', () => {
  const jwt = ['ey' + 'J' + 'a'.repeat(12), 'b'.repeat(14), 'c'.repeat(10)].join('.');
  assert.equal(redigi(`id ${jwt} fine`, {}), 'id [omesso] fine');
});

test('redigi: blocchi PEM, anche non chiusi', () => {
  const inizio = '-----BEGIN ' + 'PRIVATE KEY-----';
  const fine = '-----END ' + 'PRIVATE KEY-----';
  assert.equal(redigi(`a\n${inizio}\nQUJD\nREVG\n${fine}\nb`, {}), 'a\n[omesso]\nb');
  assert.equal(redigi(`a\n${inizio}\nQUJD`, {}), 'a\n[omesso]');
});

test('redigi: base64 ed esadecimali contigui di almeno 32 caratteri', () => {
  assert.equal(redigi(`x ${'a1'.repeat(16)} y`, {}), 'x [omesso] y');
  assert.equal(redigi(`x ${'QUJD'.repeat(10)}== y`, {}), 'x [omesso] y');
  assert.equal(redigi(`x ${'a'.repeat(31)} y`, {}), `x ${'a'.repeat(31)} y`);
});

test('redigi: valori delle variabili d\'ambiente segrete, se lunghi almeno 6 caratteri', () => {
  const env = { LH_VETRINA_MASTER_ADMIN_PASSWORD: 'pw.lunga!', GITHUB_TOKEN: 'tok.corto', GH_TOKEN: 'abc' };
  assert.equal(redigi('a pw.lunga! b tok.corto c abc d', env), 'a [omesso] b [omesso] c abc d');
  assert.equal(redigi('a pw.lunga! b', {}), 'a pw.lunga! b');
});

test('corpo: titolo, tabella e blocco text', () => {
  const ok = corpo({ esito: 'ok', passo: 'fine', codice: 0, codespace: 'cs-1', head: 'abc1234', immagine: 'ghcr.io/x/y:build-abc', inizio: 'i', fine: 'f', log: '== avvio della vetrina z\nvetrina pronta' });
  assert.match(ok, /^## ✅ Avvio riuscito\n/);
  for (const v of ['| Passo | fine |', '| Codice d\'uscita | 0 |', '| Codespace | cs-1 |', '| Commit | abc1234 |', '| Immagine | ghcr.io/x/y:build-abc |', '| Inizio | i |', '| Fine | f |']) assert.ok(ok.includes(v), v);
  assert.match(ok, /```text\n== avvio della vetrina z\nvetrina pronta\n```\n$/);
  const ko = corpo({ esito: 'fallito', passo: 'porte pubbliche', codice: 1, log: '' });
  assert.match(ko, /^## ❌ Avvio fallito\n/);
});

test('corpo: il log passa da redigi', () => {
  const c = corpo({ esito: 'fallito', log: 'password=abc123 ok' });
  assert.match(c, /password=\[omesso\] ok/);
  assert.doesNotMatch(c, /abc123/);
});

test('corpo: recinzione più lunga se il log contiene già ```', () => {
  const c = corpo({ esito: 'ok', log: 'a\n```js\nb\n```\n````\nc' });
  assert.match(c, /\n`````text\n/);
  assert.match(c, /\n`````\n$/);
});

test('corpo: resta sotto i 60000 caratteri e tiene la coda del log', () => {
  const log = Array.from({ length: 200 }, (_, i) => `riga ${i} ${'z '.repeat(400)}`).join('\n');
  const c = corpo({ esito: 'fallito', passo: 'p', codice: 1, log });
  assert.ok(c.length < MAX_CARATTERI, String(c.length));
  assert.match(c, /riga 199 /);
  assert.match(c, /registro troncato/);
});

test('CLI: stampa il corpo su stdout', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'registro-'));
  const f = path.join(dir, 'avvio.log');
  fs.writeFileSync(f, '== avvio della vetrina x\ntoken=abc999\n');
  const r = spawnSync(process.execPath, [SCRIPT, '--log', f, '--esito', 'fallito', '--passo', 'porte pubbliche', '--codice', '1', '--codespace', 'cs'], { encoding: 'utf8' });
  assert.equal(r.status, 0, r.stderr);
  assert.match(r.stdout, /## ❌ Avvio fallito/);
  assert.match(r.stdout, /\| Passo \| porte pubbliche \|/);
  assert.match(r.stdout, /token=\[omesso\]/);
  assert.equal(spawnSync(process.execPath, [SCRIPT, '--esito', 'boh']).status, 2);
  fs.rmSync(dir, { recursive: true });
});
