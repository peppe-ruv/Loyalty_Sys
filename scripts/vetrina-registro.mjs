#!/usr/bin/env node
// Registro di avvio della vetrina per GitHub (F2-DIST-09, ADR-050, Q-729, M8.14).
// Prende avvio.log del codespace e ne fa il corpo di un commento di issue: solo l'ultima esecuzione, senza i log dei
// container, con i segreti sostituiti da «[omesso]». Solo moduli di Node, nessuna dipendenza.
// Uso: node scripts/vetrina-registro.mjs --log <file> --esito ok|fallito --passo <testo> --codice <n> \
//        [--codespace <nome> --head <commit> --immagine <riferimento> --inizio <ISO>]
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';

export const MAX_RIGHE = 200;
export const MAX_CARATTERI = 60000;
const OMESSO = '[omesso]';
const VARIABILI_SEGRETE = ['LH_VETRINA_MASTER_ADMIN_PASSWORD', 'GITHUB_TOKEN', 'GH_TOKEN'];

// Ultima esecuzione di avvio.log, senza i blocchi di log dei container, al massimo le ultime MAX_RIGHE righe.
export function estratto(log) {
  const righe = String(log ?? '').replace(/\r\n/g, '\n').split('\n');
  if (righe.at(-1) === '') righe.pop();
  let da = 0;
  righe.forEach((r, i) => { if (r.startsWith('== avvio della vetrina')) da = i; });
  const fuori = [];
  let nelBlocco = false;
  for (const riga of righe.slice(da)) {
    if (riga.startsWith('-- ultime righe di')) {
      const nome = riga.slice('-- ultime righe di'.length).trim();
      fuori.push(`[log del container ${nome} omesso]`);
      nelBlocco = true;
    } else if (riga.startsWith('== fine della diagnosi')) {
      nelBlocco = false;
      fuori.push(riga);
    } else if (!nelBlocco) {
      fuori.push(riga);
    }
  }
  return fuori.slice(-MAX_RIGHE).join('\n');
}

// Sostituisce con «[omesso]» tutto ciò che somiglia a un segreto.
export function redigi(testo, env = process.env) {
  let t = String(testo ?? '');
  for (const nome of VARIABILI_SEGRETE) {
    const valore = env[nome];
    if (valore && valore.length >= 6) t = t.split(valore).join(OMESSO);
  }
  const chiavi = 'password|passwd|secret|token|key';
  return t
    .replace(/-----BEGIN [A-Z0-9 ]+-----[\s\S]*?(?:-----END [A-Z0-9 ]+-----|$)/g, OMESSO)
    .replace(/\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})/g, OMESSO)
    .replace(/\beyJ[\w-]{5,}\.[\w-]{5,}\.[\w-]*/g, OMESSO)
    .replace(new RegExp(`("[\\w.-]*(?:${chiavi})[\\w.-]*"\\s*:\\s*)"(?:[^"\\\\]|\\\\.)*"`, 'gi'), `$1"${OMESSO}"`)
    .replace(new RegExp(`((?:${chiavi})=)[^\\s&"']*`, 'gi'), `$1${OMESSO}`)
    .replace(/\b(authorization|cookie|set-cookie)(\s*:\s*)[^\r\n"']*/gi, `$1$2${OMESSO}`)
    .replace(/[A-Za-z0-9+/_-]{32,}={0,2}/g, OMESSO);
}

const cella = (v) => String(v ?? '').replace(/[\r\n]+/g, ' ').replace(/\\/g, '\\\\').replace(/\|/g, '\\|').trim() || '-';

// Markdown del commento di issue per un avvio.
export function corpo({ esito, passo, codice, codespace, head, immagine, inizio, fine, log } = {}) {
  const ok = esito === 'ok';
  const intestazione = [
    `## ${ok ? '✅ Avvio riuscito' : '❌ Avvio fallito'}`,
    '',
    '| Voce | Valore |',
    '| --- | --- |',
    `| Passo | ${cella(passo)} |`,
    `| Codice d'uscita | ${cella(codice)} |`,
    `| Codespace | ${cella(codespace)} |`,
    `| Commit | ${cella(head)} |`,
    `| Immagine | ${cella(immagine)} |`,
    `| Inizio | ${cella(inizio)} |`,
    `| Fine | ${cella(fine ?? new Date().toISOString())} |`,
    '',
    'Registro dell\'avvio (ultima esecuzione, senza i log dei container, segreti omessi):',
    '',
  ].join('\n');
  let testo = redigi(estratto(log));
  const run = Math.max(0, ...(testo.match(/`+/g) ?? []).map((s) => s.length));
  const recinto = '`'.repeat(Math.max(3, run + 1));
  const margine = intestazione.length + 2 * recinto.length + 16;
  const budget = MAX_CARATTERI - margine - 40;
  if (testo.length > budget) {
    testo = testo.slice(testo.length - budget);
    testo = `[registro troncato all'inizio]\n${testo.slice(testo.indexOf('\n') + 1)}`;
  }
  return `${intestazione}\n${recinto}text\n${testo}\n${recinto}\n`;
}

export function main(argv = process.argv.slice(2), out = process.stdout) {
  const { values: v } = parseArgs({
    args: argv,
    options: {
      log: { type: 'string' }, esito: { type: 'string' }, passo: { type: 'string' }, codice: { type: 'string' },
      codespace: { type: 'string' }, head: { type: 'string' }, immagine: { type: 'string' },
      inizio: { type: 'string' }, fine: { type: 'string' },
    },
  });
  if (v.esito !== 'ok' && v.esito !== 'fallito') throw new Error('--esito deve essere ok oppure fallito');
  let log = '';
  try { if (v.log) log = fs.readFileSync(v.log, 'utf8'); } catch { log = '(registro non leggibile)'; }
  out.write(corpo({ ...v, log }));
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { main(); } catch (e) { process.stderr.write(`${e.message}\n`); process.exit(2); }
}
