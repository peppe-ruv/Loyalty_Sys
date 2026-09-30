#!/usr/bin/env node
// Verifica il frontmatter delle pagine Mintlify (docs/18 §3.12-§3.13, ADR-040): ogni .mdx ha un blocco YAML iniziale
// con `title` e `description` non vuoti. Nessuna dipendenza: gira nel job `docs` senza `npm ci`.
// Uso: node scripts/check-frontmatter.mjs [cartelle-o-file...]
//   (predefinito: index.mdx api concetti getting-started guide operations specifiche)
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const DEFAULT_ROOTS = ['index.mdx', 'api', 'concetti', 'getting-started', 'guide', 'operations', 'specifiche'];
const REQUIRED = ['title', 'description'];

/** Le pagine .mdx sotto `targets` (file o cartelle relativi a `root`), in ordine, senza node_modules né cartelle nascoste. */
export function listPages(root, targets = DEFAULT_ROOTS) {
  const found = [];
  const walk = (rel) => {
    const abs = path.join(root, rel);
    if (!fs.existsSync(abs)) return;
    if (fs.statSync(abs).isFile()) {
      if (rel.endsWith('.mdx')) found.push(rel);
      return;
    }
    for (const e of fs.readdirSync(abs, { withFileTypes: true }).sort((a, b) => (a.name < b.name ? -1 : 1))) {
      if (e.name === 'node_modules' || e.name.startsWith('.')) continue;
      walk(path.posix.join(rel.split(path.sep).join('/'), e.name));
    }
  };
  targets.forEach(walk);
  return found;
}

/** Problemi del frontmatter di un testo: elenco di messaggi (vuoto se valido). */
export function frontmatterProblems(text) {
  const lines = String(text).replace(/^﻿/, '').split(/\r?\n/);
  if (lines[0] !== '---') return ['frontmatter assente (la prima riga deve essere ---)'];
  const end = lines.indexOf('---', 1);
  if (end < 0) return ['frontmatter non chiuso (manca la riga ---)'];
  const block = lines.slice(1, end);
  const problems = [];
  for (const key of REQUIRED) {
    const at = block.findIndex((l) => l.startsWith(`${key}:`));
    if (at < 0) {
      problems.push(`manca «${key}»`);
      continue;
    }
    let value = block[at].slice(key.length + 1).trim();
    // Blocchi YAML a più righe (`>`, `|`): il valore sta nelle righe rientrate che seguono.
    if (/^[>|][+-]?$/.test(value)) {
      const body = [];
      for (const l of block.slice(at + 1)) {
        if (l !== '' && !/^\s/.test(l)) break;
        body.push(l.trim());
      }
      value = body.join(' ').trim();
    }
    value = value.replace(/^(["'])(.*)\1$/, '$2').trim();
    if (value === '') problems.push(`«${key}» vuoto`);
  }
  return problems;
}

/** Tutti i problemi sotto `root`: elenco di `file: messaggio`. */
export function checkRoot(root, targets = DEFAULT_ROOTS) {
  const out = [];
  for (const page of listPages(root, targets)) {
    for (const p of frontmatterProblems(fs.readFileSync(path.join(root, page), 'utf8'))) out.push(`${page}: ${p}`);
  }
  return out;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
  const args = process.argv.slice(2);
  const targets = args.length ? args : DEFAULT_ROOTS;
  const problems = checkRoot(root, targets);
  if (problems.length) {
    for (const p of problems) console.error(`✗ ${p}`);
    console.error(`check-frontmatter: ${problems.length} problemi`);
    process.exit(1);
  }
  console.log(`check-frontmatter: ok (${listPages(root, targets).length} pagine con title e description)`);
}
