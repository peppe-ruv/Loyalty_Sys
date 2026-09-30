// Test di scripts/docs-sync.mjs (M8.9b, F2-DOC-02): parser del backlog, escape MDX, link, DoR e DoD su una fixture,
// snapshot delle evidenze e verifica strutturale (--check) su un repository temporaneo.
// Uso: node --test scripts/docs-sync.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {
  storyRefs, featureRefs, screenRefs, tbDomains, tbRowRefs, proofPaths, escapeMdx, rewriteLinks,
  parseStory, parseBacklog, evaluateDoR, evaluateDoD, evaluate, epicSlug, updateDocsJson, parseWorkflows, loadContext,
  backlogGroup, readBacklogGroup, buildSnapshot, finalizeSnapshot, serializeSnapshot, snapshotProblems, renderPages,
  checkRoot, writeRoot, changedStories, SNAPSHOT,
} from './docs-sync.mjs';

const FIXTURE = `# 17 — Epic

## 3. Epic

| Epic | Obiettivo | Attori | Feature | Schermate | Servizi | TB |
|---|---|---|---|---|---|---|
| **E01 Ingresso eventi** | ogni fatto diventa un'azione | fonti | F-ING-01…10 | BO-26 | ingestion | TB-ING |
| **E-F2-DIST Distribuzione** | una sola immagine | installatore | F2-DIST-* | — | tutti | TB-DIST |
| **E-F2-AST Agente regolamento (P1)** | un regolamento | LEGAL | F2-AST-* | BO-33 | assistant | TB-AST |

## 4. Storie utente

### 4.0 Definition of Ready e Definition of Done

Testo delle definizioni.

> **Nota:** la DoD non sostituisce la fetta.

### E01 — Ingresso eventi

#### US-E01-01 · Una fonte invia un'azione valida
*Come* sistema e-commerce, *voglio* inviare l'ordine, *così che* il cliente riceva i punti.
- **Contesto reale**: Marco (MBR-000002) paga online 130 €.
- **Tocca**: F-ING-01, F-ING-03 · \`POST /v1/events\` · BO-26.
- **Decisioni**: tipo sconosciuto → 422 \`VALIDATION\`.
- **Criteri**:
  1. Dato un evento valido per MBR-000002, quando lo invio, allora 202 \`ACCEPTED\`.
  2. ✗ Dato un tipo sconosciuto, allora 422 e nessuna pubblicazione.
- **Testbook**: TB-ING (da coprire).

#### US-E01-02 · Una regola senza codice ⛔
*Come* ADMIN, *voglio* spegnere una fonte, *così che* un sistema che sbaglia si fermi.
- **Tocca**: F-ING-05 · BO-09.
- **Criteri**:
  1. Dato MARKETING, allora 403.
- **Testbook**: scoperta.

#### US-E01-14 · Invio batch — fuori perimetro PoC
*Come* sistema, *voglio* inviare 100 eventi.
- **Tocca**: F-ING-10 (P2).
- **Criteri**: nessuno nel PoC.
- **Testbook**: fuori perimetro PoC.

### Fase 2 — storie delle feature P0

Introduzione di Fase 2.

### E-F2-DIST — Distribuzione

#### US-F2-DIST-04 · Modalità embedded
*Come* chi valuta, *voglio* un solo container, *così che* provi senza installare nulla.
- **Tocca**: F2-DIST-04 · ADR-037.
- **Criteri**: da scrivere con la fetta.
- **Testbook**: TB-DIST (da scrivere con M12).

## 5. Foresta delle decisioni

## 6. Matrice di copertura

### 6.1 Storia → feature → dominio → stato

| Storia | Feature | Dominio TB | Stato |
|---|---|---|---|
| US-E01-02 | F-ING-05, docs/08 §2 | TB-ING | pianificata |

### 6.2 Storie scoperte

## 7. Indicazioni per il testbook

| Nuovo dominio | Contenuto | Storie |
|---|---|---|
| **TB-PLT** (piattaforma) | outbox | US-E01-02 |
`;

const ctx = (over = {}) => ({
  features: new Map([
    ['F-ING-01', { status: 'x', prs: [] }],
    ['F-ING-03', { status: 'x', prs: [] }],
    ['F-ING-05', { status: 'x', prs: [108] }],
    ['F2-DIST-04', { status: ' ', prs: [] }],
  ]),
  outOfScope: new Set(['F-ING-10']),
  questions: new Map([['Q-129', 'DECISA'], ['Q-255', 'APERTA']]),
  rows: [
    { id: 'TB-ING-PIP-001', domain: 'TB-ING', file: 'x', stories: new Set(['US-E01-01']), subjects: new Set() },
    { id: 'TB-ING-SRC-002', domain: 'TB-ING', file: 'x', stories: new Set(), subjects: new Set(['F-ING-05']) },
  ],
  tbDomainsDocumented: new Set(['TB-ING', 'TB-PLT']),
  executed: new Set(['TB-ING-PIP-001']),
  seedTokens: new Set(['MBR-000002']),
  screens: new Map([['BO-26', 'docs/08'], ['BO-09', 'docs/08']]),
  exists: () => true,
  jobs: new Set(['guard', 'helm-kind', 'helm install (kind)']),
  jobsByFile: new Map([['.github/workflows/ci.yml', new Set(['guard', 'helm-kind', 'helm install (kind)'])]]),
  invoked: (p) => p === 'scripts/check-helm.mjs' || p === 'scripts/docs-sync.test.mjs',
  ...over,
});
const byId = (items) => Object.fromEntries(items.map((i) => [i.id, i.status]));

