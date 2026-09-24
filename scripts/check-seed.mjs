#!/usr/bin/env node
// check-seed.mjs — coerenza dei dati demo (docs/10 §11). Scheletro M0.7: valida JSON, espressioni di
// data (§1 grammatica) e stringhe vietate. Le regole di coerenza incrociata (saldi = lotti, riferimenti
// esistenti, ecc.) e la validazione contro seed/_schemas/ si aggiungono quando i seed nascono (M1+).
import { readFileSync, readdirSync, existsSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join, resolve } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
const seedDir = resolve(here, "..", "seed");

// Grammatica delle date relative (docs/10 §1.2).
const DATE_EXPR = /^@(?:now|today|som|eom|soy|eoy|last[A-Za-z]+)(?:[+-]\d+[dhMy])*(?:T\d{2}:\d{2})?$/;
// Domini ammessi nei seed (niente aziende reali, domini diversi da example.org — §11.9).
// json-schema.org è l'URI della meta-schema JSON Schema 2020-12 (docs/05 §9, docs/06 §1): standard, non un'azienda.
const ALLOWED_HOSTS = [/(^|\.)example\.org$/, /^localhost$/, /^127\.0\.0\.1$/, /(^|\.)json-schema\.org$/];

const errors = [];
const warnings = [];

function walk(value, path, onString) {
  if (typeof value === "string") onString(value, path);
  else if (Array.isArray(value)) value.forEach((v, i) => walk(v, `${path}[${i}]`, onString));
  else if (value && typeof value === "object")
    for (const [k, v] of Object.entries(value)) walk(v, path ? `${path}.${k}` : k, onString);
}

