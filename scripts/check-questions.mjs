#!/usr/bin/env node
// Verifica che ogni domanda di docs/15 abbia un ID unico (ADR-047). Due fette in parallelo che registrano
// lo stesso Q-nnn con contenuti diversi fanno fallire il job `guard` della seconda PR, che deve rinumerare.
// Per evitarlo, chi lancia fette in parallelo assegna a ciascuna un blocco di numeri riservato (CLAUDE.md §4).
// Uso: node scripts/check-questions.mjs [percorso]   (predefinito: docs/15-DOMANDE-APERTE.md)
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';

// Una definizione è una riga di tabella che inizia con `| Q-nnn |` o un punto elenco `* **Q-nnn**` / `- **Q-nnn**`.
const DEFINITION = /^(?:\|\s*|[*-]\s+\*\*)(Q-\d+)\b/;

/** Restituisce gli ID definiti più di una volta, con i numeri di riga (1-based) delle definizioni. */
export function findDuplicates(text) {
  const seen = new Map();
  text.split('\n').forEach((line, index) => {
    const match = DEFINITION.exec(line);
    if (!match) return;
    const lines = seen.get(match[1]) ?? [];
    lines.push(index + 1);
    seen.set(match[1], lines);
  });
  return [...seen].filter(([, lines]) => lines.length > 1).map(([id, lines]) => ({ id, lines }));
}

/** Il numero più alto in uso: il prossimo blocco riservato parte dal successivo. */
export function highestId(text) {
  let max = 0;
  for (const line of text.split('\n')) {
    const match = DEFINITION.exec(line);
    if (match) max = Math.max(max, Number(match[1].slice(2)));
  }
  return max;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const path = process.argv[2] ?? 'docs/15-DOMANDE-APERTE.md';
  const text = fs.readFileSync(path, 'utf8');
  const duplicates = findDuplicates(text);
  if (duplicates.length) {
    for (const { id, lines } of duplicates) {
      console.error(`✗ ${path}: ${id} è definita più volte (righe ${lines.join(', ')}). Rinumerare quella della PR più recente.`);
    }
    process.exit(1);
  }
  console.log(`domande: ok (ID unici; il più alto è Q-${highestId(text)})`);
}
