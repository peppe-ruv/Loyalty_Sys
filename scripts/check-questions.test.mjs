import { test } from 'node:test';
import assert from 'node:assert/strict';
import { findDuplicates, highestId } from './check-questions.mjs';

test('ID unici in tabella ed elenco: nessun duplicato', () => {
  const text = '| Q-01 | a |\n| Q-02 | b |\n* **Q-03**: c\n';
  assert.deepEqual(findDuplicates(text), []);
  assert.equal(highestId(text), 3);
});

test('stesso ID in tabella e in elenco: duplicato con le righe', () => {
  const text = '| Q-365 | a |\ntesto\n* **Q-365**: b\n';
  assert.deepEqual(findDuplicates(text), [{ id: 'Q-365', lines: [1, 3] }]);
});

test('i riferimenti nel testo non sono definizioni', () => {
  const text = '| Q-10 | vedi Q-10 e Q-11 |\nIl default di Q-10 resta.\n- **Q-11**: x\n';
  assert.deepEqual(findDuplicates(text), []);
  assert.equal(highestId(text), 11);
});
