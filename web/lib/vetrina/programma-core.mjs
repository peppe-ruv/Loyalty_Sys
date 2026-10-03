// Nucleo puro della configurazione di programma (F2-DIST-09, ADR-049, ADR-051, M8.14 V10; Q-617, Q-722, Q-723).
// Lo usano DUE chiamanti, senza logica duplicata: scripts/vetrina-programma.mjs (riga di comando, token da file) e il
// BFF del web (web/lib/vetrina/programma.ts, token dell'operatore della sessione, rinnovato a ogni chiamata).
//
// ESM semplice: niente alias `@/`, niente `node:fs`, niente `process`. Il trasporto HTTP è iniettato (`transport`),
// così il nucleo non conosce né token né rete. Il seed arriva già letto (`seed`): dal filesystem per lo script, dallo
// snapshot generato (programma-seed.generated.json) per il web, perché l'immagine del ruolo web non contiene seed/.
//
// Regole che il nucleo fa rispettare:
//   - nessun dato dei membri nelle scritture di configurazione (`assertAllowedRequest` rifiuta corpi con /MBR-\d/);
//   - le storie dei membri partono SOLO come UN import dalla fonte di test, con una lista bianca propria
//     (`assertStoryImport`: fonte vetrina-test, soggetti solo tra i membri di test risolti);
//   - regola 22: premi e campagne nascono DRAFT e restano DRAFT: nessuna transizione, nessuna approvazione;
//   - regola 21: ogni scrittura ha una voce di audit con l'attore reale, verificata con `verifyAudit`.
// Test: scripts/vetrina-programma.test.mjs (node --test) e web/lib/vetrina/*.test.ts (vitest).
/** Errore di uso o di configurazione (uscita 2): nessuna richiesta è partita. */
export class UsageError extends Error {}

/** Risposta non 2xx di un servizio. `code` è il codice RFC 9457 se c'è. */
export class ApiError extends Error {
  constructor(status, code, detail) {
    super(`HTTP ${status}${code ? ` ${code}` : ''}${detail ? `: ${detail}` : ''}`);
    this.status = status;
    this.code = code;
  }
}

/** Entità di seed che per scelta NON si applicano, con il motivo (stampate nel piano). */
export const NOT_INCLUDED = [
  ['members.json, wallets.json, activity-history.json, redemptions.json, inbox.json, gamification-history.json', 'dati dei membri, movimenti, lotti, vincite, richieste, messaggi: mai (regola 9, docs/11 §11)'],
  ['campaigns.json, contests.json, contents.json', 'oggetti con flusso di approvazione (regola 22: lo script non approva né pubblica); Q-617 non li include'],
  ['editions.json', 'POST /v1/editions crea sempre in stato PLANNED e lo stato ACTIVE/CLOSED non si imposta da API: un\'edizione in stato sbagliato sarebbe peggio di nessuna'],
  ['webhooks.json', 'destinazione di rete in uscita e segreto (docs/18: «nuova destinazione di rete in uscita» → STOP); il seed porta un segreto'],
  ['scenarios.json, import-history.json, inbound-history.json, insight-synthetic.json', 'scenari e storici della demo: dati di prova, /v1/demo non esiste in enterprise'],
  ['coupon generati (size/consumed di coupon-pools.json)', 'i coupon sono dati operativi: si crea solo il pool (code, prefix, validityDays)'],
];

export async function safeJson(res) {
  try {
    const text = await res.text();
    return text ? JSON.parse(text) : null;
  } catch {
    return null;
  }
}

// ---------------------------------------------------------------------------------------------------------------
// Client delle API, con le sole richieste ammesse

/** Servizi toccati dalla riga di comando e nome della variabile d'ambiente con l'URL (come il web: LH_SVC_<NOME>_URL, docs/11 §8). */
export const SERVICES = ['ingestion', 'member', 'wallet', 'reward', 'gamification', 'engagement', 'insight'];
/** Con il servizio delle campagne: il programma del BFF crea anche le campagne in DRAFT (V10, Q-723). */
export const PROGRAM_SERVICES = [...SERVICES, 'campaign'];

/** Fonte di test della vetrina (Q-722): codice per il modulo dell'import, URN su ogni riga (la forma breve è INVALID, Q-258). */
export const STORY_FILE_NAME = 'vetrina-storie.ndjson';
export const STORY_SOURCE_CODE = 'vetrina-test';
export const STORY_SOURCE_URN = `urn:loyaltyhub:source:${STORY_SOURCE_CODE}`;
/** V11: l'azione singola ha un file proprio. NON `vetrina-storie.ndjson`: V10 riconosce l'import delle storie da quel nome. */
export const ACTION_FILE_NAME = 'vetrina-azione.ndjson';

/** Ambito della riga di comando: invariato dalla fetta M8.14d (niente campagne, niente membri, niente import). */
export const CLI_SCOPE = Object.freeze({ campaigns: false, stories: false });
/** Ambito del programma dal backoffice (V10): in più campagne in DRAFT, ricerca dei membri di test e import delle storie. */
export const PROGRAM_SCOPE = Object.freeze({ campaigns: true, stories: true });
/** Ambito di «Invia un'azione» (V11): niente campagne; ricerca membri, import e lettura del portafoglio (saldo e livello). */
export const ACTION_SCOPE = Object.freeze({ campaigns: false, stories: true, wallets: true, readOnly: true, actionImport: true });

const READ_RESOURCES = /^\/v1\/(currencies|tiers|event-types|sources|internal-mappings|theme|message-templates|notification-rules|reward-categories|reward-bands|coupon-pools|rewards|attribute-definitions|segments|badges|achievements|leaderboards|audit)$/;
const CREATE_RESOURCES = /^\/v1\/(event-types|sources|message-templates|notification-rules|reward-categories|reward-bands|coupon-pools|rewards|segments|achievements|badges|leaderboards)$/;
const REPLACE_RESOURCES = /^\/v1\/(attribute-definitions|theme)$/;
// Solo con PROGRAM_SCOPE: elenco campagne (stato), ricerca dei membri di test, elenco e dettaglio degli import.
const SCOPE_READ_CAMPAIGNS = /^\/v1\/campaigns$/;
// Portafoglio e libro mastro del solo membro (V11): saldo e livello, e i movimenti attribuiti a una riga (`actionId`).
const SCOPE_READ_WALLETS = /^\/v1\/wallets\/[A-Za-z0-9-]{3,40}(\/ledger)?$/;
const SCOPE_READ_STORIES = /^\/v1\/(members|imports|imports\/[A-Za-z0-9]{10,40})$/;

/** Difesa in profondità: solo queste richieste possono partire, mai movimenti, demo, transizioni. */
export function assertAllowedRequest(method, pathname, body, scope = CLI_SCOPE) {
  if (scope.readOnly && method !== 'GET') throw new Error(`richiesta non ammessa dallo script: ${method} ${pathname}`);
  const ok = (method === 'GET' && (READ_RESOURCES.test(pathname)
      || (scope.campaigns && SCOPE_READ_CAMPAIGNS.test(pathname))
      || (scope.stories && SCOPE_READ_STORIES.test(pathname))
      || (scope.wallets && SCOPE_READ_WALLETS.test(pathname))))
    || (method === 'POST' && (CREATE_RESOURCES.test(pathname) || (scope.campaigns && pathname === '/v1/campaigns')))
    || (method === 'PUT' && REPLACE_RESOURCES.test(pathname));
  if (!ok) throw new Error(`richiesta non ammessa dallo script: ${method} ${pathname}`);
  if (body !== undefined && /MBR-\d/.test(JSON.stringify(body))) {
    throw new Error(`il corpo di ${method} ${pathname} contiene un identificativo di membro: i dati dei membri non si applicano`);
  }
}

const MAX_STORY_FILE_BYTES = 1024 * 1024;

