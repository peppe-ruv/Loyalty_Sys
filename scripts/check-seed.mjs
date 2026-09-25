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

// Contenuti (docs/10 §7, §11.2, §11.7): riferimenti esistenti, card WIN ↔ premi in palio, destinazioni sicure.
{
  const contents = readSeed("contents.json") ?? [];
  const contests = readSeed("contests.json") ?? [];
  const campaigns = new Set((readSeed("campaigns.json") ?? []).map((c) => c.code));
  const rewards = new Set((readSeed("rewards.json") ?? []).map((r) => r.code));
  const contestCodes = new Set(contests.map((c) => c.code));
  const prizeCodes = new Set(contests.flatMap((c) => (c.prizes ?? []).map((p) => p.code)));
  const KINDS = ["CARD", "POPUP", "BANNER"];
  const PLACEMENTS = ["HOME_HERO", "HOME_GRID", "CATALOG_TOP", "CONTEST", "WIN"];
  const seen = new Set();
  for (const c of contents) {
    const where = `contents.json: ${c.code}`;
    if (seen.has(c.code)) errors.push(`${where} codice duplicato`);
    seen.add(c.code);
    if (!KINDS.includes(c.kind)) errors.push(`${where} tipo non valido ${c.kind}`);
    if (c.kind === "POPUP" ? c.placement != null : !PLACEMENTS.includes(c.placement)) errors.push(`${where} posizionamento non valido`);
    if (c.kind === "POPUP" && !["ONCE", "ONCE_PER_DAY", "ALWAYS"].includes(c.frequency)) errors.push(`${where} pop-up senza frequenza valida`);
    if (c.linkType === "CONTEST" && !contestCodes.has(c.linkCode)) errors.push(`${where} concorso inesistente ${c.linkCode}`);
    if (c.linkType === "CAMPAIGN" && !campaigns.has(c.linkCode)) errors.push(`${where} campagna inesistente ${c.linkCode}`);
    if (c.linkType === "REWARD" && !rewards.has(c.linkCode)) errors.push(`${where} premio inesistente ${c.linkCode}`);
    if (c.placement === "WIN" && (c.linkType !== "PRIZE" || !prizeCodes.has(c.linkCode))) errors.push(`${where} card WIN senza premio in palio esistente`);
    if (c.ctaTarget && !c.ctaTarget.startsWith("/portal") && !c.ctaTarget.startsWith("https://")) errors.push(`${where} destinazione non sicura ${c.ctaTarget}`);
    // Estensioni del pubblico dei pop-up (SPEC-GAP Q-71): iscrizione recente e giorni della settimana.
    const aud = c.audience ?? {};
    if (aud.registeredWithinDays != null && !(Number.isInteger(aud.registeredWithinDays) && aud.registeredWithinDays > 0)) {
      errors.push(`${where} registeredWithinDays deve essere un intero positivo`);
    }
    for (const d of aud.daysOfWeek ?? []) {
      if (!["MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"].includes(d)) errors.push(`${where} giorno non valido ${d}`);
    }
  }
  // Ogni premio in palio dei concorsi LIVE ha la sua card WIN (§11.7).
  const winFor = new Set(contents.filter((c) => c.placement === "WIN").map((c) => c.linkCode));
  for (const contest of contests.filter((c) => c.status === "LIVE")) {
    for (const p of contest.prizes ?? []) {
      if (!winFor.has(p.code)) errors.push(`contents.json: manca la card WIN del premio ${p.code} di ${contest.code}`);
    }
  }
}

