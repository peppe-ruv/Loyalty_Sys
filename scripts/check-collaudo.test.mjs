import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {
  checkCopione,
  checkReport,
  checkRepository,
  collectDefinedIds,
  parseCopione,
  reportRowIds,
  specTokens,
} from './check-collaudo.mjs';

const DEFINED = new Set(['HUB-01', 'BO-01', 'PT-01', 'F2-QA-06', 'ADR-052', 'Q-680', 'F-AUD-01']);

function step(id, { area = id.split('-')[1], extra = '', specs = '`BO-01`', skip = [] } = {}) {
  const fields = {
    'Utente di test': '- **Utente di test**: `marta.admin`.',
    Precondizioni: '- **Precondizioni**: nessuna.',
    Azioni: '- **Azioni**:\n  1. Apri la pagina.',
    'Esito atteso': '- **Esito atteso**: si apre.',
    Evidenze: '- **Evidenze**: screenshot.',
    Specifiche: `- **Specifiche**: ${specs}.`,
  };
  const body = Object.entries(fields)
    .filter(([k]) => !skip.includes(k))
    .map(([, v]) => v)
    .join('\n');
  return { area, text: `### ${id} — Titolo\n\n${body}\n${extra}\n` };
}

function copione(steps) {
  const byArea = new Map();
  for (const s of steps) byArea.set(s.area, (byArea.get(s.area) ?? '') + s.text);
  return [...byArea].map(([area, text]) => `## Area ${area} — x\n\n${text}`).join('\n');
}

const FULL = [
  step('CL-HUB-001', { specs: '`HUB-01`' }),
  step('CL-BO-001'),
  step('CL-PT-001', { specs: '`PT-01`, `F2-QA-06`' }),
  step('CL-KC-001', { specs: '`ADR-052`, `Q-680`' }),
  step('CL-AUD-001', { specs: '`F-AUD-01`' }),
];

test('un copione completo e coerente non ha problemi', () => {
  assert.deepEqual(checkCopione(copione(FULL), DEFINED), []);
});

test('parseCopione legge ID, sezione, titolo e campi (anche su più righe)', () => {
  const [first] = parseCopione(copione(FULL));
  assert.equal(first.id, 'CL-HUB-001');
  assert.equal(first.section, 'HUB');
  assert.equal(first.title, 'Titolo');
  assert.match(first.fields['Azioni'], /^1\. Apri la pagina\.$/m);
});

test('ID con formato sbagliato, duplicato o fuori sezione', () => {
  const bad = copione([...FULL, step('CL-BO-1', { area: 'BO' }), step('CL-BO-001', { area: 'BO' }), step('CL-PT-002', { area: 'KC' })]);
  const problems = checkCopione(bad, DEFINED).join('\n');
  assert.match(problems, /CL-BO-1.*ID non valido/);
  assert.match(problems, /CL-BO-001.*duplicato/);
  assert.match(problems, /CL-PT-002.*sezione «Area KC»/);
});

test('i numeri di un\'area devono crescere', () => {
  const bad = copione([...FULL, step('CL-BO-005', { area: 'BO' }), step('CL-BO-003', { area: 'BO' })]);
  assert.match(checkCopione(bad, DEFINED).join('\n'), /CL-BO-003.*devono crescere/);
});

test('ogni area deve avere almeno un passo', () => {
  const problems = checkCopione(copione(FULL.slice(0, 4)), DEFINED);
  assert.deepEqual(problems, ["l'area AUD non ha nessun passo"]);
});

test('un campo obbligatorio mancante o vuoto è un problema', () => {
  const bad = copione([step('CL-HUB-001', { skip: ['Evidenze'] }), ...FULL.slice(1)]);
  assert.match(checkCopione(bad, DEFINED).join('\n'), /CL-HUB-001.*manca il campo «Evidenze»/);
  const empty = copione([{ area: 'HUB', text: step('CL-HUB-001').text.replace('screenshot.', '') }, ...FULL.slice(1)]);
  assert.match(checkCopione(empty, DEFINED).join('\n'), /CL-HUB-001.*«Evidenze» è vuoto/);
});