test('riferimenti: intervalli e barre si espandono', () => {
  assert.deepEqual([...storyRefs('US-E10-03…05, 13…14 e US-E06-05/08')],
    ['US-E10-03', 'US-E10-04', 'US-E10-05', 'US-E10-13', 'US-E10-14', 'US-E06-05', 'US-E06-08']);
  assert.deepEqual([...storyRefs('US-F2-DIST-01 nel 2026')], ['US-F2-DIST-01']);
  assert.deepEqual([...featureRefs('F-RWD-01…03 · F2-SEC-06/07 · RNF-01')], ['F-RWD-01', 'F-RWD-02', 'F-RWD-03', 'F2-SEC-06', 'F2-SEC-07']);
  assert.deepEqual([...screenRefs('BO-14/06/11, PT-18 e HUB-01')], ['BO-14', 'BO-06', 'BO-11', 'PT-18', 'HUB-01']);
  assert.deepEqual([...tbDomains('TB-ING/CMP/WAL · TB-WEB (da coprire)')], ['TB-ING', 'TB-CMP', 'TB-WAL', 'TB-WEB']);
  assert.deepEqual([...tbRowRefs('TB-ING-ETY-006, 047 · TB-ING-ETY-061…063')],
    ['TB-ING-ETY-006', 'TB-ING-ETY-047', 'TB-ING-ETY-061', 'TB-ING-ETY-062', 'TB-ING-ETY-063']);
  assert.deepEqual(proofPaths('prove: `scripts/check-helm.mjs`, `deploy/image/Dockerfile`, `web/lib/a.test.ts`'),
    ['scripts/check-helm.mjs', 'web/lib/a.test.ts']);
});

test('escape MDX fuori dal codice inline', () => {
  assert.equal(escapeMdx('valore {x} < 5 e `{{data.*}} <b>`'), 'valore \\{x\\} &lt; 5 e `{{data.*}} <b>`');
  assert.equal(escapeMdx('nessun carattere speciale'), 'nessun carattere speciale');
});

// Compilatore MDX opzionale: se @mdx-js/mdx e remark-gfm non sono installati (nessuna dipendenza aggiunta a
// scripts/package.json) i test che lo usano si saltano e restano le asserzioni sulle stringhe esatte.
let mdx = null;
try {
  const [{ compile }, gfm] = await Promise.all([import('@mdx-js/mdx'), import('remark-gfm')]);
  mdx = { compile, gfm: gfm.default };
} catch { /* dipendenze non installate */ }
const noMdx = mdx ? false : '@mdx-js/mdx / remark-gfm non installati';

/** Colonne di ogni riga di una tabella GFM secondo il parser (il compile MDX deve riuscire). */
async function tableColumns(md) {
  const [{ unified }, { default: parse }, { default: remarkMdx }] = await Promise.all([
    import('unified'), import('remark-parse'), import('remark-mdx'),
  ]);
  await mdx.compile(md, { remarkPlugins: [mdx.gfm] });
  const tree = unified().use(parse).use(remarkMdx).use(mdx.gfm).parse(md);
  const text = (n) => (n.children ? n.children.map(text).join('') : (n.value ?? ''));
  return tree.children[0].children.map((r) => ({ n: r.children.length, cells: r.children.map(text) }));
}

test('escape MDX: la barra rovesciata si raddoppia fuori dal codice, prima di `<`, `{`, `}`', () => {
  assert.equal(escapeMdx('a\\{b'), 'a\\\\\\{b'); // a\\\{b: barra letterale + graffa escapata
  assert.equal(escapeMdx('abc\\'), 'abc\\\\');
  assert.equal(escapeMdx('\\<b'), '\\\\&lt;b');
  assert.equal(escapeMdx('x\\|y'), 'x\\\\|y');
  assert.equal(escapeMdx('percorso `C:\\dir\\{x}` e \\{y\\}'), 'percorso `C:\\dir\\{x}` e \\\\\\{y\\\\\\}');
  assert.equal(escapeMdx('senza barre: {a} <b>'), 'senza barre: \\{a\\} &lt;b>');
});

test('escape MDX (celle): `\\` e `|` in un solo passaggio, anche dentro il codice inline', () => {
  const c = { cell: true };
  assert.equal(escapeMdx('x\\|y', c), 'x\\\\\\|y');
  assert.equal(escapeMdx('a|b', c), 'a\\|b');
  assert.equal(escapeMdx('abc\\', c), 'abc\\\\');
  assert.equal(escapeMdx('valore {x}\r\nnuova riga', c), 'valore \\{x\\} nuova riga');
  assert.equal(escapeMdx('`a|b` e `a\\|b` e `C:\\dir`', c), '`a\\|b` e `a\\\\\\|b` e `C:\\dir`');
  // il testo già escapato non viene toccato una seconda volta: `\{` non esiste nell'input, lo produce l'escape
  assert.equal(escapeMdx('{a}|{b}', c), '\\{a\\}\\|\\{b\\}');
});

