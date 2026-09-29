#!/usr/bin/env node
// docs-sync — genera le pagine Mintlify delle specifiche dai file di docs/ (ADR-040, docs/18 §3.12).
// Prima parte (M8.9b, F2-DOC-02): il backlog di docs/17 con Definition of Ready e Definition of Done calcolate.
//
// Uso:
//   node scripts/docs-sync.mjs            # rigenera specifiche/backlog.mdx, specifiche/backlog/*.mdx e il gruppo
//                                         # «Backlog» di docs.json
//   node scripts/docs-sync.mjs --check    # esce ≠ 0 se le pagine committate differiscono da quelle generate
//   node scripts/docs-sync.mjs --backlog  # come senza argomenti (unica sezione generata per ora)
//
// Nessuna dipendenza: solo moduli di Node, così gira anche nel job `guard` senza `npm ci`.
// Le evidenze della DoD vengono da docs/14 (feature spuntate e PR), docs/16 e docs/testbook/ (righe TB-*), dai sorgenti
// dei test (ID TB-* presenti) e dai file di prova citati nelle storie. Nessuna rete, nessun orologio: stesso input,
// stesso output.
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

export const REPO_URL = 'https://github.com/peppe-ruv/Loyalty_Sys';
const BLOB = `${REPO_URL}/blob/main/`;
const SOURCE = 'docs/17-EPIC-E-STORIE.md';
const OUT_INDEX = 'specifiche/backlog.mdx';
const OUT_DIR = 'specifiche/backlog';
const PAGE_BASE = '/specifiche/backlog';
const HEADER = `{/* Generato da scripts/docs-sync.mjs a partire da ${SOURCE}: non modificare a mano. */}`;

// Pagine Mintlify che corrispondono ai file di docs/ (per riscrivere i link).
export const DOC_PAGES = {
  'docs/01-VISIONE-E-SCOPE.md': '/specifiche/visione-e-scope',
  'docs/02-CATALOGO-FUNZIONALE.md': '/specifiche/catalogo-funzionale',
  'docs/03-MODELLO-DI-DOMINIO.md': '/specifiche/modello-di-dominio',
  'docs/04-ARCHITETTURA.md': '/specifiche/architettura',
  'docs/05-EVENTI-E-TOPIC.md': '/specifiche/eventi-e-topic',
  'docs/06-CONVENZIONI-BACKEND.md': '/specifiche/convenzioni-backend',
  'docs/07-FRONTEND-FONDAMENTA.md': '/specifiche/frontend-fondamenta',
  'docs/08-FRONTEND-BACKOFFICE.md': '/specifiche/frontend-backoffice',
  'docs/09-FRONTEND-PORTALE.md': '/specifiche/frontend-portale',
  'docs/10-DATI-DEMO.md': '/specifiche/dati-demo',
  'docs/12-PIANO-DI-SVILUPPO.md': '/specifiche/piano-di-sviluppo',
  'docs/13-REGISTRO-DECISIONI.md': '/specifiche/registro-decisioni',
  'docs/15-DOMANDE-APERTE.md': '/specifiche/domande-aperte',
  'docs/16-TESTBOOK-FUNZIONALE.md': '/specifiche/testbook-funzionale',
  'docs/17-EPIC-E-STORIE.md': PAGE_BASE,
  'docs/18-FASE-2.md': '/specifiche/fase-2',
};

// ---------------------------------------------------------------------------------------------------------------
// Riferimenti: storie, feature, domini di testbook, schermate, dati demo.

const STORY_REF = /US-(E\d{2}|F2-[A-Z0-9]+)-(\d{2})((?:\s*(?:…|\.\.\.|\/|,)\s*\d{2}(?![\d-]))*)/g;
const FEATURE_REF = /\b(F2?-[A-Z0-9]+)-(\d{2})((?:\s*(?:…|\.\.\.|\/)\s*\d{2}(?![\d-]))*)/g;
const SCREEN_REF = /\b(BO|PT|HUB)-(\d{2})((?:\/\d{2}(?![\d-]))*)/g;
const TB_ROW_ID = /TB-[A-Z0-9]{3}(?:-[A-Z]{2,5})?-\d{3,4}/g;
const SEED_REF = /(?<![A-Z0-9-])(?:MBR|CMP|RWD|SCN|IW|SEG|ACH|BDG|LDB|POP|NR|MSG|CNT|BNR|POOL|ED|RDM|WH)-[A-Z0-9]+(?:-[A-Z0-9]+)*\b/g;
const Q_REF = /\bQ-(\d+)\b/g;
const pad2 = (n) => String(n).padStart(2, '0');

/** Espande «03…07», «05/08/09», «03…07, 13…16» in un elenco di numeri a due cifre. */
function expandTail(first, tail) {
  const nums = [Number(first)];
  for (const m of tail.matchAll(/(…|\.\.\.|\/|,)\s*(\d{2})/g)) {
    const n = Number(m[2]);
    if (m[1] === '…' || m[1] === '...') {
      for (let k = nums[nums.length - 1] + 1; k <= n; k++) nums.push(k);
    } else nums.push(n);
  }
  return nums.map(pad2);
}

/** Gli ID di storia citati in un testo, con gli intervalli espansi. */
export function storyRefs(text) {
  const out = new Set();
  for (const m of String(text).matchAll(STORY_REF)) for (const n of expandTail(m[2], m[3])) out.add(`US-${m[1]}-${n}`);
  return out;
}

/** Gli ID di feature (`F-…`, `F2-…`) citati in un testo, con gli intervalli espansi. */
export function featureRefs(text) {
  const out = new Set();
  for (const m of String(text).matchAll(FEATURE_REF)) for (const n of expandTail(m[2], m[3])) out.add(`${m[1]}-${n}`);
  return out;
}

/** Le schermate citate (`BO-14/06/11` diventa BO-14, BO-06, BO-11). */
export function screenRefs(text) {
  const out = new Set();
  for (const m of String(text).matchAll(SCREEN_REF)) {
    out.add(`${m[1]}-${m[2]}`);
    for (const t of m[3].matchAll(/\/(\d{2})/g)) out.add(`${m[1]}-${t[1]}`);
  }
  return out;
}

/** I domini di testbook citati (`TB-ING/CMP/WAL` diventa TB-ING, TB-CMP, TB-WAL). */
export function tbDomains(text) {
  const out = new Set();
  for (const m of String(text).matchAll(/\bTB-([A-Z0-9]{3,4})((?:\s*\/\s*(?:TB-)?[A-Z0-9]{3,4}\b)*)/g)) {
    out.add(`TB-${m[1]}`);
    for (const t of m[2].matchAll(/\/\s*(?:TB-)?([A-Z0-9]{3,4})\b/g)) out.add(`TB-${t[1]}`);
  }
  return out;
}