// Tema (docs/servizi/engagement-service.md §3, F-THM-01): 5 colori esadecimali e contrasto AA come al salvataggio (Q-79).
{
  const theme = readSeed("theme.json");
  if (theme) {
    const HEX = /^#[0-9A-Fa-f]{6}$/;
    const colors = theme.colors ?? {};
    for (const k of ["primary", "secondary", "coin", "night", "bg"]) {
      if (!HEX.test(colors[k] ?? "")) errors.push(`theme.json: colore ${k} non esadecimale`);
    }
    const lum = (hex) => {
      const [r, g, b] = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255)
        .map((c) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4));
      return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    };
    const contrast = (a, b) => { const [x, y] = [lum(a), lum(b)].sort((p, q) => q - p); return (x + 0.05) / (y + 0.05); };
    for (const k of ["primary", "bg"]) {
      if (HEX.test(colors.night ?? "") && HEX.test(colors[k] ?? "") && contrast(colors.night, colors[k]) < 4.5) {
        errors.push(`theme.json: contrasto night/${k} sotto 4,5:1`);
      }
    }
    if (!theme.programName) errors.push("theme.json: programName mancante");
  }
}

// Segmenti e storico attività (docs/10 §3, docs/servizi/member-service.md §2, §6; M6.6): codici univoci nel formato
// docs/06 §2, tipo ammesso, criteri solo sullo spazio member.* esteso (docs/03 §10), statici con membri esistenti; ogni
// segmento citato da campagne, premi e contenuti esiste (§11.2); i membri attesi (`expectedMembers`, la colonna "Membri
// attesi" di docs/10 §3) coincidono con la valutazione dei criteri sui seed. Lo storico ha membri e tipi esistenti e
// rispetta le storie (Marco senza bolletta digitale né domiciliazione: servono a SCN-DIGITAL; Elisa senza acquisti).
// SPEC-GAP: Q-83 — docs/10 §3 attende 4 membri in SEG-TORINO, ma members.json ha un solo membro di Torino (Davide):
// le città dei seed non sono state cambiate, expectedMembers = [MBR-000004].
// SPEC-GAP: Q-84 — docs/10 §3 dice SEG-VIP-EVENT "usato da RWD-PLATINUM-EVENT", ma rewards.json non lo cita (il premio
// è visibile per tier PLATINUM): il premio non è stato toccato, il segmento risulta "usato da nessuno".
{
  const segments = readSeed("segments.json") ?? [];
  const members = readSeed("members.json") ?? [];
  const history = readSeed("activity-history.json") ?? { memberActivity: [] };
  const actionTypes = new Set((readSeed("event-types.json") ?? []).map((t) => t.code));
  const byId = new Map(members.map((m) => [m.id, m]));
  const CODE = /^[A-Z][A-Z0-9-]{2,39}$/;
  const SIMPLE = ["tier", "status", "labels", "city", "age", "registeredDaysAgo", "balance.PTS", "lifetimeEarned.PTS", "lastActivityDaysAgo", "purchases.amount90d"];
  const CMPS = ["eq", "neq", "gt", "gte", "lt", "lte", "in", "nin", "contains", "ncontains", "exists", "nexists", "between", "startsWith"];
  const strip = (f) => (f.startsWith("member.") ? f.slice(7) : f);
  const knownField = (f) => {
    const s = strip(f);
    return SIMPLE.includes(s) || /^attributes\..+/.test(s) || /^actions\..+\.(count30d|total)$/.test(s);
  };
  // Giorni fa di un'espressione @today±Nd…: basta la parte in giorni/mesi/anni (approssimati) per i confronti.
  const daysAgo = (expr) => {
    if (typeof expr !== "string" || !expr.startsWith("@")) return null;
    let d = 0;
    for (const [, sign, n, unit] of expr.matchAll(/([+-])(\d+)([dhMy])/g)) {
      const k = unit === "d" ? 1 : unit === "M" ? 30 : unit === "y" ? 365 : 0;
      d += (sign === "-" ? 1 : -1) * Number(n) * k;
    }
    return d;
  };
  const lastActivity = new Map();
  for (const entry of history.memberActivity ?? []) {
    const m = byId.get(entry.memberId);
    if (!m) errors.push(`activity-history.json: membro inesistente ${entry.memberId}`);
    else if (m.status === "ANONYMIZED") errors.push(`activity-history.json: ${entry.memberId} è anonimizzato`);
    for (const a of entry.actions ?? []) {
      if (!actionTypes.has(a.type)) errors.push(`activity-history.json: ${entry.memberId} usa il tipo inesistente ${a.type}`);
      const ago = daysAgo(a.at);
      if (ago === null) errors.push(`activity-history.json: ${entry.memberId} ha una data non relativa "${a.at}"`);
      else if (!lastActivity.has(entry.memberId) || ago < lastActivity.get(entry.memberId)) lastActivity.set(entry.memberId, ago);
      if (a.type === "purchase.completed" && !(a.amount > 0)) errors.push(`activity-history.json: acquisto di ${entry.memberId} senza importo`);
      if (entry.memberId === "MBR-000002" && ["ebill.activated", "directdebit.activated"].includes(a.type)) {
        errors.push("activity-history.json: Marco non deve avere ancora bolletta digitale né domiciliazione (SCN-DIGITAL)");
      }
      if (entry.memberId === "MBR-000009" && a.type === "purchase.completed") {
        errors.push("activity-history.json: Elisa non ha ancora comprato (SCN-REFERRAL)");
      }
    }
  }
  const resolve = (m, field) => {
    const s = strip(field);
    if (s === "tier") return m.tier;
    if (s === "status") return m.status;
    if (s === "labels") return m.labels ?? [];
    if (s === "city") return m.city ?? undefined;
    if (s === "balance.PTS") return m.points ?? 0;
    if (s === "registeredDaysAgo") return daysAgo(m.registeredAt) ?? undefined;
    if (s === "lastActivityDaysAgo") return lastActivity.has(m.id) ? lastActivity.get(m.id) : undefined;
    return Symbol.for("unsupported");
  };
  const leaf = (m, r) => {
    const v = resolve(m, r.field);
    if (v === Symbol.for("unsupported")) throw new Error(`campo ${r.field} non verificabile da check-seed`);
    if (r.cmp === "exists") return v !== undefined && !(Array.isArray(v) && v.length === 0);
    if (r.cmp === "nexists") return v === undefined || (Array.isArray(v) && v.length === 0);
    if (v === undefined) return false;
    if (Array.isArray(v)) {
      if (r.cmp === "contains") return v.includes(r.value);
      if (r.cmp === "ncontains") return !v.includes(r.value);
      if (r.cmp === "in") return v.some((x) => r.value.includes(x));
      if (r.cmp === "nin") return !v.some((x) => r.value.includes(x));
      return v.some((x) => scalar(x, r));
    }
    return scalar(v, r);
  };
  const scalar = (v, r) => {
    switch (r.cmp) {
      case "eq": return v === r.value;
      case "neq": return v !== r.value;
      case "gt": return v > r.value;
      case "gte": return v >= r.value;
      case "lt": return v < r.value;
      case "lte": return v <= r.value;
      case "in": return r.value.includes(v);
      case "nin": return !r.value.includes(v);
      case "between": return v >= r.value[0] && v <= r.value[1];
      case "startsWith": return String(v).startsWith(r.value);
      default: return false;
    }
  };
  const evalNode = (m, n) => {
    if (n.op || n.rules) {
      const rules = n.rules ?? [];
      if (n.op === "any") return rules.some((r) => evalNode(m, r));
      if (n.op === "not") return !rules.every((r) => evalNode(m, r));
      return rules.every((r) => evalNode(m, r));
    }
    return leaf(m, n);
  };
  const checkShape = (code, n, path) => {
    if (n.op || n.rules) {
      if (!["all", "any", "not"].includes(n.op)) errors.push(`segments.json: ${code} ${path}.op non valido`);
      if (!Array.isArray(n.rules) || n.rules.length === 0) errors.push(`segments.json: ${code} ${path}.rules vuoto`);
      (n.rules ?? []).forEach((r, i) => checkShape(code, r, `${path}.rules[${i}]`));
      return;
    }
    if (!knownField(n.field ?? "")) errors.push(`segments.json: ${code} ${path}.field fuori dallo spazio member.* (${n.field})`);
    if (!CMPS.includes(n.cmp)) errors.push(`segments.json: ${code} ${path}.cmp non valido (${n.cmp})`);
  };
  const codes = new Set();
  for (const s of segments) {
    const where = `segments.json: ${s.code}`;
    if (!CODE.test(s.code ?? "")) errors.push(`${where} codice non valido`);
    if (codes.has(s.code)) errors.push(`${where} codice duplicato`);
    codes.add(s.code);
    if (!s.name) errors.push(`${where} senza nome`);
    if (!["STATIC", "DYNAMIC"].includes(s.type)) errors.push(`${where} tipo non valido ${s.type}`);
    let actual = null;
    if (s.type === "DYNAMIC") {
      if (!s.criteria) errors.push(`${where} dinamico senza criteri`);
      else {
        checkShape(s.code, s.criteria, "criteria");
        try {
          actual = members.filter((m) => m.status !== "ANONYMIZED" && evalNode(m, s.criteria)).map((m) => m.id);
        } catch (e) {
          warnings.push(`${where}: ${e.message}`);
        }
      }
    } else {
      for (const id of s.memberIds ?? []) {
        const m = byId.get(id);
        if (!m || m.status === "ANONYMIZED") errors.push(`${where} membro inesistente o anonimizzato ${id}`);
      }
      actual = [...new Set(s.memberIds ?? [])];
    }
    if (actual && s.expectedMembers) {
      const want = [...s.expectedMembers].sort().join(",");
      const got = [...actual].sort().join(",");
      if (want !== got) errors.push(`${where} membri attesi [${want}] ma i criteri danno [${got}]`);
    }
  }
  const cited = [
    ...(readSeed("campaigns.json") ?? []).flatMap((c) => (c.audience?.segments ?? []).map((x) => [`campaigns.json: ${c.code}`, x])),
    ...(readSeed("rewards.json") ?? []).flatMap((r) => (r.eligibleSegments ?? []).map((x) => [`rewards.json: ${r.code}`, x])),
    ...(readSeed("contents.json") ?? []).flatMap((c) => (c.audience?.segments ?? []).map((x) => [`contents.json: ${c.code}`, x])),
  ];
  for (const [where, code] of cited) {
    if (!codes.has(code)) errors.push(`${where} cita il segmento inesistente ${code}`);
  }
}