test('MDX compila e le tabelle mantengono le colonne con barre rovesciate e pipe', { skip: noMdx }, async () => {
  const inputs = ['a\\{b', 'x\\|y', 'x\\\\|y', 'abc\\', '\\<b', '`a\\|b` e `C:\\dir`', '`a|b` | c', '{a}|{b}\\'];
  for (const raw of inputs) {
    await mdx.compile(escapeMdx(raw), { remarkPlugins: [mdx.gfm] });
    const rows = await tableColumns(`| A | B |\n|---|---|\n| ${escapeMdx(raw, { cell: true })} | z |\n`);
    assert.deepEqual(rows.map((r) => r.n), [2, 2], `colonne rotte per ${JSON.stringify(raw)}`);
    assert.equal(rows[1].cells[1], 'z');
  }
  // il testo visibile è quello scritto: nessuna barra persa né aggiunta fuori dal codice
  assert.equal((await tableColumns(`| A | B |\n|---|---|\n| ${escapeMdx('x\\|y', { cell: true })} | z |\n`))[1].cells[0], 'x\\|y');
  assert.equal((await tableColumns(`| A | B |\n|---|---|\n| ${escapeMdx('a\\{b', { cell: true })} | z |\n`))[1].cells[0], 'a\\{b');
  assert.equal((await tableColumns(`| A | B |\n|---|---|\n| ${escapeMdx('abc\\', { cell: true })} | z |\n`))[1].cells[0], 'abc\\');
});

test('link: docs verso Mintlify, il resto verso GitHub', () => {
  assert.equal(rewriteLinks('[TB](16-TESTBOOK-FUNZIONALE.md#metodo)'), '[TB](/specifiche/testbook-funzionale#metodo)');
  assert.equal(rewriteLinks('[seed](../seed/members.json)'), '[seed](https://github.com/peppe-ruv/Loyalty_Sys/blob/main/seed/members.json)');
  assert.equal(rewriteLinks('[sito](https://example.org) [qui](#sezione)'), '[sito](https://example.org) [qui](#sezione)');
});

test('parser: storia con campi, criteri e perimetro', () => {
  const s = parseStory([
    '#### US-E01-01 · Titolo',
    '*Come* fonte, *voglio* inviare, *così che* paghi.',
    '- **Tocca**: F-ING-01.',
    '- **Criteri**:',
    '  1. Dato A, allora B.',
    '  2. ✗ Dato C, allora 400.',
    '- **Testbook**: TB-ING.',
  ], 'E01');
  assert.equal(s.id, 'US-E01-01');
  assert.equal(s.fields.Tocca, 'F-ING-01.');
  assert.equal(s.criteria.length, 2);
  assert.equal(s.outOfScope, false);
  assert.equal(s.phase, 1);
});

test('parser: epic, definizioni, storie, matrice e domini proposti', () => {
  const m = parseBacklog(FIXTURE);
  assert.deepEqual(m.epics.map((e) => [e.id, e.stories.length]), [['E01', 3], ['E-F2-DIST', 1], ['E-F2-AST', 0]]);
  assert.match(m.dordod, /Testo delle definizioni/);
  const [s1, s2, s3] = m.epics[0].stories;
  assert.equal(s3.outOfScope, true);
  assert.equal(s2.matrix, 'F-ING-05, docs/08 §2');
  assert.deepEqual([...s2.proposedDomains], ['TB-PLT']);
  assert.equal(s1.criteria.length, 2);
  assert.equal(epicSlug(m.epics[1]), 'e-f2-dist-distribuzione');
  assert.equal(epicSlug(m.epics[2]), 'e-f2-ast-agente-regolamento');
});

test('DoR: storia pronta e storie non pronte', () => {
  const [s1, s2] = parseBacklog(FIXTURE).epics[0].stories;
  const r1 = byId(evaluateDoR(s1, ctx()));
  assert.deepEqual(r1, { R1: 'ok', R2: 'ok', R3: 'ok', R4: 'ok', R5: 'ok', R6: 'ok', R7: 'ok', R8: 'ok' });
  const r2 = byId(evaluateDoR(s2, ctx()));
  assert.equal(r2.R4, 'ko', '403 senza criterio ✗');
  assert.equal(r2.R5, 'ok', 'dominio proposto in §7');
  assert.equal(r2.R7, 'tbd', 'nessun dato demo citato');
  const f2 = parseBacklog(FIXTURE).epics[1].stories[0];
  assert.equal(byId(evaluateDoR(f2, ctx())).R3, 'ko', 'criteri da scrivere con la fetta');
});

test('DoR: dato demo assente e domanda inesistente', () => {
  const [s1] = parseBacklog(FIXTURE.replace('Marco (MBR-000002)', 'Marco (MBR-000099) con Q-999')).epics[0].stories;
  const r = byId(evaluateDoR(s1, ctx()));
  assert.equal(r.R7, 'ko');
  assert.equal(r.R6, 'ko');
});