/**
 * Lista bianca PROPRIA dell'import delle storie (non passa dal controllo /MBR-\d/ delle scritture di configurazione:
 * le righe portano `member:MBR-…` per costruzione). Ammesso solo `POST /v1/imports` EVENTS dalla fonte `vetrina-test`,
 * un file NDJSON in cui OGNI riga ha l'URN della fonte di test, un id `vt-…` e un soggetto `member:<id>` tra i membri
 * di test già risolti dal chiamante. Nessun altro soggetto (niente e-mail né ID esterni: nessun dato personale).
 */
export function assertStoryImport(multipart, subjects) {
  const fail = (why) => { throw new Error(`import delle storie non ammesso: ${why}`); };
  const fields = multipart?.fields ?? {};
  const file = multipart?.file;
  if (fields.kind !== 'EVENTS') fail('kind deve essere EVENTS');
  if (fields.source !== STORY_SOURCE_CODE) fail(`la fonte deve essere ${STORY_SOURCE_CODE}`);
  if (!file || typeof file.text !== 'string' || !/^[A-Za-z0-9._-]+\.ndjson$/.test(String(file.name))) fail('serve un file .ndjson');
  if (file.text.length > MAX_STORY_FILE_BYTES) fail('file troppo grande');
  if (!subjects || subjects.size === 0) fail('nessun membro di test risolto');
  const lines = file.text.split('\n').filter((l) => l.trim() !== '');
  if (lines.length === 0 || lines.length > 2000) fail('numero di righe non valido');
  for (const line of lines) {
    let ev;
    try {
      ev = JSON.parse(line);
    } catch {
      return fail('riga non JSON');
    }
    if (ev?.source !== STORY_SOURCE_URN) fail('ogni riga deve avere la fonte in forma di URN');
    if (typeof ev.subject !== 'string' || !ev.subject.startsWith('member:') || !subjects.has(ev.subject.slice(7))) fail('soggetto fuori dai membri di test');
    if (typeof ev.id !== 'string' || !/^vt-[a-z0-9.-]+-\d{2,3}$/.test(ev.id)) fail('id riga non nella forma vt-<utente>-<nn>');
    if (typeof ev.type !== 'string' || !/^[a-z]+(\.[a-z]+)+$/.test(ev.type)) fail('tipo azione non valido');
  }
}

/**
 * Azioni inviabili dalla fonte di test (V11, BO-32, Q-675). `valued` = ha un importo in euro. L'elenco è un
 * sottoinsieme esplicito di `allowedTypes` della fonte `vetrina-test` (il controllo incrociato è nei test).
 */
export const ACTION_TYPES = [
  { type: 'purchase.completed', label: 'Acquisto completato', valued: true },
  { type: 'purchase.returned', label: 'Reso di un acquisto (non storna punti)', valued: true },
  { type: 'selfreading.submitted', label: 'Autolettura inviata', valued: false },
  { type: 'app.login.daily', label: "Accesso giornaliero all'app", valued: false },
  { type: 'review.submitted', label: 'Recensione inviata', valued: false },
  { type: 'ebill.activated', label: 'Bolletta digitale attivata', valued: false },
  { type: 'directdebit.activated', label: 'Domiciliazione attivata', valued: false },
  { type: 'survey.completed', label: 'Sondaggio completato', valued: false },
  { type: 'quiz.completed', label: 'Quiz completato', valued: false },
  { type: 'newsletter.subscribed', label: 'Iscrizione alla newsletter', valued: false },
];
export const MAX_ACTION_AMOUNT = 10000;

/**
 * Lista bianca PROPRIA dell'azione singola (V11): `POST /v1/imports` EVENTS dalla fonte `vetrina-test`, file
 * `vetrina-azione.ndjson` con UNA riga: fonte in forma di URN (Q-258), id `vt-act-…`, soggetto `member:<id>` tra i
 * membri di test già risolti, tipo tra quelli inviabili. Il caller non può far passare nient'altro.
 */
export function assertActionImport(multipart, subjects) {
  const fail = (why) => { throw new Error(`import dell'azione non ammesso: ${why}`); };
  const fields = multipart?.fields ?? {};
  const file = multipart?.file;
  if (fields.kind !== 'EVENTS') fail('kind deve essere EVENTS');
  if (fields.source !== STORY_SOURCE_CODE) fail(`la fonte deve essere ${STORY_SOURCE_CODE}`);
  if (!file || typeof file.text !== 'string' || file.name !== ACTION_FILE_NAME) fail(`il file deve chiamarsi ${ACTION_FILE_NAME}`);
  if (file.text.length > 4096) fail('file troppo grande');
  if (!subjects || subjects.size === 0) fail('nessun membro di test risolto');
  const lines = file.text.split('\n').filter((l) => l.trim() !== '');
  if (lines.length !== 1) fail('serve una sola riga');
  let ev;
  try {
    ev = JSON.parse(lines[0]);
  } catch {
    return fail('riga non JSON');
  }
  if (ev?.source !== STORY_SOURCE_URN) fail('la riga deve avere la fonte in forma di URN');
  if (typeof ev.subject !== 'string' || !ev.subject.startsWith('member:') || !subjects.has(ev.subject.slice(7))) fail('soggetto fuori dai membri di test');
  if (typeof ev.id !== 'string' || !/^vt-act-[a-f0-9]{16,32}$/.test(ev.id)) fail('id riga non nella forma vt-act-<id>');
  if (!ACTION_TYPES.some((a) => a.type === ev.type)) fail('tipo di azione non inviabile');
}

/**
 * Trasporto HTTP su `fetch`: `(service, method, path, {query, body, multipart, headers}) → {status, body}`.
 * `getToken` è chiamata a OGNI richiesta (il BFF rinnova l'access token di 5 minuti: mai un token tenuto in cache qui).
 * Redirect non seguiti (il token non cambia host); il token non esce mai dall'header `Authorization`.
 */
export function fetchTransport({ targets, getToken, fetch: doFetch, timeoutMs = 30000 }) {
  return async (service, method, pathname, { query, body, multipart, headers: extra } = {}) => {
    const base = targets[service];
    if (!base) throw new UsageError(`URL del servizio ${service} non configurato`);
    const qs = query ? `?${new URLSearchParams(query).toString()}` : '';
    const token = await getToken();
    const headers = { Accept: 'application/json', Authorization: `Bearer ${token}`, ...(extra ?? {}) };
    const init = { method, headers, redirect: 'manual', signal: AbortSignal.timeout(timeoutMs) };
    if (multipart) {
      const form = new FormData();
      for (const [k, v] of Object.entries(multipart.fields ?? {})) form.set(k, String(v));
      form.set('file', new Blob([multipart.file.text], { type: multipart.file.type ?? 'application/x-ndjson' }), multipart.file.name);
      init.body = form; // il Content-Type con il confine lo imposta fetch
    } else if (body !== undefined) {
      headers['Content-Type'] = 'application/json';
      init.body = JSON.stringify(body);
    }
    const res = await doFetch(`${base}${pathname}${qs}`, init);
    return { status: res.status, body: await safeJson(res) };
  };
}

export class Api {
  /** @param {{ transport: Function, scope?: object }} opts */
  constructor({ transport, scope = CLI_SCOPE }) {
    this.transport = transport;
    this.scope = scope;
    this.writes = 0;
    /** ID dei membri di test risolti: gli unici soggetti ammessi nell'import delle storie. */
    this.storySubjects = new Set();
  }