test('le azioni devono essere un elenco numerato', () => {
  const bad = copione([{ area: 'HUB', text: step('CL-HUB-001').text.replace('1. Apri la pagina.', '- Apri la pagina.') }, ...FULL.slice(1)]);
  assert.match(checkCopione(bad, DEFINED).join('\n'), /elenco numerato/);
});

test('serve almeno un ID di specifica, riconosciuto e definito in docs/', () => {
  const none = copione([step('CL-HUB-001', { specs: 'niente' }), ...FULL.slice(1)]);
  assert.match(checkCopione(none, DEFINED).join('\n'), /CL-HUB-001.*non cita nessun ID/);
  const unknown = copione([step('CL-HUB-001', { specs: '`XYZ-01`' }), ...FULL.slice(1)]);
  assert.match(checkCopione(unknown, DEFINED).join('\n'), /«XYZ-01» non è un ID di specifica ammesso/);
  const missing = copione([step('CL-HUB-001', { specs: '`BO-99`, `Q-999`' }), ...FULL.slice(1)]);
  const problems = checkCopione(missing, DEFINED).join('\n');
  assert.match(problems, /«BO-99» non è definito in docs/);
  assert.match(problems, /«Q-999» non è definito in docs/);
});

test('TB- si controlla solo nella forma: non si verifica in docs/', () => {
  const ok = copione([step('CL-HUB-001', { specs: '`TB-CMP-042`' }), ...FULL.slice(1)]);
  assert.deepEqual(checkCopione(ok, DEFINED), []);
  const bad = copione([step('CL-HUB-001', { specs: '`TB-CMP-42`' }), ...FULL.slice(1)]);
  assert.match(checkCopione(bad, DEFINED).join('\n'), /«TB-CMP-42» non è un ID/);
});

test('un riferimento a un passo inesistente è un problema', () => {
  const bad = copione([step('CL-HUB-001', { extra: 'Dipende da CL-BO-077.' }), ...FULL.slice(1)]);
  assert.match(checkCopione(bad, DEFINED).join('\n'), /cita CL-BO-077, che non esiste/);
});

test('ogni scrittura di configurazione deve essere citata da un passo AUD', () => {
  const write = '- **Scrittura di configurazione**: sì.';
  const orphan = copione([FULL[0], step('CL-BO-001', { extra: write }), ...FULL.slice(2)]);
  assert.match(checkCopione(orphan, DEFINED).join('\n'), /CL-BO-001.*nessun passo AUD lo cita/);
  const covered = copione([FULL[0], step('CL-BO-001', { extra: write }), ...FULL.slice(2, 4), step('CL-AUD-001', { specs: '`F-AUD-01`', extra: 'Verifica CL-BO-001.' })]);
  assert.deepEqual(checkCopione(covered, DEFINED), []);
  const no = copione([FULL[0], step('CL-BO-001', { extra: '- **Scrittura di configurazione**: no.' }), ...FULL.slice(2)]);
  assert.deepEqual(checkCopione(no, DEFINED), []);
  const odd = copione([FULL[0], step('CL-BO-001', { extra: '- **Scrittura di configurazione**: forse.' }), ...FULL.slice(2)]);
  assert.match(checkCopione(odd, DEFINED).join('\n'), /deve valere «sì» o «no»/);
});

