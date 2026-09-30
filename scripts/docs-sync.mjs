#!/usr/bin/env node
// docs-sync — genera le pagine Mintlify delle specifiche dai file di docs/ e di contracts/events/ (ADR-040, docs/18 §3.12).
// Prima parte (M8.9b, F2-DOC-02): il backlog di docs/17 con Definition of Ready e Definition of Done.
// Seconda parte (M8.9c, F2-DOC-01): le pagine degli eventi (specifiche/eventi/*.mdx), una per famiglia, dagli schemi
// JSON di contracts/events/ (type, versioni, campi, x-lh-pii); la famiglia dlq non ha schemi e ha una pagina fissa.
//
// Le voci che dipendono solo dal testo della storia (R1–R4, D3) si calcolano a ogni esecuzione da docs/17. Le voci che
// dipendono dalle evidenze del repository (R5–R8, D1, D2, D4) stanno in uno snapshot committato,
// `specifiche/backlog/_status.json`, che si aggiorna con --refresh nella PR di stato cumulativa (ADR-047).
// Le pagine sono quindi una funzione pura di docs/17, del generatore e dello snapshot: una PR di codice non le rompe.
//
// Uso:
//   node scripts/docs-sync.mjs                # rigenera le pagine dallo snapshot esistente (nessuna evidenza letta)
//   node scripts/docs-sync.mjs --backlog      # alias del precedente
//   node scripts/docs-sync.mjs --check        # solo struttura (job guard): pagine, gruppo «Backlog» di docs.json e
//                                             # copertura dello snapshot e pagine degli
//                                             # eventi da contracts/events/; non legge docs/14, docs/15, docs/16,
//                                             # testbook, sorgenti dei test, seed né le intestazioni di docs/07–09 e docs/18
//   node scripts/docs-sync.mjs --refresh      # rilegge le evidenze del repository, riscrive snapshot, pagine e docs.json
//   node scripts/docs-sync.mjs --check-status # avviso: quante storie cambierebbero con --refresh (esce sempre 0)
//
// Nessuna dipendenza: solo moduli di Node, così gira anche nel job `guard` senza `npm ci`.
// Le evidenze vengono da docs/14 (feature spuntate e PR), docs/16 e docs/testbook/ (righe TB-*), dai sorgenti dei test
// (ID TB-* presenti), dai file di prova citati nelle storie e da .github/workflows/ (job e script invocati).
// Nessuna rete, nessun orologio: stesso input, stesso output; il refresh registra lo SHA breve del commit di HEAD.
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

export const REPO_URL = 'https://github.com/peppe-ruv/Loyalty_Sys';
const BLOB = `${REPO_URL}/blob/main/`;
const SOURCE = 'docs/17-EPIC-E-STORIE.md';
const OUT_INDEX = 'specifiche/backlog.mdx';
const OUT_DIR = 'specifiche/backlog';
export const SNAPSHOT = `${OUT_DIR}/_status.json`;
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

/**
 * Escape per MDX fuori dal codice inline, in un solo passaggio (nessun doppio escape): la barra rovesciata
 * diventa `\\` (altrimenti `a\{b` si leggerebbe come barra escapata seguita da `{` libera), `<` diventa `&lt;`,
 * le graffe si proteggono. Con `{ cell: true }` il testo è il contenuto di una cella di tabella GFM: `|` diventa
 * `\|` (anche dentro il codice inline, dove GFM lo richiede) e gli a capo diventano spazi. Dentro il codice
 * inline le barre rovesciate restano intatte, salvo quelle che precedono una `|` in una cella: lì `\|` varrebbe
 * `|`, quindi si raddoppiano per non spezzare la riga (GFM non sa rappresentare un `\|` letterale in un codice
 * di cella: il render mostra una barra in più, ma le colonne restano corrette).
 */