// Attributi personalizzati (docs/10 §3, F-MBR-03): definizioni valide e valori dei membri coerenti col tipo.
{
  const defs = readSeed("attribute-definitions.json") ?? [];
  const byKey = new Map();
  for (const d of defs) {
    const where = `attribute-definitions.json: ${d.key}`;
    if (!/^[a-z][a-zA-Z0-9]{1,39}$/.test(d.key ?? "") || d.key === "story") errors.push(`${where} chiave non valida`);
    if (byKey.has(d.key)) errors.push(`${where} chiave duplicata`);
    if (!["STRING", "NUMBER", "BOOLEAN", "DATE"].includes(d.type)) errors.push(`${where} tipo non valido ${d.type}`);
    if ((d.options ?? []).length > 0 && d.type !== "STRING") errors.push(`${where} opzioni solo per STRING`);
    if (!d.label) errors.push(`${where} etichetta mancante`);
    byKey.set(d.key, d);
  }
  for (const m of readSeed("members.json") ?? []) {
    for (const [k, v] of Object.entries(m.attributes ?? {})) {
      const d = byKey.get(k);
      const where = `members.json: ${m.id} attributo ${k}`;
      if (!d) { errors.push(`${where} non definito`); continue; }
      const ok =
        d.type === "NUMBER" ? typeof v === "number" :
        d.type === "BOOLEAN" ? typeof v === "boolean" :
        d.type === "DATE" ? typeof v === "string" && /^\d{4}-\d{2}-\d{2}$/.test(v) :
        typeof v === "string" && ((d.options ?? []).length === 0 || d.options.includes(v));
      if (!ok) errors.push(`${where} valore non coerente col tipo ${d.type}`);
    }
  }
}