/** Gli ID di riga TB citati esplicitamente: «TB-ING-ETY-006, 047, 051» e «TB-ING-ETY-061…064» si espandono. */
export function tbRowRefs(text) {
  const out = new Set();
  for (const m of String(text).matchAll(/\b(TB-[A-Z0-9]{3}(?:-[A-Z]{2,5})?)-(\d{3})((?:\s*(?:…|\.\.\.|,)\s*\d{3}(?![\d-]))*)/g)) {
    const nums = [Number(m[2])];
    for (const t of m[3].matchAll(/(…|\.\.\.|,)\s*(\d{3})/g)) {
      const n = Number(t[2]);
      if (t[1] === ',') nums.push(n);
      else for (let k = nums[nums.length - 1] + 1; k <= n; k++) nums.push(k);
    }
    for (const n of nums) out.add(`${m[1]}-${String(n).padStart(3, '0')}`);
  }
  return out;
}

/** Percorsi citati tra backtick che somigliano a prove automatiche (test, script di verifica, workflow). */
export function proofPaths(text) {
  const out = [];
  for (const m of String(text).matchAll(/`([^`\s]+)`/g)) {
    const p = m[1];
    if (!p.includes('/') || !/\.[a-z]+$/.test(p)) continue;
    if (/\/src\/test\/|\.test\.[cm]?[jt]sx?$|(?:Test|IT)\.java$|^\.github\/workflows\/|^scripts\/check-|^scripts\/.*\.test\.mjs$/.test(p)) out.push(p);
  }
  return [...new Set(out)];
}

// ---------------------------------------------------------------------------------------------------------------
// MDX: escape e link.

/** Divide il testo in segmenti di codice inline (tra backtick) e di testo. */
function splitCode(text) {
  const parts = [];
  const re = /(`+)([\s\S]*?)\1/g;
  let last = 0;
  let m;
  while ((m = re.exec(text))) {
    if (m.index > last) parts.push({ code: false, s: text.slice(last, m.index) });
    parts.push({ code: true, s: m[0] });
    last = m.index + m[0].length;
  }
  if (last < text.length) parts.push({ code: false, s: text.slice(last) });
  return parts;
}

/** Escape per MDX fuori dal codice inline: `<` diventa `&lt;`, le graffe si proteggono. */
export function escapeMdx(text) {
  return splitCode(String(text))
    .map((p) => (p.code ? p.s : p.s.replace(/</g, '&lt;').replace(/\{/g, '\\{').replace(/\}/g, '\\}')))
    .join('');
}

/** Applica `fn` solo ai segmenti fuori dal codice inline e dai link Markdown già presenti. */
function mapText(text, fn) {
  return splitCode(text)
    .map((p) => {
      if (p.code) return p.s;
      return p.s.split(/(\[[^\]]*\]\([^)]*\))/).map((s, i) => (i % 2 ? s : fn(s))).join('');
    })
    .join('');
}

/** Riscrive i link Markdown relativi al repository: docs/*.md verso la pagina Mintlify, il resto verso GitHub. */
export function rewriteLinks(text, fromDir = 'docs') {
  return String(text).replace(/\]\(([^)\s]+)\)/g, (all, href) => {
    if (/^(https?:|mailto:|#|\/)/.test(href)) return all;
    const [file, anchor] = href.split('#');
    const repoPath = path.posix.normalize(path.posix.join(fromDir, file));
    const page = DOC_PAGES[repoPath];
    if (page) return `](${page}${anchor ? `#${anchor}` : ''})`;
    return `](${BLOB}${repoPath}${anchor ? `#${anchor}` : ''})`;
  });
}

const slugify = (s) =>
  s.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');

// ---------------------------------------------------------------------------------------------------------------
// Parser di docs/17.

