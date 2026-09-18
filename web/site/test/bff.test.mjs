import { test } from 'node:test';
import assert from 'node:assert/strict';

import { getInbox, getNextBestAction, getSummary, vetrina } from '../lib/bff.mjs';

const CON_BFF = { BFF_URL: 'http://bff:3001' };
const VETRINA = {};

/** Una fetch che non arriva a destinazione: è così che fallisce un BFF spento, non con `!ok`. */
const irraggiungibile = () => Promise.reject(new TypeError('fetch failed'));
const risponde = (body, ok = true) => () => Promise.resolve({ ok, json: async () => body });

test('senza BFF_URL il portale è in vetrina', () => {
  assert.equal(vetrina(VETRINA), true);
  assert.equal(vetrina(CON_BFF), false);
});

test('in vetrina i tre riquadri hanno sempre qualcosa da mostrare', async () => {
  const s = await getSummary('demo-member', VETRINA);
  assert.equal(s.tier, 'PLUS');
  assert.equal(typeof s.updatedAt, 'string');
  assert.notEqual((await getNextBestAction('demo-member', VETRINA)).action, 'NO_ACTION');
  assert.ok((await getInbox('demo-member', VETRINA)).length > 0);
});

test('il saldo degrada invece di far cadere la pagina quando il BFF non risponde', async () => {
  // Il guasto che questo test riproduce: `getSummary` era l'unica delle tre senza `try`, e con il
  // BFF irraggiungibile la home tornava 500 invece del messaggio «il saldo non è disponibile».
  assert.equal(await getSummary('demo-member', CON_BFF, irraggiungibile), null);
});

test('anche offerta e inbox degradano, ciascuna al proprio vuoto', async () => {
  assert.equal(await getNextBestAction('demo-member', CON_BFF, irraggiungibile), null);
  assert.deepEqual(await getInbox('demo-member', CON_BFF, irraggiungibile), []);
});

test('una risposta di errore del BFF vale come assenza, non come dato', async () => {
  assert.equal(await getSummary('m', CON_BFF, risponde({ error: 'BOOM' }, false)), null);
  assert.deepEqual(await getInbox('m', CON_BFF, risponde({ error: 'BOOM' }, false)), []);
});

test('una inbox che non è una lista non arriva in pagina', async () => {
  // Il BFF inoltra ciò che riceve: se a monte esce un oggetto d'errore con 200, `.map` esploderebbe.
  assert.deepEqual(await getInbox('m', CON_BFF, risponde({ error: 'BOOM' })), []);
});

test('l’identificatore del membro finisce nel percorso codificato', async () => {
  let visto = '';
  await getSummary('a/b?c', CON_BFF, (url) => { visto = url; return Promise.resolve({ ok: true, json: async () => ({}) }); });
  assert.equal(visto, 'http://bff:3001/api/members/a%2Fb%3Fc/summary');
});
