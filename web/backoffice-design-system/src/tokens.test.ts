/**
 * I due blocchi del tema scuro di `tokens.css` sono per forza duplicati (CSS puro non
 * permette di riusare un blocco di dichiarazioni): questi test impediscono che divergano,
 * che nascano token scuri senza controparte chiara o che qualcuno torni ai nomi senza prefisso.
 */

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

const css = readFileSync(fileURLToPath(new URL('../tokens.css', import.meta.url)), 'utf8');

/** Dichiarazioni `--token: valore;` contenute nel blocco che segue `selector`. */
function declarations(selector: string): Map<string, string> {
  const start = css.indexOf(selector);
  expect(start, `selettore non trovato: ${selector}`).toBeGreaterThanOrEqual(0);
  const open = css.indexOf('{', start);
  let depth = 0;
  let end = open;
  for (let i = open; i < css.length; i += 1) {
    if (css[i] === '{') depth += 1;
    if (css[i] === '}') {
      depth -= 1;
      if (depth === 0) {
        end = i;
        break;
      }
    }
  }
  const body = css.slice(open + 1, end);
  const found = new Map<string, string>();
  for (const [, name, value] of body.matchAll(/(--[\w-]+)\s*:\s*([^;]+);/g)) {
    if (name !== undefined && value !== undefined) found.set(name, value.trim());
  }
  return found;
}

const light = declarations(':root {');
const darkAuto = declarations(':root:not([data-theme="light"])');
const darkForced = declarations(':root[data-theme="dark"]');

describe('token del design system', () => {
  it('il tema chiaro definisce i token e il prefisso --lh- è sempre presente', () => {
    expect(light.size).toBeGreaterThan(30);
    for (const name of light.keys()) {
      expect(name.startsWith('--lh-'), `token senza prefisso: ${name}`).toBe(true);
    }
  });

  it('il tema scuro automatico e quello forzato sono identici', () => {
    expect([...darkForced.entries()].sort()).toEqual([...darkAuto.entries()].sort());
  });

  it('ogni token scuro ha una controparte chiara', () => {
    for (const name of darkAuto.keys()) {
      expect(light.has(name), `token dichiarato solo nel tema scuro: ${name}`).toBe(true);
    }
  });

  it('i token ridefiniti nel tema scuro cambiano davvero valore', () => {
    for (const [name, value] of darkAuto) {
      expect(light.get(name), `token scuro identico a quello chiaro: ${name}`).not.toBe(value);
    }
  });

  it('dichiara color-scheme in tutti e tre i blocchi (form nativi e barre di scorrimento)', () => {
    // `prefers-color-scheme` è la media query, non la dichiarazione: va esclusa dal conteggio.
    const declared = css.match(/(?<!prefers-)color-scheme:\s*(light|dark)/g) ?? [];
    expect(declared).toEqual(['color-scheme: light', 'color-scheme: dark', 'color-scheme: dark']);
  });
});