function checkString(file, value, path) {
  if (value.startsWith("@") && !DATE_EXPR.test(value)) {
    errors.push(`${file}: espressione data non valida in ${path}: "${value}"`);
  }
  const urls = value.match(/https?:\/\/[^\s"']+/g) ?? [];
  for (const url of urls) {
    try {
      const host = new URL(url).hostname;
      if (!ALLOWED_HOSTS.some((re) => re.test(host))) {
        warnings.push(`${file}: dominio non in allowlist in ${path}: ${host}`);
      }
    } catch {
      /* URL non parsabile: ignora */
    }
  }
}

if (!existsSync(seedDir)) {
  console.log("check-seed: cartella seed/ assente, niente da controllare.");
  process.exit(0);
}

const files = readdirSync(seedDir).filter((f) => f.endsWith(".json"));
if (files.length === 0) {
  console.log("check-seed: nessun file seed ancora (arrivano con M1). OK.");
  process.exit(0);
}

for (const file of files) {
  const full = join(seedDir, file);
  let data;
  try {
    data = JSON.parse(readFileSync(full, "utf8"));
  } catch (e) {
    errors.push(`${file}: JSON non valido — ${e.message}`);
    continue;
  }
  walk(data, "", (v, p) => checkString(file, v, p));
  const schema = join(seedDir, "_schemas", `${file.replace(/\.json$/, "")}.schema.json`);
  if (existsSync(schema)) {
    try {
      JSON.parse(readFileSync(schema, "utf8"));
    } catch (e) {
      errors.push(`_schemas/${file}: schema non valido — ${e.message}`);
    }
    // TODO(M1): validazione completa dei seed contro lo schema (ajv).
  }
}

// Coerenza del catalogo premi (docs/10 §8 regole 5): fascia e categoria esistenti; premio con evasione automatica
// ⇒ pool esistente con codici disponibili (generati − consumati) ≥ stock residuo dichiarato.
function readSeed(file) {
  try {
    return JSON.parse(readFileSync(join(seedDir, file), "utf8"));
  } catch {
    return null;
  }
}
const rewards = readSeed("rewards.json");
if (Array.isArray(rewards)) {
  const bands = new Set((readSeed("reward-bands.json") ?? []).map((b) => b.code));
  const categories = new Set((readSeed("reward-categories.json") ?? []).map((c) => c.code));
  const pools = new Map((readSeed("coupon-pools.json") ?? []).map((p) => [p.code, p]));
  for (const r of rewards) {
    if (!bands.has(r.band)) errors.push(`rewards.json: ${r.code} usa la fascia inesistente "${r.band}"`);
    if (r.category && !categories.has(r.category)) errors.push(`rewards.json: ${r.code} usa la categoria inesistente "${r.category}"`);
    if (r.fulfilment === "AUTO_COUPON") {
      const pool = pools.get(r.couponPool);
      if (!pool) {
        errors.push(`rewards.json: ${r.code} è AUTO_COUPON ma il pool "${r.couponPool}" non esiste in coupon-pools.json`);
        continue;
      }
      // Le richieste d'esempio evase con coupon consumano codici dello stesso pool.
      // I premi coupon dei concorsi non ancora chiusi (docs/10 §6) pescano dallo stesso pool.
      const issuedBySeed = (readSeed("redemptions.json") ?? []).filter((x) => x.rewardCode === r.code && x.coupon).length;
      const available = (pool.size ?? 0) - (pool.consumed ?? 0) - issuedBySeed;
      const contestDemand = (readSeed("contests.json") ?? [])
        .filter((c) => c.status !== "ENDED" && c.status !== "ARCHIVED")
        .flatMap((c) => c.prizes ?? [])
        .filter((p) => p.type === "COUPON" && p.rewardCode === r.code)
        .reduce((sum, p) => sum + (p.quantity ?? 0), 0);
      const declared = (r.stockRemaining ?? r.stockTotal ?? 0) + contestDemand;
      if (available < declared) {
        errors.push(`coupon-pools.json: ${pool.code} ha ${available} codici disponibili, meno dello stock di ${r.code} più i premi dei concorsi (${declared})`);
      }
    }
  }
}

// Concorsi (docs/10 §6, docs/servizi/gamification-service.md §2): meccanica e distribuzione ammesse, periodo, premi
// con codice univoco, quantità ≥ 1, punti per POINTS e premio coupon esistente (AUTO_COUPON) per COUPON.
const contests = readSeed("contests.json");
if (Array.isArray(contests)) {
  const rewardByCode = new Map((rewards ?? []).map((r) => [r.code, r]));
  const seen = new Set();
  for (const c of contests) {
    if (seen.has(c.code)) errors.push(`contests.json: codice duplicato ${c.code}`);
    seen.add(c.code);
    if (!["WHEEL", "SCRATCH", "BOX"].includes(c.mechanic)) errors.push(`contests.json: ${c.code} ha meccanica "${c.mechanic}"`);
    if (!["UNIFORM", "BUSINESS_HOURS"].includes(c.distribution)) errors.push(`contests.json: ${c.code} ha distribuzione "${c.distribution}"`);
    if (!c.startAt || !c.endAt) errors.push(`contests.json: ${c.code} senza periodo`);
    if (typeof c.seed !== "number") errors.push(`contests.json: ${c.code} senza seme fisso`);
    const prizeCodes = new Set();
    for (const p of c.prizes ?? []) {
      if (prizeCodes.has(p.code)) errors.push(`contests.json: ${c.code} ripete il premio ${p.code}`);
      prizeCodes.add(p.code);
      if (!(p.quantity >= 1)) errors.push(`contests.json: ${c.code}/${p.code} con quantità ${p.quantity}`);
      if (p.type === "POINTS" && !(p.points > 0)) errors.push(`contests.json: ${c.code}/${p.code} senza punti`);
      if (p.type === "COUPON") {
        const r = rewardByCode.get(p.rewardCode);
        if (!r) errors.push(`contests.json: ${c.code}/${p.code} usa il premio inesistente ${p.rewardCode}`);
        else if (r.fulfilment !== "AUTO_COUPON") errors.push(`contests.json: ${c.code}/${p.code}: ${p.rewardCode} non è AUTO_COUPON`);
      }
      if (!["POINTS", "COUPON", "PHYSICAL"].includes(p.type)) errors.push(`contests.json: ${c.code}/${p.code} ha tipo "${p.type}"`);
    }
    if ((c.prizes ?? []).length === 0) errors.push(`contests.json: ${c.code} senza montepremi`);
  }
}

// Storico di gioco (docs/10 §6): membri e concorsi esistenti, vincite non oltre il montepremi dei concorsi chiusi.
const gHistory = readSeed("gamification-history.json");
if (gHistory && Array.isArray(contests)) {
  const memberIds = new Set((readSeed("members.json") ?? []).map((m) => m.id));
  const contestByCode = new Map(contests.map((c) => [c.code, c]));
  const refs = [...(gHistory.grants ?? []), ...(gHistory.plays ?? [])];
  for (const r of refs) {
    if (!memberIds.has(r.memberId)) errors.push(`gamification-history.json: membro inesistente ${r.memberId}`);
    if (!contestByCode.has(r.contestCode)) errors.push(`gamification-history.json: concorso inesistente ${r.contestCode}`);
  }
  for (const p of gHistory.plays ?? []) {
    const c = contestByCode.get(p.contestCode);
    if (p.outcome === "WIN" && c && !(c.prizes ?? []).some((x) => x.code === p.prizeCode)) {
      errors.push(`gamification-history.json: premio ${p.prizeCode} assente in ${p.contestCode}`);
    }
  }
  for (const cc of gHistory.closedContests ?? []) {
    const c = contestByCode.get(cc.contestCode);
    if (!c) {
      errors.push(`gamification-history.json: concorso inesistente ${cc.contestCode}`);
      continue;
    }
    if (c.status !== "ENDED" && c.status !== "ARCHIVED") errors.push(`gamification-history.json: ${c.code} non è chiuso`);
    const units = (c.prizes ?? []).reduce((s, p) => s + (p.quantity ?? 0), 0);
    const wins = (cc.winners ?? []).reduce((s, w) => s + (w.wins ?? 0), 0);
    if (wins !== cc.claimed) errors.push(`gamification-history.json: ${c.code} somma vincite ${wins} ≠ claimed ${cc.claimed}`);
    if (cc.claimed > units) errors.push(`gamification-history.json: ${c.code} ${cc.claimed} vincite oltre il montepremi (${units})`);
    for (const w of cc.winners ?? []) {
      if (!memberIds.has(w.memberId)) errors.push(`gamification-history.json: vincitore inesistente ${w.memberId}`);
    }
  }
}

// Obiettivi e badge (docs/10 §6): badge collegati esistenti, metriche coerenti, progressi di membri e obiettivi
// esistenti e sotto il traguardo se non completati.
const achievementsSeed = readSeed("achievements.json");
if (Array.isArray(achievementsSeed)) {
  const badgeCodes = new Set((readSeed("badges.json") ?? []).map((b) => b.code));
  const byCode = new Map(achievementsSeed.map((a) => [a.code, a]));
  for (const a of achievementsSeed) {
    if (a.badgeCode && !badgeCodes.has(a.badgeCode)) errors.push(`achievements.json: ${a.code} usa il badge inesistente ${a.badgeCode}`);
    if (!["COUNT", "SUM", "DISTINCT_TYPES", "STREAK"].includes(a.metric)) errors.push(`achievements.json: ${a.code} metrica "${a.metric}"`);
    if (!["EVER", "MONTH", "EDITION"].includes(a.period)) errors.push(`achievements.json: ${a.code} periodo "${a.period}"`);
    if (a.metric === "SUM" && !a.sumField) errors.push(`achievements.json: ${a.code} SUM senza sumField`);
    if (a.metric === "STREAK" && !["DAY", "WEEK"].includes(a.streakUnit)) errors.push(`achievements.json: ${a.code} STREAK senza unità`);
    if (!(a.target >= 1)) errors.push(`achievements.json: ${a.code} traguardo ${a.target}`);
  }
  const memberIds = new Set((readSeed("members.json") ?? []).map((m) => m.id));
  for (const p of readSeed("gamification-history.json")?.achievementProgress ?? []) {
    const a = byCode.get(p.achievementCode);
    if (!a) errors.push(`gamification-history.json: obiettivo inesistente ${p.achievementCode}`);
    if (!memberIds.has(p.memberId)) errors.push(`gamification-history.json: membro inesistente ${p.memberId}`);
    if (a && !p.completedAt && p.value >= a.target) errors.push(`gamification-history.json: ${p.memberId}/${a.code} al traguardo ma non completato`);
    if (a && p.completedAt && p.value < a.target) errors.push(`gamification-history.json: ${p.memberId}/${a.code} completato sotto il traguardo`);
  }
}

// Classifiche (docs/10 §6): metrica e periodo ammessi, punteggi di membri e classifiche esistenti.
const leaderboardsSeed = readSeed("leaderboards.json");
if (Array.isArray(leaderboardsSeed)) {
  const codes = new Set(leaderboardsSeed.map((l) => l.code));
  const memberIds = new Set((readSeed("members.json") ?? []).map((m) => m.id));
  for (const l of leaderboardsSeed) {
    if (!["PTS_EARNED", "STS_EARNED", "ACTION_COUNT"].includes(l.metric)) errors.push(`leaderboards.json: ${l.code} metrica "${l.metric}"`);
    if (!["MONTH", "EDITION", "ALL_TIME"].includes(l.period)) errors.push(`leaderboards.json: ${l.code} periodo "${l.period}"`);
    if (l.metric === "ACTION_COUNT" && !(l.actionTypes ?? []).length) errors.push(`leaderboards.json: ${l.code} senza tipi di azione`);
  }
  for (const s of readSeed("gamification-history.json")?.leaderboardScores ?? []) {
    if (!codes.has(s.leaderboardCode)) errors.push(`gamification-history.json: classifica inesistente ${s.leaderboardCode}`);
    if (!memberIds.has(s.memberId)) errors.push(`gamification-history.json: membro inesistente ${s.memberId}`);
    if (!(s.score > 0)) errors.push(`gamification-history.json: punteggio non positivo per ${s.memberId}`);
  }
}

// Richieste d'esempio (docs/10 §5): membro e premio esistenti, costo = soglia della fascia, niente PENDING (il
// timeout le respingerebbe dopo 10 minuti), coupon solo per premi a evasione automatica.
const redemptions = readSeed("redemptions.json");
if (Array.isArray(redemptions) && Array.isArray(rewards)) {
  const members = new Set((readSeed("members.json") ?? []).map((m) => m.id));
  const thresholds = new Map((readSeed("reward-bands.json") ?? []).map((b) => [b.code, b.pointsThreshold]));
  const byCode = new Map(rewards.map((r) => [r.code, r]));
  const ids = new Set();
  for (const x of redemptions) {
    if (ids.has(x.id)) errors.push(`redemptions.json: id duplicato ${x.id}`);
    ids.add(x.id);
    const r = byCode.get(x.rewardCode);
    if (!members.has(x.memberId)) errors.push(`redemptions.json: ${x.id} usa il membro inesistente ${x.memberId}`);
    if (!r) {
      errors.push(`redemptions.json: ${x.id} usa il premio inesistente ${x.rewardCode}`);
      continue;
    }
    if (thresholds.get(r.band) !== x.pointsCost) errors.push(`redemptions.json: ${x.id} costa ${x.pointsCost}, la fascia ${r.band} ${thresholds.get(r.band)}`);
    if (x.status === "PENDING") errors.push(`redemptions.json: ${x.id} è PENDING (verrebbe respinta dal timeout)`);
    if (x.coupon && r.fulfilment !== "AUTO_COUPON") errors.push(`redemptions.json: ${x.id} ha un coupon ma ${r.code} non è AUTO_COUPON`);
  }
  // Le richieste attive dello storico hanno già preso stock: residuo ≤ totale − attive (un annullo lo restituisce).
  for (const r of rewards) {
    if (r.stockTotal == null) continue;
    const taken = redemptions.filter((x) => x.rewardCode === r.code && (x.status === "CONFIRMED" || x.status === "FULFILLED")).length;
    if ((r.stockRemaining ?? r.stockTotal) > r.stockTotal - taken) {
      errors.push(`rewards.json: ${r.code} ha residuo ${r.stockRemaining ?? r.stockTotal} ma lo storico ne ha già ${taken} su ${r.stockTotal}`);
    }
  }
}

// Messaggi (docs/10 §7, docs/servizi/engagement-service.md §2, §5–§6): template con canale/categoria ammessi e
// segnaposto su data/member/event con i soli formattatori number/date; regole su template esistenti, mai su
// message.delivered, condizioni solo su data.*; inbox di membri esistenti e non anonimizzati, 3–8 messaggi per membro
// attivo, ogni segnaposto data.* valorizzato, riferimenti a richieste/campagne esistenti e coerenti; non letti della
// storia (Marco 3, Chiara 1 scadenza, Sofia 1 richiesta confermata, gli altri 0). Anche le campagne SEND_MESSAGE
// puntano a template esistenti (§11.4).
const templatesSeed = readSeed("message-templates.json");
if (Array.isArray(templatesSeed)) {
  const PLACEHOLDER = /\{\{\s*([^{}|]*?)\s*(?:\|\s*([^{}|]*?)\s*)?\}\}/g;
  const placeholders = (tpl) => [...String(tpl ?? "").matchAll(PLACEHOLDER)].map((m) => ({ path: m[1], fmt: m[2] }));
  const byCode = new Map();
  for (const t of templatesSeed) {
    if (byCode.has(t.code)) errors.push(`message-templates.json: codice duplicato ${t.code}`);
    byCode.set(t.code, t);
    if (!/^[A-Z][A-Z0-9-]{2,39}$/.test(t.code ?? "")) errors.push(`message-templates.json: codice non valido "${t.code}"`);
    if (!["INAPP", "EMAIL_FAKE"].includes(t.channel)) errors.push(`message-templates.json: ${t.code} canale "${t.channel}"`);
    if (!["POINTS", "TIER", "REWARD", "GAME", "PROGRAM"].includes(t.category)) errors.push(`message-templates.json: ${t.code} categoria "${t.category}"`);
    if (!t.name || !t.titleTpl || !t.bodyTpl) errors.push(`message-templates.json: ${t.code} senza nome, titolo o testo`);
    for (const p of [...placeholders(t.titleTpl), ...placeholders(t.bodyTpl)]) {
      if (!/^(data|member|event)\.[A-Za-z0-9_.]+$/.test(p.path)) errors.push(`message-templates.json: ${t.code} segnaposto {{${p.path}}} fuori da data/member/event`);
      if (p.fmt && !["number", "date"].includes(p.fmt)) errors.push(`message-templates.json: ${t.code} formattatore |${p.fmt}`);
    }
  }
  for (const c of readSeed("campaigns.json") ?? []) {
    for (const e of c.effects ?? []) {
      if (e.type === "SEND_MESSAGE" && !byCode.has(e.templateCode)) errors.push(`campaigns.json: ${c.code} usa il template inesistente ${e.templateCode}`);
    }
  }
  const ruleCodes = new Set();
  for (const r of readSeed("notification-rules.json") ?? []) {
    if (ruleCodes.has(r.code)) errors.push(`notification-rules.json: codice duplicato ${r.code}`);
    ruleCodes.add(r.code);
    if (!byCode.has(r.templateCode)) errors.push(`notification-rules.json: ${r.code} usa il template inesistente ${r.templateCode}`);
    if (!/^[a-z]+(\.[a-z]+)+$/.test(r.factType ?? "")) errors.push(`notification-rules.json: ${r.code} tipo di fatto "${r.factType}"`);
    if (r.factType === "message.delivered") errors.push(`notification-rules.json: ${r.code} su message.delivered (ciclo)`);
    const leaves = [];
    const walkCond = (n) => (n?.rules ? n.rules.forEach(walkCond) : n?.field && leaves.push(n.field));
    walkCond(r.condition);
    for (const f of leaves) if (!f.startsWith("data.")) errors.push(`notification-rules.json: ${r.code} condizione su "${f}" (solo data.*)`);
  }
  const inboxSeed = readSeed("inbox.json");
  if (Array.isArray(inboxSeed)) {
    const members = new Map((readSeed("members.json") ?? []).map((m) => [m.id, m]));
    const redemptionById = new Map((readSeed("redemptions.json") ?? []).map((x) => [x.id, x]));
    const campaignCodes = new Set((readSeed("campaigns.json") ?? []).map((c) => c.code));
    const ids = new Set();
    const perMember = new Map();
    for (const x of inboxSeed) {
      if (ids.has(x.id)) errors.push(`inbox.json: id duplicato ${x.id}`);
      ids.add(x.id);
      const m = members.get(x.memberId);
      if (!m) errors.push(`inbox.json: ${x.id} usa il membro inesistente ${x.memberId}`);
      else if (m.status === "ANONYMIZED") errors.push(`inbox.json: ${x.id} per un membro anonimizzato`);
      const t = byCode.get(x.templateCode);
      if (!t) {
        errors.push(`inbox.json: ${x.id} usa il template inesistente ${x.templateCode}`);
        continue;
      }
      if (!x.at || typeof x.read !== "boolean") errors.push(`inbox.json: ${x.id} senza data o stato di lettura`);
      for (const p of [...placeholders(t.titleTpl), ...placeholders(t.bodyTpl)]) {
        if (p.path.startsWith("data.") && x.data?.[p.path.slice(5)] === undefined) errors.push(`inbox.json: ${x.id} non valorizza {{${p.path}}}`);
      }
      const rdm = x.data?.redemptionId && redemptionById.get(x.data.redemptionId);
      if (x.data?.redemptionId && !rdm) errors.push(`inbox.json: ${x.id} cita la richiesta inesistente ${x.data.redemptionId}`);
      if (rdm && rdm.memberId !== x.memberId) errors.push(`inbox.json: ${x.id} cita ${rdm.id} di un altro membro`);
      if (rdm && x.data.rewardCode && rdm.rewardCode !== x.data.rewardCode) errors.push(`inbox.json: ${x.id} premio diverso da ${rdm.id}`);
      if (x.data?.campaignCode && !campaignCodes.has(x.data.campaignCode)) errors.push(`inbox.json: ${x.id} cita la campagna inesistente ${x.data.campaignCode}`);
      const agg = perMember.get(x.memberId) ?? { total: 0, unread: [] };
      agg.total++;
      if (!x.read) agg.unread.push(x.templateCode);
      perMember.set(x.memberId, agg);
    }
    for (const m of members.values()) {
      if (m.status !== "ACTIVE") continue;
      const n = perMember.get(m.id)?.total ?? 0;
      if (n < 3 || n > 8) errors.push(`inbox.json: ${m.id} ha ${n} messaggi (attesi 3–8)`);
    }
    // SPEC-GAP: Q-64 — la "richiesta confermata" di Sofia usa MSG-REWARD-CONFIRMED (fuori dagli 11 template + MSG-BIRTHDAY).
    const expectedUnread = { "MBR-000002": null, "MBR-000007": ["MSG-POINTS-EXPIRING"], "MBR-000011": ["MSG-REWARD-CONFIRMED"] };
    for (const [id, agg] of perMember) {
      const want = id in expectedUnread ? expectedUnread[id] : [];
      if (want === null ? agg.unread.length !== 3 : JSON.stringify(agg.unread) !== JSON.stringify(want)) {
        errors.push(`inbox.json: ${id} ha non letti ${JSON.stringify(agg.unread)} (docs/10 §7)`);
      }
    }
  }
}

for (const w of warnings) console.warn(`⚠ ${w}`);
if (errors.length > 0) {
  for (const e of errors) console.error(`✗ ${e}`);
  console.error(`check-seed: ${errors.length} errore/i.`);
  process.exit(1);
}
console.log(`check-seed: ${files.length} file OK${warnings.length ? `, ${warnings.length} avviso/i` : ""}.`);