test('DoD: evidenze da docs/14, testbook e marcatori', () => {
  const [s1, s2, s3] = parseBacklog(FIXTURE).epics[0].stories;
  const d1 = byId(evaluateDoD(s1, ctx()));
  assert.deepEqual(d1, { D1: 'ok', D2: 'ok', D3: 'ok', D4: 'tbd' });
  assert.equal(evaluate(s1, ctx()).done, 'yes');
  // Nessuna riga eseguita: D2 non soddisfatta.
  assert.equal(byId(evaluateDoD(s1, ctx({ executed: new Set() }))).D2, 'ko');
  // ⛔ nel titolo: D3 non soddisfatta; la riga sulla feature nel dominio proposto non conta (dominio TB-PLT).
  const d2 = byId(evaluateDoD(s2, ctx()));
  assert.equal(d2.D3, 'ko');
  assert.equal(evaluate(s2, ctx()).done, 'no');
  assert.equal(evaluate(s3, ctx()).ready, 'na');
  const f2 = parseBacklog(FIXTURE).epics[1].stories[0];
  assert.equal(byId(evaluateDoD(f2, ctx())).D1, 'ko', 'F2-DIST-04 non spuntata');
});

test('DoD: file di prova citato ma assente', () => {
  const s = parseStory([
    '#### US-F2-DIST-01 · Immagine',
    '*Come* chi installa, *voglio* un\'immagine, *così che* la usi.',
    '- **Tocca**: F2-DIST-01.',
    '- **Criteri**:',
    '  1. Dato l\'immagine, allora parte.',
    '- **Testbook**: prove automatiche: `scripts/check-inesistente.mjs`.',
  ], 'E-F2-DIST');
  const c = ctx({ features: new Map([['F2-DIST-01', { status: 'x', prs: [52] }]]), exists: () => false });
  const d = evaluateDoD(s, c);
  assert.equal(byId(d).D2, 'ko');
  assert.match(d.find((i) => i.id === 'D2').evidence, /file non trovato/);
  assert.match(d.find((i) => i.id === 'D1').evidence, /pull\/52/);
});

test('docs.json: gruppo Backlog con le epiche di Fase 1 e Fase 2, backlog tolto da «Dati & processo»', () => {
  const json = JSON.stringify({
    navigation: { tabs: [{ tab: 'Specifiche tecniche', groups: [{ group: 'Dati & processo', pages: ['specifiche/dati-demo', 'specifiche/backlog'] }] }] },
  });
  const out = JSON.parse(updateDocsJson(json, parseBacklog(FIXTURE)));
  const groups = out.navigation.tabs[0].groups;
  assert.deepEqual(groups[0].pages, ['specifiche/dati-demo']);
  assert.deepEqual(groups[1], {
    group: 'Backlog',
    pages: [
      'specifiche/backlog',
      { group: 'Epiche di Fase 1', pages: ['specifiche/backlog/e01-ingresso-eventi'] },
      { group: 'Epiche di Fase 2', pages: ['specifiche/backlog/e-f2-dist-distribuzione'] },
    ],
  });
});

// ---------------------------------------------------------------------------------------------------------------
// Evidenze: workflow, D1, D2, R5.

const story = (over = {}) => parseStory([
  `#### ${over.id ?? 'US-F2-DIST-01'} · Titolo`,
  '*Come* chi installa, *voglio* un\'immagine, *così che* la usi.',
  `- **Tocca**: ${over.tocca ?? 'F2-DIST-01'}.`,
  '- **Criteri**:',
  ...(over.criteria ?? ['Dato l\'immagine, allora parte.']).map((c, i) => `  ${i + 1}. ${c}`),
  `- **Testbook**: ${over.testbook ?? 'TB-DIST.'}`,
], 'E-F2-DIST');
const dist = () => ctx({ features: new Map([['F2-DIST-01', { status: 'x', prs: [52] }]]), rows: [] });
const item = (items, id) => items.find((i) => i.id === id);

test('workflow: job per id e per nome, script invocati solo da righe non commentate', () => {
  const wf = parseWorkflows(new Map([['.github/workflows/ci.yml', [
    'name: ci', 'on: push', 'jobs:',
    '  guard:', '    name: guard', '    steps:', '      - name: passo', '        run: node --test scripts/a.test.mjs && node scripts/check-a.mjs',
    '  helm-kind:', '    name: "helm install (kind)"', '    steps:', '      # node scripts/commentato.mjs', '      - run: bash -n scripts/smoke.sh',
    '  changes:', '    steps:', '      - run: grep -Eq \'^(scripts/check-b\\.mjs)$\'',
  ].join('\n')]]));
  assert.deepEqual([...wf.jobs].sort(), ['changes', 'guard', 'helm install (kind)', 'helm-kind']);
  assert.ok(wf.jobsByFile.get('.github/workflows/ci.yml').has('guard'));
  assert.ok(!wf.jobs.has('passo'), 'il nome di un passo non è un job');
  assert.ok(wf.invoked('scripts/check-a.mjs') && wf.invoked('scripts/a.test.mjs') && wf.invoked('scripts/smoke.sh'));
  assert.ok(!wf.invoked('scripts/commentato.mjs'), 'riga commentata');
  assert.ok(!wf.invoked('scripts/check-b.mjs'), 'nome dentro un filtro di percorsi, non un\'invocazione');
});

