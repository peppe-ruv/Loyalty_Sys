#!/usr/bin/env node
// Verifica il copione di collaudo di release (ADR-052 decisione 2, Q-680, F2-QA-06): e2e/collaudo/copione.md.
// Controlli, tutti di forma e di riferimenti (il copione lo esegue un agente nel browser, non questo script):
//   1. ogni passo è un titolo `### CL-<AREA>-NNN — titolo` con AREA in HUB, BO, PT, KC, AUD, ID unico, numeri crescenti
//      nell'area e area uguale a quella della sezione `## Area <AREA> — …` che lo contiene; tutte le aree hanno passi;
//   2. ogni passo ha i campi obbligatori non vuoti (utente di test, precondizioni, azioni numerate, esito atteso,
//      evidenze, specifiche) e almeno un ID di specifica;
//   3. gli ID di specifica hanno un prefisso noto e, per BO, PT, HUB, F, F2, ADR e Q, sono definiti in docs/
//      (titolo, prima cella di una riga di tabella o punto elenco in grassetto); TB si controlla solo nella forma;
//   4. i riferimenti a altri passi (CL-…) esistono, e ogni passo con `Scrittura di configurazione: sì` è citato da un
//      passo AUD (regola 21 di CLAUDE.md: ogni scrittura di configurazione lascia una voce di audit);
//   5. il copione non ricopia credenziali: password, seme o URI dell'OTP stanno solo nel runbook (deploy/vetrina/README.md);
//   6. i file di accompagnamento esistono: AVVIO.md, rapporto-modello.md (con una riga per ogni passo) e i rapporti in
//      rapporti/*.md citano solo passi esistenti.
// Uso: node scripts/check-collaudo.mjs [radice-del-repository]   (predefinita: la cartella corrente)
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const AREAS = ['HUB', 'BO', 'PT', 'KC', 'AUD'];
export const REQUIRED_FIELDS = ['Utente di test', 'Precondizioni', 'Azioni', 'Esito atteso', 'Evidenze', 'Specifiche'];
export const WRITE_FIELD = 'Scrittura di configurazione';

const STEP_HEADING = /^###\s+(CL-[A-Z]+-\d+)\b\s*(?:[—-]\s*(.*))?$/;
const STEP_ID = /^CL-(HUB|BO|PT|KC|AUD)-(\d{3})$/;
const STEP_REF = /\bCL-[A-Z]+-\d+\b/g;
const AREA_HEADING = /^##\s+Area\s+([A-Z]+)\b/;
const FIELD_LINE = /^- \*\*([^*]+)\*\*:\s*(.*)$/;

// Prefissi ammessi per gli ID di specifica; `verifiable` = deve essere definito in docs/.
const SPEC_FORMATS = [
  { prefix: 'BO', re: /^BO-\d{2}$/, verifiable: true },
  { prefix: 'PT', re: /^PT-\d{2}$/, verifiable: true },
  { prefix: 'HUB', re: /^HUB-\d{2}$/, verifiable: true },
  { prefix: 'F2', re: /^F2-[A-Z]+-\d{2}$/, verifiable: true },
  { prefix: 'F', re: /^F-[A-Z]+-\d{2}$/, verifiable: true },
  { prefix: 'ADR', re: /^ADR-\d{3}$/, verifiable: true },
  { prefix: 'Q', re: /^Q-\d{3}$/, verifiable: true },
  { prefix: 'TB', re: /^TB-[A-Z]+-\d{3}$/, verifiable: false },
];
const SPEC_TOKEN = /^[A-Z][A-Z0-9]*-[A-Z0-9-]+$/;