// Webhook (docs/10 §7, docs/servizi/engagement-service.md §2, §5–§6; F-WBH-01, M7.2): uno solo, disabilitato, verso
// https://example.org; tipi di fatto del catalogo dei contratti (contracts/events/fact) e mai message.delivered;
// segreto whsec_… presente. Registro storico (SPEC-GAP: Q-102): solo consegne chiuse (OK/GAVE_UP: nessuna partirebbe
// davvero), dei tipi sottoscritti, per membri esistenti, dentro la pulizia dei 14 giorni, tentativi coerenti coi ritenti.
{
  const hooks = readSeed("webhooks.json");
  if (hooks) {
    const factDir = resolve(here, "..", "contracts", "events", "fact");
    const factTypes = new Set(
      existsSync(factDir) ? readdirSync(factDir).filter((f) => f.endsWith(".schema.json")).map((f) => f.replace(/\.schema\.json$/, "")) : [],
    );
    const memberIds = new Set((readSeed("members.json") ?? []).map((m) => m.id));
    if (!Array.isArray(hooks) || hooks.length !== 1) errors.push("webhooks.json: atteso esattamente un webhook (docs/10 §7)");
    const codes = new Set();
    const eventIds = new Set();
    for (const w of Array.isArray(hooks) ? hooks : []) {
      const where = `webhooks.json: ${w.code}`;
      if (!/^[A-Z][A-Z0-9-]{2,39}$/.test(w.code ?? "")) errors.push(`${where} codice non valido`);
      if (codes.has(w.code)) errors.push(`${where} codice duplicato`);
      codes.add(w.code);
      if (!w.name) errors.push(`${where} nome mancante`);
      if (w.enabled !== false) errors.push(`${where} deve essere disabilitato (docs/10 §7)`);
      let url = null;
      try {
        url = new URL(w.url);
      } catch {
        errors.push(`${where} URL non valido`);
      }
      if (url && (url.protocol !== "https:" || !/(^|\.)example\.org$/.test(url.hostname))) {
        errors.push(`${where} URL solo https:// verso example.org`);
      }
      if (!/^whsec_\S{8,}$/.test(w.secret ?? "")) errors.push(`${where} segreto whsec_… mancante`);
      const types = w.factTypes ?? [];
      if (types.length === 0) errors.push(`${where} nessun tipo di fatto`);
      for (const t of types) {
        if (t === "message.delivered") errors.push(`${where} message.delivered non si consegna (ciclo)`);
        else if (factTypes.size > 0 && !factTypes.has(t)) errors.push(`${where} tipo di fatto sconosciuto ${t}`);
      }
      for (const d of w.deliveries ?? []) {
        const dw = `${where} consegna ${d.eventId}`;
        if (!d.eventId || eventIds.has(d.eventId)) errors.push(`${dw} eventId mancante o duplicato`);
        eventIds.add(d.eventId);
        if (!types.includes(d.factType)) errors.push(`${dw} tipo ${d.factType} non sottoscritto`);
        if (d.memberId && !memberIds.has(d.memberId)) errors.push(`${dw} membro inesistente ${d.memberId}`);
        if (!["OK", "GAVE_UP"].includes(d.status)) errors.push(`${dw} stato ${d.status}: nel seed solo OK o GAVE_UP`);
        const attempts = d.attempts ?? 1;
        if (!Number.isInteger(attempts) || attempts < 1 || attempts > 4) errors.push(`${dw} tentativi fuori da 1…4`);
        if (d.status === "GAVE_UP" && attempts !== 4) errors.push(`${dw} GAVE_UP richiede 4 tentativi`);
        if (d.status === "OK" && !(d.httpStatus >= 200 && d.httpStatus < 300)) errors.push(`${dw} OK richiede un 2xx`);
        const age = /^@(?:now|today)-(\d+)d/.exec(d.at ?? "");
        if (!age || Number(age[1]) > 13) errors.push(`${dw} data "${d.at}" oltre la pulizia dei 14 giorni`);
      }
    }
  }
}

