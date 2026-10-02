#!/usr/bin/env node
// Membri di test della vetrina enterprise (M8.14 V8; ADR-051 decisioni 1 e 2, Q-673). Gira dopo che hub e web sono su,
// da `vetrina.sh membri` (e da `vetrina.sh codespace`):
//   - Anna Rossi, Marco Bianchi e Giulia Ferri hanno la registrazione GIA' COMPLETATA: per ciascuno, login dal BFF nel
//     realm dei membri (browser vero, Authorization Code + PKCE, nessun client con password-grant) e registrazione
//     ordinaria del portale `POST /v1/portal/members` col profilo di seed/members.json (ADR-048). Idempotente sul `sub`:
//     201 la prima volta, 200 poi. Il loro ID è quello generato dal servizio, non MBR-00000n (Q-673);
//   - Laura Conti è lo scenario di registrazione DA ZERO: il suo account Keycloak non ha profilo. Se un avvio precedente
//     l'ha registrata, un operatore di test con ruolo ADMIN (marta.admin, login reale con password e OTP fissi) anonimizza
//     il suo membro con `POST /v1/members/{id}/anonymize` (attore reale e voce di audit dal servizio): l'anonimizzazione
//     cancella il legame (iss, sub), quindi al prossimo login il portale la porta di nuovo alla registrazione (PT-16).
// Nessuna API nuova, nessuna credenziale nuova: le credenziali sono quelle fisse e pubbliche degli overlay di vetrina
// (scripts/vetrina-utenti.mjs) e non si stampano mai. Stampa solo esiti e codici.
//
// Uso: node scripts/vetrina-membri.mjs --web https://<nome>-8000.<dominio di inoltro> [--settle <s>] [--allow-http]
// Uscita: 0 fatto · 1 passo fallito (messaggio in italiano) · 2 uso non valido.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  createContext, eventually, expect, http, oidcLogin, parseJson, problemCode, SmokeError, UsageError, validateOrigin,
} from './smoke-enterprise.mjs';
import { loadTestUsers } from './vetrina-utenti.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
/** Membri con registrazione già completata (Q-673), nell'ordine del seed MBR-000001..3. */
export const REGISTERED = ['anna.rossi', 'marco.bianchi', 'giulia.ferri'];
/** Scenario di registrazione da zero. */
export const FROM_ZERO = 'laura.conti';
/** L'anonimizzazione è riservata al ruolo ADMIN (docs/08 §2, member.anonymize). */
export const ADMIN_OPERATOR = 'marta.admin';

const HELP = `Uso: node scripts/vetrina-membri.mjs --web <origine https> [--settle <s>] [--allow-http]
Registra Anna, Marco e Giulia dal portale con i dati del seed e riporta Laura alla registrazione da zero (Q-673).`;

export function parseArgs(argv) {
  const opts = { allowHttp: false, settle: 120, help: false };
  const args = [...argv];
  while (args.length) {
    const a = args.shift();
    if (a === '--web') opts.web = args.shift();
    else if (a === '--settle') opts.settle = Number(args.shift());
    else if (a === '--allow-http') opts.allowHttp = true;
    else if (a === '--help' || a === '-h') opts.help = true;
    else throw new UsageError(`argomento sconosciuto: ${a}`);
  }
  if (opts.help) return opts;
  if (!opts.web) throw new UsageError('manca --web');
  opts.web = validateOrigin(opts.web, { allowHttp: opts.allowHttp, label: '--web' });
  if (!Number.isFinite(opts.settle) || opts.settle < 0 || opts.settle > 3600) throw new UsageError('--settle: secondi tra 0 e 3600');
  return opts;
}

/** Corpo di POST /v1/portal/members dal profilo di seed (nessun memberId né id del seed: l'id lo assegna il servizio). */
export function registrationBody(seedMember) {
  const body = {
    firstName: seedMember.firstName,
    lastName: seedMember.lastName,
    nickname: seedMember.nickname,
    email: seedMember.email,
    phone: seedMember.phone,
    consents: { marketing: Boolean(seedMember.consents?.marketing), profiling: Boolean(seedMember.consents?.profiling) },
  };
  if (seedMember.city) body.city = seedMember.city;
  return body;
}

const jsonHeaders = (web, csrf) => ({ 'content-type': 'application/json', origin: web, 'x-lh-csrf': csrf });

/** Registra un membro di test dal portale con la sua sessione. Restituisce `creato` o `già registrato`. */
async function registerMember(ctx, web, user, seedMember) {
  const login = await oidcLogin(ctx, { web, username: user.username, password: user.password, realm: 'members', fixed: true });
  const res = await http(ctx, `${web}/api/lh/member/v1/portal/members`, {
    method: 'POST', jar: login.jar, headers: jsonHeaders(web, login.csrf), body: JSON.stringify(registrationBody(seedMember)),
  });
  if (res.status === 409) {
    throw new SmokeError(`registrazione di ${user.firstName} ${user.lastName}: 409${problemCode(res)}: l'e-mail ${seedMember.email} è già di un altro membro. `
      + 'Il database dell\'hub contiene un membro con quell\'e-mail non legato a questo account: azzera la vetrina (vetrina.sh reset).');
  }
  expect(res.status === 201 || res.status === 200, `registrazione di ${user.firstName} ${user.lastName}: POST /v1/portal/members HTTP ${res.status}${problemCode(res)}`);
  const profile = await http(ctx, `${web}/api/lh/member/v1/portal/me/profile`, { jar: login.jar });
  expect(profile.status === 200 && parseJson(profile.text)?.firstName === user.firstName,
    `profilo di ${user.firstName} ${user.lastName}: GET /v1/portal/me/profile HTTP ${profile.status}${problemCode(profile)}`);
  return res.status === 201 ? 'creato' : 'già registrato';
}