test('D2: un job che non esiste, un workflow da solo o uno script non invocato non sono prove (S1)', () => {
  const d2 = (testbook, criteria) => item(evaluateDoD(story({ testbook, criteria }), dist()), 'D2');
  const nonEsiste = d2('job `docs` · prove automatiche: `scripts/check-mermaid.mjs`.');
  assert.equal(nonEsiste.status, 'ko');
  assert.match(nonEsiste.evidence, /job docs: assente da \.github\/workflows/);
  assert.match(nonEsiste.evidence, /nessun workflow lo invoca/);
  assert.equal(d2('prove automatiche: `.github/workflows/ci.yml`.').status, 'ko', 'workflow generico da solo');
  assert.equal(d2('prove automatiche: `.github/workflows/ci.yml` (job `docs`).').status, 'ko', 'job inesistente');
  assert.equal(d2('prove automatiche: `.github/workflows/ci.yml` (job `helm install (kind)`).').status, 'ok', 'workflow con un suo job');
  assert.equal(d2('prove automatiche: `scripts/check-helm.mjs`.').status, 'ok', 'script invocato da un workflow');
  assert.equal(d2('prove automatiche: `scripts/check-mermaid.mjs`.').status, 'ko', 'script mai invocato');
  assert.equal(d2('job `guard`.').status, 'ko', 'un job da solo non prova i criteri');
});

test('D2: non passa con criteri non pronti o con un residuo dichiarato (S1, S2)', () => {
  const tb = 'prove automatiche: `scripts/check-helm.mjs`.';
  const d2 = (criteria) => item(evaluateDoD(story({ testbook: tb, criteria }), dist()), 'D2');
  assert.equal(d2(['Dato A, allora B.']).status, 'ok');
  const pending = d2(['Dato A, allora B.', 'Passkey e MFA: criteri da scrivere con la fetta.']);
  assert.equal(pending.status, 'ko');
  assert.match(pending.evidence, /R3/);
  assert.equal(d2(['criteri senza la forma richiesta']).status, 'ko', 'non in forma Dato/Quando/Allora');
  const residual = story({ testbook: tb, criteria: ['Dato il profilo `enterprise`, allora smoke verde: ⚠ residuo dichiarato (Q-491, TOBE-008).'] });
  assert.equal(item(evaluateDoD(residual, dist()), 'D2').status, 'ko');
  assert.equal(item(evaluateDoD(residual, dist()), 'D3').status, 'ko');
  const todo = story({ testbook: tb, criteria: ['Dato Keycloak, allora rifiutato; ⚠ prova end to end da fare.'] });
  assert.equal(item(evaluateDoD(todo, dist()), 'D2').status, 'ko');
  const legit = story({ testbook: tb, criteria: ['Dato il budget, allora l\'ultima attivazione si riduce al residuo del budget (Q-237).'] });
  assert.equal(item(evaluateDoD(legit, dist()), 'D2').status, 'ok', '«residuo» come quantità non è un residuo dichiarato');
});

test('R5: un job citato conta solo se esiste in .github/workflows', () => {
  const r5 = (testbook) => item(evaluateDoR(story({ testbook }), dist()), 'R5');
  const fake = r5('job `docs` (da creare con M8.9).');
  assert.equal(fake.status, 'ko');
  assert.match(fake.evidence, /job docs assente da \.github\/workflows/);
  assert.equal(r5('job `guard`.').status, 'ok');
  assert.equal(r5('job `helm install (kind)`.').status, 'ok', 'per nome del job');
  assert.equal(r5('TB-DIST · job `docs`.').status, 'ok', 'il dominio basta');
});

test('D1: in Fase 2 una feature spuntata senza numero di PR non conta (S4)', () => {
  const noPr = ctx({ features: new Map([['F2-DIST-01', { status: 'x', prs: [] }]]), rows: [] });
  const d1 = item(evaluateDoD(story(), noPr), 'D1');
  assert.equal(d1.status, 'ko');
  assert.match(d1.evidence, /spuntata senza numero di PR/);
  assert.equal(item(evaluateDoD(story(), dist()), 'D1').status, 'ok');
  // Fase 1: la PR non è richiesta.
  const [s1] = parseBacklog(FIXTURE).epics[0].stories;
  assert.equal(item(evaluateDoD(s1, ctx()), 'D1').status, 'ok');
});

// ---------------------------------------------------------------------------------------------------------------
// loadContext: corpo della sezione di testbook (S3).

function tmpDir() {
  return fs.mkdtempSync(path.join(os.tmpdir(), 'docs-sync-'));
}
function put(root, p, text) {
  fs.mkdirSync(path.dirname(path.join(root, p)), { recursive: true });
  fs.writeFileSync(path.join(root, p), text);
}