export function escapeMdx(text, { cell = false } = {}) {
  const outside = { '\\': '\\\\', '<': '&lt;', '{': '\\{', '}': '\\}', '|': cell ? '\\|' : '|' };
  const flat = (s) => (cell ? s.replace(/\r?\n/g, ' ') : s);
  return splitCode(String(text))
    .map((p) => {
      if (!p.code) return flat(p.s).replace(/[\\<{}|]/g, (c) => outside[c]);
      if (!cell) return p.s;
      return flat(p.s).replace(/\\*\|/g, (m) => `${'\\'.repeat(2 * (m.length - 1))}\\|`);
    })
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

/**
 * I workflow di GitHub Actions: i job (id e `name:`) per file e gli script davvero invocati da una riga non commentata
 * (`node scripts/x.mjs`, `bash scripts/y.sh`). Senza dipendenze: legge solo il livello dei job.
 */
export function parseWorkflows(files) {
  const jobsByFile = new Map();
  const runLines = [];
  for (const [file, text] of files) {
    const jobs = new Set();
    let inJobs = false;
    let cur = null;
    let named = false;
    for (const line of text.split('\n')) {
      if (/^\s*#/.test(line)) continue;
      runLines.push(line);
      if (/^jobs:\s*$/.test(line)) { inJobs = true; continue; }
      if (inJobs && /^\S/.test(line)) inJobs = false;
      if (!inJobs) continue;
      const id = line.match(/^ {2}([A-Za-z0-9_-]+):\s*(?:#.*)?$/);
      if (id) { cur = id[1]; named = false; jobs.add(cur); continue; }
      const nm = cur && !named && line.match(/^ {4}name:\s*(.+?)\s*$/);
      if (nm) { jobs.add(nm[1].replace(/^(["'])(.*)\1$/, '$2')); named = true; }
    }
    jobsByFile.set(file, jobs);
  }
  const jobs = new Set([...jobsByFile.values()].flatMap((j) => [...j]));
  const invoked = (p) => runLines.some((l) => {
    const i = l.indexOf(p);
    return i > 0 && /\b(?:node|bash|sh|npx|pnpm|python3?)\b/.test(l.slice(0, i));
  });
  return { jobsByFile, jobs, invoked };
}

function loadWorkflows(root) {
  const dir = '.github/workflows';
  const files = new Map();
  if (fs.existsSync(path.join(root, dir))) {
    for (const f of fs.readdirSync(path.join(root, dir)).sort()) {
      if (/\.ya?ml$/.test(f)) files.set(`${dir}/${f}`, fs.readFileSync(path.join(root, dir, f), 'utf8'));
    }
  }
  return parseWorkflows(files);
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
    // Sezioni: dall'intestazione (## o ###) alla successiva. Conta l'intero corpo, «**Regola.**» compresa: le feature e
    // le storie citate lì valgono per tutte le righe della sezione.
    const sections = [];
    let h2 = '';
    let h3 = '';
    let sec = null;
    for (const l of text) {
      if (/^## /.test(l)) { h2 = l; h3 = ''; sec = { h2, h3, body: [] }; sections.push(sec); }
      else if (/^### /.test(l)) { h3 = l; sec = { h2, h3, body: [] }; sections.push(sec); }
      if (sec) sec.body.push(l);
    }
    for (const sc of sections) {
      const body = sc.body.join('\n');
      const sectionStories = new Set([...storyRefs(sc.h2), ...storyRefs(sc.h3), ...storyRefs(body)]);
      const sectionFeatures = featureRefs(body);
      for (const l of sc.body) {
        const m = l.match(/^\|\s*`?(TB-[A-Z0-9]{3}(?:-[A-Z]{2,5})?-\d{3,4})`?\s*\|/);
        if (!m) continue;
        const id = m[1];
        const domain = id.slice(0, 6);
        const area = (id.match(/^TB-[A-Z0-9]{3}-([A-Z]{2,5})-/) ?? [])[1];
        tbDomainsDocumented.add(domain);
        const row = {
          id, domain, file: f,
          stories: new Set([...storyRefs(l), ...sectionStories]),
          subjects: new Set([...featureRefs(l), ...screenRefs(l), ...sectionFeatures]),
        };
        for (const r of rules) {
          if (!r.areas.has('*') && !(area && r.areas.has(area))) continue;
          for (const x of r.stories) row.stories.add(x);
          for (const x of r.subjects) row.subjects.add(x);
        }
        rows.push(row);
      }
    }
  }

  // ID TB-* nei sorgenti dei test (CSV dei casi, classi Testbook*, file *.test.ts).
  const executed = new Set();
  // I file versionati (git ls-files), così il risultato locale coincide con quello della CI; senza git, la cartella.
  const dirs = ['services', 'libs', 'deploy', 'web', 'e2e'];
  let files = [];
  try {
    files = execFileSync('git', ['ls-files', '-z', '--', ...dirs], { cwd: root, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024, stdio: ['ignore', 'pipe', 'ignore'] })
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

  return { features, outOfScope, questions, rows, tbDomainsDocumented, executed, seedTokens, screens, exists, ...loadWorkflows(root) };
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

const LABEL = {
  R1: 'Forma della storia', R2: 'ID di specifica', R3: 'Criteri di accettazione', R4: 'Casi negativi',
  R5: 'Dominio di testbook', R6: 'Dipendenze e domande', R7: 'Dati demo', R8: 'Stati dell\'interfaccia',
  D1: 'Feature completate', D2: 'Criteri provati', D3: 'Nessuno scostamento aperto', D4: 'Controlli della fetta',
};
/** Le voci che dipendono dalle evidenze del repository: stanno nello snapshot. Le altre si calcolano dal testo. */
export const EVIDENCE_DOR = ['R5', 'R6', 'R7', 'R8'];
export const EVIDENCE_DOD = ['D1', 'D2', 'D4'];
const item = (id, status, evidence) => ({ id, label: LABEL[id], status, evidence });

/** I job citati nel campo *Testbook* («job `guard`»). */
const citedJobs = (tb) => [...new Set([...String(tb).matchAll(/job `([^`]+)`/g)].map((m) => m[1]))];

/** Voci R1–R4 della Definition of Ready: dipendono solo dal testo della storia. */
export function textDoR(story) {
  const items = [];
  const push = (id, status, evidence) => items.push(item(id, status, evidence));
  const f = story.fields;

  push('R1',
    /\*Come\*[\s\S]*\*vogli(?:o|amo)\*[\s\S]*\*così che\*/i.test(story.sentence) ? OK : KO,
    /\*Come\*/i.test(story.sentence) ? 'frase *Come … voglio … così che …*' : 'manca la frase *Come … voglio … così che …*');

  const fromTouch = [...new Set(`${f.Tocca ?? ''}`.match(SPEC_ID) ?? [])];
  const fromMatrix = [...new Set(`${story.matrix ?? ''}`.match(SPEC_ID) ?? [])].filter((x) => !fromTouch.includes(x));
  if (fromTouch.length) push('R2', OK, list(fromTouch));
  else if (fromMatrix.length) push('R2', OK, `${list(fromMatrix)} (dalla matrice di §6.1)`);
  else push('R2', KO, 'nessun ID nel campo *Tocca* né nella matrice di §6.1');

  const pending = /da scrivere con la fetta/i.test(`${f.Criteri ?? ''} ${story.criteria.join(' ')}`);
  const gwt = story.criteria.filter((c) => /\bDat[oaie]\b/i.test(c) && /\ballora\b/i.test(c));
  let r3;
  if (!story.criteria.length) r3 = [KO, 'nessun criterio'];
  else if (pending && !gwt.length) r3 = [KO, 'criteri da scrivere con la fetta'];
  else if (pending) r3 = [KO, `${nn(gwt.length, 'criterio', 'criteri')} Dato/Quando/Allora, altri da scrivere con la fetta`];
  else if (!gwt.length) r3 = [KO, 'criteri senza la forma Dato/Quando/Allora'];
  else r3 = [OK, `${nn(gwt.length, 'criterio', 'criteri')} Dato/Quando/Allora`];
  push('R3', ...r3);

  const errs = errorPaths(story);
  const negatives = story.criteria.filter((c) => c.includes('✗')).length;
  if (!errs.length) push('R4', OK, negatives ? nn(negatives, 'criterio ✗', 'criteri ✗') : 'nessuna risposta d\'errore 4xx nella storia');
  else push('R4', negatives ? OK : KO,
    negatives ? `${nn(negatives, 'criterio ✗', 'criteri ✗')} per ${list(errs, 4)}` : `risposte d'errore (${list(errs, 4)}) senza criterio ✗`);
  return items;
}

const SPEC_ID = /\b(?:F2?-[A-Z0-9]+-\d{2}|RNF-\d{2}|BO-\d{2}|PT-\d{2}|HUB-\d{2}|EVT-[A-Z]+-\d{2}|ADR-\d{3})\b|\bdocs\/\d{2} §\s?[\w.-]+/g;

/** Voci R5–R8 della Definition of Ready: dipendono dalle evidenze del repository. */
export function evidenceDoR(story, ctx) {
  const items = [];
  const push = (id, status, evidence) => items.push(item(id, status, evidence));
  const f = story.fields;

  const tb = `${f.Testbook ?? ''}`;
  const domains = [...tbDomains(tb)];
  const proposed = [...story.proposedDomains].filter((d) => !domains.includes(d));
  // Un job citato conta solo se esiste in .github/workflows/*.yml (per id o per `name:`).
  const jobsCited = citedJobs(tb);
  const jobsOk = jobsCited.filter((j) => ctx.jobs?.has(j));
  const jobsBad = jobsCited.filter((j) => !jobsOk.includes(j));
  const proofs = proofPaths(tb);
  const citing = [...new Set(ctx.rows.filter((r) => r.stories.has(story.id)).map((r) => r.domain))].sort();
  if (domains.length || proposed.length || jobsOk.length || proofs.length || citing.length) {
    const parts = domains.map((d) => (ctx.tbDomainsDocumented.has(d) ? `${d} (con righe)` : `${d} (dominio senza righe)`));
    for (const d of proposed) parts.push(`${d} (proposto in §7${ctx.tbDomainsDocumented.has(d) ? ', con righe' : ''})`);
    for (const d of citing) if (!domains.includes(d) && !proposed.includes(d)) parts.push(`${d} (le sue righe citano la storia)`);
    for (const j of jobsOk) parts.push(`job ${j}`);
    for (const j of jobsBad) parts.push(`job ${j} (assente da .github/workflows)`);
    if (proofs.length) parts.push(nn(proofs.length, 'prova automatica', 'prove automatiche'));
    push('R5', OK, list(parts, 4));
  } else {
    push('R5', KO, !tb ? 'campo *Testbook* assente'
      : jobsBad.length ? `nessun dominio TB; job ${list(jobsBad, 3)} assente da .github/workflows` : 'nessun dominio TB né job di verifica');
  }

  const qs = [...new Set([...story.raw.matchAll(Q_REF)].map((m) => `Q-${m[1]}`))].sort((x, y) => x.slice(2) - y.slice(2));
  if (!qs.length) push('R6', OK, 'nessuna domanda citata');
  else {
    const missing = qs.filter((q) => !ctx.questions.has(q));
    const open = qs.filter((q) => ctx.questions.get(q) === 'APERTA');
    const closed = qs.length - missing.length - open.length;
    const parts = [];
    if (closed) parts.push(nn(closed, 'decisa o superata', 'decise o superate'));
    if (open.length) parts.push(`${nn(open.length, 'aperta', 'aperte')} con default in uso (SPEC-GAP): ${list(open, 4)}`);
    if (missing.length) parts.push(`assenti da docs/15: ${list(missing, 4)}`);
    push('R6', missing.length ? KO : OK, parts.join('; '));
  }

  const seedText = `${f['Contesto reale'] ?? ''} ${story.criteria.filter((c) => !c.includes('✗')).map(precondition).join(' ')}`;
  const seedIds = [...new Set(seedText.match(SEED_REF) ?? [])].sort();
  if (!seedIds.length) push('R7', TBD, 'nessun ID di dati demo citato');
  else {
    const missing = seedIds.filter((s) => !ctx.seedTokens.has(s));
    push('R7', missing.length ? KO : OK,
      missing.length ? `assenti da seed/: ${list(missing, 4)}` : `in seed/: ${list(seedIds, 5)}`);
  }

  const { screens } = storySubjects(story);
  if (!screens.length) push('R8', OK, 'nessuna schermata');
  else {
    const missing = screens.filter((s) => !ctx.screens.has(s));
    push('R8', missing.length ? KO : OK,
      missing.length ? `schermate non specificate: ${list(missing, 4)}` : `${list(screens, 5)} ${screens.length === 1 ? 'specificata' : 'specificate'}; stati di docs/07 §6`);
  }
  return items;
}

/** Voci R1–R8 della Definition of Ready, valutate su un contesto di evidenze. */
export function evaluateDoR(story, ctx) {
  return [...textDoR(story), ...evidenceDoR(story, ctx)];
}

const prLink = (n) => `[#${n}](${REPO_URL}/pull/${n})`;
const blobLink = (p, label = p) => `[${label}](${BLOB}${p.split('/').map(encodeURIComponent).join('/')})`;

/** Una prova automatica citata nel campo *Testbook*: conta solo se esiste ed è davvero agganciata alla CI. */
function checkProof(p, ctx, jobsOk) {
  if (!ctx.exists(p)) return { p, ok: false, missing: true, note: 'file non trovato' };
  if (p.startsWith('.github/workflows/')) {
    // Un workflow da solo non prova nulla: serve un job citato che esista in quel file.
    const paired = jobsOk.filter((j) => ctx.jobsByFile?.get(p)?.has(j));
    return paired.length ? { p, ok: true, note: `job ${paired.join(', ')}` } : { p, ok: false, note: 'nessun job citato esiste in questo workflow: non è una prova' };
  }
  if (p.startsWith('scripts/')) {
    return ctx.invoked?.(p) ? { p, ok: true } : { p, ok: false, note: 'nessun workflow lo invoca: non è una prova in CI' };
  }
  return { p, ok: true };
}

/** Un criterio che il testo stesso dichiara ancora scoperto («residuo dichiarato», «divergenza residua», «prova … da fare»). */
const RESIDUAL = /residuo dichiarato|residuo aperto|divergenza residua|residuo \(?TOBE|prova[^.;)]*da fare/i;

/** Voci D1, D2 e D4 della Definition of Done: dipendono dalle evidenze. D2 qui è la sola evidenza (senza i cancelli di testo). */
export function evidenceDoD(story, ctx) {
  const items = [];
  const push = (id, status, evidence) => items.push(item(id, status, evidence));
  const { features, screens } = storySubjects(story);

  // D1 — feature spuntate in docs/14; in Fase 2 anche il numero della pull request (DoD 11).
  const inScope = features.filter((x) => !ctx.outOfScope.has(x));
  if (!inScope.length) push('D1', TBD, 'nessuna feature F- o F2- nella storia');
  else {
    const done = [];
    const notDone = [];
    const prs = new Set();
    for (const x of inScope) {
      const st = ctx.features.get(x);
      if (st?.status === 'x' && story.phase === 2 && !st.prs.length) notDone.push(`${x} (spuntata senza numero di PR)`);
      else if (st?.status === 'x') done.push(x);
      else notDone.push(`${x} ${!st ? '(assente da docs/14)' : st.status === '~' ? '(in corso)' : '(da fare)'}`);
      for (const n of st?.prs ?? []) prs.add(n);
    }
    const prText = prs.size ? `; PR ${list([...prs].sort((a, b) => a - b).map(prLink), 8)}` : '';
    const where = blobLink('docs/14-STATO-AVANZAMENTO.md', 'docs/14');
    if (!notDone.length) push('D1', OK, `${list(done)} ${done.length === 1 ? 'spuntata' : 'spuntate'} in ${where}${prText}`);
    else push('D1', KO, `${list(notDone, 4)}${done.length ? `; spuntate: ${list(done, 4)}` : ''}${prText}`);
  }

  // D2 — righe di testbook eseguite o prove automatiche agganciate alla CI.
  const tb = story.fields.Testbook ?? '';
  const named = tbRowRefs(tb);
  const direct = ctx.rows.filter((r) => r.stories.has(story.id) || named.has(r.id));
  const domains = new Set([...tbDomains(tb), ...story.proposedDomains]);
  const subjects = new Set([...features, ...screens]);
  const byFeature = ctx.rows.filter((r) => domains.has(r.domain) && !r.stories.has(story.id) && [...r.subjects].some((s) => subjects.has(s)));
  const run = (rs) => rs.filter((r) => ctx.executed.has(r.id));
  const directRun = run(direct);
  const featureRun = run(byFeature);
  const jobsCited = citedJobs(tb);
  const jobsOk = jobsCited.filter((j) => ctx.jobs?.has(j));
  const jobsBad = jobsCited.filter((j) => !jobsOk.includes(j));
  const proofs = proofPaths(tb).map((p) => checkProof(p, ctx, jobsOk));
  const proofsOk = proofs.filter((x) => x.ok);
  const proofsMissing = proofs.filter((x) => x.missing);
  const parts = [];
  if (direct.length) parts.push(`${nn(direct.length, 'riga legata', 'righe legate')} alla storia, ${directRun.length} ${directRun.length === 1 ? 'eseguita' : 'eseguite'} dai test: ${list(directRun.length ? directRun.map((r) => r.id) : direct.map((r) => r.id), 4)}`);
  if (byFeature.length) {
    const doms = [...new Set(byFeature.map((r) => r.domain))].sort().join(', ');
    parts.push(`${nn(byFeature.length, 'riga', 'righe')} sulle feature o schermate della storia in ${doms}, ${featureRun.length} ${featureRun.length === 1 ? 'eseguita' : 'eseguite'}: ${list((featureRun.length ? featureRun : byFeature).map((r) => r.id), 3)}`);
  }
  if (proofs.length) {
    parts.push(`prove automatiche: ${proofs.map((x) => (x.ok ? `${blobLink(x.p, path.posix.basename(x.p))}${x.note ? ` (${x.note})` : ''}` : `${path.posix.basename(x.p)} (${x.note})`)).join(', ')}`);
  }
  if (jobsBad.length) parts.push(`job ${list(jobsBad, 3)}: assente da .github/workflows`);
  const d2ok = (directRun.length || featureRun.length || proofsOk.length) && !proofsMissing.length;
  push('D2', d2ok ? OK : KO, parts.length ? parts.join('; ') : 'nessuna riga di testbook né prova automatica');

  push('D4', TBD, 'build, stati dell\'interfaccia, OpenAPI, registry, axe, lingue, diagrammi, audit: sulla pull request');
  return items;
}

/** Perché D2 non può essere soddisfatta a prescindere dalle prove: criteri non pronti o residui dichiarati. */
function d2Gates(story, r3) {
  const gates = [];
  if (r3.status === KO) gates.push('criteri non pronti (R3): nessuna prova conta finché non sono tutti Dato/Quando/Allora');
  if (story.criteria.some((c) => RESIDUAL.test(c))) gates.push('un criterio dichiara un residuo o una prova da fare: le prove citate non lo coprono');
  return gates;
}

/** D3 — scostamenti aperti: dipende solo dal testo della storia. */
export function textDoD(story) {
  const blocked = (story.raw.match(/⛔/g) ?? []).length;
  const warn = (story.raw.match(/⚠/g) ?? []).length;
  if (!blocked && !warn) return item('D3', OK, 'nessun ⛔ né ⚠ nella storia');
  const p = [];
  if (blocked) p.push(`${blocked} ${blocked === 1 ? 'marcatore' : 'marcatori'} ⛔ (regola senza codice)`);
  if (warn) p.push(`${warn} ${warn === 1 ? 'marcatore' : 'marcatori'} ⚠ (divergenza)`);
  return item('D3', KO, p.join(', '));
}

/** Voci D1–D4: D3 e i cancelli di D2 dal testo della storia, D1, D2 e D4 dalle evidenze. */
function assembleDoD(story, evidence, r3) {
  const by = Object.fromEntries(evidence.map((i) => [i.id, i]));
  const gates = d2Gates(story, r3);
  const d2 = gates.length ? item('D2', KO, `${gates.join('; ')}; evidenza: ${by.D2.evidence}`) : by.D2;
  return [by.D1, d2, textDoD(story), by.D4];
}

/** Voci D1–D4 della Definition of Done, valutate su un contesto di evidenze. */
export function evaluateDoD(story, ctx) {
  const r3 = textDoR(story).find((i) => i.id === 'R3');
  return assembleDoD(story, evidenceDoD(story, ctx), r3);
}

/** Stato sintetico di una storia da DoR e DoD già assemblate. */
function summarize(story, dor, dod) {
  const ready = dor.some((i) => i.status === KO) ? 'no' : 'yes';
  const core = dod.filter((i) => i.id !== 'D4');
  const done = core.some((i) => i.status === KO) ? 'no' : core.some((i) => i.status === TBD) ? 'tbd' : 'yes';
  return { story, dor, dod, ready, done };
}

/** Le voci basate sulle evidenze di una storia (la voce dello snapshot), o null se è fuori perimetro. */
export function evidenceOf(story, ctx) {
  if (story.outOfScope) return null;
  return { dor: evidenceDoR(story, ctx), dod: evidenceDoD(story, ctx) };
}

/** Stato di una storia da evidenze appena lette (usato dal refresh e dai test). */
export function evaluate(story, ctx) {
  if (story.outOfScope) return { story, dor: [], dod: [], ready: 'na', done: 'na' };
  return evaluateWith(story, evidenceOf(story, ctx));
}

/** Stato di una storia dalle voci basate sulle evidenze (di una lettura o dello snapshot) e dal suo testo. */
export function evaluateWith(story, evidence) {
  if (story.outOfScope) return { story, dor: [], dod: [], ready: 'na', done: 'na' };
  const text = textDoR(story);
  const dor = [...text, ...evidence.dor];
  const dod = assembleDoD(story, evidence.dod, text.find((i) => i.id === 'R3'));
  return summarize(story, dor, dod);
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
function prose(text, storyIndex, opts) {
  const linked = mapText(String(text), (s) =>
    s
      .replace(/US-(E\d{2}|F2-[A-Z0-9]+)-(\d{2})/g, (id) => {
        const target = storyIndex.get(id);
        return target ? `[${id}](${target})` : id;
      })
      .replace(/(?<![\w/&#])#(\d{1,4})\b/g, (all, n) => prLink(n)));
  return rewriteLinks(escapeMdx(linked, opts));
}

/** Parole chiave del criterio in grassetto: Dato, quando, allora. */
function criterion(text, storyIndex) {
  const bold = mapText(text, (s) =>
    s.replace(/(^|[✗⛔]\s*)(Dat[oaie])\b/, '$1**$2**').replace(/,\s(quando)\b/g, ', **$1**').replace(/,\s(allora)\b/g, ', **$1**'));
  return cap(prose(bold, storyIndex));
}

/** Opzioni di `escapeMdx` per il contenuto di una cella di tabella GFM (`\\`, `|`, `<`, `{`, `}` in un solo passaggio). */
const CELL = { cell: true };

function checklist(title, items, storyIndex) {
  const out = [`| ${title} | Esito | Evidenza |`, '|---|---|---|'];
  for (const i of items) out.push(`| ${i.id} ${i.label} | ${ICON[i.status]} | ${prose(i.evidence, storyIndex, CELL)} |`);
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
    `Questa epica ha ${c.total} ${c.total === 1 ? 'storia' : 'storie'}${c.out ? `, di cui ${c.out} fuori perimetro` : ''}: ${c.ready} ${c.ready === 1 ? 'pronta' : 'pronte'} secondo la Definition of Ready, ${c.done} ${c.done === 1 ? 'fatta' : 'fatte'} secondo la Definition of Done${c.tbd ? `, ${c.tbd} da verificare` : ''}. Come si legge ogni voce è spiegato nell'[indice del backlog](${PAGE_BASE}#come-leggere-una-storia).`,
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
      out.push(`| ${e.id} ${escapeMdx(e.name, CELL)} | ${prose(e.goal, storyIndex, CELL)} | — | — | — |`);
      continue;
    }
    const c = counts(evs);
    for (const k of Object.keys(tot)) tot[k] += c[k];
    const n = c.out ? `${c.total} (${c.out} fuori perimetro)` : `${c.total}`;
    out.push(`| [${e.id} ${escapeMdx(e.name, CELL)}](${PAGE_BASE}/${epicSlug(e)}) | ${prose(e.goal, storyIndex, CELL)} | ${n} | ${c.ready} | ${c.done} |`);
  }
  out.push(`| **Totale** | | **${tot.total}** | **${tot.ready}** | **${tot.done}** |`);
  return { table: out.join('\n'), tot };
}

/** «commit `abc1234` del 2026-09-29»: l'ultimo refresh delle evidenze. */
function refreshLabel(snapshot) {
  const sha = snapshot.refreshedAt ? `commit \`${snapshot.refreshedAt}\`` : 'commit non registrato';
  return snapshot.refreshedDate ? `${sha} del ${snapshot.refreshedDate}` : sha;
}

function renderIndex(model, evsByEpic, storyIndex, snapshot) {
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
  Oggi il backlog ha ${c.total} storie: ${c.ready} pronte, ${c.done} fatte${c.tbd ? `, ${c.tbd} con la DoD da verificare` : ''} e ${c.out} fuori perimetro della PoC. Le voci basate sulle evidenze del repository sono aggiornate all'ultimo refresh: ${refreshLabel(snapshot)}.
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

\`scripts/docs-sync.mjs\` legge solo file del repository, senza rete. Le voci sono di due tipi.

Quelle che dipendono solo dal testo della storia si calcolano a ogni generazione da \`docs/17\`:

- **R1–R4**: la frase *Come … voglio … così che …*, gli ID di specifica nel campo *Tocca* (o nella matrice di \`docs/17 §6.1\`), i criteri *Dato/Quando/Allora* e i criteri negativi ✗.
- **D3**: i marcatori ⛔ e ⚠ nel testo della storia.

Quelle che dipendono dalle evidenze del repository stanno in uno snapshot committato, \`specifiche/backlog/_status.json\`. Lo snapshot si aggiorna con \`--refresh\` nella pull request di stato cumulativa (\`docs(stato)\`, ADR-047), non a ogni pull request di codice:

- **R5–R8**: il dominio di testbook e i job di verifica citati (un job conta solo se esiste in \`.github/workflows/\`), le domande di \`docs/15\`, i token dei dati demo in \`seed/\`, le schermate specificate in \`docs/07\`, \`docs/08\`, \`docs/09\` e \`docs/18 §5\`.
- **D1**: le righe \`[x]\` di \`docs/14\` per ogni \`F-\` e \`F2-\` della storia e i numeri delle pull request sulla stessa riga. Una feature della sezione «Fuori PoC» non conta; in Fase 2 serve anche il numero della pull request.
- **D2**: le righe \`TB-*\` di \`docs/16\` e \`docs/testbook/\` legate alla storia (la citano nella riga, nel titolo o nel testo della sezione, compresa la regola, o nella regola dell'inventario che copre la loro area, oppure il campo *Testbook* le nomina) o, nel dominio della storia, alle sue feature e schermate. Una riga conta come eseguita se il suo ID compare nei sorgenti dei test (\`src/test/\`, \`*.test.ts\`, \`e2e/\`). In alternativa valgono le prove citate nel campo *Testbook*: un file di test che esiste, uno script che un workflow invoca, un workflow insieme a un suo job che esiste. D2 non è mai soddisfatta se i criteri non sono pronti (R3) o se un criterio dichiara un residuo.

Le pagine mostrano lo stato dell'ultimo refresh: ${refreshLabel(snapshot)}.

Per aggiornare le pagine, esegui:

\`\`\`bash
# Rigenera le pagine e il gruppo «Backlog» di docs.json dallo snapshot esistente
node scripts/docs-sync.mjs
# Rilegge le evidenze del repository e aggiorna snapshot, pagine e docs.json
node scripts/docs-sync.mjs --refresh
# Verifica solo la struttura, senza leggere le evidenze (lo esegue il job guard)
node scripts/docs-sync.mjs --check
# Avviso: quante storie cambierebbero con --refresh (esce sempre 0)
node scripts/docs-sync.mjs --check-status
\`\`\`

## Limiti noti

- Lo stato basato sulle evidenze è quello dell'ultimo refresh: può essere più vecchio del repository finché la pull request di stato cumulativa non lo aggiorna.
- Una storia può risultare con D2 non soddisfatta anche se è provata: il collegamento tra righe di testbook e storie passa dai riferimenti scritti (nella riga, nel titolo o nel testo della sezione, nella regola dell'inventario, nel campo *Testbook*). Una riga che non cita né la storia né una sua feature non la prova. In quel caso aggiungi il riferimento in \`docs/testbook/\` o cita le righe nel campo *Testbook*.
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
// Generazione, snapshot e navigazione.

/** Il gruppo «Backlog» della scheda «Specifiche tecniche» di docs.json. */
export function backlogGroup(model) {
  const pagesOf = (phase) => model.epics.filter((e) => e.phase === phase && e.stories.length).map((e) => `${OUT_DIR}/${epicSlug(e)}`);
  return {
    group: 'Backlog',
    pages: ['specifiche/backlog', { group: 'Epiche di Fase 1', pages: pagesOf(1) }, { group: 'Epiche di Fase 2', pages: pagesOf(2) }],
  };
}

function specTab(doc) {
  const tab = doc.navigation?.tabs?.find((t) => t.tab === 'Specifiche tecniche');
  if (!tab) throw new Error('docs.json: scheda «Specifiche tecniche» assente');
  return tab;
}

/** Sostituisce solo il gruppo «Backlog» di docs.json e lascia il resto com'è (stessa formattazione a 2 spazi). */
export function updateDocsJson(json, model) {
  const doc = JSON.parse(json);
  const tab = specTab(doc);
  for (const g of tab.groups) g.pages = g.pages.filter((p) => p !== 'specifiche/backlog');
  const group = backlogGroup(model);
  const i = tab.groups.findIndex((g) => g.group === 'Backlog');
  if (i >= 0) tab.groups[i] = group;
  else {
    const after = tab.groups.findIndex((g) => g.group === 'Dati & processo');
    tab.groups.splice(after < 0 ? tab.groups.length : after + 1, 0, group);
  }
  return `${JSON.stringify(doc, null, 2)}\n`;
}

/** Il gruppo «Backlog» com'è in docs.json, o null. */
export function readBacklogGroup(json) {
  return specTab(JSON.parse(json)).groups.find((g) => g.group === 'Backlog') ?? null;
}

/** JSON con le chiavi ordinate: per confrontare due gruppi senza dipendere dall'ordine delle chiavi. */
const canon = (x) => JSON.stringify(x, (k, v) => (v && typeof v === 'object' && !Array.isArray(v)
  ? Object.fromEntries(Object.keys(v).sort().map((key) => [key, v[key]])) : v));

// --- Snapshot delle evidenze -----------------------------------------------------------------------------------

const allStories = (model) => model.epics.flatMap((e) => e.stories);
const entryOf = (ev) => ({
  dor: Object.fromEntries(ev.dor.map((i) => [i.id, { status: i.status, evidence: i.evidence }])),
  dod: Object.fromEntries(ev.dod.map((i) => [i.id, { status: i.status, evidence: i.evidence }])),
});
const evidenceFromEntry = (entry) => ({
  dor: EVIDENCE_DOR.map((id) => item(id, entry.dor[id].status, entry.dor[id].evidence)),
  dod: EVIDENCE_DOD.map((id) => item(id, entry.dod[id].status, entry.dod[id].evidence)),
});

/** Le incoerenze tra le storie di docs/17 e lo snapshot: storie senza voce, voci senza storia, voci incomplete. */
export function snapshotProblems(model, snap) {
  const problems = [];
  const known = new Set();
  const valid = (x) => x && [OK, KO, TBD].includes(x.status) && typeof x.evidence === 'string';
  for (const s of allStories(model)) {
    known.add(s.id);
    const entry = snap.stories?.[s.id];
    if (!entry) problems.push(`${s.id}: storia in docs/17 senza voce nello snapshot`);
    else if (!s.outOfScope && !(EVIDENCE_DOR.every((id) => valid(entry.dor?.[id])) && EVIDENCE_DOD.every((id) => valid(entry.dod?.[id])))) {
      problems.push(`${s.id}: voce dello snapshot incompleta`);
    }
  }
  for (const id of Object.keys(snap.stories ?? {})) if (!known.has(id)) problems.push(`${id}: voce nello snapshot per una storia che non è più in docs/17`);
  return problems;
}

/** Lo stato di tutte le storie dallo snapshot (pura: nessuna evidenza letta). */
function evaluateAll(model, snap) {
  return new Map(model.epics.map((e) => [e.id, e.stories.map((s) => evaluateWith(s, s.outOfScope ? null : evidenceFromEntry(snap.stories[s.id])))]));
}

/** Snapshot completo: metadati, totali (derivati) e voci, con le chiavi in ordine stabile. */
export function finalizeSnapshot(base, model) {
  const stories = {};
  for (const id of Object.keys(base.stories).sort()) stories[id] = base.stories[id];
  const snap = { refreshedAt: base.refreshedAt ?? null, refreshedDate: base.refreshedDate ?? null, stories };
  const c = counts([...evaluateAll(model, snap).values()].flat());
  return {
    generatedBy: 'node scripts/docs-sync.mjs --refresh',
    refreshedAt: snap.refreshedAt,
    refreshedDate: snap.refreshedDate,
    totals: { stories: c.total, outOfScope: c.out, ready: c.ready, done: c.done, toVerify: c.tbd },
    stories,
  };
}

export const serializeSnapshot = (snap) => `${JSON.stringify(snap, null, 2)}\n`;

/** Legge le evidenze del repository e ne ricava lo snapshot. */
export function buildSnapshot(model, ctx, meta = {}) {
  const stories = {};
  for (const s of allStories(model)) {
    const ev = evidenceOf(s, ctx);
    stories[s.id] = ev ? entryOf(ev) : { outOfScope: true };
  }
  return finalizeSnapshot({ ...meta, stories }, model);
}

/** SHA breve e data del commit di HEAD: deterministici, a differenza di un orologio. */
function gitMeta(root) {
  const git = (...args) => execFileSync('git', args, { cwd: root, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim();
  try {
    return { refreshedAt: git('rev-parse', '--short=7', 'HEAD'), refreshedDate: git('show', '-s', '--format=%cs', 'HEAD') };
  } catch {
    return { refreshedAt: null, refreshedDate: null };
  }
}

// --- Eventi ----------------------------------------------------------------------------------------------------

const EVENTS_DIR = 'contracts/events';
const EVENTS_OUT = 'specifiche/eventi';
const EVENT_FAMILIES = ['action', 'effect', 'fact', 'audit', 'dlq'];
const EVENTS_HEADER = `{/* Generato da scripts/docs-sync.mjs a partire da ${EVENTS_DIR}/: non modificare a mano. */}`;
const EVENT_TYPE_PREFIX = 'io.loyaltyhub.';
const SCHEMA_ID = /^urn:loyaltyhub:schema:(.+):(\d+)$/;

/** Segue un `$ref` locale (`#/...`) e unisce `allOf`: nodo con `properties`, `required` e i campi propri. */
function resolveNode(node, rootSchema, depth = 0) {
  if (!node || typeof node !== 'object' || depth > 8) return node ?? {};
  let out = { ...node };
  if (typeof out.$ref === 'string' && out.$ref.startsWith('#/')) {
    let target = rootSchema;
    for (const seg of out.$ref.slice(2).split('/')) target = target?.[seg.replace(/~1/g, '/').replace(/~0/g, '~')];
    const { $ref, ...rest } = out;
    out = { ...resolveNode(target, rootSchema, depth + 1), ...rest };
  }
  if (Array.isArray(out.allOf)) {
    const { allOf, ...rest } = out;
    out = allOf.reduce((acc, part) => {
      const p = resolveNode(part, rootSchema, depth + 1);
      return {
        ...acc,
        ...p,
        properties: { ...acc.properties, ...p.properties },
        required: [...(acc.required ?? []), ...(p.required ?? [])],
      };
    }, rest);
  }
  return out;
}

/** Il tipo di un campo come testo semplice: enum e const come valori separati da `/`. */
function fieldType(def, rootSchema) {
  const node = resolveNode(def, rootSchema);
  if (Array.isArray(node.enum)) return node.enum.map((v) => JSON.stringify(v).replace(/^"|"$/g, '')).join('/');
  if ('const' in node) return `${JSON.stringify(node.const).replace(/^"|"$/g, '')} (fisso)`;
  const variants = node.oneOf ?? node.anyOf;
  if (Array.isArray(variants)) return variants.map((v) => fieldType(v, rootSchema)).join('/');
  const types = Array.isArray(node.type) ? node.type : node.type ? [node.type] : [];
  const shown = types.map((t) => {
    if (t !== 'array') return t;
    const items = resolveNode(node.items, rootSchema);
    return items.type && items.type !== 'object' && !items.properties ? `array<${Array.isArray(items.type) ? items.type.join('/') : items.type}>` : 'array';
  });
  return shown.length ? shown.join('/') : 'any';
}

/** Le righe della tabella dei campi, con visita ricorsiva di oggetti e array (`items[].sku`). */
function fieldRows(node, rootSchema, prefix = '', depth = 0) {
  const rows = [];
  if (depth > 6) return rows;
  const resolved = resolveNode(node, rootSchema);
  const required = new Set(resolved.required ?? []);
  for (const [name, rawDef] of Object.entries(resolved.properties ?? {})) {
    const def = resolveNode(rawDef, rootSchema);
    const pathName = `${prefix}${name}`;
    rows.push({
      path: pathName,
      type: fieldType(def, rootSchema),
      required: required.has(name),
      pii: def['x-lh-pii'] === true,
      description: typeof def.description === 'string' ? def.description : '',
    });
    const items = def.items ? resolveNode(def.items, rootSchema) : null;
    if (items?.properties) rows.push(...fieldRows(items, rootSchema, `${pathName}[].`, depth + 1));
    if (def.properties) rows.push(...fieldRows(def, rootSchema, `${pathName}.`, depth + 1));
  }
  return rows;
}

/** Type e versione di uno schema: da `$id` (`urn:loyaltyhub:schema:<famiglia>.<nome>:<n>`), altrimenti dal nome del file. */
function schemaIdentity(data, family, file) {
  const m = typeof data.$id === 'string' ? data.$id.match(SCHEMA_ID) : null;
  if (m) return { type: m[1], version: Number(m[2]) };
  const base = file.replace(/\.schema\.json$/, '');
  const v = base.match(/^(.*)\.v(\d+)$/);
  return { type: `${family}.${v ? v[1] : base}`, version: v ? Number(v[2]) : 1 };
}

/** Il riferimento di specifica tra parentesi alla fine del titolo dello schema (per esempio `EVT-FACT-01, docs/05 §5`). */
function specRef(title) {
  const m = typeof title === 'string' ? title.match(/\(([^()]*)\)\s*$/) : null;
  return m ? m[1] : '';
}

function renderSchemaSection(family, file, data) {
  const { type, version } = schemaIdentity(data, family, file);
  let out = `## \`${EVENT_TYPE_PREFIX}${type}\` · versione ${version}\n\n`;
  const ref = specRef(data.title);
  if (ref) out += `${escapeMdx(`Specifica: ${ref}.`)}\n\n`;
  const superseded = typeof data['x-lh-superseded-by'] === 'string' ? data['x-lh-superseded-by'].match(SCHEMA_ID) : null;
  if (superseded) out += `Versione superata: la versione corrente è la ${superseded[2]}.\n\n`;
  if (data.description) out += `${escapeMdx(data.description)}\n\n`;
  out += '| Campo | Tipo | Obbligatorio | PII | Descrizione |\n| --- | --- | --- | --- | --- |\n';
  for (const r of fieldRows(data, data)) {
    const cells = [
      escapeMdx(`\`${r.path}\``, { cell: true }),
      escapeMdx(r.type, { cell: true }),
      r.required ? 'sì' : 'no',
      r.pii ? '**sì**' : 'no',
      escapeMdx(r.description, { cell: true }),
    ];
    out += `| ${cells.join(' | ')} |\n`;
  }
  return out;
}

const DLQ_PAGE = `---
title: "Eventi dlq"
description: "Il topic lh.dlq.v1: header di diagnosi, ritentativi e codici di errore."
---

${EVENTS_HEADER}

La famiglia \`dlq\` non ha schemi JSON in \`${EVENTS_DIR}/\`: il topic \`lh.dlq.v1\` riceve il messaggio originale che un consumer non ha potuto elaborare, con gli header di diagnosi descritti in [Architettura](/specifiche/architettura). Le altre famiglie sono in [Eventi e topic](/specifiche/eventi-e-topic).

## Header di diagnosi

| Header | Contenuto |
| --- | --- |
| \`lh-original-topic\` | il topic da cui arriva il messaggio |
| \`lh-consumer\` | il consumer che non ha potuto elaborarlo |
| \`lh-error-class\` | la classe dell'errore |
| \`lh-error-message\` | il messaggio dell'errore |
| \`lh-attempts\` | i tentativi fatti |

## Ritentativi

Un errore ritentabile si riprova 3 volte con attese di 1 s, 5 s e 15 s. Gli errori non ritentabili (validazione, deserializzazione) vanno subito in DLQ.

## Codici di errore

| Codice | Quando |
| --- | --- |
| \`LOOP_GUARD\` | \`lhhop\` maggiore di 3: la catena di azioni generate dal ponte interno è troppo lunga |
| \`PRODUCER_NOT_ALLOWED\` | il messaggio è stato pubblicato da un modulo non ammesso per quel \`type\` in \`${EVENTS_DIR}/producers.yaml\` |
| \`SIGNATURE_INVALID\` | la firma del messaggio non è valida |
`;

/**
 * Le pagine degli eventi: una per famiglia (action, effect, fact, audit, dlq) da `contracts/events/`, con i `type`, le
 * versioni e i campi (`x-lh-pii` evidenziato). Funzione pura degli schemi e del generatore: percorso → contenuto.
 * Ogni testo preso dagli schemi passa da `escapeMdx`, quindi non può rompere la compilazione MDX.
 */
export function renderEvents(root) {
  const eventsDir = path.join(root, EVENTS_DIR);
  const files = new Map();
  if (!fs.existsSync(eventsDir)) return files;

  for (const family of EVENT_FAMILIES) {
    const familyDir = path.join(eventsDir, family);
    const names = fs.existsSync(familyDir) ? fs.readdirSync(familyDir).filter((f) => f.endsWith('.schema.json')) : [];
    if (names.length === 0) {
      if (family === 'dlq') files.set(`${EVENTS_OUT}/dlq.mdx`, DLQ_PAGE);
      continue;
    }
    const schemas = names.map((file) => {
      const data = JSON.parse(fs.readFileSync(path.join(familyDir, file), 'utf8'));
      return { file, data, ...schemaIdentity(data, family, file) };
    });
    schemas.sort((a, b) => (a.type < b.type ? -1 : a.type > b.type ? 1 : a.version - b.version));

    const sections = schemas.map((s) => renderSchemaSection(family, s.file, s.data));
    const intro =
      `Questa pagina è generata dagli schemi JSON in \`${EVENTS_DIR}/${family}/\`. ` +
      'Un campo con PII **sì** non può comparire in un evento pubblicato (ADR-032). ' +
      'Il formato dei messaggi e i topic sono in [Eventi e topic](/specifiche/eventi-e-topic).';
    const page = [
      `---\ntitle: "Eventi ${family}"\ndescription: "Schemi degli eventi della famiglia ${family}: type, versioni e campi."\n---`,
      EVENTS_HEADER,
      intro,
      ...sections.map((s) => s.trimEnd()),
    ].join('\n\n');
    files.set(`${EVENTS_OUT}/${family}.mdx`, `${page}\n`);
  }
  return files;
}

/** Le pagine specifiche/eventi/*.mdx oggi presenti, per trovare quelle non più generate. */
function existingEventPages(root) {
  const dir = path.join(root, EVENTS_OUT);
  return fs.existsSync(dir) ? fs.readdirSync(dir).filter((f) => f.endsWith('.mdx')).sort().map((f) => `${EVENTS_OUT}/${f}`) : [];
}

// --- Pagine ----------------------------------------------------------------------------------------------------

/** Le pagine del backlog: percorso → contenuto. Funzione pura di docs/17 (modello), del generatore e dello snapshot. */
export function renderPages(model, snap) {
  const storyIndex = new Map();
  for (const e of model.epics) for (const s of e.stories) storyIndex.set(s.id, `${PAGE_BASE}/${epicSlug(e)}#${s.id.toLowerCase()}`);
  const evsByEpic = evaluateAll(model, snap);
  const files = new Map();
  files.set(OUT_INDEX, renderIndex(model, evsByEpic, storyIndex, snap));
  for (const e of model.epics) {
    if (!e.stories.length) continue;
    files.set(`${OUT_DIR}/${epicSlug(e)}.mdx`, renderEpic(e, evsByEpic.get(e.id), storyIndex));
  }
  return { files, evsByEpic };
}

const readIf = (root, p) => (fs.existsSync(path.join(root, p)) ? fs.readFileSync(path.join(root, p), 'utf8') : null);
const readModel = (root) => parseBacklog(fs.readFileSync(path.join(root, SOURCE), 'utf8'));
const MSG_REFRESH = 'esegui node scripts/docs-sync.mjs --refresh (e committa il risultato)';

function readSnapshot(root) {
  const text = readIf(root, SNAPSHOT);
  if (text === null) return { error: `${SNAPSHOT}: snapshot assente` };
  try {
    return { snap: JSON.parse(text), text };
  } catch (e) {
    return { error: `${SNAPSHOT}: JSON non valido (${e.message})` };
  }
}

/** Le pagine generate oggi in specifiche/backlog/, per trovare quelle non più generate. */
function existingPages(root) {
  return fs.existsSync(path.join(root, OUT_DIR))
    ? fs.readdirSync(path.join(root, OUT_DIR)).filter((f) => f.endsWith('.mdx')).sort().map((f) => `${OUT_DIR}/${f}`)
    : [];
}

/**
 * Verifica strutturale (job guard): ricalcola pagine e totali da docs/17 e dallo snapshot committato, e le pagine degli
 * eventi dagli schemi di contracts/events/, e li confronta con i file committati (segnala anche le pagine non più generate); di docs.json confronta solo il gruppo «Backlog». Non legge docs/14, docs/15, docs/16, il testbook,
 * i sorgenti dei test, i seed né le intestazioni di docs/07–09 e docs/18. Restituisce l'elenco dei problemi.
 */
export function checkRoot(root) {
  const problems = [];
  const model = readModel(root);
  const { snap, text, error } = readSnapshot(root);
  if (error) return [error, MSG_REFRESH];
  const structural = snapshotProblems(model, snap);
  if (structural.length) return [...structural, MSG_REFRESH];

  const { files } = renderPages(model, snap);
  const eventFiles = renderEvents(root);
  for (const [k, v] of eventFiles) files.set(k, v);
  for (const [p, content] of files) {
    const current = readIf(root, p);
    if (current === null) problems.push(`${p}: pagina assente`);
    else if (current !== content) problems.push(`${p}: diversa da quella generata`);
  }
  for (const o of [...existingPages(root), ...existingEventPages(root)]) if (!files.has(o)) problems.push(`${o}: pagina non più generata`);
  if (text !== serializeSnapshot(finalizeSnapshot(snap, model))) problems.push(`${SNAPSHOT}: totali o formattazione diversi da quelli attesi`);

  const docsJson = readIf(root, 'docs.json');
  if (docsJson === null) problems.push('docs.json: assente');
  else {
    let group = null;
    try {
      group = readBacklogGroup(docsJson);
    } catch (e) {
      problems.push(`docs.json: ${e.message}`);
    }
    if (!group) problems.push('docs.json: gruppo «Backlog» assente');
    else if (canon(group) !== canon(backlogGroup(model))) problems.push('docs.json: gruppo «Backlog» diverso da quello generato');
  }
  if (problems.length) problems.push('Rigenera con: node scripts/docs-sync.mjs (evidenze aggiornate: --refresh) e committa il risultato.');
  return problems;
}

/** Scrive pagine, snapshot e gruppo «Backlog» di docs.json; toglie le pagine non più generate. */
export function writeRoot(root, model, snap) {
  const { files, evsByEpic } = renderPages(model, snap);
  const eventFiles = renderEvents(root);
  for (const [k, v] of eventFiles) files.set(k, v);
  const all = new Map(files);
  all.set(SNAPSHOT, serializeSnapshot(snap));
  all.set('docs.json', updateDocsJson(fs.readFileSync(path.join(root, 'docs.json'), 'utf8'), model));
  const changed = [];
  for (const [p, content] of all) {
    const abs = path.join(root, p);
    if (readIf(root, p) === content) continue;
    changed.push(p);
    fs.mkdirSync(path.dirname(abs), { recursive: true });
    fs.writeFileSync(abs, content);
  }
  const removed = [...existingPages(root), ...existingEventPages(root)].filter((f) => !files.has(f));
  for (const o of removed) fs.rmSync(path.join(root, o));
  return { changed, removed, evsByEpic };
}

/** Le storie la cui voce dello snapshot cambia tra due snapshot. */
export function changedStories(before, after) {
  const ids = new Set([...Object.keys(before?.stories ?? {}), ...Object.keys(after.stories)]);
  return [...ids].sort().filter((id) => JSON.stringify(before?.stories?.[id]) !== JSON.stringify(after.stories[id]));
}

const totalsText = (t) => `${t.stories} storie, ${t.ready} pronte, ${t.done} fatte, ${t.outOfScope} fuori perimetro`;

function main(argv) {
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
  const modes = ['--check', '--refresh', '--check-status'];
  const known = [...modes, '--backlog'];
  const bad = argv.filter((a) => !known.includes(a));
  const chosen = argv.filter((a) => modes.includes(a));
  if (bad.length || chosen.length > 1) {
    console.error(`uso: node scripts/docs-sync.mjs [${known.join(' | ')}] (una sola modalità)${bad.length ? `; argomenti sconosciuti: ${bad.join(' ')}` : ''}`);
    process.exit(2);
  }
  const mode = chosen[0] ?? 'generate';

  if (mode === '--check') {
    const problems = checkRoot(root);
    if (problems.length) {
      for (const p of problems) console.error(problems.indexOf(p) === problems.length - 1 ? p : `✗ ${p}`);
      process.exit(1);
    }
    const { snap } = readSnapshot(root);
    console.log(`docs-sync: ok (struttura verificata; ${totalsText(snap.totals)}; evidenze del ${snap.refreshedDate ?? '?'} al commit ${snap.refreshedAt ?? '?'})`);
    return;
  }

  const model = readModel(root);
  const existing = readSnapshot(root);

  if (mode === '--check-status') {
    const fresh = buildSnapshot(model, loadContext(root), {});
    const diff = changedStories(existing.snap, fresh);
    if (existing.error) console.warn(`avviso: ${existing.error}`);
    console.log(`docs-sync --check-status: ${diff.length} ${diff.length === 1 ? 'storia cambierebbe' : 'storie cambierebbero'} con --refresh${diff.length ? ` (${diff.slice(0, 12).join(', ')}${diff.length > 12 ? ', …' : ''}); ${MSG_REFRESH.replace('esegui', 'aggiorna con')}` : ''}`);
    return;
  }

  let snap;
  if (mode === '--refresh') {
    snap = buildSnapshot(model, loadContext(root), gitMeta(root));
  } else {
    if (existing.error) {
      console.error(`✗ ${existing.error}; ${MSG_REFRESH}`);
      process.exit(1);
    }
    const problems = snapshotProblems(model, existing.snap);
    if (problems.length) {
      for (const p of problems) console.error(`✗ ${p}`);
      console.error(MSG_REFRESH);
      process.exit(1);
    }
    snap = finalizeSnapshot(existing.snap, model);
  }
  const { changed, removed } = writeRoot(root, model, snap);
  const moved = mode === '--refresh' ? changedStories(existing.snap, snap).length : 0;
  console.log(`docs-sync: ${changed.length} file scritti, ${removed.length} rimossi; ${totalsText(snap.totals)}${mode === '--refresh' ? `; ${moved} voci dello snapshot cambiate` : ''}`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) main(process.argv.slice(2));
