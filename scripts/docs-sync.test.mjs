// Test di scripts/docs-sync.mjs (M8.9b, F2-DOC-02): parser del backlog, escape MDX, link, DoR e DoD su una fixture.
// Uso: node --test scripts/docs-sync.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  storyRefs, featureRefs, screenRefs, tbDomains, tbRowRefs, proofPaths, escapeMdx, rewriteLinks,
  parseStory, parseBacklog, evaluateDoR, evaluateDoD, evaluate, epicSlug, updateDocsJson,
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

test('docs.json: gruppo Backlog con Fase 1 e Fase 2, backlog tolto da «Dati & processo»', () => {
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
      { group: 'Fase 1', pages: ['specifiche/backlog/e01-ingresso-eventi'] },
      { group: 'Fase 2', pages: ['specifiche/backlog/e-f2-dist-distribuzione'] },
    ],
  });
});
