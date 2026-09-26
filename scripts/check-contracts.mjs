#!/usr/bin/env node
// check-contracts.mjs — gate rapido sui contratti eventi (docs/05 §9). Controlli strutturali:
// ogni esempio ha gli attributi d'envelope, un `type` con schema corrispondente, `dataschema` coerente,
// gli audit hanno `lhactor`, e ogni schema di `data` ha un esempio. La validazione JSON Schema completa
// vive nel test Java ContractsTest (job `backend`); qui diamo un segnale veloce senza dipendenze.
import { readFileSync, readdirSync, existsSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join, resolve } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
const eventsDir = resolve(here, "..", "contracts", "events");
const examplesDir = join(eventsDir, "examples");

const REQUIRED = ["specversion", "id", "source", "type", "subject", "time", "datacontenttype", "dataschema", "lhtenant", "lhcorrelationid", "lhhop", "data"];
const errors = [];
const schemaFileName = (name, version) => (version > 1 ? `${name}.v${version}.schema.json` : `${name}.schema.json`);

if (!existsSync(examplesDir)) {
  console.error("check-contracts: contracts/events/examples/ assente.");
  process.exit(1);
}

const examples = readdirSync(examplesDir).filter((f) => f.endsWith(".json"));
const seenExamples = new Set(examples);

for (const file of examples) {
  let event;
  try {
    event = JSON.parse(readFileSync(join(examplesDir, file), "utf8"));
  } catch (e) {
    errors.push(`${file}: JSON non valido — ${e.message}`);
    continue;
  }
  for (const key of REQUIRED) {
    if (event[key] === undefined) errors.push(`${file}: manca l'attributo d'envelope "${key}"`);
  }
  const type = String(event.type ?? "");
  const familyDotName = type.replace(/^io\.loyaltyhub\./, "");
  const dot = familyDotName.indexOf(".");
  if (dot < 0) {
    errors.push(`${file}: type non valido "${type}"`);
    continue;
  }
  const family = familyDotName.slice(0, dot);
  const name = familyDotName.slice(dot + 1);
  // dataschema = urn:loyaltyhub:schema:<famiglia>.<nome>:<n>; la versione n>1 vive in <nome>.v<n>.schema.json (docs/05 §9).
  const prefix = `urn:loyaltyhub:schema:${familyDotName}:`;
  const version = String(event.dataschema ?? "").startsWith(prefix) ? Number(event.dataschema.slice(prefix.length)) : NaN;
  if (!Number.isInteger(version) || version < 1) {
    errors.push(`${file}: dataschema atteso ${prefix}<versione>, trovato ${event.dataschema}`);
    continue;
  }
  const schemaName = schemaFileName(name, version);
  if (!existsSync(join(eventsDir, family, schemaName))) errors.push(`${file}: schema mancante ${family}/${schemaName}`);
  if (family === "audit" && !event.lhactor) {
    errors.push(`${file}: audit senza lhactor`);
  }
}

// Dati personali fuori dal bus (ADR-032, CLAUDE.md regola 10): ogni campo dichiara x-lh-pii; uno schema con un
// campo x-lh-pii: true è ammesso solo come versione superata (x-lh-superseded-by verso una versione esistente).
function piiFields(node, path, out) {
  for (const [key, prop] of Object.entries(node?.properties ?? {})) {
    const at = path ? `${path}.${key}` : key;
    if (typeof prop["x-lh-pii"] !== "boolean") out.missing.push(at);
    else if (prop["x-lh-pii"]) out.pii.push(at);
    piiFields(prop, at, out);
    if (prop.items && typeof prop.items === "object") piiFields(prop.items, `${at}[]`, out);
  }
  return out;
}
function checkPii(dir, family) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (entry.isDirectory()) {
      if (entry.name !== "examples") checkPii(join(dir, entry.name), entry.name);
      continue;
    }
    if (!family || !entry.name.endsWith(".schema.json")) continue;
    const rel = `${family}/${entry.name}`;
    const schema = JSON.parse(readFileSync(join(dir, entry.name), "utf8"));
    const { missing, pii } = piiFields(schema, "", { missing: [], pii: [] });
    for (const m of missing) errors.push(`${rel}: il campo ${m} non dichiara x-lh-pii`);
    const m = /^(.*?)(?:\.v(\d+))?\.schema\.json$/.exec(entry.name);
    const version = m[2] ? Number(m[2]) : 1;
    if (!String(schema.$id ?? "").endsWith(`:${version}`)) errors.push(`${rel}: $id ${schema.$id} non termina con :${version}`);
    if (pii.length === 0) continue;
    const next = /:(\d+)$/.exec(String(schema["x-lh-superseded-by"] ?? ""));
    if (!next || Number(next[1]) <= version || !existsSync(join(dir, schemaFileName(m[1], Number(next[1]))))) {
      errors.push(`${rel}: campi x-lh-pii: true (${pii.join(", ")}) in una versione non superata`);
    }
  }
}
checkPii(eventsDir, null);

// Ogni schema di data ha un esempio.
function walkSchemas(dir, family) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (entry.isDirectory()) {
      walkSchemas(join(dir, entry.name), entry.name);
    } else if (entry.name.endsWith(".schema.json") && entry.name !== "envelope.schema.json" && family) {
      const name = entry.name.replace(/\.schema\.json$/, "");
      const example = `${family}.${name}.json`;
      if (!seenExamples.has(example)) errors.push(`${example}: schema senza esempio`);
    }
  }
}
walkSchemas(eventsDir, null);

if (errors.length > 0) {
  for (const e of errors) console.error(`✗ ${e}`);
  console.error(`check-contracts: ${errors.length} errore/i.`);
  process.exit(1);
}
console.log(`check-contracts: ${examples.length} esempi coerenti con gli schemi.`);