  async request(service, method, pathname, { query, body, multipart, headers } = {}) {
    if (method === 'POST' && pathname === '/v1/imports') {
      if (!this.scope.stories) throw new Error(`richiesta non ammessa dallo script: ${method} ${pathname}`);
      // La lista bianca la decide l'AMBITO, non il nome del file: azione singola solo con ACTION_SCOPE, storie solo con
      // PROGRAM_SCOPE; l'altra forma è rifiutata anche se il file ha il nome «giusto».
      if (this.scope.actionImport) assertActionImport(multipart, this.storySubjects);
      else assertStoryImport(multipart, this.storySubjects);
    } else {
      assertAllowedRequest(method, pathname, body, this.scope);
    }
    if (method !== 'GET') this.writes++;
    const res = await this.transport(service, method, pathname, { query, body, multipart, headers });
    if (res.status >= 300 && res.status < 400) throw new ApiError(res.status, 'REDIRECT', 'redirect non seguito');
    if (res.status < 200 || res.status >= 300) {
      const json = res.body;
      const detail = typeof json?.detail === 'string' ? json.detail : typeof json?.message === 'string' ? json.message : '';
      throw new ApiError(res.status, typeof json?.code === 'string' ? json.code : null, detail.slice(0, 200));
    }
    return { status: res.status, body: res.body };
  }
}

export function asItems(body) {
  if (Array.isArray(body)) return body;
  if (body && Array.isArray(body.items)) return body.items;
  throw new Error('risposta di elenco non riconosciuta');
}

/** Espressioni di data del seed (docs/10 §1) che servono alla configurazione: @now, @today, @today±Nd. */
export function resolveSeedDate(value, now) {
  if (typeof value !== 'string' || !value.startsWith('@')) return value ?? null;
  if (value === '@now') return new Date(now).toISOString();
  const m = /^@today(?:([+-])(\d{1,4})d)?$/.exec(value);
  if (!m) throw new UsageError(`espressione di data non supportata dallo script: ${value}`);
  const d = new Date(now);
  d.setUTCHours(0, 0, 0, 0);
  if (m[1]) d.setUTCDate(d.getUTCDate() + (m[1] === '-' ? -1 : 1) * Number(m[2]));
  return d.toISOString();
}

const pick = (obj, keys) => Object.fromEntries(keys.filter((k) => obj[k] !== undefined && obj[k] !== null).map((k) => [k, obj[k]]));

function looseEqual(a, b) {
  return JSON.stringify(a ?? null) === JSON.stringify(b ?? null);
}

// ---------------------------------------------------------------------------------------------------------------
// Piano: entità nell'ordine delle dipendenze

const NO_CREATE = 'nessuna API di creazione (le PUT agiscono solo su righe già esistenti)';

/** Specifiche delle collezioni standard: GET elenco, POST per creare ciò che manca. */
const COLLECTIONS = [
  {
    id: 'event-types', service: 'ingestion', file: 'event-types', list: '/v1/event-types', keyOf: (x) => x.code,
    create: '/v1/event-types', audit: { type: 'event_type' },
    build: (s) => (s.origin === 'CUSTOM'
      ? { body: pick(s, ['code', 'name', 'description', 'category', 'icon', 'dataSchema', 'sampleData', 'enabled']) }
      : { skip: 'origine SYSTEM: la POST crea solo tipi CUSTOM e nessun\'altra API crea quelli di sistema' }),
  },
  {
    id: 'sources', service: 'ingestion', file: 'sources', list: '/v1/sources', keyOf: (x) => x.code,
    create: '/v1/sources', audit: { type: 'source' },
    build: (s, ctx) => {
      if (s.kind !== 'HTTP') return { skip: `fonte ${s.kind}: da API si creano solo fonti HTTP` };
      const missing = (s.allowedTypes ?? []).filter((t) => !ctx.known.has(`event-types:${t}`));
      if (missing.length) {
        return { skip: 'tipi azione ammessi non presenti (quelli di sistema non si creano da API): senza, la fonte si aprirebbe a tutti i tipi' };
      }
      return { body: pick(s, ['code', 'name', 'kind', 'enabled', 'allowedTypes', 'description']) };
    },
  },
  { id: 'currencies', service: 'wallet', file: 'currencies', list: '/v1/currencies', keyOf: (x) => x.code, create: null,
    build: () => ({ skip: NO_CREATE }) },
  { id: 'tiers', service: 'wallet', file: 'tiers', list: '/v1/tiers', keyOf: (x) => x.code, create: null,
    build: () => ({ skip: NO_CREATE }) },
  { id: 'internal-mappings', service: 'ingestion', file: 'internal-mappings', list: '/v1/internal-mappings', keyOf: (x) => x.factType.replace(/^(io\.loyaltyhub\.)?fact\./, ''), create: null,
    build: () => ({ skip: NO_CREATE }) },
  {
    id: 'message-templates', service: 'engagement', file: 'message-templates', list: '/v1/message-templates', keyOf: (x) => x.code,
    create: '/v1/message-templates', audit: { type: 'MESSAGE_TEMPLATE' },
    build: (s) => ({ body: pick(s, ['code', 'name', 'channel', 'titleTpl', 'bodyTpl', 'icon', 'linkTarget', 'category']) }),
  },
  {
    id: 'notification-rules', service: 'engagement', file: 'notification-rules', list: '/v1/notification-rules', keyOf: (x) => x.code,
    create: '/v1/notification-rules', audit: { type: 'NOTIFICATION_RULE' },
    build: (s) => ({ body: pick(s, ['code', 'factType', 'condition', 'templateCode', 'enabled']), needs: [`message-templates:${s.templateCode}`] }),
  },
  {
    id: 'reward-categories', service: 'reward', file: 'reward-categories', list: '/v1/reward-categories', keyOf: (x) => x.code,
    create: '/v1/reward-categories', audit: { type: 'REWARD_CATEGORY' },
    build: (s) => ({ body: pick(s, ['code', 'name', 'icon', 'sortOrder']) }),
  },
  {
    id: 'reward-bands', service: 'reward', file: 'reward-bands', list: '/v1/reward-bands', keyOf: (x) => x.code,
    create: '/v1/reward-bands', audit: { type: 'REWARD_BAND' }, order: (a, b) => a.sortOrder - b.sortOrder,
    build: (s) => ({ body: pick(s, ['code', 'name', 'pointsThreshold', 'color', 'sortOrder']) }),
  },
  {
    // Solo il pool: size e consumed del seed sono coupon generati e usati, cioè dati operativi.
    id: 'coupon-pools', service: 'reward', file: 'coupon-pools', list: '/v1/coupon-pools', keyOf: (x) => x.code,
    create: '/v1/coupon-pools', audit: { type: 'COUPON_POOL' },
    build: (s) => ({ body: pick(s, ['code', 'name', 'prefix', 'validityDays']) }),
    remember: (item, ctx) => { if (item.response?.id) ctx.poolIds.set(item.key, item.response.id); },
    rememberExisting: (x, ctx) => { if (x.id) ctx.poolIds.set(x.code, x.id); },
  },
  {
    // Sempre DRAFT (regola 22): l'API ignora lo stato del seed e lo script non fa transizioni.
    id: 'rewards', service: 'reward', file: 'rewards', list: '/v1/rewards', keyOf: (x) => x.code,
    create: '/v1/rewards', audit: { type: 'REWARD' }, compareKeys: ['name', 'type', 'fulfilment'],
    build: (s, ctx) => {
      const needs = [`reward-categories:${s.category}`, `reward-bands:${s.band}`];
      if (s.couponPool) needs.push(`coupon-pools:${s.couponPool}`);
      const base = pick(s, ['code', 'name', 'description', 'terms', 'type', 'category', 'band', 'fulfilment', 'stockTotal', 'perMemberLimit', 'eligibleTiers', 'eligibleSegments']);
      const validFrom = resolveSeedDate(s.validFrom, ctx.now);
      return {
        needs,
        body: () => ({
          ...base,
          ...(validFrom ? { validFrom } : {}),
          ...(s.couponPool ? { couponPoolId: ctx.poolIds.get(s.couponPool) ?? `<id di ${s.couponPool}>` } : {}),
        }),
      };
    },
  },
  {
    id: 'segments', service: 'member', file: 'segments', list: '/v1/segments', paged: true, keyOf: (x) => x.code,
    create: '/v1/segments', audit: { type: 'SEGMENT' },
    // SegmentService.create valida i criteri contro le definizioni degli attributi personalizzati: un segmento che
    // usa `member.<attributo>` dipende dalla PUT degli attributi e, se questa manca o fallisce, si salta (non si tenta).
    build: (s, ctx) => (s.type === 'DYNAMIC'
      ? {
        body: { ...pick(s, ['code', 'name', 'description', 'type', 'criteria']) },
        needs: criteriaAttributeKeys(s.criteria, ctx.seed['attribute-definitions']).map((k) => `attribute-definitions:${k}`),
      }
      : { skip: `segmento ${s.type}: l'elenco è fatto di membri (MBR-*), dato dei membri` }),
  },
  {
    id: 'badges', service: 'gamification', file: 'badges', list: '/v1/badges', keyOf: (x) => x.code,
    create: '/v1/badges', audit: { type: 'BADGE' },
    build: (s) => ({ body: pick(s, ['code', 'name', 'description', 'icon', 'color']) }),
  },
  {
    id: 'achievements', service: 'gamification', file: 'achievements', list: '/v1/achievements', keyOf: (x) => x.code,
    create: '/v1/achievements', audit: { type: 'ACHIEVEMENT' },
    build: (s) => ({
      body: pick(s, ['code', 'name', 'description', 'icon', 'actionTypes', 'filter', 'metric', 'sumField', 'streakUnit', 'target', 'period', 'repeatable', 'badgeCode']),
      needs: s.badgeCode ? [`badges:${s.badgeCode}`] : [],
    }),
  },
  {
    id: 'leaderboards', service: 'gamification', file: 'leaderboards', list: '/v1/leaderboards', keyOf: (x) => x.code,
    create: '/v1/leaderboards', audit: { type: 'LEADERBOARD' },
    build: (s) => ({ body: pick(s, ['code', 'name', 'metric', 'actionTypes', 'period', 'topN', 'status']) }),
  },
];