test('loadContext: feature e storie citate nel corpo della sezione valgono per le sue righe', () => {
  const root = tmpDir();
  try {
    put(root, 'docs/14-STATO-AVANZAMENTO.md', '# 14\n');
    put(root, 'docs/15-DOMANDE-APERTE.md', '# 15\n');
    put(root, 'docs/16-TESTBOOK-FUNZIONALE.md', '# 16\n');
    for (const f of ['07-FRONTEND-FONDAMENTA', '08-FRONTEND-BACKOFFICE', '09-FRONTEND-PORTALE']) put(root, `docs/${f}.md`, '# x\n\n## BO-01 Titolo\n');
    put(root, 'docs/18-FASE-2.md', '## 5. Schermate\n\n| BO-26 | x |\n\n## 6. Milestone\n');
    put(root, 'docs/testbook/TB-ING-ingresso.md', [
      '# TB-ING', '', '## 2. ETY', '', '### 2.1 Tipi', '',
      '**Regola.** F-ING-01 normalizza il tipo; vale anche per US-E05-07.', '',
      '| ID | condizioni |', '|---|---|', '| TB-ING-ETY-001 | tipo breve |', '',
      '### 2.2 Altro', '', '| TB-ING-ETY-002 | senza regola |', '',
    ].join('\n'));
    const { rows } = loadContext(root);
    const r1 = rows.find((r) => r.id === 'TB-ING-ETY-001');
    const r2 = rows.find((r) => r.id === 'TB-ING-ETY-002');
    assert.ok(r1.subjects.has('F-ING-01') && r1.stories.has('US-E05-07'));
    assert.ok(!r2.subjects.has('F-ING-01') && !r2.stories.has('US-E05-07'), 'un\'altra sezione non eredita');
    // La storia con F-ING-01 nel dominio TB-ING trova la riga per feature.
    const [s] = parseBacklog(FIXTURE).epics[0].stories;
    const dod = evaluateDoD(s, ctx({ rows, executed: new Set(['TB-ING-ETY-001']) }));
    assert.equal(item(dod, 'D2').status, 'ok');
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

// ---------------------------------------------------------------------------------------------------------------
// Snapshot e verifica strutturale.

const DOCS_JSON = `${JSON.stringify({
  name: 'Demo',
  navigation: {
    tabs: [
      { tab: 'Specifiche tecniche', groups: [{ group: 'Dati & processo', pages: ['specifiche/dati-demo'] }] },
      { tab: 'Altro', groups: [{ group: 'Guide', pages: ['guide/a'] }] },
    ],
  },
}, null, 2)}\n`;
const META = { refreshedAt: 'abc1234', refreshedDate: '2026-09-29' };

/** Un repository temporaneo con docs/17 e docs.json, già «rinfrescato» con le evidenze della fixture. */
function repo(docs17 = FIXTURE) {
  const root = tmpDir();
  put(root, 'docs/17-EPIC-E-STORIE.md', docs17);
  put(root, 'docs.json', DOCS_JSON);
  const model = parseBacklog(docs17);
  writeRoot(root, model, buildSnapshot(model, ctx(), META));
  return root;
}
const withRepo = (fn, docs17) => {
  const root = repo(docs17);
  try {
    return fn(root);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
};
const read17 = (root) => fs.readFileSync(path.join(root, 'docs/17-EPIC-E-STORIE.md'), 'utf8');
const edit17 = (root, from, to) => {
  const text = read17(root);
  assert.ok(text.includes(from), `manca «${from}»`);
  fs.writeFileSync(path.join(root, 'docs/17-EPIC-E-STORIE.md'), text.replace(from, to));
};

test('check: repository coerente, rigenerazione senza differenze', () => {
  withRepo((root) => {
    assert.deepEqual(checkRoot(root), []);
    const model = parseBacklog(read17(root));
    const snap = JSON.parse(fs.readFileSync(path.join(root, SNAPSHOT), 'utf8'));
    // Modalità predefinita: pagine dallo snapshot esistente, nessun file cambia.
    assert.deepEqual(writeRoot(root, model, finalizeSnapshot(snap, model)).changed, []);
    // docs.json: stessa serializzazione, con il solo gruppo «Backlog» aggiunto.
    const docs = fs.readFileSync(path.join(root, 'docs.json'), 'utf8');
    assert.equal(updateDocsJson(docs, model), docs);
    const before = JSON.parse(DOCS_JSON);
    const after = JSON.parse(docs);
    after.navigation.tabs[0].groups.splice(1, 1);
    assert.deepEqual(after, before, 'il resto di docs.json è intatto');
  });
});

test('snapshot: chiavi ordinate, voci per storia, fuori perimetro senza evidenze, SHA e non un orologio', () => {
  withRepo((root) => {
    const text = fs.readFileSync(path.join(root, SNAPSHOT), 'utf8');
    const snap = JSON.parse(text);
    assert.deepEqual(Object.keys(snap.stories), ['US-E01-01', 'US-E01-02', 'US-E01-14', 'US-F2-DIST-04']);
    assert.deepEqual(snap.stories['US-E01-14'], { outOfScope: true });
    assert.deepEqual(Object.keys(snap.stories['US-E01-01'].dor), ['R5', 'R6', 'R7', 'R8']);
    assert.deepEqual(Object.keys(snap.stories['US-E01-01'].dod), ['D1', 'D2', 'D4']);
    assert.equal(snap.refreshedAt, 'abc1234');
    assert.equal(snap.totals.stories, 4);
    assert.equal(snap.totals.outOfScope, 1);
    assert.equal(text, serializeSnapshot(snap));
    assert.match(fs.readFileSync(path.join(root, 'specifiche/backlog.mdx'), 'utf8'), /commit `abc1234` del 2026-09-29/);
  });
});

test('check: non legge docs/14, docs/15, docs/16, testbook, test, seed né le intestazioni di docs/07–09 e docs/18', () => {
  withRepo((root) => {
    put(root, 'docs/14-STATO-AVANZAMENTO.md', '- [ ] `F-ING-01` da fare\n');
    put(root, 'docs/testbook/TB-ING-ingresso.md', '| TB-ING-PIP-001 | riga |\n');
    put(root, 'seed/members.json', '{}');
    put(root, 'services/x/src/test/A.java', 'TB-ING-PIP-001');
    const seen = [];
    const { readFileSync, readdirSync, existsSync } = fs;
    const spy = (fn) => (p, ...rest) => { seen.push(String(p)); return fn(p, ...rest); };
    fs.readFileSync = spy(readFileSync);
    fs.readdirSync = spy(readdirSync);
    fs.existsSync = spy(existsSync);
    try {
      assert.deepEqual(checkRoot(root), []);
      // Cambiano le evidenze (docs/14 e testbook): la verifica strutturale non se ne accorge.
      put(root, 'docs/14-STATO-AVANZAMENTO.md', '- [x] `F-ING-01` fatta #200\n- [x] `F-ING-05` fatta #201\n');
      put(root, 'docs/testbook/TB-ING-ingresso.md', '| TB-ING-PIP-001 | riga cambiata |\n| TB-ING-PIP-002 | nuova riga |\n');
      assert.deepEqual(checkRoot(root), []);
    } finally {
      Object.assign(fs, { readFileSync, readdirSync, existsSync });
    }
    const forbidden = /docs\/(0[7-9]|1[4-6]|18)|docs\/testbook|\/seed|\/services|\/libs|\/web|\/e2e|\.github/;
    assert.deepEqual(seen.filter((p) => forbidden.test(p.replace(root, ''))), []);
    assert.ok(seen.some((p) => p.endsWith('docs/17-EPIC-E-STORIE.md')) && seen.some((p) => p.endsWith('_status.json')));
  });
});

test('check: fallisce se il testo di una storia cambia R1–R4 o D3 senza rigenerare le pagine', () => {
  // R4: senza ✗ il 422 non ha più il suo criterio negativo.
  withRepo((root) => {
    edit17(root, '2. ✗ Dato un tipo sconosciuto', '2. Dato un tipo sconosciuto');
    const problems = checkRoot(root);
    assert.ok(problems.some((p) => /e01-ingresso-eventi\.mdx: diversa da quella generata/.test(p)), problems.join('\n'));
  });
  // D3: un ⚠ nella storia.
  withRepo((root) => {
    edit17(root, 'Marco (MBR-000002) paga online', 'Marco (MBR-000002) paga online ⚠ divergenza');
    assert.ok(checkRoot(root).some((p) => /diversa da quella generata/.test(p)));
  });
  // R1: la frase della storia.
  withRepo((root) => {
    edit17(root, '*così che* il cliente riceva i punti.', 'e basta.');
    assert.ok(checkRoot(root).some((p) => /diversa da quella generata/.test(p)));
  });
});

test('check: fallisce se manca la voce di una storia o ce n\'è una per una storia sparita', () => {
  const nuova = '#### US-E01-03 · Nuova\n*Come* fonte, *voglio* inviare, *così che* paghi.\n- **Tocca**: F-ING-01.\n- **Criteri**:\n  1. Dato A, allora B.\n- **Testbook**: TB-ING.\n\n#### US-E01-14';
  withRepo((root) => {
    edit17(root, '#### US-E01-14', nuova);
    const problems = checkRoot(root);
    assert.ok(problems.some((p) => /US-E01-03: storia in docs\/17 senza voce nello snapshot/.test(p)), problems.join('\n'));
    assert.ok(problems.some((p) => /node scripts\/docs-sync\.mjs --refresh/.test(p)));
  });
  withRepo((root) => {
    const text = read17(root);
    const a = text.indexOf('#### US-E01-02');
    const b = text.indexOf('#### US-E01-14');
    fs.writeFileSync(path.join(root, 'docs/17-EPIC-E-STORIE.md'), text.slice(0, a) + text.slice(b));
    const problems = checkRoot(root);
    assert.ok(problems.some((p) => /US-E01-02: voce nello snapshot per una storia che non è più in docs\/17/.test(p)), problems.join('\n'));
    assert.ok(problems.some((p) => /--refresh/.test(p)));
  });
  withRepo((root) => {
    const p = path.join(root, SNAPSHOT);
    const snap = JSON.parse(fs.readFileSync(p, 'utf8'));
    delete snap.stories['US-E01-01'].dod.D2;
    fs.writeFileSync(p, serializeSnapshot(snap));
    assert.ok(checkRoot(root).some((x) => /US-E01-01: voce dello snapshot incompleta/.test(x)));
  });
  withRepo((root) => {
    fs.rmSync(path.join(root, SNAPSHOT));
    assert.ok(checkRoot(root).some((x) => /snapshot assente/.test(x)));
  });
});

test('check: totali dello snapshot fuori sincronia o pagina orfana', () => {
  withRepo((root) => {
    const p = path.join(root, SNAPSHOT);
    const snap = JSON.parse(fs.readFileSync(p, 'utf8'));
    snap.totals.ready = 99;
    fs.writeFileSync(p, serializeSnapshot(snap));
    assert.ok(checkRoot(root).some((x) => /_status\.json: totali/.test(x)));
  });
  withRepo((root) => {
    put(root, 'specifiche/backlog/e99-vecchia.mdx', 'x');
    assert.ok(checkRoot(root).some((x) => /e99-vecchia\.mdx: pagina non più generata/.test(x)));
  });
});

test('check: di docs.json conta solo il gruppo «Backlog»', () => {
  // Il resto del file può cambiare (altre schede, altri gruppi, altre chiavi, formattazione) senza far fallire la verifica.
  withRepo((root) => {
    const p = path.join(root, 'docs.json');
    const doc = JSON.parse(fs.readFileSync(p, 'utf8'));
    doc.name = 'Altro nome';
    doc.navigation.tabs[1].groups.push({ group: 'Nuovo gruppo', pages: ['guide/b'] });
    doc.navigation.tabs[0].groups[0].pages.push('specifiche/nuova-pagina');
    fs.writeFileSync(p, JSON.stringify(doc));
    assert.deepEqual(checkRoot(root), []);
    assert.deepEqual(readBacklogGroup(fs.readFileSync(p, 'utf8')), backlogGroup(parseBacklog(FIXTURE)));
  });
  // Il gruppo «Backlog» diverso o mancante invece fa fallire.
  withRepo((root) => {
    const p = path.join(root, 'docs.json');
    const doc = JSON.parse(fs.readFileSync(p, 'utf8'));
    doc.navigation.tabs[0].groups.find((g) => g.group === 'Backlog').pages.pop();
    fs.writeFileSync(p, `${JSON.stringify(doc, null, 2)}\n`);
    assert.ok(checkRoot(root).some((x) => /gruppo «Backlog» diverso/.test(x)));
  });
  withRepo((root) => {
    const p = path.join(root, 'docs.json');
    const doc = JSON.parse(fs.readFileSync(p, 'utf8'));
    doc.navigation.tabs[0].groups = doc.navigation.tabs[0].groups.filter((g) => g.group !== 'Backlog');
    fs.writeFileSync(p, `${JSON.stringify(doc, null, 2)}\n`);
    assert.ok(checkRoot(root).some((x) => /gruppo «Backlog» assente/.test(x)));
  });
});

test('modalità predefinita e refresh: pagine pure di docs/17 e snapshot; le evidenze cambiano solo col refresh', () => {
  withRepo((root) => {
    // Una modifica al testo che non cambia le storie: la rigenerazione dallo snapshot basta, senza leggere evidenze.
    edit17(root, '2. ✗ Dato un tipo sconosciuto', '2. Dato un tipo sconosciuto');
    const model = parseBacklog(read17(root));
    const snap = JSON.parse(fs.readFileSync(path.join(root, SNAPSHOT), 'utf8'));
    assert.deepEqual(snapshotProblems(model, snap), []);
    const out = writeRoot(root, model, finalizeSnapshot(snap, model));
    assert.ok(out.changed.includes('specifiche/backlog/e01-ingresso-eventi.mdx'));
    assert.deepEqual(checkRoot(root), []);
    // Un refresh con evidenze diverse cambia solo lo snapshot e le pagine che ne dipendono.
    const refreshed = buildSnapshot(model, ctx({ executed: new Set() }), { refreshedAt: 'def5678', refreshedDate: '2026-10-01' });
    assert.deepEqual(changedStories(snap, refreshed), ['US-E01-01']);
    writeRoot(root, model, refreshed);
    assert.deepEqual(checkRoot(root), []);
    assert.match(fs.readFileSync(path.join(root, 'specifiche/backlog.mdx'), 'utf8'), /commit `def5678`/);
    // Pure: stesso modello e stesso snapshot, stesse pagine.
    const a = renderPages(model, refreshed).files;
    const b = renderPages(model, JSON.parse(JSON.stringify(refreshed))).files;
    assert.deepEqual([...a], [...b]);
  });
});

test('renderEvents genera pagine con escape MDX', async () => {
  const tmpDir = path.join(os.tmpdir(), `docs-sync-test-${Date.now()}`);
  fs.mkdirSync(tmpDir);
  const eventsDir = path.join(tmpDir, 'contracts/events/action');
  fs.mkdirSync(eventsDir, { recursive: true });

  const mockSchema = {
    title: 'action.test',
    description: 'Un test con <tag> e {var} e <!-- commento -->',
    type: 'object',
    required: ['id'],
    properties: {
      id: { type: 'string', 'x-lh-pii': false },
      email: { type: 'string', 'x-lh-pii': true }
    }
  };
  fs.writeFileSync(path.join(eventsDir, 'action.test.schema.json'), JSON.stringify(mockSchema));

  const docsSync = await import('./docs-sync.mjs');
  const files = docsSync.renderEvents(tmpDir);

  const actionPage = files.get('specifiche/eventi/action.mdx');
  assert.ok(actionPage);
  assert.match(actionPage, /&lt;tag>/);
  assert.match(actionPage, /\\\{var\\\}/);
  assert.match(actionPage, /&lt;!-- commento -->/);
  assert.match(actionPage, /⚠️ Sì/);

  fs.rmSync(tmpDir, { recursive: true, force: true });
});
