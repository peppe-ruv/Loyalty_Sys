/**
 * I due blocchi del tema scuro di `tokens.css` sono per forza duplicati (CSS puro non
 * permette di riusare un blocco di dichiarazioni): questi test impediscono che divergano,
 * che nascano token scuri senza controparte chiara o che qualcuno torni ai nomi senza prefisso.
 *
 * Dalla 0.7.0 le fondamenta sono i Design Tokens Italia (ADR-027), e i test coprono anche le due
 * regole che rendono la scelta verificabile invece che documentale: ogni colore `--lh-*` risolve a
 * una primitiva `--it-color-*`, e le coppie testo/fondo dichiarate stanno sopra la soglia WCAG in
 * entrambi i temi.
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

/** Unico colore del sistema senza una primitiva Italia: il giallo-verde dei concorsi. */
const SENZA_PRIMITIVA_ITALIA = new Set(['--lh-volt']);

/** Risolve la catena `var(--x)` fino al valore letterale, nel tema richiesto. */
function resolve(name: string, theme: Map<string, string>): string {
  let value = theme.get(name) ?? light.get(name);
  expect(value, `token non dichiarato: ${name}`).toBeDefined();
  for (let hop = 0; hop < 8; hop += 1) {
    const alias = /^var\((--[\w-]+)\)$/.exec(value ?? '');
    if (alias?.[1] === undefined) return (value ?? '').trim();
    value = light.get(alias[1]);
    expect(value, `alias verso un token inesistente: ${alias[1]}`).toBeDefined();
  }
  throw new Error(`catena di alias troppo lunga a partire da ${name}`);
}