// Credenziali che non devono stare nel copione (decisione di Giuseppe: si rimanda al runbook).
const CREDENTIAL_PATTERNS = [
  { re: /Aurora-[A-Za-z]+-\d+!/, what: 'una password della vetrina' },
  { re: /otpauth:\/\//i, what: "un URI dell'OTP" },
  { re: /secret=[A-Z2-7]{16,}/, what: "un seme dell'OTP" },
  { re: /\b[A-Z2-7]{32}\b/, what: "un seme dell'OTP in base32" },
];

/** Divide il copione in passi: { id, area, section, title, line, fields: { etichetta: testo }, text }. */
export function parseCopione(text) {
  const lines = text.split(/\r?\n/);
  const steps = [];
  let section = null;
  let current = null;
  let field = null;
  lines.forEach((line, index) => {
    const area = AREA_HEADING.exec(line);
    if (area) {
      section = area[1];
      current = null;
      field = null;
      return;
    }
    const heading = STEP_HEADING.exec(line);
    if (heading) {
      current = { id: heading[1], title: (heading[2] ?? '').trim(), section, line: index + 1, fields: {}, text: '' };
      steps.push(current);
      field = null;
      return;
    }
    if (/^#{1,3}\s/.test(line)) {
      current = null;
      field = null;
      return;
    }
    if (!current) return;
    current.text += `${line}\n`;
    const match = FIELD_LINE.exec(line);
    if (match) {
      field = match[1].trim();
      current.fields[field] = match[2].trim();
    } else if (field && line.trim()) {
      current.fields[field] += `\n${line.trim()}`;
    }
  });
  return steps;
}

/** ID di specifica citati in un campo: i token tra apici inversi che sembrano un ID (A-1, F2-QA-06…). */
export function specTokens(value) {
  const tokens = [];
  for (const match of String(value ?? '').matchAll(/`([^`]+)`/g)) tokens.push(match[1].trim());
  return tokens;
}

/** ID definiti nei testi delle specifiche: titolo, prima cella di una riga di tabella, punto elenco in grassetto. */
export function collectDefinedIds(texts) {
  const defined = new Set();
  const id = '((?:BO|PT|HUB|ADR|Q)-\\d+|F2?-[A-Z]+-\\d+)';
  const heading = new RegExp(`^#{1,6}\\s+(?:\\d+\\.\\s+)?${id}\\b`);
  const table = new RegExp(`^\\|\\s*\`?${id}\`?\\s*\\|`);
  const bullet = new RegExp(`^[*-]\\s+\\*\\*${id}\\*\\*`);
  for (const text of texts) {
    for (const line of text.split(/\r?\n/)) {
      const match = heading.exec(line) ?? table.exec(line) ?? bullet.exec(line);
      if (match) defined.add(match[1]);
    }
  }
  return defined;
}

/** Problemi del copione: elenco di messaggi (vuoto se valido). `defined` è l'insieme degli ID definiti in docs/. */
export function checkCopione(text, defined) {
  const problems = [];
  const steps = parseCopione(text);
  if (steps.length === 0) return ['nessun passo trovato (titoli `### CL-<AREA>-NNN — titolo`)'];

  const ids = new Map();
  const lastNumber = new Map();
  for (const step of steps) {
    const where = `${step.id} (riga ${step.line})`;
    const match = STEP_ID.exec(step.id);
    if (!match) {
      problems.push(`${where}: ID non valido, il formato è CL-(${AREAS.join('|')})-NNN (tre cifre)`);
      continue;
    }
    if (ids.has(step.id)) problems.push(`${where}: ID duplicato (già alla riga ${ids.get(step.id)})`);
    ids.set(step.id, step.line);
    const [, area, number] = match;
    if (step.section !== area) {
      problems.push(`${where}: il passo è nella sezione «Area ${step.section ?? '—'}» ma l'ID è dell'area ${area}`);
    }
    if (lastNumber.has(area) && Number(number) <= lastNumber.get(area)) {
      problems.push(`${where}: i numeri dell'area ${area} devono crescere (dopo ${String(lastNumber.get(area)).padStart(3, '0')})`);
    }
    lastNumber.set(area, Number(number));
    if (!step.title) problems.push(`${where}: manca il titolo dopo l'ID`);

    for (const field of REQUIRED_FIELDS) {
      if (!(field in step.fields)) problems.push(`${where}: manca il campo «${field}»`);
      else if (!step.fields[field].trim()) problems.push(`${where}: il campo «${field}» è vuoto`);
    }
    if (step.fields['Azioni'] && !/^1\.\s+\S/m.test(step.fields['Azioni'])) {
      problems.push(`${where}: «Azioni» deve essere un elenco numerato (1. 2. …)`);
    }

    const specs = specTokens(step.fields['Specifiche']);
    if (specs.length === 0) problems.push(`${where}: «Specifiche» non cita nessun ID (BO-…, PT-…, HUB-…, F-…, F2-…, ADR-…, Q-…)`);
    for (const spec of specs) {
      const format = SPEC_FORMATS.find((f) => f.re.test(spec));
      if (!format) {
        problems.push(`${where}: «${spec}» non è un ID di specifica ammesso (BO-nn, PT-nn, HUB-nn, F-XXX-nn, F2-XXX-nn, ADR-nnn, Q-nnn, TB-DOM-NNN)`);
      } else if (format.verifiable && defined && !defined.has(spec)) {
        problems.push(`${where}: «${spec}» non è definito in docs/`);
      }
    }
  }

  for (const area of AREAS) {
    if (!lastNumber.has(area)) problems.push(`l'area ${area} non ha nessun passo`);
  }

  // Riferimenti tra passi.
  for (const step of steps) {
    for (const ref of new Set(step.text.match(STEP_REF) ?? [])) {
      if (ref !== step.id && !ids.has(ref)) problems.push(`${step.id} (riga ${step.line}): cita ${ref}, che non esiste`);
    }
  }

  // Ogni scrittura di configurazione ha il suo passo di audit.
  const audText = steps.filter((s) => s.id.startsWith('CL-AUD-')).map((s) => s.text).join('\n');
  for (const step of steps) {
    const write = step.fields[WRITE_FIELD];
    if (write === undefined) continue;
    if (!/^(sì|si|no)(?![A-Za-zÀ-ÿ])/i.test(write)) {
      problems.push(`${step.id} (riga ${step.line}): «${WRITE_FIELD}» deve valere «sì» o «no»`);
    } else if (/^s[ìi](?![A-Za-zÀ-ÿ])/i.test(write) && !audText.includes(step.id)) {
      problems.push(`${step.id} (riga ${step.line}): scrive configurazione ma nessun passo AUD lo cita (regola 21)`);
    }
  }

  // Niente credenziali nel copione.
  text.split(/\r?\n/).forEach((line, index) => {
    for (const { re, what } of CREDENTIAL_PATTERNS) {
      if (re.test(line)) problems.push(`riga ${index + 1}: il copione contiene ${what}: rimanda al runbook (deploy/vetrina/README.md)`);
    }
  });
  return problems;
}

/** ID `CL-…` che stanno nella prima colonna di una tabella (modello e rapporti). */
export function reportRowIds(text) {
  const found = [];
  for (const line of text.split(/\r?\n/)) {
    const match = /^\|\s*(CL-[A-Z]+-\d+)\s*\|/.exec(line);
    if (match) found.push(match[1]);
  }
  return found;
}

/** Problemi di un rapporto o del modello. `complete`: deve avere una riga per ogni passo del copione (il modello). */
export function checkReport(text, stepIds, name, complete) {
  const problems = [];
  const rows = reportRowIds(text);
  const known = new Set(stepIds);
  for (const id of rows) if (!known.has(id)) problems.push(`${name}: la riga ${id} non corrisponde a nessun passo del copione`);
  const seen = new Set();
  for (const id of rows) {
    if (seen.has(id)) problems.push(`${name}: la riga ${id} è ripetuta`);
    seen.add(id);
  }
  if (complete) {
    for (const id of stepIds) if (!seen.has(id)) problems.push(`${name}: manca la riga del passo ${id}`);
  }
  return problems;
}

function listMarkdown(dir) {
  if (!fs.existsSync(dir)) return [];
  const out = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) out.push(...listMarkdown(full));
    else if (entry.name.endsWith('.md')) out.push(full);
  }
  return out;
}

