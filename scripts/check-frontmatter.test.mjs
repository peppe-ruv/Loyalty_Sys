// Test di scripts/check-frontmatter.mjs (M8.9c, F2-DOC-01).
// Uso: node --test scripts/check-frontmatter.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { frontmatterProblems, checkRoot, listPages } from './check-frontmatter.mjs';

const OK = '---\ntitle: "Una pagina"\ndescription: Una descrizione\n---\n\nTesto.\n';

test('frontmatter valido, con virgolette, apici e blocchi a più righe', () => {
  assert.deepEqual(frontmatterProblems(OK), []);
  assert.deepEqual(frontmatterProblems("---\ntitle: 'T'\ndescription: >\n  una descrizione\n  su due righe\nicon: bolt\n---\n"), []);
  assert.deepEqual(frontmatterProblems('---\r\ntitle: T\r\ndescription: D\r\n---\r\n'), []);
});

test('frontmatter assente, non chiuso, senza title o description, vuoti', () => {
  assert.deepEqual(frontmatterProblems('# Solo testo\n'), ['frontmatter assente (la prima riga deve essere ---)']);
  assert.deepEqual(frontmatterProblems('---\ntitle: T\n'), ['frontmatter non chiuso (manca la riga ---)']);
  assert.deepEqual(frontmatterProblems('---\ntitle: T\n---\n'), ['manca «description»']);
  assert.deepEqual(frontmatterProblems('---\ndescription: D\n---\n'), ['manca «title»']);
  assert.deepEqual(frontmatterProblems('---\ntitle: ""\ndescription:   \n---\n'), ['«title» vuoto', '«description» vuoto']);
  assert.deepEqual(frontmatterProblems('---\ntitle: T\ndescription: >\nicon: bolt\n---\n'), ['«description» vuoto']);
});

test('checkRoot scorre le cartelle e segnala file e problema; ignora node_modules e cartelle nascoste', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'check-fm-'));
  try {
    for (const [p, text] of Object.entries({
      'index.mdx': OK,
      'concetti/a.mdx': OK,
      'concetti/b.mdx': '# senza frontmatter\n',
      'concetti/node_modules/x.mdx': '# ignorato\n',
      'concetti/.nascosta/y.mdx': '# ignorato\n',
      'guide/note.md': '# non è mdx\n',
      'fuori/c.mdx': '# fuori dalle cartelle\n',
    })) {
      fs.mkdirSync(path.dirname(path.join(root, p)), { recursive: true });
      fs.writeFileSync(path.join(root, p), text);
    }
    assert.deepEqual(listPages(root), ['index.mdx', 'concetti/a.mdx', 'concetti/b.mdx']);
    assert.deepEqual(checkRoot(root), ['concetti/b.mdx: frontmatter assente (la prima riga deve essere ---)']);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});
