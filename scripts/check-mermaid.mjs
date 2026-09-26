#!/usr/bin/env node
// Verifica i diagrammi Mermaid della documentazione (docs/18 §3.12, ADR-040).
// Uso: node scripts/check-mermaid.mjs [cartelle...]   (default: docs site)
// Fallisce se un blocco ```mermaid non è sintatticamente valido o non ha accTitle e accDescr.
// Dipendenze di sviluppo: mermaid@11, jsdom (nessun browser necessario).
import { JSDOM } from 'jsdom';
import fs from 'node:fs';
import path from 'node:path';

const dom = new JSDOM('<!doctype html><html><body></body></html>');
globalThis.window = dom.window;
globalThis.document = dom.window.document;
Object.defineProperty(globalThis, 'navigator', { value: dom.window.navigator, configurable: true });
globalThis.DOMParser = dom.window.DOMParser;
globalThis.Element = dom.window.Element;
const { default: mermaid } = await import('mermaid');
mermaid.initialize({ startOnLoad: false });

const roots = process.argv.slice(2).length ? process.argv.slice(2) : ['docs', 'site'];
const files = [];
const walk = (d) => {
  if (!fs.existsSync(d)) return;
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    if (e.name === 'node_modules' || e.name.startsWith('.')) continue;
    const p = path.join(d, e.name);
    if (e.isDirectory()) walk(p);
    else if (/\.(md|mdx)$/.test(e.name)) files.push(p);
  }
};
roots.forEach(walk);

let blocks = 0, errors = 0;
for (const f of files) {
  const src = fs.readFileSync(f, 'utf8');
  for (const m of src.matchAll(/```mermaid\r?\n([\s\S]*?)```/g)) {
    blocks++;
    const line = src.slice(0, m.index).split('\n').length;
    const code = m[1];
    try { await mermaid.parse(code); }
    catch (e) { errors++; console.error(`✗ ${f}:${line} sintassi: ${String(e.message || e).split('\n')[0]}`); }
    if (!/^\s*accTitle:/m.test(code) || !/^\s*accDescr:/m.test(code)) {
      errors++; console.error(`✗ ${f}:${line} manca accTitle o accDescr`);
    }
  }
}
console.log(`${blocks} diagrammi in ${files.length} file, ${errors} errori`);
process.exit(errors ? 1 : 0);