/** Una storia dal suo blocco di righe (intestazione `#### US-…` compresa). */
export function parseStory(lines, epicId) {
  const head = lines[0].match(/^#### (US-[A-Z0-9-]+) · (.+)$/);
  if (!head) throw new Error(`intestazione di storia non valida: ${lines[0]}`);
  const story = { id: head[1], title: head[2].trim(), epic: epicId, sentence: '', fields: {}, criteria: [], raw: lines.join('\n') };
  let current = null;
  for (const line of lines.slice(1)) {
    const f = line.match(/^- \*\*([^*]+)\*\*:\s*(.*)$/);
    if (f) {
      current = f[1].trim();
      story.fields[current] = f[2].trim();
      continue;
    }
    const item = line.match(/^\s{2,}\d+\.\s+(.*)$/);
    if (item && current === 'Criteri') {
      story.criteria.push(item[1].trim());
      continue;
    }
    if (!line.trim()) continue;
    if (!current && !story.sentence) story.sentence = line.trim();
    else if (current) story.fields[current] = `${story.fields[current]} ${line.trim()}`.trim();
  }
  // Criteri su una sola riga («- **Criteri**: Dato …, allora …»): un criterio unico.
  if (!story.criteria.length && story.fields.Criteri) story.criteria.push(story.fields.Criteri);
  story.matrix = '';
  story.proposedDomains = new Set();
  story.outOfScope = /fuori perimetro/i.test(story.title);
  story.phase = story.id.startsWith('US-F2-') ? 2 : 1;
  return story;
}

/** Tabella Markdown → righe di celle (senza intestazione e separatore). */
function tableRows(lines) {
  return lines
    .filter((l) => /^\|/.test(l) && !/^\|\s*-/.test(l))
    .slice(1)
    .map((l) => l.replace(/^\||\|$/g, '').split('|').map((c) => c.trim()));
}

/** Il backlog: epic (da §3), definizioni DoR/DoD (§4.0), storie (§4) e codici d'errore del catalogo (§5.1). */
export function parseBacklog(md) {
  const lines = md.split('\n');
  const sectionOf = (re, stop = /^## /) => {
    const a = lines.findIndex((l) => re.test(l));
    if (a < 0) return [];
    const b = lines.findIndex((l, i) => i > a && stop.test(l));
    return lines.slice(a + 1, b < 0 ? lines.length : b);
  };

  // §3 Epic.
  const epics = [];
  for (const cells of tableRows(sectionOf(/^## 3\. Epic/))) {
    const m = cells[0].match(/^\*\*(E\d{2}|E-F2-[A-Z0-9]+) (.+)\*\*$/);
    if (!m) continue;
    epics.push({
      id: m[1], name: m[2].trim(), goal: cells[1], actors: cells[2], features: cells[3], screens: cells[4],
      services: cells[5], tb: cells[6], phase: m[1].startsWith('E-F2-') ? 2 : 1, stories: [],
    });
  }

  // §4 Storie.
  const s4 = sectionOf(/^## 4\. Storie utente/);
  const dordod = [];
  const phase2Intro = [];
  const stories = [];
  let block = null;
  let epicId = null;
  let mode = null;
  const flush = () => {
    if (block) stories.push(parseStory(block, epicId));
    block = null;
  };
  for (const line of s4) {
    const h3 = line.match(/^### (.+)$/);
    if (h3) {
      flush();
      const e = h3[1].match(/^(E\d{2}|E-F2-[A-Z0-9]+) — /);
      if (e) { epicId = e[1]; mode = 'epic'; }
      else if (/^4\.0 /.test(h3[1])) mode = 'dordod';
      else if (/^Fase 2/.test(h3[1])) mode = 'phase2';
      else mode = null;
      continue;
    }
    if (/^#### US-/.test(line)) {
      flush();
      block = [line];
      continue;
    }
    if (block) block.push(line);
    else if (mode === 'dordod') dordod.push(line);
    else if (mode === 'phase2') phase2Intro.push(line);
  }
  flush();

  for (const s of stories) {
    const epic = epics.find((e) => e.id === s.epic);
    if (!epic) throw new Error(`${s.id}: epic ${s.epic} assente dalla tabella di §3`);
    epic.stories.push(s);
  }

  // §6.1: la colonna «Feature» della matrice di copertura completa il campo *Tocca*.
  for (const cells of tableRows(sectionOf(/^### 6\.1 /, /^###? /))) {
    const s = stories.find((x) => x.id === cells[0]);
    if (s) s.matrix = cells[1] ?? '';
  }
  // §7: i domini proposti («Domini da aggiungere a docs/16») assegnano un dominio alle storie scoperte.
  for (const cells of tableRows(sectionOf(/^## 7\. /))) {
    const d = cells[0].match(/^\*\*(TB-[A-Z0-9]{3})\*\*/);
    if (!d) continue;
    for (const id of storyRefs(cells[cells.length - 1])) {
      const s = stories.find((x) => x.id === id);
      if (s) s.proposedDomains.add(d[1]);
    }
  }
  return { epics, stories, dordod: dordod.join('\n').trim(), phase2Intro: phase2Intro.join('\n').trim() };
}

// ---------------------------------------------------------------------------------------------------------------
// Contesto del repository: le evidenze.

function walk(root, rel, files) {
  const abs = path.join(root, rel);
  if (!fs.existsSync(abs)) return;
  for (const e of fs.readdirSync(abs, { withFileTypes: true })) {
    if (['node_modules', '.next', 'target', 'dist', 'build', '.git'].includes(e.name)) continue;
    const p = path.posix.join(rel, e.name);
    if (e.isDirectory()) walk(root, p, files);
    else files.push(p);
  }
}

/** Legge dal repository tutto ciò che serve a valutare DoR e DoD. */
export function loadContext(root) {
  const read = (p) => fs.readFileSync(path.join(root, p), 'utf8');
  const exists = (p) => fs.existsSync(path.join(root, p));

  // docs/14: feature spuntate, PR, feature fuori PoC.
  const features = new Map();
  const outOfScope = new Set();
  let fuori = false;
  for (const l of read('docs/14-STATO-AVANZAMENTO.md').split('\n')) {
    if (/^## /.test(l)) fuori = /^## Fuori PoC/.test(l);
    const m = l.match(/^- \[(x|~| )\] `(F2?-[A-Z0-9]+-\d{2})`/);
    if (m) {
      const prs = [...new Set([...l.matchAll(/#(\d+)\b/g)].map((x) => Number(x[1])))].sort((a, b) => a - b);
      features.set(m[2], { status: m[1], prs });
    }
    const o = fuori && l.match(/^- `(F2?-[A-Z0-9]+-\d{2})`/);
    if (o) outOfScope.add(o[1]);
  }

  // docs/15: domande e stato.
  const questions = new Map();
  for (const l of read('docs/15-DOMANDE-APERTE.md').split('\n')) {
    let m = l.match(/^\|\s*(Q-\d+)\s*\|/);
    if (m) {
      const cells = l.replace(/^\||\|\s*$/g, '').split('|').map((c) => c.trim()).filter(Boolean);
      const last = cells[cells.length - 1] ?? '';
      const st = last.match(/^(APERTA|DECISA|CHIUSA|SUPERATA)/);
      if (!questions.has(m[1])) questions.set(m[1], st ? st[1] : 'DEFINITA');
      continue;
    }
    m = l.match(/^[*-]\s+\*\*(Q-\d+)\*\*/);
    if (m && !questions.has(m[1])) questions.set(m[1], 'DEFINITA');
  }

  // Righe di testbook documentate, con i titoli di sezione che le contengono.
  const tbFiles = ['docs/16-TESTBOOK-FUNZIONALE.md'];
  if (exists('docs/testbook')) {
    for (const f of fs.readdirSync(path.join(root, 'docs/testbook')).sort()) if (f.endsWith('.md')) tbFiles.push(`docs/testbook/${f}`);
  }
  const rows = [];
  const tbDomainsDocumented = new Set();
  for (const f of tbFiles) {
    const text = read(f).split('\n');
    // Inventario delle regole: «| R-01 | … | Aree |». Le righe TB-<DOM>-<AREA>-nnn di un'area ereditano le storie e
    // le feature citate dalle regole che elencano quell'area (catena riga → regola → specifica del testbook).
    const rules = [];
    for (const l of text) {
      if (!/^\|\s*R-?\d{2}\s*\|/.test(l)) continue;
      const cells = l.replace(/^\||\|\s*$/g, '').split('|').map((c) => c.trim());
      const areas = new Set();
      const last = cells[cells.length - 1];
      if (/^tutte\b/i.test(last)) areas.add('*');
      for (const m of last.matchAll(/\b([A-Z]{2,5})(?:-\d{3})?\b/g)) areas.add(m[1]);
      rules.push({ areas, stories: storyRefs(l), subjects: new Set([...featureRefs(l), ...screenRefs(l)]) });
    }
    let h2 = '';
    let h3 = '';
    for (const l of text) {
      if (/^## /.test(l)) { h2 = l; h3 = ''; }
      else if (/^### /.test(l)) h3 = l;
      const m = l.match(/^\|\s*`?(TB-[A-Z0-9]{3}(?:-[A-Z]{2,5})?-\d{3,4})`?\s*\|/);
      if (!m) continue;
      const id = m[1];
      const domain = id.slice(0, 6);
      const area = (id.match(/^TB-[A-Z0-9]{3}-([A-Z]{2,5})-/) ?? [])[1];
      tbDomainsDocumented.add(domain);
      const row = {
        id, domain, file: f,
        stories: new Set([...storyRefs(l), ...storyRefs(h2), ...storyRefs(h3)]),
        subjects: new Set([...featureRefs(l), ...screenRefs(l)]),
      };
      for (const r of rules) {
        if (!r.areas.has('*') && !(area && r.areas.has(area))) continue;
        for (const x of r.stories) row.stories.add(x);
        for (const x of r.subjects) row.subjects.add(x);
      }
      rows.push(row);
    }
  }

  // ID TB-* nei sorgenti dei test (CSV dei casi, classi Testbook*, file *.test.ts).
  const executed = new Set();
  // I file versionati (git ls-files), così il risultato locale coincide con quello della CI; senza git, la cartella.
  const dirs = ['services', 'libs', 'deploy', 'web', 'e2e'];
  let files = [];
  try {
    files = execFileSync('git', ['ls-files', '-z', '--', ...dirs], { cwd: root, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 })
      .split('\0').filter(Boolean);
  } catch {
    for (const d of dirs) walk(root, d, files);
  }
  for (const f of files.sort()) {
    const isTest = f.includes('/src/test/') || /\.test\.[cm]?[jt]sx?$/.test(f) || f.startsWith('e2e/');
    if (!isTest || !exists(f) || !/\.(java|kt|csv|tsx?|jsx?|mjs|json|ya?ml|txt)$/.test(f)) continue;
    for (const m of read(f).matchAll(TB_ROW_ID)) executed.add(m[0]);
  }

  // Dati demo: ogni token con trattino nei seed.
  const seedTokens = new Set();
  if (exists('seed')) {
    for (const f of fs.readdirSync(path.join(root, 'seed')).sort()) {
      if (!f.endsWith('.json')) continue;
      for (const m of read(`seed/${f}`).matchAll(/[A-Z][A-Z0-9]*(?:-[A-Z0-9]+)+/g)) seedTokens.add(m[0]);
    }
  }

  // Schermate specificate: titoli di docs/07–09 e tabella di docs/18 §5.
  const screens = new Map();
  for (const f of ['docs/07-FRONTEND-FONDAMENTA.md', 'docs/08-FRONTEND-BACKOFFICE.md', 'docs/09-FRONTEND-PORTALE.md']) {
    for (const l of read(f).split('\n')) {
      if (!/^#{2,4} /.test(l)) continue;
      for (const s of screenRefs(l)) if (!screens.has(s)) screens.set(s, f.slice(5, 7) === '07' ? 'docs/07' : `docs/${f.slice(5, 7)}`);
    }
  }
  const d18 = read('docs/18-FASE-2.md').split('\n');
  const a = d18.findIndex((l) => /^## 5\. /.test(l));
  const b = d18.findIndex((l, i) => i > a && /^## /.test(l));
  for (const l of d18.slice(a, b)) {
    const m = l.match(/^\|\s*([^|]+)\|/);
    if (!m) continue;
    for (const s of screenRefs(m[1])) if (!screens.has(s)) screens.set(s, 'docs/18 §5');
  }

  return { features, outOfScope, questions, rows, tbDomainsDocumented, executed, seedTokens, screens, exists };
}

// ---------------------------------------------------------------------------------------------------------------
// Definition of Ready e Definition of Done.

const OK = 'ok';
const KO = 'ko';
const TBD = 'tbd';
/** «1 riga», «3 righe». */
const nn = (k, one, many) => `${k} ${k === 1 ? one : many}`;
const list = (xs, n = 6) => (xs.length > n ? `${xs.slice(0, n).join(', ')} e altri ${xs.length - n}` : xs.join(', '));

/** Un codice HTTP 4xx che non sia una quantità (400 giorni, 404 righe…). */
const HTTP_4XX = /(?<![\d.,])\b(4[0-2]\d)\b(?!\s*(?:giorni|gg|righe|MB|KB|MiB|ms|s\b|min|PTS|STS|punti|€|%|membri|eventi|elementi))/g;

/** L'esito di un criterio: il testo dopo «allora». */
const outcome = (c) => (c.match(/\ballora\b([\s\S]*)$/i) ?? [, ''])[1];
/** La precondizione di un criterio: il testo prima di «quando» o «allora». */
const precondition = (c) => c.split(/,\s*(?:quando|allora)\b/i)[0];

/** Rami d'errore della storia: risposte HTTP 4xx nelle decisioni o nell'esito di un criterio. */
function errorPaths(story) {
  const text = `${story.fields.Decisioni ?? ''} ${story.criteria.map(outcome).join(' ')}`;
  return [...new Set([...text.matchAll(HTTP_4XX)].map((m) => m[1]))].sort();
}

function storySubjects(story) {
  const touch = `${story.fields.Tocca ?? ''} ${story.matrix ?? ''}`;
  return { features: [...featureRefs(touch)], screens: [...screenRefs(touch)] };
}

/** Voci R1–R8 della Definition of Ready. */
export function evaluateDoR(story, ctx) {
  const items = [];
  const push = (id, label, status, evidence) => items.push({ id, label, status, evidence });
  const f = story.fields;

  push('R1', 'Forma della storia',
    /\*Come\*[\s\S]*\*vogli(?:o|amo)\*[\s\S]*\*così che\*/i.test(story.sentence) ? OK : KO,
    /\*Come\*/i.test(story.sentence) ? 'frase *Come … voglio … così che …*' : 'manca la frase *Come … voglio … così che …*');

  const SPEC_ID = /\b(?:F2?-[A-Z0-9]+-\d{2}|RNF-\d{2}|BO-\d{2}|PT-\d{2}|HUB-\d{2}|EVT-[A-Z]+-\d{2}|ADR-\d{3})\b|\bdocs\/\d{2} §\s?[\w.-]+/g;
  const fromTouch = [...new Set(`${f.Tocca ?? ''}`.match(SPEC_ID) ?? [])];
  const fromMatrix = [...new Set(`${story.matrix ?? ''}`.match(SPEC_ID) ?? [])].filter((x) => !fromTouch.includes(x));
  if (fromTouch.length) push('R2', 'ID di specifica', OK, list(fromTouch));
  else if (fromMatrix.length) push('R2', 'ID di specifica', OK, `${list(fromMatrix)} (dalla matrice di §6.1)`);
  else push('R2', 'ID di specifica', KO, 'nessun ID nel campo *Tocca* né nella matrice di §6.1');

  const pending = /da scrivere con la fetta/i.test(`${f.Criteri ?? ''} ${story.criteria.join(' ')}`);
  const gwt = story.criteria.filter((c) => /\bDat[oaie]\b/i.test(c) && /\ballora\b/i.test(c));
  let r3;
  if (!story.criteria.length) r3 = [KO, 'nessun criterio'];
  else if (pending && !gwt.length) r3 = [KO, 'criteri da scrivere con la fetta'];
  else if (pending) r3 = [KO, `${nn(gwt.length, 'criterio', 'criteri')} Dato/Quando/Allora, altri da scrivere con la fetta`];
  else if (!gwt.length) r3 = [KO, 'criteri senza la forma Dato/Quando/Allora'];
  else r3 = [OK, `${nn(gwt.length, 'criterio', 'criteri')} Dato/Quando/Allora`];
  push('R3', 'Criteri di accettazione', ...r3);

  const errs = errorPaths(story);
  const negatives = story.criteria.filter((c) => c.includes('✗')).length;
  if (!errs.length) push('R4', 'Casi negativi', OK, negatives ? nn(negatives, 'criterio ✗', 'criteri ✗') : 'nessuna risposta d\'errore 4xx nella storia');
  else push('R4', 'Casi negativi', negatives ? OK : KO,
    negatives ? `${nn(negatives, 'criterio ✗', 'criteri ✗')} per ${list(errs, 4)}` : `risposte d'errore (${list(errs, 4)}) senza criterio ✗`);

  const tb = `${f.Testbook ?? ''}`;
  const domains = [...tbDomains(tb)];
  const proposed = [...story.proposedDomains].filter((d) => !domains.includes(d));
  const jobs = [...tb.matchAll(/job `([^`]+)`/g)].map((m) => m[1]);
  const proofs = proofPaths(tb);
  const citing = [...new Set(ctx.rows.filter((r) => r.stories.has(story.id)).map((r) => r.domain))].sort();
  if (domains.length || proposed.length || jobs.length || proofs.length || citing.length) {
    const parts = domains.map((d) => (ctx.tbDomainsDocumented.has(d) ? `${d} (con righe)` : `${d} (dominio senza righe)`));
    for (const d of proposed) parts.push(`${d} (proposto in §7${ctx.tbDomainsDocumented.has(d) ? ', con righe' : ''})`);
    for (const d of citing) if (!domains.includes(d) && !proposed.includes(d)) parts.push(`${d} (le sue righe citano la storia)`);
    for (const j of jobs) parts.push(`job ${j}`);
    if (proofs.length) parts.push(nn(proofs.length, 'prova automatica', 'prove automatiche'));
    push('R5', 'Dominio di testbook', OK, list(parts, 4));
  } else push('R5', 'Dominio di testbook', KO, tb ? 'nessun dominio TB né job di verifica' : 'campo *Testbook* assente');

  const qs = [...new Set([...story.raw.matchAll(Q_REF)].map((m) => `Q-${m[1]}`))].sort((x, y) => x.slice(2) - y.slice(2));
  if (!qs.length) push('R6', 'Dipendenze e domande', OK, 'nessuna domanda citata');
  else {
    const missing = qs.filter((q) => !ctx.questions.has(q));
    const open = qs.filter((q) => ctx.questions.get(q) === 'APERTA');
    const closed = qs.length - missing.length - open.length;
    const parts = [];
    if (closed) parts.push(nn(closed, 'decisa o superata', 'decise o superate'));
    if (open.length) parts.push(`${nn(open.length, 'aperta', 'aperte')} con default in uso (SPEC-GAP): ${list(open, 4)}`);
    if (missing.length) parts.push(`assenti da docs/15: ${list(missing, 4)}`);
    push('R6', 'Dipendenze e domande', missing.length ? KO : OK, parts.join('; '));
  }

  const seedText = `${f['Contesto reale'] ?? ''} ${story.criteria.filter((c) => !c.includes('✗')).map(precondition).join(' ')}`;
  const seedIds = [...new Set(seedText.match(SEED_REF) ?? [])].sort();
  if (!seedIds.length) push('R7', 'Dati demo', TBD, 'nessun ID di dati demo citato');
  else {
    const missing = seedIds.filter((s) => !ctx.seedTokens.has(s));
    push('R7', 'Dati demo', missing.length ? KO : OK,
      missing.length ? `assenti da seed/: ${list(missing, 4)}` : `in seed/: ${list(seedIds, 5)}`);
  }

  const { screens } = storySubjects(story);
  if (!screens.length) push('R8', 'Stati dell\'interfaccia', OK, 'nessuna schermata');
  else {
    const missing = screens.filter((s) => !ctx.screens.has(s));
    push('R8', 'Stati dell\'interfaccia', missing.length ? KO : OK,
      missing.length ? `schermate non specificate: ${list(missing, 4)}` : `${list(screens, 5)} ${screens.length === 1 ? 'specificata' : 'specificate'}; stati di docs/07 §6`);
  }
  return items;
}

const prLink = (n) => `[#${n}](${REPO_URL}/pull/${n})`;
const blobLink = (p, label = p) => `[${label}](${BLOB}${p.split('/').map(encodeURIComponent).join('/')})`;

/** Voci D1–D4 della Definition of Done. */
export function evaluateDoD(story, ctx) {
  const items = [];
  const push = (id, label, status, evidence) => items.push({ id, label, status, evidence });
  const { features, screens } = storySubjects(story);

  // D1 — feature spuntate in docs/14.
  const inScope = features.filter((x) => !ctx.outOfScope.has(x));
  if (!inScope.length) push('D1', 'Feature completate', TBD, 'nessuna feature F- o F2- nella storia');
  else {
    const done = [];
    const notDone = [];
    const prs = new Set();
    for (const x of inScope) {
      const st = ctx.features.get(x);
      if (st?.status === 'x') done.push(x);
      else notDone.push(`${x} ${!st ? '(assente da docs/14)' : st.status === '~' ? '(in corso)' : '(da fare)'}`);
      for (const n of st?.prs ?? []) prs.add(n);
    }
    const prText = prs.size ? `; PR ${list([...prs].sort((a, b) => a - b).map(prLink), 8)}` : '';
    const where = blobLink('docs/14-STATO-AVANZAMENTO.md', 'docs/14');
    if (!notDone.length) push('D1', 'Feature completate', OK, `${list(done)} ${done.length === 1 ? 'spuntata' : 'spuntate'} in ${where}${prText}`);
    else push('D1', 'Feature completate', KO, `${list(notDone, 4)}${done.length ? `; spuntate: ${list(done, 4)}` : ''}${prText}`);
  }

  // D2 — righe di testbook eseguite o prove automatiche.
  const named = tbRowRefs(story.fields.Testbook ?? '');
  const direct = ctx.rows.filter((r) => r.stories.has(story.id) || named.has(r.id));
  const domains = new Set([...tbDomains(story.fields.Testbook ?? ''), ...story.proposedDomains]);
  const subjects = new Set([...features, ...screens]);
  const byFeature = ctx.rows.filter((r) => domains.has(r.domain) && !r.stories.has(story.id) && [...r.subjects].some((s) => subjects.has(s)));
  const run = (rs) => rs.filter((r) => ctx.executed.has(r.id));
  const directRun = run(direct);
  const featureRun = run(byFeature);
  const proofs = proofPaths(story.fields.Testbook ?? '');
  const proofsFound = proofs.filter((p) => ctx.exists(p));
  const parts = [];
  if (direct.length) parts.push(`${nn(direct.length, 'riga legata', 'righe legate')} alla storia, ${directRun.length} ${directRun.length === 1 ? 'eseguita' : 'eseguite'} dai test: ${list(directRun.length ? directRun.map((r) => r.id) : direct.map((r) => r.id), 4)}`);
  if (byFeature.length) {
    const doms = [...new Set(byFeature.map((r) => r.domain))].sort().join(', ');
    parts.push(`${nn(byFeature.length, 'riga', 'righe')} sulle feature o schermate della storia in ${doms}, ${featureRun.length} ${featureRun.length === 1 ? 'eseguita' : 'eseguite'}: ${list((featureRun.length ? featureRun : byFeature).map((r) => r.id), 3)}`);
  }
  if (proofs.length) {
    parts.push(`prove automatiche: ${proofs.map((p) => (ctx.exists(p) ? blobLink(p, path.posix.basename(p)) : `${path.posix.basename(p)} (file non trovato)`)).join(', ')}`);
  }
  const d2ok = (directRun.length || featureRun.length || proofsFound.length) && proofsFound.length === proofs.length;
  push('D2', 'Criteri provati', d2ok ? OK : KO, parts.length ? parts.join('; ') : 'nessuna riga di testbook né prova automatica');

  // D3 — scostamenti aperti.
  const blocked = (story.raw.match(/⛔/g) ?? []).length;
  const warn = (story.raw.match(/⚠/g) ?? []).length;
  if (!blocked && !warn) push('D3', 'Nessuno scostamento aperto', OK, 'nessun ⛔ né ⚠ nella storia');
  else {
    const p = [];
    if (blocked) p.push(`${blocked} ${blocked === 1 ? 'marcatore' : 'marcatori'} ⛔ (regola senza codice)`);
    if (warn) p.push(`${warn} ${warn === 1 ? 'marcatore' : 'marcatori'} ⚠ (divergenza)`);
    push('D3', 'Nessuno scostamento aperto', KO, p.join(', '));
  }

  push('D4', 'Controlli della fetta', TBD, 'build, stati dell\'interfaccia, OpenAPI, registry, axe, lingue, diagrammi, audit: sulla pull request');
  return items;
}

/** Stato sintetico di una storia. */
export function evaluate(story, ctx) {
  if (story.outOfScope) return { story, dor: [], dod: [], ready: 'na', done: 'na' };
  const dor = evaluateDoR(story, ctx);
  const dod = evaluateDoD(story, ctx);
  const ready = dor.some((i) => i.status === KO) ? 'no' : 'yes';
  const core = dod.filter((i) => i.id !== 'D4');
  const done = core.some((i) => i.status === KO) ? 'no' : core.some((i) => i.status === TBD) ? 'tbd' : 'yes';
  return { story, dor, dod, ready, done };
}

// ---------------------------------------------------------------------------------------------------------------
// Rendering.

const ICON = { [OK]: '✅', [KO]: '⬜', [TBD]: '🔎 da verificare' };
const yaml = (s) => `"${String(s).replace(/\\/g, '\\\\').replace(/"/g, '\\"')}"`;
const plain = (s) => String(s).replace(/`/g, '').replace(/\*\*/g, '').replace(/\*/g, '');
const cap = (s) => (s ? s[0].toUpperCase() + s.slice(1) : s);

export function epicSlug(epic) {
  return `${epic.id.toLowerCase()}-${slugify(epic.name.replace(/\(P1\)/, ''))}`;
}

/** Testo della storia: escape MDX, link alle altre storie e alle pull request (#nn). */
function prose(text, storyIndex) {
  const linked = mapText(String(text), (s) =>
    s
      .replace(/US-(E\d{2}|F2-[A-Z0-9]+)-(\d{2})/g, (id) => {
        const target = storyIndex.get(id);
        return target ? `[${id}](${target})` : id;
      })
      .replace(/(?<![\w/&#])#(\d{2,4})\b/g, (all, n) => prLink(n)));
  return rewriteLinks(escapeMdx(linked));
}

/** Parole chiave del criterio in grassetto: Dato, quando, allora. */
function criterion(text, storyIndex) {
  const bold = mapText(text, (s) =>
    s.replace(/(^|[✗⛔]\s*)(Dat[oaie])\b/, '$1**$2**').replace(/,\s(quando)\b/g, ', **$1**').replace(/,\s(allora)\b/g, ', **$1**'));
  return cap(prose(bold, storyIndex));
}

const cell = (s) => String(s).replace(/\|/g, '\\|').replace(/\n/g, ' ');

function checklist(title, items, storyIndex) {
  const out = [`| ${title} | Esito | Evidenza |`, '|---|---|---|'];
  for (const i of items) out.push(`| ${i.id} ${i.label} | ${ICON[i.status]} | ${cell(prose(i.evidence, storyIndex))} |`);
  return out.join('\n');
}

const READY_LABEL = { yes: '✅ pronta', no: '⬜ non pronta', na: 'non applicabile' };
const DONE_LABEL = { yes: '✅ fatta', no: '⬜ non fatta', tbd: '🔎 da verificare', na: 'non applicabile' };

function renderStory(ev, storyIndex) {
  const s = ev.story;
  const out = [`<a id="${s.id.toLowerCase()}" />`, '', `## ${s.id} · ${escapeMdx(s.title)}`, ''];
  if (s.sentence) out.push(`> ${prose(s.sentence, storyIndex)}`, '');
  if (s.outOfScope) out.push('**Stato**: fuori perimetro della PoC, senza DoR né DoD.', '');
  else {
    const missingR = ev.dor.filter((i) => i.status === KO).map((i) => i.id);
    const missingD = ev.dod.filter((i) => i.status === KO && i.id !== 'D4').map((i) => i.id);
    out.push(`**DoR**: ${READY_LABEL[ev.ready]}${missingR.length ? ` (${missingR.join(', ')})` : ''} · **DoD**: ${DONE_LABEL[ev.done]}${missingD.length ? ` (${missingD.join(', ')})` : ''}`, '');
  }
  for (const [label, key] of [['Contesto reale', 'Contesto reale'], ['Tocca', 'Tocca'], ['Decisioni', 'Decisioni']]) {
    if (s.fields[key]) out.push(`**${label}**: ${prose(s.fields[key], storyIndex)}`, '');
  }
  if (s.criteria.length) {
    out.push('**Criteri di accettazione**', '');
    s.criteria.forEach((c, i) => out.push(`${i + 1}. ${criterion(c, storyIndex)}`));
    out.push('');
  }
  if (s.fields.Testbook) out.push(`**Testbook**: ${prose(s.fields.Testbook, storyIndex)}`, '');
  if (!s.outOfScope) {
    out.push(checklist('Definition of Ready', ev.dor, storyIndex), '');
    out.push(checklist('Definition of Done', ev.dod, storyIndex), '');
  }
  return out.join('\n');
}

function counts(evs) {
  const inScope = evs.filter((e) => e.ready !== 'na');
  return {
    total: evs.length,
    out: evs.length - inScope.length,
    ready: inScope.filter((e) => e.ready === 'yes').length,
    done: inScope.filter((e) => e.done === 'yes').length,
    tbd: inScope.filter((e) => e.done === 'tbd').length,
  };
}

function renderEpic(epic, evs, storyIndex) {
  const c = counts(evs);
  const out = [
    '---',
    `title: ${yaml(`${epic.id} · ${plain(epic.name)}`)}`,
    `sidebarTitle: ${yaml(`${epic.id} ${plain(epic.name)}`)}`,
    `description: ${yaml(`${epic.id} ${plain(epic.name)}: ${plain(epic.goal)}. Storie utente con criteri di accettazione, Definition of Ready e Definition of Done.`)}`,
    '---',
    '',
    HEADER,
    '',
    `**Obiettivo**: ${prose(epic.goal, storyIndex)}.`,
    '',
    `**Attori**: ${prose(epic.actors, storyIndex)} · **Feature**: ${prose(epic.features, storyIndex)} · **Schermate**: ${prose(epic.screens, storyIndex)} · **Servizi**: ${prose(epic.services, storyIndex)} · **Testbook**: ${prose(epic.tb, storyIndex)}`,
    '',
    `Questa epica ha ${c.total} ${c.total === 1 ? 'storia' : 'storie'}${c.out ? `, di cui ${c.out} fuori perimetro` : ''}: ${c.ready} pronte secondo la Definition of Ready, ${c.done} fatte secondo la Definition of Done${c.tbd ? `, ${c.tbd} da verificare` : ''}. Come si legge ogni voce è spiegato nell'[indice del backlog](${PAGE_BASE}#come-leggere-una-storia).`,
    '',
  ];
  for (const ev of evs) out.push(renderStory(ev, storyIndex));
  return `${out.join('\n').replace(/\n{3,}/g, '\n\n').trim()}\n`;
}

/** Il testo di §4.0 in MDX: la nota diventa un componente <Note>. */
function renderDefinitions(md, storyIndex) {
  const out = [];
  for (const line of md.split('\n')) {
    const note = line.match(/^> \*\*Nota:\*\*\s*(.*)$/);
    if (note) out.push('<Note>', `  ${cap(prose(note[1], storyIndex))}`, '</Note>');
    else if (/^\*\*(Definition of Ready|Definition of Done)\*\*/.test(line)) {
      const [, name, rest] = line.match(/^\*\*(Definition of Ready|Definition of Done)\*\*\s*—?\s*(.*)$/);
      out.push(`### ${name}`, '', cap(prose(rest, storyIndex)));
    } else out.push(prose(line, storyIndex));
  }
  return out.join('\n');
}

const DIAGRAM = `\`\`\`mermaid
flowchart LR
  accTitle: Dall'epica alla storia fatta
  accDescr: Un'epica raccoglie storie utente; una storia che soddisfa la Definition of Ready entra in una fetta e arriva su main con una pull request; la storia è fatta quando la Definition of Done trova le feature spuntate in docs/14 e le righe di testbook eseguite dai test.
  EP[Epica] --> ST[Storia utente]
  ST --> DOR{Definition of Ready}
  DOR -->|non pronta| ST
  DOR -->|pronta| FT[Fetta e pull request]
  FT --> DOD{Definition of Done}
  DOD -->|non fatta| FT
  DOD -->|fatta| TB[Testbook eseguito dai test]
  classDef svc fill:#EFF6FF,stroke:#2563EB,color:#1E3A8A
  classDef store fill:#F1F5F9,stroke:#475569,color:#0F172A
  classDef topic fill:#FEF3C7,stroke:#D97706,color:#78350F
  classDef ext fill:#FFFFFF,stroke:#94A3B8,stroke-dasharray:4 2,color:#334155
  classDef human fill:#ECFDF5,stroke:#059669,color:#064E3B
  class EP,ST human
  class FT svc
  class TB store
\`\`\``;

function epicTable(epics, evsByEpic, storyIndex) {
  const out = ['| Epica | Obiettivo | Storie | DoR pronte | DoD fatte |', '|---|---|--:|--:|--:|'];
  const tot = { total: 0, ready: 0, done: 0, out: 0 };
  for (const e of epics) {
    const evs = evsByEpic.get(e.id);
    if (!evs.length) {
      out.push(`| ${e.id} ${escapeMdx(e.name)} | ${prose(e.goal, storyIndex)} | — | — | — |`);
      continue;
    }
    const c = counts(evs);
    for (const k of Object.keys(tot)) tot[k] += c[k];
    const n = c.out ? `${c.total} (${c.out} fuori perimetro)` : `${c.total}`;
    out.push(`| [${e.id} ${escapeMdx(e.name)}](${PAGE_BASE}/${epicSlug(e)}) | ${prose(e.goal, storyIndex)} | ${n} | ${c.ready} | ${c.done} |`);
  }
  out.push(`| **Totale** | | **${tot.total}** | **${tot.ready}** | **${tot.done}** |`);
  return { table: out.join('\n'), tot };
}

function renderIndex(model, evsByEpic, storyIndex) {
  const p1 = model.epics.filter((e) => e.phase === 1);
  const p2 = model.epics.filter((e) => e.phase === 2);
  const t1 = epicTable(p1, evsByEpic, storyIndex);
  const t2 = epicTable(p2, evsByEpic, storyIndex);
  const all = [...evsByEpic.values()].flat();
  const c = counts(all);
  const reasons = (items, prefix) => {
    const m = new Map();
    for (const ev of all) for (const i of ev[items]) if (i.status === KO && i.id.startsWith(prefix)) m.set(`${i.id} ${i.label}`, (m.get(`${i.id} ${i.label}`) ?? 0) + 1);
    return [...m].sort((a, b) => a[0].localeCompare(b[0])).map(([k, v]) => `| ${k} | ${v} |`).join('\n');
  };
  return `---
title: "Backlog: epiche e storie utente"
sidebarTitle: "Backlog"
description: "Epiche e storie utente di Fase 1 e Fase 2 in formato agile, con criteri di accettazione, Definition of Ready e Definition of Done calcolate dalle evidenze del repository."
---

${HEADER}

Il backlog raccoglie le epiche e le storie utente del Loyalty Hub, di Fase 1 (la PoC) e di Fase 2 (la piattaforma enterprise). Ogni storia ha la forma delle cerimonie agile: chi la chiede, cosa vuole e perché, il contesto reale del programma demo «Club Aurora», i criteri di accettazione *Dato/Quando/Allora* e il dominio del testbook che la prova.

Usa queste pagine per sapere, storia per storia, se è **pronta** per entrare in una fetta (Definition of Ready) e se è **fatta** (Definition of Done). Lo stato non lo scrive nessuno a mano: lo calcola \`scripts/docs-sync.mjs\` dal sorgente [\`docs/17-EPIC-E-STORIE.md\`](${BLOB}${SOURCE}) e dalle evidenze del repository.

<Note>
  Oggi il backlog ha ${c.total} storie: ${c.ready} pronte, ${c.done} fatte${c.tbd ? `, ${c.tbd} con la DoD da verificare` : ''} e ${c.out} fuori perimetro della PoC.
</Note>

## Dall'epica alla storia fatta

Una storia nasce dentro un'epica. Quando soddisfa la Definition of Ready entra in una fetta, cioè un ramo e una pull request verso \`main\`. Dopo il merge la Definition of Done cerca le evidenze: la feature spuntata in \`docs/14\`, le righe di testbook eseguite dai test, nessuno scostamento aperto.

${DIAGRAM}

## Epiche di Fase 1

Le epiche della PoC: il ciclo completo dall'azione del cliente ai punti, ai premi, al gioco e alla governance.

${t1.table}

## Epiche di Fase 2

Le epiche della piattaforma enterprise (\`docs/18\`). Le epiche marcate P1 ricevono le storie con la loro milestone.

${t2.table}

## Definition of Ready e Definition of Done

${renderDefinitions(model.dordod, storyIndex)}

## Come leggere una storia

Ogni pagina di epica elenca le sue storie nell'ordine del sorgente. L'ancora di ogni storia è il suo ID, per esempio \`${PAGE_BASE}/e01-ingresso-eventi#us-e01-01\`. Per ogni storia trovi:

1. la frase *Come … voglio … così che …*;
2. lo stato sintetico: **DoR** pronta o non pronta, **DoD** fatta, non fatta o da verificare, con le voci mancanti tra parentesi;
3. contesto reale, feature e schermate toccate (*Tocca*), decisioni;
4. i criteri di accettazione, con **Dato**, **quando** e **allora** in grassetto; i criteri negativi sono marcati ✗, le regole di specifica senza codice ⛔ e le divergenze ⚠;
5. le due liste di controllo, con l'esito di ogni voce e l'evidenza trovata.

Gli esiti delle voci sono tre:

- ✅ la voce è soddisfatta e l'evidenza lo dimostra;
- ⬜ la voce non è soddisfatta; l'evidenza dice cosa manca;
- 🔎 da verificare: lo script non può deciderlo da solo. Nella DoR non blocca la storia; nella DoD la lascia «da verificare».

## Come si calcola lo stato

\`scripts/docs-sync.mjs\` legge solo file del repository, senza rete:

- **DoR**: i campi della storia in \`docs/17\` (frase, *Tocca*, criteri, *Testbook*), la matrice di \`docs/17 §6.1\` e i domini proposti in \`docs/17 §7\`, le domande di \`docs/15\`, i token dei dati demo in \`seed/\`, le schermate specificate in \`docs/07\`, \`docs/08\`, \`docs/09\` e \`docs/18 §5\`.
- **D1**: le righe \`[x]\` di \`docs/14\` per ogni \`F-\` e \`F2-\` della storia e i numeri delle pull request sulla stessa riga. Una feature della sezione «Fuori PoC» non conta.
- **D2**: le righe \`TB-*\` di \`docs/16\` e \`docs/testbook/\` legate alla storia (la citano nella riga, nel titolo della sezione o nella regola dell'inventario che copre la loro area, oppure il campo *Testbook* le nomina) o, nel dominio della storia, alle sue feature e schermate. Il dominio viene dal campo *Testbook* o dalla proposta di \`docs/17 §7\`; le feature dal campo *Tocca* o dalla matrice di \`docs/17 §6.1\`. Una riga conta come eseguita se il suo ID compare nei sorgenti dei test (\`src/test/\`, \`*.test.ts\`, \`e2e/\`). In alternativa valgono i file di prova citati nel campo *Testbook*, se esistono.
- **D3**: i marcatori ⛔ e ⚠ nel testo della storia.

Per rigenerare le pagine dopo una modifica a \`docs/17\` o alle evidenze, esegui:

\`\`\`bash
# Rigenera le pagine del backlog e il gruppo «Backlog» di docs.json
node scripts/docs-sync.mjs
# Verifica che le pagine committate siano aggiornate (lo esegue il job guard)
node scripts/docs-sync.mjs --check
\`\`\`

## Limiti noti

- Una riga di testbook conta come eseguita quando il suo ID compare nei sorgenti dei test, non quando il test è verde: l'esito lo dà \`scripts/testbook.sh\` in CI.
- Le righe trovate tramite le feature della storia provano la regola della feature, non necessariamente ogni criterio della storia.
- Una riga eredita storie e feature dalle regole dell'inventario del suo testbook che coprono la sua area: è un collegamento per area, più largo di quello per singola riga.
- Il campo *Testbook* di una storia riporta il testo del sorgente, che può essere più vecchio delle evidenze: fa fede la voce D2.
- La voce D4 (controlli della pull request) non si deduce dal repository e resta da verificare sulla pull request della fetta.
- Una storia senza ID di feature ha D1 da verificare: la sua DoD resta «da verificare» anche con le righe di testbook.

## Voci mancanti più frequenti

| Voce della DoR | Storie |
|---|--:|
${reasons('dor', 'R')}

| Voce della DoD | Storie |
|---|--:|
${reasons('dod', 'D')}
`;
}

// ---------------------------------------------------------------------------------------------------------------
// Generazione e navigazione.

/** Aggiorna il gruppo «Backlog» della scheda «Specifiche tecniche» di docs.json. */
export function updateDocsJson(json, model) {
  const doc = JSON.parse(json);
  const tab = doc.navigation.tabs.find((t) => t.tab === 'Specifiche tecniche');
  if (!tab) throw new Error('docs.json: scheda «Specifiche tecniche» assente');
  for (const g of tab.groups) g.pages = g.pages.filter((p) => p !== 'specifiche/backlog');
  const pagesOf = (phase) => model.epics.filter((e) => e.phase === phase && e.stories.length).map((e) => `${OUT_DIR}/${epicSlug(e)}`);
  const group = {
    group: 'Backlog',
    pages: ['specifiche/backlog', { group: 'Fase 1', pages: pagesOf(1) }, { group: 'Fase 2', pages: pagesOf(2) }],
  };
  const i = tab.groups.findIndex((g) => g.group === 'Backlog');
  if (i >= 0) tab.groups[i] = group;
  else {
    const after = tab.groups.findIndex((g) => g.group === 'Dati & processo');
    tab.groups.splice(after < 0 ? tab.groups.length : after + 1, 0, group);
  }
  return `${JSON.stringify(doc, null, 2)}\n`;
}

/** Tutti i file generati: percorso → contenuto. */
export function generate(root) {
  const model = parseBacklog(fs.readFileSync(path.join(root, SOURCE), 'utf8'));
  const ctx = loadContext(root);
  const storyIndex = new Map();
  for (const e of model.epics) for (const s of e.stories) storyIndex.set(s.id, `${PAGE_BASE}/${epicSlug(e)}#${s.id.toLowerCase()}`);
  const evsByEpic = new Map(model.epics.map((e) => [e.id, e.stories.map((s) => evaluate(s, ctx))]));
  const files = new Map();
  files.set(OUT_INDEX, renderIndex(model, evsByEpic, storyIndex));
  for (const e of model.epics) {
    if (!e.stories.length) continue;
    files.set(`${OUT_DIR}/${epicSlug(e)}.mdx`, renderEpic(e, evsByEpic.get(e.id), storyIndex));
  }
  files.set('docs.json', updateDocsJson(fs.readFileSync(path.join(root, 'docs.json'), 'utf8'), model));
  return { files, model, evsByEpic };
}

function main(argv) {
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
  const check = argv.includes('--check');
  const { files, evsByEpic } = generate(root);
  const stale = [];
  const existing = fs.existsSync(path.join(root, OUT_DIR))
    ? fs.readdirSync(path.join(root, OUT_DIR)).filter((f) => f.endsWith('.mdx')).map((f) => `${OUT_DIR}/${f}`)
    : [];
  const orphans = existing.filter((f) => !files.has(f));
  for (const [p, content] of files) {
    const abs = path.join(root, p);
    const current = fs.existsSync(abs) ? fs.readFileSync(abs, 'utf8') : null;
    if (current === content) continue;
    stale.push(p);
    if (!check) {
      fs.mkdirSync(path.dirname(abs), { recursive: true });
      fs.writeFileSync(abs, content);
    }
  }
  if (!check) for (const o of orphans) fs.rmSync(path.join(root, o));
  const c = counts([...evsByEpic.values()].flat());
  if (check) {
    if (stale.length || orphans.length) {
      for (const p of stale) console.error(`✗ ${p}: diverso dal generato`);
      for (const p of orphans) console.error(`✗ ${p}: pagina non più generata`);
      console.error('Rigenera con: node scripts/docs-sync.mjs (e committa il risultato).');
      process.exit(1);
    }
    console.log(`docs-sync: ok (${files.size} file verificati; ${c.total} storie, ${c.ready} pronte, ${c.done} fatte)`);
    return;
  }
  console.log(`docs-sync: ${stale.length} file scritti, ${orphans.length} rimossi; ${c.total} storie, ${c.ready} pronte, ${c.done} fatte, ${c.out} fuori perimetro`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) main(process.argv.slice(2));