// Riferimenti incrociati residui (AUDIT-SEED-1): ciò che i controlli sopra non coprono ancora.
{
  const memberIds = new Set((readSeed("members.json") ?? []).map((m) => m.id));
  const tierCodes = new Set((readSeed("tiers.json") ?? []).map((t) => t.code));
  const bandCodes = new Set((readSeed("reward-bands.json") ?? []).map((b) => b.code));
  const categoryCodes = new Set((readSeed("reward-categories.json") ?? []).map((c) => c.code));
  const typeCodes = new Set((readSeed("event-types.json") ?? []).map((t) => t.code));
  const contestCodes = new Set((readSeed("contests.json") ?? []).map((c) => c.code));
  const sourcesSeed = readSeed("sources.json") ?? [];
  const sourceCodes = new Set(sourcesSeed.map((x) => x.code));
  // Tipi accettati da almeno una fonte esterna abilitata (internal e simulator non hanno elenco: ponte e demo).
  const allowedByEnabled = new Set(sourcesSeed.filter((x) => x.enabled !== false).flatMap((x) => x.allowedTypes ?? []));

  for (const m of readSeed("members.json") ?? []) {
    if (m.referredBy && !memberIds.has(m.referredBy)) errors.push(`members.json: ${m.id} invitato da ${m.referredBy}, che non esiste`);
  }
  for (const w of readSeed("wallets.json") ?? []) {
    if (!memberIds.has(w.memberId)) errors.push(`wallets.json: wallet del membro inesistente ${w.memberId}`);
    if (w.tier && !tierCodes.has(w.tier)) errors.push(`wallets.json: ${w.memberId} ha il livello inesistente ${w.tier}`);
  }
  for (const r of readSeed("rewards.json") ?? []) {
    if (r.band && !bandCodes.has(r.band)) errors.push(`rewards.json: ${r.code} usa la fascia inesistente ${r.band}`);
    if (r.category && !categoryCodes.has(r.category)) errors.push(`rewards.json: ${r.code} usa la categoria inesistente ${r.category}`);
    for (const t of r.eligibleTiers ?? []) if (!tierCodes.has(t)) errors.push(`rewards.json: ${r.code} ammette il livello inesistente ${t}`);
  }
  for (const c of readSeed("campaigns.json") ?? []) {
    for (const t of c.triggerActionTypes ?? []) {
      if (!typeCodes.has(t)) errors.push(`campaigns.json: ${c.code} scatta sul tipo azione inesistente ${t}`);
      // I tipi interni (ponte) arrivano da "internal": nessuna fonte esterna li deve ammettere.
      const internalOnly = (readSeed("event-types.json") ?? []).find((x) => x.code === t)?.category === "INTERNAL";
      if (typeCodes.has(t) && !internalOnly && !allowedByEnabled.has(t)) {
        warnings.push(`campaigns.json: ${c.code} scatta su ${t}, che nessuna fonte esterna abilitata ammette (solo simulatore o ponte)`);
      }
    }
    for (const e of c.effects ?? []) {
      if (e.type === "GRANT_PLAYS" && !contestCodes.has(e.contestCode)) errors.push(`campaigns.json: ${c.code} dà giocate al concorso inesistente ${e.contestCode}`);
    }
  }
  for (const sg of readSeed("segments.json") ?? []) {
    for (const id of sg.expectedMembers ?? []) if (!memberIds.has(id)) errors.push(`segments.json: ${sg.code} attende il membro inesistente ${id}`);
  }
  for (const a of readSeed("activity-history.json")?.memberActivity ?? []) {
    if (!memberIds.has(a.memberId)) errors.push(`activity-history.json: attività del membro inesistente ${a.memberId}`);
  }
  // Scenari (docs/10 §8): esiti della pipeline e riferimenti. Un passo negativo può citare apposta una fonte
  // sconosciuta (expect REJECTED, Q-129) o un membro sconosciuto (expect UNMATCHED).
  const OUTCOMES = new Set(["ACCEPTED", "DUPLICATE", "REJECTED", "UNMATCHED"]);
  for (const sc of readSeed("scenarios.json") ?? []) {
    (sc.steps ?? []).forEach((st, i) => {
      const w = `scenarios.json: ${sc.code} passo ${i + 1}`;
      if (st.expect && !OUTCOMES.has(st.expect)) errors.push(`${w} attende l'esito inesistente ${st.expect}`);
      if (!typeCodes.has(st.type)) errors.push(`${w} usa il tipo azione inesistente ${st.type}`);
      if (st.source && !sourceCodes.has(st.source) && st.expect !== "REJECTED") errors.push(`${w} usa la fonte inesistente ${st.source}`);
      if (st.memberId && !memberIds.has(st.memberId) && st.expect !== "UNMATCHED") errors.push(`${w} usa il membro inesistente ${st.memberId}`);
    });
  }
}

