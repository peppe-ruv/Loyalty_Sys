/**
 * Accesso al BFF dal portale.
 *
 * Il portale è la punta di un backend di quattordici servizi, e qui si decide che cosa vede
 * l'utente quando quel backend non c'è. Due modi, e li distingue `BFF_URL`:
 *
 *  - **configurato** → si chiama il BFF vero. Ogni guasto degrada (RF-51): niente saldo, niente
 *    offerta, inbox vuota — mai una pagina di errore. Una fetch senza rete solleva, non risponde
 *    `!ok`: per questo ogni chiamata è dentro `try`, e ha una scadenza, perché un BFF che non
 *    chiude la connessione bloccherebbe la pagina fino al limite della piattaforma.
 *  - **assente** → modalità vetrina: dati finti da `demo.mjs`, dichiarati in pagina.
 */

import * as demo from './demo.mjs';

/** Millisecondi oltre i quali si rinuncia: la home deve uscire comunque. */
const SCADENZA = 2_000;

/** Vetrina quando non c'è un BFF a cui parlare: è l'assenza di configurazione a deciderlo, non un flag da ricordare. */
export function vetrina(env = process.env) {
  return !env.BFF_URL;
}

function base(env) {
  return env.BFF_URL ?? '';
}

/**
 * Una chiamata al BFF che non può far cadere la pagina.
 * @param {() => Promise<Response>} chiamata
 * @param {*} ripiego valore restituito quando il BFF non risponde o risponde male
 */
async function tollerante(chiamata, ripiego) {
  try {
    const r = await chiamata();
    return r.ok ? await r.json() : ripiego;
  } catch {
    return ripiego;
  }
}

export async function getSummary(memberId, env = process.env, fetchImpl = fetch) {
  if (vetrina(env)) return demo.summary();
  return tollerante(
    () => fetchImpl(`${base(env)}/api/members/${encodeURIComponent(memberId)}/summary`, { cache: 'no-store', signal: AbortSignal.timeout(SCADENZA) }),
    null,
  );
}

export async function getNextBestAction(memberId, env = process.env, fetchImpl = fetch) {
  if (vetrina(env)) return demo.nextBestAction();
  return tollerante(
    () =>
      fetchImpl(`${base(env)}/api/members/${encodeURIComponent(memberId)}/next-best-action`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ page: 'home' }),
        cache: 'no-store',
        signal: AbortSignal.timeout(SCADENZA),
      }),
    null,
  );
}

export async function getInbox(memberId, env = process.env, fetchImpl = fetch) {
  if (vetrina(env)) return demo.inbox();
  const out = await tollerante(
    () => fetchImpl(`${base(env)}/api/members/${encodeURIComponent(memberId)}/inbox`, { cache: 'no-store', signal: AbortSignal.timeout(SCADENZA) }),
    [],
  );
  return Array.isArray(out) ? out : [];
}