/** Esegue tutti i controlli sul repository in `root`: restituisce { problems, steps }. */
export function checkRepository(root) {
  const problems = [];
  const dir = path.join(root, 'e2e', 'collaudo');
  const copionePath = path.join(dir, 'copione.md');
  if (!fs.existsSync(copionePath)) return { problems: [`manca ${path.relative(root, copionePath)}`], steps: [] };

  const defined = collectDefinedIds(listMarkdown(path.join(root, 'docs')).map((f) => fs.readFileSync(f, 'utf8')));
  const text = fs.readFileSync(copionePath, 'utf8');
  problems.push(...checkCopione(text, defined).map((p) => `copione.md: ${p}`));
  const stepIds = parseCopione(text).map((s) => s.id);

  const avvio = path.join(dir, 'AVVIO.md');
  if (!fs.existsSync(avvio)) problems.push('manca e2e/collaudo/AVVIO.md (prompt di avvio, Q-682)');
  else {
    const avvioText = fs.readFileSync(avvio, 'utf8');
    if (!avvioText.includes('collaudo')) problems.push('AVVIO.md non cita l\'etichetta `collaudo` delle issue (Q-685)');
    for (const { re, what } of CREDENTIAL_PATTERNS) {
      if (re.test(avvioText)) problems.push(`AVVIO.md contiene ${what}: rimanda al runbook`);
    }
  }

  const model = path.join(dir, 'rapporto-modello.md');
  if (!fs.existsSync(model)) problems.push('manca e2e/collaudo/rapporto-modello.md (modello del rapporto, Q-682)');
  else problems.push(...checkReport(fs.readFileSync(model, 'utf8'), stepIds, 'rapporto-modello.md', true));

  for (const file of listMarkdown(path.join(dir, 'rapporti'))) {
    problems.push(...checkReport(fs.readFileSync(file, 'utf8'), stepIds, path.relative(dir, file), false));
  }
  return { problems, steps: parseCopione(text) };
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const root = path.resolve(process.argv[2] ?? '.');
  const { problems, steps } = checkRepository(root);
  if (problems.length) {
    for (const problem of problems) console.error(`✗ ${problem}`);
    process.exit(1);
  }
  const perArea = AREAS.map((a) => `${a} ${steps.filter((s) => s.id.startsWith(`CL-${a}-`)).length}`).join(', ');
  console.log(`collaudo: ok (${steps.length} passi: ${perArea})`);
}