// Tipi azione SYSTEM con un contratto (docs/05 §3, contracts/events/action/*.schema.json, precedenza 2 su seed/): lo
// schema di `data` del seed coincide con quello del contratto (a meno di `$id`/`title`), così l'ingresso valida con
// il contratto (TB-ING SCH-*). I sampleData dei tipi e i `data` dei passi di scenario non negativi devono avere
// almeno i campi obbligatori dello schema.
{
  const contractsDir = resolve(here, "..", "contracts", "events", "action");
  const canon = (v) => (Array.isArray(v) ? v.map(canon) : v && typeof v === "object"
    ? Object.fromEntries(Object.keys(v).sort().map((k) => [k, canon(v[k])])) : v);
  const types = readSeed("event-types.json") ?? [];
  const byCode = new Map(types.map((t) => [t.code, t]));
  if (existsSync(contractsDir)) {
    for (const f of readdirSync(contractsDir).filter((x) => x.endsWith(".schema.json"))) {
      const code = f.replace(/\.schema\.json$/, "");
      const t = byCode.get(code);
      if (!t) continue;
      const contract = JSON.parse(readFileSync(join(contractsDir, f), "utf8"));
      delete contract.$id;
      delete contract.title;
      if (JSON.stringify(canon(contract)) !== JSON.stringify(canon(t.dataSchema))) {
        errors.push(`event-types.json: dataSchema di ${code} diverso da contracts/events/action/${f}`);
      }
    }
  }
  const missingRequired = (schema, data) => (schema?.required ?? []).filter((k) => data?.[k] === undefined);
  for (const t of types) {
    const miss = missingRequired(t.dataSchema, t.sampleData ?? {});
    if (miss.length) errors.push(`event-types.json: sampleData di ${t.code} senza i campi obbligatori ${miss.join(", ")}`);
  }
  for (const sc of readSeed("scenarios.json") ?? []) {
    (sc.steps ?? []).forEach((st, i) => {
      const t = byCode.get(st.type);
      if (!t || st.expect || st.data === undefined) return;
      const miss = missingRequired(t.dataSchema, st.data);
      if (miss.length) errors.push(`scenarios.json: ${sc.code} passo ${i + 1} senza i campi obbligatori ${miss.join(", ")}`);
    });
  }
}

