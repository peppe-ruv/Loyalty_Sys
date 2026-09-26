#!/usr/bin/env node
// Verifica che le ADR di docs/13 siano solo in aggiunta e che gli ID di seed/ non cambino
// senza la label "decisione" sulla pull request (docs/18 §3.13, ADR-041).
// Uso in CI: node scripts/check-adr-append-only.mjs <base-ref> <head-ref> [--pr-labels a,b,c]
//
// Confronta il testo di docs/13-REGISTRO-DECISIONI.md tra base e head: fallisce se una riga
// esistente è stata modificata o rimossa (sono ammesse solo righe nuove, incluse le annotazioni
// "Superata da ADR-nnn"). Confronta gli ID (`"id": "..."`) nei file sotto seed/: se uno cambia
// o sparisce, richiede la label "decisione" sulla PR (passata da CI con --pr-labels).
import { execSync } from 'node:child_process';
import fs from 'node:fs';

const [baseRef, headRef, ...rest] = process.argv.slice(2);
if (!baseRef || !headRef) {
  console.error('Uso: check-adr-append-only.mjs <base-ref> <head-ref> [--pr-labels a,b,c]');
  process.exit(2);
}
const labelsArg = rest.find((a) => a.startsWith('--pr-labels='));
const prLabels = labelsArg ? labelsArg.split('=')[1].split(',').map((s) => s.trim()) : [];

function fileAt(ref, path) {
  try { return execSync(`git show ${ref}:${path}`, { encoding: 'utf8', maxBuffer: 1024 * 1024 * 32 }); }
  catch { return null; }
}

let failed = false;

// --- 1. docs/13: append-only ---
const adrPath = 'docs/13-REGISTRO-DECISIONI.md';
const baseAdr = fileAt(baseRef, adrPath);
const headAdr = fileAt(headRef, adrPath);
if (baseAdr !== null && headAdr !== null) {
  const baseLines = baseAdr.split('\n').map((l) => l.trim()).filter(Boolean);
  const headLineSet = new Set(headAdr.split('\n').map((l) => l.trim()));
  const missing = baseLines.filter((l) => !headLineSet.has(l));
  if (missing.length) {
    failed = true;
    console.error(`✗ ${adrPath}: ${missing.length} riga/righe esistenti modificate o rimosse. Le ADR si aggiungono, non si riscrivono.`);
    missing.slice(0, 20).forEach((l) => console.error(`    - ${l.slice(0, 160)}`));
    if (missing.length > 20) console.error(`    … e altre ${missing.length - 20}`);
  }
}

// --- 2. seed/: ID stabili senza label "decisione" ---
function extractIds(dir, ref) {
  const ids = new Map(); // path -> Set(ids)
  let files;
  try {
    files = execSync(`git ls-tree -r --name-only ${ref} -- ${dir}`, { encoding: 'utf8' })
      .split('\n').filter((f) => f.endsWith('.json'));
  } catch { return ids; }
  for (const f of files) {
    const content = fileAt(ref, f);
    if (!content) continue;
    const found = new Set();
    for (const m of content.matchAll(/"id"\s*:\s*"([^"]+)"/g)) found.add(m[1]);
    ids.set(f, found);
  }
  return ids;
}

const baseIds = extractIds('seed', baseRef);
const headIds = extractIds('seed', headRef);
let idChanged = false;
for (const [file, baseSet] of baseIds) {
  const headSet = headIds.get(file) ?? new Set();
  for (const id of baseSet) {
    if (!headSet.has(id)) { idChanged = true; console.error(`✗ seed ID rimosso o rinominato in ${file}: ${id}`); }
  }
}
if (idChanged && !prLabels.includes('decisione')) {
  failed = true;
  console.error('  → un ID di seed/ è cambiato senza la label "decisione" sulla PR.');
} else if (idChanged) {
  console.log('  (ID di seed/ cambiati, label "decisione" presente: ok)');
}

if (!failed) console.log('guard: ok (ADR solo in aggiunta, ID seed coerenti)');
process.exit(failed ? 1 : 0);