/**
 * Riporta Laura alla registrazione da zero: anonimizza i suoi membri non ancora anonimizzati. Si cercano per e-mail e per
 * nome; si toccano solo membri che sono davvero lei (e-mail uguale, oppure nome e cognome uguali), mai altri.
 */
async function resetLaura(ctx, web, users) {
  const op = users.operators[ADMIN_OPERATOR];
  expect(op?.password, `utente di test ${ADMIN_OPERATOR} non trovato negli overlay di vetrina`);
  const laura = users.members[FROM_ZERO];
  const login = await oidcLogin(ctx, { web, username: op.username, password: op.password, totpSecret: users.totpSecret, fixed: true });
  const found = new Map();
  for (const q of [laura.email, `${laura.firstName} ${laura.lastName}`]) {
    const res = await http(ctx, `${web}/api/lh/member/v1/members?${new URLSearchParams({ q, size: '50' })}`, { jar: login.jar });
    expect(res.status === 200, `ricerca di ${laura.firstName} ${laura.lastName}: GET /v1/members HTTP ${res.status}${problemCode(res)}`);
    for (const m of parseJson(res.text)?.items ?? []) found.set(m.id, m);
  }
  const mine = [...found.values()].filter((m) => m.status !== 'ANONYMIZED'
    && (String(m.email ?? '').toLowerCase() === laura.email.toLowerCase() || (m.firstName === laura.firstName && m.lastName === laura.lastName)));
  for (const m of mine) {
    const res = await http(ctx, `${web}/api/lh/member/v1/members/${encodeURIComponent(m.id)}/anonymize`, {
      method: 'POST', jar: login.jar, headers: jsonHeaders(web, login.csrf), body: JSON.stringify({ confirm: m.id }),
    });
    expect(res.status === 200, `anonimizzazione del membro di ${laura.firstName} ${laura.lastName} (${m.id}): HTTP ${res.status}${problemCode(res)}`);
  }
  return mine.length;
}

/** @returns {Promise<number>} 0 fatto, 1 passo fallito, 2 uso non valido. */
export async function main(argv, deps = {}) {
  const out = deps.stdout ?? process.stdout;
  const err = deps.stderr ?? process.stderr;
  let opts;
  try {
    opts = parseArgs(argv);
  } catch (e) {
    err.write(`${e.message}\n${HELP}\n`);
    return 2;
  }
  if (opts.help) {
    out.write(`${HELP}\n`);
    return 0;
  }
  const ctx = createContext(deps);
  try {
    const users = deps.users ?? loadTestUsers();
    const seed = JSON.parse(fs.readFileSync(path.join(deps.root ?? ROOT, 'seed/members.json'), 'utf8'));
    ctx.log(`vetrina: membri di test su ${opts.web}`);
    // Il web è su ma la discovery del realm dei membri può non esserlo ancora: si aspetta il login, non solo la health.
    await eventually(ctx, opts.settle, async () => {
      const res = await http(ctx, `${opts.web}/api/auth/login?realm=members`);
      expect(res.status === 303 && res.headers.get('location'), `web: /api/auth/login?realm=members HTTP ${res.status}, atteso 303`);
    });
    for (const name of REGISTERED) {
      const user = users.members[name];
      const s = seed.find((m) => String(m.email).toLowerCase() === String(user?.email).toLowerCase());
      expect(user?.password && s, `membro di test ${name}: manca negli overlay di vetrina o in seed/members.json (e-mail ${user?.email})`);
      const outcome = await registerMember(ctx, opts.web, user, s);
      ctx.ok(`${user.firstName} ${user.lastName} (${s.id} del seed): registrazione dal portale ${outcome}, profilo aperto (200)`);
    }
    const n = await resetLaura(ctx, opts.web, users);
    const laura = users.members[FROM_ZERO];
    ctx.ok(n ? `${laura.firstName} ${laura.lastName}: ${n} membro anonimizzato (audit con l'attore ${ADMIN_OPERATOR}), al prossimo login rifà la registrazione`
      : `${laura.firstName} ${laura.lastName}: nessun profilo attivo, al primo login fa la registrazione da zero`);
    ctx.log('vetrina: membri di test pronti');
    return 0;
  } catch (e) {
    const msg = e instanceof SmokeError || e instanceof UsageError ? e.message : `errore inatteso (${e?.name ?? 'sconosciuto'}: ${e?.message ?? ''})`;
    err.write(`FALLITO: ${msg}\n`);
    return 1;
  }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  process.exitCode = await main(process.argv.slice(2));
}