/** Attributi personalizzati del seed usati da un criterio (`member.<chiave>`), visitando anche i gruppi annidati. */
export function criteriaAttributeKeys(criteria, attributeDefs) {
  const custom = new Set((attributeDefs ?? []).map((a) => a.key));
  const used = new Set();
  const walk = (node) => {
    if (!node || typeof node !== 'object') return;
    if (typeof node.field === 'string' && node.field.startsWith('member.') && custom.has(node.field.slice(7))) used.add(node.field.slice(7));
    for (const child of [...(Array.isArray(node.rules) ? node.rules : []), ...(Array.isArray(node.groups) ? node.groups : [])]) walk(child);
  };
  walk(criteria);
  return [...used].sort();
}

/**
 * Campagne del seed (V10, Q-723): si creano SOLO quelle LIVE nel seed e non di sistema, e sempre in DRAFT (la POST non
 * accetta lo stato; lo script non invia, non approva, non pubblica: regola 22). Le altre si riportano come escluse.
 * Attiva solo con l'opzione `campaigns` di `buildPlan` (la riga di comando non le tocca).
 */
// SPEC-GAP: Q-723 (default A: campagne in DRAFT, storie in attesa che siano LIVE; decisione con Giuseppe in sospeso)
const CAMPAIGNS_SPEC = {
  id: 'campaigns', service: 'campaign', file: 'campaigns', list: '/v1/campaigns', keyOf: (x) => x.code,
  create: '/v1/campaigns', audit: { type: 'CAMPAIGN' }, compareKeys: ['name'],
  build: (s) => {
    if (s.system) return { skip: 'campagna di sistema (instant win): la POST non crea campagne di sistema' };
    if (s.status !== 'LIVE') return { skip: `nel seed è ${s.status}: richiede un passaggio di approvazione o è conclusa, non si crea` };
    // Dipendenze degli effetti: template dei messaggi, premio del coupon e concorso delle giocate. I concorsi non si creano
    // da qui (nessuna API di creazione nel perimetro): una campagna che li richiama pubblicata finirebbe in DLQ con
    // CONTEST_NOT_FOUND, quindi si riporta come saltata («dipendenza non disponibile»).
    const needs = [];
    for (const e of s.effects ?? []) {
      if (e.type === 'SEND_MESSAGE' && e.templateCode) needs.push(`message-templates:${e.templateCode}`);
      if (e.type === 'GRANT_PLAYS' && e.contestCode) needs.push(`contests:${e.contestCode}`);
      if (e.type === 'ISSUE_COUPON' && e.rewardCode) needs.push(`rewards:${e.rewardCode}`);
    }
    return {
      needs: [...new Set(needs)],
      body: pick(s, ['code', 'name', 'description', 'memberDescription', 'icon', 'triggerActionTypes', 'audience', 'conditions', 'effects', 'limits', 'schedule',
        'priority', 'exclusiveGroup', 'visibleInPortal', 'labels', 'requiresLegal']),
    };
  },
};

/** Ordine di esecuzione: dipendenze prima (tipi azione → fonti, categorie/fasce/pool → premi, template → regole…). */
const ORDER = ['event-types', 'sources', 'currencies', 'tiers', 'internal-mappings', 'theme', 'message-templates', 'notification-rules',
  'reward-categories', 'reward-bands', 'coupon-pools', 'rewards', 'attribute-definitions', 'segments', 'badges', 'achievements', 'leaderboards'];

async function listAll(api, spec) {
  if (!spec.paged) return asItems((await api.request(spec.service, 'GET', spec.list)).body);
  const out = [];
  for (let page = 0; page < 200; page++) {
    const body = (await api.request(spec.service, 'GET', spec.list, { query: { page: String(page), size: '100' } })).body;
    const items = asItems(body);
    out.push(...items);
    const total = body?.page?.totalPages;
    if (items.length === 0 || (typeof total === 'number' && page + 1 >= total) || (typeof total !== 'number' && items.length < 100)) break;
  }
  return out;
}

async function planCollection(ctx, spec) {
  const items = [];
  let existing = [];
  if (!ctx.offline) {
    try {
      existing = await listAll(ctx.api, spec);
    } catch (e) {
      return [{ entity: spec.id, service: spec.service, key: '*', action: 'error', reason: `lettura fallita: ${describeError(e)}` }];
    }
  }
  const byKey = new Map(existing.map((x) => [spec.keyOf(x), x]));
  for (const x of existing) {
    ctx.known.add(`${spec.id}:${spec.keyOf(x)}`);
    spec.rememberExisting?.(x, ctx);
  }
  const seed = spec.order ? [...ctx.seed[spec.file]].sort(spec.order) : ctx.seed[spec.file];
  for (const s of seed) {
    const key = spec.keyOf(s);
    const base = { entity: spec.id, service: spec.service, key };
    const found = byKey.get(key);
    if (found) {
      const built = spec.build(s, ctx);
      const body = typeof built.body === 'function' ? built.body(ctx) : built.body;
      const keys = spec.compareKeys ?? Object.keys(body ?? {});
      const differing = body ? keys.filter((k) => found[k] !== undefined && !looseEqual(found[k], body[k])) : [];
      // Una voce presente e uguale al seed è candidata alla verifica dell'audit (--verify-audit); una divergente no.
      items.push({
        ...base, action: 'present', note: differing.length ? `diverge dal seed (${differing.join(', ')}), non modificato` : undefined,
        audit: !differing.length && spec.audit ? { type: spec.audit.type, ids: [key], actions: ['CREATE', 'UPDATE'] } : undefined,
      });
      continue;
    }
    const built = spec.build(s, ctx);
    if (built.skip) {
      items.push({ ...base, action: 'skip', reason: built.skip });
      continue;
    }
    const unmet = (built.needs ?? []).filter((n) => !ctx.known.has(n));
    if (unmet.length) {
      items.push({ ...base, action: 'skip', reason: `dipendenza non disponibile: ${unmet.join(', ')}` });
      continue;
    }
    ctx.known.add(`${spec.id}:${key}`);
    items.push({
      ...base, action: 'create', method: 'POST', path: spec.create, body: built.body, needs: built.needs ?? [],
      audit: { type: spec.audit.type, ids: [key], actions: ['CREATE', 'UPDATE'] },
      remember: spec.remember,
    });
  }
  return items;
}

