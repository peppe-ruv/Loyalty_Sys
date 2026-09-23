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
      const available = (pool.size ?? 0) - (pool.consumed ?? 0);
      const declared = r.stockRemaining ?? r.stockTotal ?? 0;
      if (available < declared) {
        errors.push(`coupon-pools.json: ${pool.code} ha ${available} codici disponibili, meno dello stock di ${r.code} (${declared})`);
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