test('il copione non può contenere credenziali', () => {
  const secret = ['Aurora', 'Operatori', '26!'].join('-');
  const bad = copione([step('CL-HUB-001', { extra: `Password ${secret}.` }), ...FULL.slice(1)]);
  assert.match(checkCopione(bad, DEFINED).join('\n'), /contiene una password della vetrina/);
  const seed = copione([step('CL-HUB-001', { extra: `Seme ${'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'.slice(0, 32)}.` }), ...FULL.slice(1)]);
  assert.match(checkCopione(seed, DEFINED).join('\n'), /seme dell'OTP/);
});

test('specTokens legge solo i token tra apici inversi', () => {
  assert.deepEqual(specTokens('`BO-01`, testo, `F2-QA-06`'), ['BO-01', 'F2-QA-06']);
  assert.deepEqual(specTokens(undefined), []);
});

test('collectDefinedIds: titoli, celle di tabella e punti elenco sono definizioni, le citazioni no', () => {
  const docs = [
    '### BO-01 — Dashboard\n## 8. HUB-01 — Demo Hub\n| F2-QA-06 | Collaudo | P1 |\n| `Q-680` | domanda |\n* **Q-681**: x\n',
    'Vedi BO-77 e F-ZZZ-09 nel testo.\n| altro | PT-55 |\n',
  ];
  const defined = collectDefinedIds(docs);
  assert.deepEqual([...defined].sort(), ['BO-01', 'F2-QA-06', 'HUB-01', 'Q-680', 'Q-681']);
});

test('righe del rapporto: la prima colonna con un ID di passo', () => {
  const text = '| Passo | Esito |\n| --- | --- |\n| CL-HUB-001 | OK |\n| altro | CL-BO-001 |\n';
  assert.deepEqual(reportRowIds(text), ['CL-HUB-001']);
});

test('checkReport: il modello è completo, un rapporto cita solo passi esistenti', () => {
  const ids = ['CL-HUB-001', 'CL-BO-001'];
  assert.deepEqual(checkReport('| CL-HUB-001 | |\n| CL-BO-001 | |\n', ids, 'modello', true), []);
  assert.match(checkReport('| CL-HUB-001 | |\n', ids, 'modello', true).join('\n'), /manca la riga del passo CL-BO-001/);
  assert.match(checkReport('| CL-HUB-001 | |\n| CL-HUB-001 | |\n', ids, 'r', false).join('\n'), /ripetuta/);
  assert.match(checkReport('| CL-PT-009 | |\n', ids, 'r', false).join('\n'), /nessun passo del copione/);
  assert.deepEqual(checkReport('| CL-HUB-001 | |\n', ids, 'r', false), []);
});

test('checkRepository: file di accompagnamento mancanti, poi tutto a posto', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'collaudo-'));
  try {
    const dir = path.join(root, 'e2e', 'collaudo');
    fs.mkdirSync(dir, { recursive: true });
    fs.mkdirSync(path.join(root, 'docs'), { recursive: true });
    fs.writeFileSync(
      path.join(root, 'docs', '07.md'),
      '## 8. HUB-01 — Demo Hub\n### BO-01 — Dashboard\n### PT-01 — Home\n| F2-QA-06 | x |\n### ADR-052 — x\n| Q-680 | x |\n| F-AUD-01 | x |\n',
    );
    fs.writeFileSync(path.join(dir, 'copione.md'), copione(FULL));
    const missing = checkRepository(root).problems.join('\n');
    assert.match(missing, /manca e2e\/collaudo\/AVVIO\.md/);
    assert.match(missing, /manca e2e\/collaudo\/rapporto-modello\.md/);

    fs.writeFileSync(path.join(dir, 'AVVIO.md'), 'Apri una issue con l\'etichetta `collaudo`.\n');
    fs.writeFileSync(path.join(dir, 'rapporto-modello.md'), FULL.map((s) => `| ${s.text.match(/CL-[A-Z]+-\d+/)[0]} | |`).join('\n'));
    assert.deepEqual(checkRepository(root).problems, []);

    fs.mkdirSync(path.join(dir, 'rapporti'));
    fs.writeFileSync(path.join(dir, 'rapporti', 'v1.0.0.md'), '| CL-BO-099 | OK |\n');
    assert.match(checkRepository(root).problems.join('\n'), /v1\.0\.0\.md: la riga CL-BO-099/);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test('il copione del repository è valido', () => {
  const root = path.resolve(import.meta.dirname, '..');
  assert.deepEqual(checkRepository(root).problems, []);
});