/** Attributi personalizzati: la PUT sostituisce l'intero elenco, quindi si rimanda l'esistente più i mancanti. */
async function planAttributes(ctx) {
  const id = 'attribute-definitions';
  let existing = [];
  if (!ctx.offline) {
    try {
      existing = asItems((await ctx.api.request('member', 'GET', '/v1/attribute-definitions')).body);
    } catch (e) {
      return [{ entity: id, service: 'member', key: '*', action: 'error', reason: `lettura fallita: ${describeError(e)}` }];
    }
  }
  const have = new Set(existing.map((a) => a.key));
  const missing = ctx.seed[id].filter((a) => !have.has(a.key));
  const present = ctx.seed[id].filter((a) => have.has(a.key));
  for (const a of ctx.seed[id]) ctx.known.add(`${id}:${a.key}`);
  // La PUT sostituisce l'elenco: un'unica voce di audit (entityId `all`) copre tutte le chiavi presenti.
  const items = present.map((a) => ({ entity: id, service: 'member', key: a.key, action: 'present', audit: { type: 'attribute_definition', ids: ['all'], actions: ['UPDATE', 'CREATE'] } }));
  if (missing.length) {
    const merged = [...existing.map((a) => pick(a, ['key', 'label', 'type', 'options'])), ...missing.map((a) => pick(a, ['key', 'label', 'type', 'options']))];
    items.push({
      entity: id, service: 'member', key: 'all', action: 'create', method: 'PUT', path: '/v1/attribute-definitions', body: merged,
      label: `${missing.length} attributi (${missing.map((a) => a.key).join(', ')})`,
      provides: ctx.seed[id].map((a) => `${id}:${a.key}`),
      audit: { type: 'attribute_definition', ids: ['all'], actions: ['UPDATE', 'CREATE'] },
    });
  }
  return items;
}

/** Tema: GET risponde col tema Aurora se la riga manca (updatedAt nullo). Si scrive solo se mai salvato e diverso. */
async function planTheme(ctx) {
  const id = 'theme';
  const s = ctx.seed.theme;
  const body = { ...pick(s, ['programName', 'tagline', 'logoUrl', 'heroTitle', 'heroSubtitle', 'fontDisplay']), colors: s.colors, currencyNames: s.currencyNames };
  if (ctx.offline) {
    return [{ entity: id, service: 'engagement', key: 'default', action: 'create', method: 'PUT', path: '/v1/theme', body, audit: { type: 'THEME', ids: ['default'], actions: ['UPDATE', 'CREATE'] } }];
  }
  let current;
  try {
    current = (await ctx.api.request('engagement', 'GET', '/v1/theme')).body;
  } catch (e) {
    return [{ entity: id, service: 'engagement', key: 'default', action: 'error', reason: `lettura fallita: ${describeError(e)}` }];
  }
  const norm = (t) => JSON.stringify({
    ...Object.fromEntries(['programName', 'tagline', 'logoUrl', 'heroTitle', 'heroSubtitle', 'fontDisplay'].map((k) => [k, t?.[k] ?? null])),
    colors: Object.fromEntries(Object.entries(t?.colors ?? {}).sort().map(([k, v]) => [k, String(v).toUpperCase()])),
    currencyNames: Object.fromEntries(Object.entries(t?.currencyNames ?? {}).sort()),
  });
  if (norm(current) === norm(body)) {
    // Con `updatedAt` nullo il tema è quello di ripiego del servizio: nessuna scrittura, quindi nessuna voce di audit.
    return [{ entity: id, service: 'engagement', key: 'default', action: 'present', audit: current?.updatedAt ? { type: 'THEME', ids: ['default'], actions: ['UPDATE', 'CREATE'] } : undefined }];
  }
  if (current?.updatedAt) {
    return [{ entity: id, service: 'engagement', key: 'default', action: 'present', note: 'già personalizzato, diverge dal seed, non modificato' }];
  }
  return [{
    entity: id, service: 'engagement', key: 'default', action: 'create', method: 'PUT', path: '/v1/theme', body: { ...body, version: current?.version ?? 0 },
    audit: { type: 'THEME', ids: ['default'], actions: ['UPDATE', 'CREATE'] },
  }];
}

export function describeError(e) {
  if (e instanceof ApiError) return e.message;
  if (e?.name === 'TimeoutError') return 'timeout';
  if (e?.cause?.code ?? e?.code) return String(e.cause?.code ?? e.code);
  // Errori nostri (sessione scaduta, IdP non raggiungibile, regole del piano): il testo è già italiano e senza dettagli di rete.
  if (e instanceof Error && e.message && !(e instanceof TypeError)) return e.message;
  return e?.name ?? 'errore di rete';
}

/**
 * Piano di configurazione. `options.campaigns`: crea in DRAFT le campagne LIVE del seed. `options.vetrinaSource`: aggiunge
 * ai sorgenti la fonte di test `vetrina-test` di `seed.vetrinaTest` (Q-722). Senza opzioni il piano è quello della
 * riga di comando di M8.14d, invariato.
 */
// SPEC-GAP: Q-722 (fonte vetrina-test da seed/vetrina-test.json, non da sources.json)
export async function buildPlan({ seed, api, offline, now, options = {} }) {
  const effectiveSeed = options.vetrinaSource && seed.vetrinaTest?.source
    ? { ...seed, sources: [...seed.sources, seed.vetrinaTest.source] }
    : seed;
  const ctx = { seed: effectiveSeed, api, offline, now, known: new Set(), poolIds: new Map() };
  const specs = options.campaigns ? [...COLLECTIONS, CAMPAIGNS_SPEC] : COLLECTIONS;
  const byId = new Map(specs.map((c) => [c.id, c]));
  const order = options.campaigns ? [...ORDER, 'campaigns'] : ORDER;
  const plan = [];
  for (const id of order) {
    if (id === 'theme') plan.push(...await planTheme(ctx));
    else if (id === 'attribute-definitions') plan.push(...await planAttributes(ctx));
    else plan.push(...await planCollection(ctx, byId.get(id)));
  }
  // Un corpo che contiene un identificativo di membro non parte mai: lo si verifica già sul piano.
  for (const item of plan) {
    if (item.action !== 'create') continue;
    assertAllowedRequest(item.method, item.path, typeof item.body === 'function' ? item.body(ctx) : item.body, options.campaigns ? PROGRAM_SCOPE : CLI_SCOPE);
  }
  return { plan, ctx };
}

/**
 * Esegue le creazioni del piano nell'ordine delle dipendenze. `onProgress({done, total, item})` dopo ogni voce (anche
 * fallita o già presente): il BFF lo mostra come «33 di 60». `log` è facoltativo.
 */
export async function applyPlan({ plan, ctx, api, log, onProgress }) {
  const failed = new Set();
  const results = [];
  const total = plan.filter((i) => i.action === 'create').length;
  let done = 0;
  for (const item of plan) {
    if (item.action !== 'create') continue;
    const dep = (item.needs ?? []).find((n) => failed.has(n));
    if (dep) {
      item.action = 'skip';
      item.reason = `dipendenza non creata: ${dep}`;
      done++;
      onProgress?.({ done, total, item });
      continue;
    }
    try {
      const body = typeof item.body === 'function' ? item.body(ctx) : item.body;
      const res = await api.request(item.service, item.method, item.path, { body });
      item.response = res.body;
      item.result = 'created';
      item.remember?.(item, ctx);
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) {
        // Creato nel frattempo da un'altra esecuzione: stato voluto raggiunto, nessuna voce di audit attesa da noi.
        item.result = 'already';
      } else if (e?.name === 'SessionGoneError') {
        // Sessione dell'operatore scaduta: inutile continuare, ogni chiamata successiva fallirebbe allo stesso modo.
        throw e;
      } else {
        item.result = 'failed';
        item.error = describeError(e);
        failed.add(`${item.entity}:${item.key}`);
        for (const p of item.provides ?? []) failed.add(p);
        log?.err(`  ✗ ${item.entity} ${item.key}: ${item.error}`);
      }
    }
    results.push(item);
    done++;
    onProgress?.({ done, total, item });
  }
  return results;
}

