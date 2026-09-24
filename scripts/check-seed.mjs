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

for (const w of warnings) console.warn(`⚠ ${w}`);
if (errors.length > 0) {
  for (const e of errors) console.error(`✗ ${e}`);
  console.error(`check-seed: ${errors.length} errore/i.`);
  process.exit(1);
}
console.log(`check-seed: ${files.length} file OK${warnings.length ? `, ${warnings.length} avviso/i` : ""}.`);