// Storico del monitor ingressi (docs/servizi/ingestion-service.md §6, BO-26): 40 righe degli ultimi 3 giorni con tutti
// e quattro gli esiti; riferimenti esistenti tranne dove l'esito dichiara il contrario (fonte sconosciuta, tipo
// sconosciuto, membro non trovato); gli ACCEPTED sono azioni di activity-history.json (stesso membro, tipo, istante)
// ammesse dalla fonte; i DUPLICATE reinviano un ACCEPTED dello storico; i MEMBER_NOT_ACTIVE citano un membro non attivo.
{
  const hist = readSeed("inbound-history.json");
  if (hist) {
    const events = hist.events ?? [];
    const W = "inbound-history.json";
    const members = readSeed("members.json") ?? [];
    const sources = new Map((readSeed("sources.json") ?? []).map((x) => [x.code, x]));
    const types = new Set((readSeed("event-types.json") ?? []).map((t) => t.code));
    const bySubject = (subj) => {
      const [kind, ...rest] = String(subj).split(":");
      const v = rest.join(":");
      if (kind === "member") return members.find((m) => m.id === v);
      if (kind === "external") return members.find((m) => m.externalId === v);
      if (kind === "email") return members.find((m) => (m.email ?? "").toLowerCase() === v.toLowerCase());
      return undefined;
    };
    const activity = new Set();
    for (const a of readSeed("activity-history.json")?.memberActivity ?? []) {
      for (const x of a.actions ?? []) activity.add(`${a.memberId}|${x.type}|${x.at}`);
    }
    const CODES = new Set(["SOURCE_DISABLED", "UNKNOWN_TYPE", "TYPE_NOT_ALLOWED", "INVALID_DATA", "INVALID_TIME", "MEMBER_NOT_ACTIVE"]);
    const accepted = new Set();
    if (events.length !== 40) errors.push(`${W}: attese 40 righe (ingestion §6), trovate ${events.length}`);
    const RECENT = /^@today-[12]dT\d{2}:\d{2}$/;
    for (const e of events) {
      const w = `${W}: ${e.eventId}`;
      if (!String(e.eventId ?? "").startsWith("hist-")) errors.push(`${w}: eventId senza prefisso hist-`);
      if (!RECENT.test(e.receivedAt ?? "")) errors.push(`${w}: receivedAt fuori dagli ultimi 3 giorni (atteso @today-1d/-2d con orario)`);
      const src = sources.get(e.source);
      const code = e.rejectCode;
      if (e.status === "REJECTED" && !CODES.has(code)) errors.push(`${w}: rejectCode non valido ${code}`);
      if (e.status !== "REJECTED" && code) errors.push(`${w}: rejectCode su un esito ${e.status}`);
      if (!src && code !== "SOURCE_DISABLED") errors.push(`${w}: fonte inesistente ${e.source}`);
      if (!types.has(e.type) && code !== "UNKNOWN_TYPE") errors.push(`${w}: tipo azione inesistente ${e.type}`);
      const m = bySubject(e.subject);
      if (e.status === "UNMATCHED" && m) errors.push(`${w}: UNMATCHED ma il soggetto è il membro ${m.id}`);
      if (e.status === "ACCEPTED") {
        if (!m || m.status !== "ACTIVE") errors.push(`${w}: ACCEPTED senza un membro ACTIVE`);
        else if (!activity.has(`${m.id}|${e.type}|${e.receivedAt}`)) errors.push(`${w}: ACCEPTED assente da activity-history.json`);
        if (src && (src.allowedTypes ?? []).length && !src.allowedTypes.includes(e.type)) errors.push(`${w}: tipo non ammesso dalla fonte`);
        accepted.add(`${e.source}|${e.eventId}`);
      }
      if (code === "MEMBER_NOT_ACTIVE" && (!m || m.status === "ACTIVE")) errors.push(`${w}: MEMBER_NOT_ACTIVE senza un membro non attivo`);
    }
    for (const e of events.filter((x) => x.status === "DUPLICATE")) {
      if (!accepted.has(`${e.source}|${e.eventId}`)) errors.push(`${W}: ${e.eventId} DUPLICATE senza l'ACCEPTED originale`);
    }
    for (const st of ["ACCEPTED", "DUPLICATE", "REJECTED", "UNMATCHED"]) {
      if (!events.some((e) => e.status === st)) errors.push(`${W}: nessuna riga ${st} (servono tutti gli esiti)`);
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