const DENY_ACTORS = new Set(['', 'demo', 'system', 'anonymous', 'unknown', '-']);

export function isRealActor(record) {
  const name = String(record.actorName ?? '').trim().toLowerCase();
  const role = String(record.actorRole ?? '').trim().toLowerCase();
  return !DENY_ACTORS.has(name) && role !== 'system' && !name.startsWith('member:') && !name.startsWith('src-');
}

/**
 * Regola 21: per ogni voce da verificare deve esistere su GET /v1/audit (insight) una voce con lo stesso tipo e
 * identificativo dell'entità, successiva a `since`, con attore reale. Se dal token si conosce l'identità
 * dell'operatore (`expectedActor`), l'attore della voce deve essere proprio quella: la scrittura di un altro operatore
 * sulla stessa entità nella stessa finestra non vale. Senza identità (token opaco) si accetta ogni attore reale e
 * l'esito lo dichiara. L'API filtra per `entityType` e `entityId` (uguaglianza esatta): si legge un elenco per tipo
 * di entità (il filtro per identificativo costerebbe una richiesta per scrittura) e si abbina l'identificativo qui.
 * L'audit viaggia sul bus: si riprova fino al timeout.
 */
export async function verifyAudit({ api, items, since, expectedActor = null, timeoutSec, intervalSec, sleep }) {
  const pending = new Map(items.map((item) => [`${item.entity}:${item.key}`, item]));
  const problems = new Map();
  const attempts = Math.max(1, Math.ceil(timeoutSec / Math.max(intervalSec, 0.001)) + 1);
  for (let attempt = 0; attempt < attempts && pending.size; attempt++) {
    if (attempt > 0) await sleep(intervalSec * 1000);
    const byType = new Map();
    for (const item of pending.values()) {
      if (!byType.has(item.audit.type)) byType.set(item.audit.type, await fetchAudit(api, { since, entityType: item.audit.type }));
    }
    for (const [id, item] of [...pending]) {
      const matching = byType.get(item.audit.type).filter((r) => item.audit.ids.includes(r.entityId)
        && String(r.entityType ?? '').toLowerCase() === item.audit.type.toLowerCase()
        && item.audit.actions.includes(String(r.action ?? '').toUpperCase()));
      const real = matching.filter(isRealActor);
      if (real.some((r) => expectedActor === null || String(r.actorName ?? '').trim() === expectedActor)) {
        pending.delete(id);
        problems.delete(id);
      } else {
        problems.set(id, !matching.length ? 'voce di audit mancante'
          : real.length ? 'voce di audit di un altro attore, non dell\'operatore del token'
            : 'voce di audit con attore non reale');
      }
    }
  }
  return { verified: items.length - pending.size, problems: [...pending.keys()].map((id) => ({ id, reason: problems.get(id) })) };
}

/**
 * Voci di audit di un tipo di entità dall'istante `since`, più recenti per prima. Un inserimento durante la lettura
 * sposta le pagine successive in avanti: ripete una voce già letta, non ne salta; si deduplica per `id`.
 */
export async function fetchAudit(api, { since, entityType }) {
  const out = new Map();
  for (let page = 0; page < 100; page++) {
    const body = (await api.request('insight', 'GET', '/v1/audit', { query: { from: since.toISOString(), entityType, page: String(page), size: '100' } })).body;
    const items = asItems(body);
    for (const r of items) out.set(r.id ?? `${page}:${out.size}`, r);
    const total = body?.page?.totalPages;
    if (items.length === 0 || (typeof total === 'number' ? page + 1 >= total : items.length < 100)) break;
  }
  return [...out.values()];
}

// ---------------------------------------------------------------------------------------------------------------
// Storie dei membri di test (V10, Q-722, Q-723): UN import NDJSON dalla fonte vetrina-test

/** Raggruppamento del piano per l'anteprima (docs/08 BO-01): entità → riga della tabella. */
export const PLAN_GROUPS = [
  { id: 'currency-tiers', label: 'Valute e livelli', entities: ['currencies', 'tiers', 'internal-mappings'] },
  { id: 'actions', label: 'Tipi di azione e fonte di test', entities: ['event-types', 'sources'] },
  { id: 'rewards', label: 'Premi (in bozza), fasce e coupon', entities: ['reward-categories', 'reward-bands', 'coupon-pools', 'rewards'] },
  { id: 'campaigns', label: 'Campagne (in bozza)', entities: ['campaigns'] },
  { id: 'segments', label: 'Attributi e segmenti', entities: ['attribute-definitions', 'segments'] },
  { id: 'gamification', label: 'Badge, obiettivi e classifiche', entities: ['badges', 'achievements', 'leaderboards'] },
  { id: 'engagement', label: 'Messaggi, notifiche e tema', entities: ['message-templates', 'notification-rules', 'theme'] },
];

/** Cosa resta fuori per scelta nel programma del backoffice (le campagne escluse si leggono dalle voci `skip` del piano). */
export const NOT_INCLUDED_PROGRAM = [
  ['Membri, movimenti, lotti, vincite, richieste, messaggi', 'dati dei membri: mai (regola 9). I membri di test si registrano dal portale; le loro storie sono azioni importate, non saldi.'],
  ['Concorsi e contenuti', 'oggetti con flusso di approvazione: si preparano a mano e li approva un altro operatore (regola 22)'],
  ['Edizioni', 'la POST crea sempre in stato PLANNED e lo stato non si imposta da API'],
  ['Webhook', 'destinazione di rete in uscita e segreto: serve una decisione (docs/18)'],
  ['Scenari e storici della demo', 'dati di prova della demo: /v1/demo non esiste in enterprise'],
  ['Coupon generati', 'dati operativi: si crea solo il pool'],
];

/** `@today-NdTHH:MM` (UTC) → istante ISO. N da 1 a 29: l'ingestion accetta al più 30 giorni nel passato e 5 minuti nel futuro. */
export function resolveStoryTime(at, now) {
  const m = /^@today-(\d{1,2})d(?:T(\d{2}):(\d{2}))?$/.exec(String(at));
  if (!m) throw new UsageError(`data di una storia non supportata: ${at}`);
  const days = Number(m[1]);
  const hh = Number(m[2] ?? 12);
  const mm = Number(m[3] ?? 0);
  if (days < 1 || days > 29 || hh > 23 || mm > 59) throw new UsageError(`data di una storia fuori dalla finestra di ingestion (1–29 giorni nel passato): ${at}`);
  const d = new Date(now);
  d.setUTCHours(0, 0, 0, 0);
  d.setUTCDate(d.getUTCDate() - days);
  d.setUTCHours(hh, mm, 0, 0);
  return d.toISOString().replace('.000Z', 'Z');
}

/** STS attesi di una storia dalle regole delle campagne del seed: 1 STS per euro intero (CMP-PURCHASE-BASE, senza moltiplicatore) + 25 per ogni mese con un'autolettura (CMP-SELF-READING, 1 al mese). */
// SPEC-GAP: Q-724 (i PTS delle storie non coincidono con docs/10 §2: solo gli STS sono esatti)
export function expectedSts(rows, now) {
  const purchases = rows.filter((r) => r.type === 'purchase.completed').reduce((n, r) => n + Math.floor(Number(r.data?.amount ?? 0)), 0);
  const months = new Set(rows.filter((r) => r.type === 'selfreading.submitted').map((r) => resolveStoryTime(r.at, now).slice(0, 7)));
  return purchases + 25 * months.size;
}