function luminance(hex: string): number {
  const canale = (indice: number): number => {
    const c = Number.parseInt(hex.slice(1 + indice * 2, 3 + indice * 2), 16) / 255;
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * canale(0) + 0.7152 * canale(1) + 0.0722 * canale(2);
}

/** Rapporto di contrasto WCAG 2.1 fra due colori esadecimali. */
function contrast(primo: string, secondo: string): number {
  const [alta, bassa] = [luminance(primo), luminance(secondo)].sort((a, b) => b - a) as [number, number];
  return (alta + 0.05) / (bassa + 0.05);
}

/**
 * Coppie che il design system dichiara leggibili: 4,5:1 per il testo, 3:1 per ciò che porta
 * significato senza essere testo (bordo di un controllo, anello di focus). `--lh-line` non è in
 * elenco: è un separatore decorativo e resta sotto soglia per scelta.
 */
const COPPIE: ReadonlyArray<readonly [string, string, number]> = [
  ['--lh-ink', '--lh-ground', 4.5],
  ['--lh-ink', '--lh-surface', 4.5],
  ['--lh-ink', '--lh-sunk', 4.5],
  ['--lh-muted', '--lh-ground', 4.5],
  ['--lh-muted', '--lh-surface', 4.5],
  ['--lh-accent', '--lh-surface', 4.5],
  ['--lh-on-accent', '--lh-accent', 4.5],
  ['--lh-on-accent-soft', '--lh-accent-soft', 4.5],
  ['--lh-link', '--lh-surface', 4.5],
  ['--lh-ok', '--lh-surface', 4.5],
  ['--lh-on-ok-soft', '--lh-ok-soft', 4.5],
  ['--lh-warn', '--lh-surface', 4.5],
  ['--lh-on-warn-soft', '--lh-warn-soft', 4.5],
  ['--lh-danger', '--lh-surface', 4.5],
  ['--lh-on-danger-soft', '--lh-danger-soft', 4.5],
  ['--lh-on-volt', '--lh-volt', 4.5],
  ['--lh-side-ink', '--lh-side', 4.5],
  ['--lh-side-muted', '--lh-side', 4.5],
  ['--lh-line-strong', '--lh-surface', 3],
  ['--lh-focus', '--lh-ground', 3],
  ['--lh-focus', '--lh-surface', 3],
];

describe('token del design system', () => {
  it('il tema chiaro definisce i token e ogni nome ha un prefisso noto', () => {
    expect(light.size).toBeGreaterThan(30);
    for (const name of light.keys()) {
      expect(
        name.startsWith('--lh-') || name.startsWith('--it-'),
        `token senza prefisso --lh- o --it-: ${name}`,
      ).toBe(true);
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

describe('fondamenta Design Tokens Italia (ADR-027)', () => {
  it('ogni colore --lh- risolve a una primitiva --it-color-, tranne quelli dichiarati', () => {
    for (const [name, value] of light) {
      if (!name.startsWith('--lh-') || !value.startsWith('#')) continue;
      expect(
        SENZA_PRIMITIVA_ITALIA.has(name),
        `colore letterale fuori dalla tavolozza Italia: ${name}: ${value}`,
      ).toBe(true);
    }
    for (const [name, value] of light) {
      if (!name.startsWith('--lh-')) continue;
      const alias = /^var\((--[\w-]+)\)$/.exec(value);
      if (!alias?.[1]?.startsWith('--it-color-')) continue;
      expect(light.has(alias[1]), `alias verso una primitiva inesistente: ${name} → ${alias[1]}`).toBe(true);
    }
  });

  it('il tema scuro ripunta gli alias e non ridefinisce le primitive', () => {
    for (const name of darkAuto.keys()) {
      expect(name.startsWith('--it-'), `primitiva Italia ridefinita nel tema scuro: ${name}`).toBe(false);
    }
  });

  it('le primitive dichiarate sono tutte usate da almeno un token --lh-', () => {
    const usate = new Set<string>();
    for (const blocco of [light, darkAuto]) {
      for (const [name, value] of blocco) {
        if (!name.startsWith('--lh-')) continue;
        for (const [, riferita] of value.matchAll(/var\((--it-[\w-]+)\)/g)) {
          if (riferita !== undefined) usate.add(riferita);
        }
      }
    }
    const inutilizzate = [...light.keys()].filter(
      (name) => name.startsWith('--it-color-') && !usate.has(name),
    );
    expect(inutilizzate, 'primitive di colore dichiarate ma non usate').toEqual([]);
  });
});

describe('contrasto delle coppie dichiarate (WCAG 2.1 AA)', () => {
  for (const [tema, blocco] of [
    ['chiaro', light],
    ['scuro', darkAuto],
  ] as const) {
    it(`tema ${tema}: ogni coppia sta sopra la propria soglia`, () => {
      for (const [testo, fondo, soglia] of COPPIE) {
        const rapporto = contrast(resolve(testo, blocco), resolve(fondo, blocco));
        expect(
          Number(rapporto.toFixed(2)),
          `${testo} su ${fondo} nel tema ${tema}: ${rapporto.toFixed(2)}:1, soglia ${soglia.toFixed(1)}:1`,
        ).toBeGreaterThanOrEqual(soglia);
      }
    });
  }
});

/**
 * Le coppie qui sopra sono quelle *dichiarate*. Non bastano: il guasto che questa sezione impedisce è
 * che una regola dei componenti usi una coppia diversa da quelle dichiarate e nessuno se ne accorga.
 * È successo davvero — le chip accento scrivevano con `--lh-on-accent` su `--lh-accent-soft`, cioè
 * bianco su verde chiarissimo, 1,09:1, mentre i test passavano perché controllavano la coppia giusta
 * invece di quella usata. Perciò qui si legge il CSS dei componenti e si verifica ciò che fa.
 */
describe('contrasto delle coppie che i componenti usano davvero', () => {
  const componenti = readFileSync(
    fileURLToPath(new URL('./components/components.css', import.meta.url)),
    'utf8',
  );

  /** Ogni blocco `selettore { ... }` con sia un `background` sia un `color` presi da token. */
  const regole = [...componenti.matchAll(/([^{}]+)\{([^{}]*)\}/g)]
    .map(([, selettore, corpo]) => {
      const fondo = /(?:background|background-color):\s*var\((--lh-[\w-]+)\)/.exec(corpo ?? '');
      const testo = /(?:^|[;\s])color:\s*var\((--lh-[\w-]+)/.exec(corpo ?? '');
      return fondo && testo
        ? { selettore: (selettore ?? '').trim(), fondo: fondo[1]!, testo: testo[1]! }
        : null;
    })
    .filter((r): r is { selettore: string; fondo: string; testo: string } => r !== null);

  it('ci sono regole da controllare (se no la lettura del CSS si è rotta)', () => {
    expect(regole.length).toBeGreaterThan(3);
  });

  for (const [tema, blocco] of [
    ['chiaro', light],
    ['scuro', darkAuto],
  ] as const) {
    it(`tema ${tema}: testo su fondo sopra 4,5:1 in ogni regola`, () => {
      const sotto = regole
        .map((r) => ({ ...r, rapporto: contrast(resolve(r.testo, blocco), resolve(r.fondo, blocco)) }))
        .filter((r) => r.rapporto < 4.5)
        .map((r) => `${r.selettore}: ${r.testo} su ${r.fondo} = ${r.rapporto.toFixed(2)}:1`);
      expect(sotto, 'regole sotto la soglia AA').toEqual([]);
    });
  }

  it('nessun valore letterale come ripiego di un token', () => {
    // `var(--lh-x, #fff)` nasconde esattamente questa classe di errori: se il token esiste ma è quello
    // sbagliato il ripiego non entra mai in gioco, e sembra che ci sia una rete di sicurezza.
    const ripieghi = [...componenti.matchAll(/var\(\s*--lh-[\w-]+\s*,\s*([^)]+)\)/g)].map((m) => m[0]);
    expect(ripieghi, 'var(--lh-…, letterale) nei componenti').toEqual([]);
  });
});
