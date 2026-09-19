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
const ALLOWED_HOSTS = [/(^|\.)example\.org$/, /^localhost$/, /^127\.0\.0\.1$/];

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

for (const w of warnings) console.warn(`⚠ ${w}`);
if (errors.length > 0) {
  for (const e of errors) console.error(`✗ ${e}`);
  console.error(`check-seed: ${errors.length} errore/i.`);
  process.exit(1);
}
console.log(`check-seed: ${files.length} file OK${warnings.length ? `, ${warnings.length} avviso/i` : ""}.`);
