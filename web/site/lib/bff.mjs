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

/**
 * Di chi è la pagina.
 *
 * In vetrina è un membro inventato, e va bene. Con un BFF vero **non si inventa**: l'identificativo deve
 * arrivare dal token OIDC (ADR-012), e finché quel pezzo non c'è un id fisso nel codice vorrebbe dire che
 * chiunque apra l'indirizzo pubblico — un motore di ricerca compreso — vede il saldo di quel membro e fa
 * registrare una decisione a suo nome a ogni visita. Meglio nessun dato che il dato di qualcun altro.
 * `DEMO_MEMBER_ID` resta per chi vuole una dimostrazione su dati veri e lo dichiara apposta.
 */
export function membroCorrente(env = process.env) {
  if (vetrina(env)) return 'demo-member';
  return env.DEMO_MEMBER_ID || null;
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
  if (!Array.isArray(out)) return [];
  // Non basta che sia una lista: la pagina legge `subject` e `body` da ogni elemento, e un `null` in mezzo —
  // una riga vuota, un errore inoltrato, un contratto cambiato a monte — farebbe cadere tutta la home.
  return out.filter((m) => m !== null && typeof m === 'object' && typeof m.id === 'string');
}