/**
 * Campagne LIVE (non di sistema) del seed che assegnano PUNTI alle azioni delle storie: senza di loro le righe non fanno
 * punti. Moltiplicatori e giocate non contano: senza il loro effetto i punti arrivano comunque.
 */
export function campaignsNeededByStories(seed) {
  const types = new Set((seed.vetrinaTest?.stories ?? []).flatMap((s) => s.rows.map((r) => r.type)));
  return (seed.campaigns ?? [])
    .filter((c) => c.status === 'LIVE' && !c.system && (c.triggerActionTypes ?? []).some((t) => types.has(t))
      && (c.effects ?? []).some((e) => e.type === 'GRANT_POINTS'))
    .map((c) => c.code)
    .sort();
}

/**
 * Riga dell'azione singola (V11): un CloudEvent con dati validi per lo schema di `contracts/events/action/`. Gli
 * identificativi (ordine, contratto…) si derivano da `uid` (esadecimale minuscolo, almeno 16 caratteri: un UUID senza
 * trattini). Importo solo per le azioni con valore (acquisto, reso): positivo, al più MAX_ACTION_AMOUNT, al più due decimali.
 */
/**
 * Importo in euro: un numero finito, oppure una stringa `^\d{1,5}([.,]\d{1,2})?$` (la virgola è il separatore dei
 * decimali). Un punto seguito da esattamente tre cifre («1.000») è ambiguo (migliaia o decimali?) e si rifiuta con un
 * messaggio chiaro; booleani, liste, esadecimali ed esponenti non sono importi. Positivo, al più MAX_ACTION_AMOUNT.
 */
export function parseActionAmount(amount) {
  const bad = (why) => new UsageError(`importo non valido: ${why}`);
  const rule = `serve un numero positivo, al più ${MAX_ACTION_AMOUNT}, con la virgola per i decimali (al più due) e senza separatore delle migliaia`;
  let value;
  if (typeof amount === 'number') {
    value = amount;
  } else if (typeof amount === 'string') {
    const s = amount.trim();
    if (/^\d{1,3}\.\d{3}$/.test(s)) throw bad(`«${s}» è ambiguo (migliaia o decimali?): senza separatore delle migliaia, e con la virgola per i decimali (per esempio ${s.replace('.', '')} oppure 1,5)`);
    if (!/^\d{1,5}([.,]\d{1,2})?$/.test(s)) throw bad(rule);
    value = Number(s.replace(',', '.'));
  } else {
    throw bad(rule);
  }
  const cents = Math.round(value * 100);
  if (!Number.isFinite(value) || value <= 0 || value > MAX_ACTION_AMOUNT || Math.abs(cents - value * 100) > 1e-6) throw bad(rule);
  return cents / 100;
}

export function buildActionRow({ type, memberId, username, amount, now, uid }) {
  const def = ACTION_TYPES.find((a) => a.type === type);
  if (!def) throw new UsageError(`tipo di azione non inviabile: ${type}`);
  const hex = String(uid);
  if (!/^[a-f0-9]{16,32}$/.test(hex)) throw new UsageError('identificativo di invio non valido');
  const tag = hex.slice(0, 8).toUpperCase();
  let value;
  if (def.valued) {
    value = parseActionAmount(amount);
  } else if (amount !== undefined && amount !== null && amount !== '') {
    throw new UsageError('questa azione non ha un importo');
  }
  const data = {
    'purchase.completed': { orderId: `ORD-VT-${tag}`, amount: value, currency: 'EUR', channel: 'APP' },
    'purchase.returned': { orderId: `ORD-VT-${tag}`, amount: value },
    'selfreading.submitted': { meterId: `MTR-VT-${String(username ?? 'TEST').replace(/[^a-z0-9]/gi, '').slice(0, 12).toUpperCase()}`, reading: 1000 + (parseInt(hex.slice(0, 6), 16) % 9000) },
    'app.login.daily': { platform: 'WEB' },
    'review.submitted': { productId: 'PRD-VT-01', rating: 5 },
    'ebill.activated': { contractId: `CTR-VT-${tag}` },
    'directdebit.activated': { contractId: `CTR-VT-${tag}` },
    'survey.completed': { surveyId: 'SRV-VT-01', score: 80 },
    'quiz.completed': { quizId: 'QZ-VT-01', correctAnswers: 8, totalQuestions: 10 },
    'newsletter.subscribed': {},
  }[type];
  const row = {
    specversion: '1.0',
    id: `vt-act-${hex}`,
    source: STORY_SOURCE_URN,
    type,
    subject: `member:${memberId}`,
    time: new Date(now).toISOString().replace(/\.\d{3}Z$/, 'Z'),
    data,
  };
  return { row, file: { name: ACTION_FILE_NAME, type: 'application/x-ndjson', text: `${JSON.stringify(row)}\n` } };
}

async function sha256Hex(text) {
  const digest = await globalThis.crypto.subtle.digest('SHA-256', new TextEncoder().encode(text));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

/**
 * File NDJSON dell'import: un CloudEvent per riga, soggetto `member:<id>` (mai e-mail), fonte in forma di URN, id
 * deterministico `vt-<utente>-<nn>` (dedup su fonte e id: rieseguire non raddoppia i punti).
 * @param members Map utente → id del membro risolto
 */
export async function buildStoryFile(vetrinaTest, members, now) {
  const lines = [];
  for (const story of vetrinaTest.stories) {
    const id = members.get(story.username);
    if (!id) continue;
    story.rows.forEach((r, i) => {
      lines.push(JSON.stringify({
        specversion: '1.0',
        id: `vt-${story.username}-${String(i + 1).padStart(2, '0')}`,
        source: STORY_SOURCE_URN,
        type: r.type,
        subject: `member:${id}`,
        time: resolveStoryTime(r.at, now),
        data: r.data ?? {},
      }));
    });
  }
  const text = lines.length ? `${lines.join('\n')}\n` : '';
  return { name: STORY_FILE_NAME, type: 'application/x-ndjson', text, rows: lines.length, sha256: await sha256Hex(text) };
}

/** Id del membro di test (e-mail esatta, stato ACTIVE, uno solo) o `null` se non è registrato. Solo in memoria. */
export async function findTestMember(api, story) {
  const found = asItems((await api.request('member', 'GET', '/v1/members', { query: { q: story.email, size: '50' } })).body)
    .filter((m) => String(m.email ?? '').toLowerCase() === story.email.toLowerCase() && m.status === 'ACTIVE');
  return found.length === 1 && typeof found[0].id === 'string' ? found[0].id : null;
}

/**
 * Stato del passo «storie»: `ready` (si può caricare), `waiting` (con i motivi), `running` (un import è in corso),
 * `present` (già caricato), `offline` (piano dal solo seed) o `error` (lettura fallita). Legge, non scrive.
 * @param plan voci del piano di configurazione (per sapere se la fonte di test esiste già)
 */
export async function planStories({ seed, api, plan = [], offline = false }) {
  const vt = seed.vetrinaTest;
  const needs = campaignsNeededByStories(seed);
  const members = vt.stories.map((s) => ({ username: s.username, name: s.name, rows: s.rows.length, state: 'unknown' }));
  const out = { state: 'waiting', reasons: [], needs, waitingFor: [], members, rows: 0, totalRows: members.reduce((n, m) => n + m.rows, 0), jobId: null, incompleteJobIds: [], resolved: new Map() };
  if (offline) return { ...out, state: 'offline', reasons: ['piano dal solo seed: nessuna lettura'] };
  try {
    const campaigns = asItems((await api.request('campaign', 'GET', '/v1/campaigns')).body);
    const live = new Set(campaigns.filter((c) => c.status === 'LIVE').map((c) => c.code));
    out.waitingFor = needs.filter((c) => !live.has(c));
    for (const [i, story] of vt.stories.entries()) {
      const memberId = await findTestMember(api, story);
      if (memberId) {
        out.resolved.set(story.username, memberId);
        members[i].state = 'ready';
        out.rows += story.rows.length;
      } else {
        members[i].state = 'missing';
      }
    }
    const jobs = asItems((await api.request('ingestion', 'GET', '/v1/imports', { query: { size: '50' } })).body)
      // Solo l'import delle storie: altri lavori della stessa fonte (V11, una riga) non vanno scambiati per questo.
      .filter((j) => j.defaultSource === STORY_SOURCE_CODE && j.fileName === STORY_FILE_NAME);
    const active = jobs.find((j) => j.status === 'QUEUED' || j.status === 'RUNNING');
    if (active) out.jobId = active.id;
    // «Presente» solo se un lavoro concluso ha accettato (o riconosciuto già presenti) tutte le righe: un import con
    // righe rifiutate o non abbinate non conta, e si offre di riprovare (con il rapporto dell'import per capire perché).
    let done = null;
    for (const j of jobs.filter((x) => x.status === 'DONE').slice(0, 5)) {
      const d = (await api.request('ingestion', 'GET', `/v1/imports/${encodeURIComponent(j.id)}`)).body?.job ?? j;
      const c = d.counts ?? {};
      if (out.rows > 0 && d.rowsTotal === out.rows && (c.accepted ?? 0) + (c.duplicate ?? 0) === d.rowsTotal) { done = d; break; }
      out.incompleteJobIds.push(j.id);
    }
    const sourceThere = plan.some((i) => i.entity === 'sources' && i.key === STORY_SOURCE_CODE && i.action === 'present');
    if (out.waitingFor.length) out.reasons.push(`in attesa delle campagne attive (${out.waitingFor.join(', ')})`);
    if (!sourceThere) out.reasons.push(`in attesa della fonte ${STORY_SOURCE_CODE}`);
    if (out.resolved.size === 0) out.reasons.push('nessun membro di test registrato: accedi al portale con Anna, Marco o Giulia');
    out.state = active ? 'running' : out.reasons.length ? 'waiting' : done ? 'present' : 'ready';
    return out;
  } catch (e) {
    return { ...out, state: 'error', reasons: [`lettura fallita: ${describeError(e)}`] };
  }
}

/** Dopo un import incompleto la stessa chiave restituirebbe lo stesso lavoro: la si cambia, gli eventi già accettati risultano duplicati. */
function retryKey(sha, stories) {
  const n = stories.incompleteJobIds?.length ?? 0;
  return n ? `${sha}-r${n}` : sha;
}

/**
 * Carica le storie: UN import dalla fonte di test e attesa del lavoro (`onProgress({done, total})` su righe elaborate).
 * L'`Idempotency-Key` è lo sha256 del file: lo stesso file non crea un secondo lavoro. Una riga rifiutata o non abbinata
 * è un errore dichiarato, non un successo parziale silenzioso.
 */
export async function applyStories({ seed, api, stories, now, onProgress, sleep = (ms) => new Promise((r) => setTimeout(r, ms)), pollMs = 1000, timeoutMs = 180000 }) {
  if (stories.state !== 'ready' && stories.state !== 'running') throw new Error(`le storie non sono caricabili ora (${stories.state})`);
  api.storySubjects = new Set(stories.resolved.values());
  const file = await buildStoryFile(seed.vetrinaTest, stories.resolved, now);
  if (!file.rows) throw new Error('nessuna riga da caricare');
  const job = stories.jobId ? { id: stories.jobId } : (await api.request('ingestion', 'POST', '/v1/imports', {
    multipart: { file: { name: file.name, type: file.type, text: file.text }, fields: { kind: 'EVENTS', source: STORY_SOURCE_CODE } },
    headers: { 'Idempotency-Key': retryKey(file.sha256, stories) },
  })).body;
  if (typeof job?.id !== 'string') throw new Error('risposta dell\'import non riconosciuta');
  const jobId = job.id;
  const created = typeof job.createdAt === 'string' ? Date.parse(job.createdAt) : NaN;
  let detail = null;
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    detail = (await api.request('ingestion', 'GET', `/v1/imports/${encodeURIComponent(jobId)}`)).body?.job ?? null;
    if (detail) onProgress?.({ done: detail.rowsDone ?? 0, total: detail.rowsTotal ?? file.rows, status: detail.status });
    if (detail && (detail.status === 'DONE' || detail.status === 'FAILED')) break;
    if (Date.now() > deadline) throw new Error('l\'import non è finito entro il tempo massimo: riprova più tardi, non si duplica nulla');
    await sleep(pollMs);
  }
  const counts = detail.counts ?? {};
  const bad = (counts.rejected ?? 0) + (counts.invalid ?? 0) + (counts.unmatched ?? 0);
  const problems = [];
  if (detail.status === 'FAILED') problems.push(detail.errorDetail ? String(detail.errorDetail).slice(0, 200) : 'import fallito');
  else if (bad) problems.push(`${bad} righe non accettate (rifiutate, non valide o con membro non abbinato): apri l'import per il rapporto`);
  return {
    jobId,
    status: detail.status,
    rows: file.rows,
    counts,
    problems,
    /** Un lavoro già esistente (stessa chiave di idempotenza) ha la sua voce di audit di prima: non la si cerca nella finestra di questa esecuzione. */
    reused: Number.isFinite(created) && created < now - 120000,
  };
}

/**
 * Anteprima per la UI: righe del piano per gruppo (da creare, già presenti, saltati), cosa resta fuori col motivo e
 * quante scritture partirebbero. Niente identificativi di membri né dati personali.
 */
export function summarizeProgram({ plan, stories, actor }) {
  const groups = PLAN_GROUPS.map((g) => {
    const items = plan.filter((i) => g.entities.includes(i.entity));
    const count = (a) => items.filter((i) => i.action === a).length;
    return { id: g.id, label: g.label, create: count('create'), present: count('present'), skipped: count('skip'), errors: count('error') };
  });
  const reasons = new Map();
  for (const i of plan.filter((x) => x.action === 'skip')) reasons.set(i.reason, [...(reasons.get(i.reason) ?? []), `${i.entity}:${i.key}`]);
  const excluded = [
    ...[...reasons].map(([why, keys]) => ({ what: `${keys.length} ${keys.length === 1 ? 'voce' : 'voci'} (${keys.slice(0, 3).map((k) => k.split(':')[1]).join(', ')}${keys.length > 3 ? ', …' : ''})`, why })),
    ...NOT_INCLUDED_PROGRAM.map(([what, why]) => ({ what, why })),
  ];
  return {
    actor,
    groups,
    create: groups.reduce((n, g) => n + g.create, 0),
    present: groups.reduce((n, g) => n + g.present, 0),
    errors: groups.reduce((n, g) => n + g.errors, 0),
    rewardsCreate: plan.filter((i) => i.entity === 'rewards' && i.action === 'create').length,
    campaignsCreate: plan.filter((i) => i.entity === 'campaigns' && i.action === 'create').length,
    excluded,
    stories: {
      state: stories.state,
      reasons: stories.reasons,
      waitingFor: stories.waitingFor,
      rows: stories.rows,
      totalRows: stories.totalRows,
      jobId: stories.jobId,
      incompleteJobIds: stories.incompleteJobIds ?? [],
      members: stories.members,
    },
  };
}

/** Piano completo del programma: configurazione (con campagne e fonte di test) più stato delle storie. */
export async function planProgram({ seed, api, now, offline = false }) {
  const { plan, ctx } = await buildPlan({ seed, api, offline, now, options: { campaigns: true, vetrinaSource: true } });
  const stories = await planStories({ seed, api, plan, offline });
  return { plan, ctx, stories };
}
