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
  const schemaFile = join(eventsDir, family, `${name}.schema.json`);
  if (!existsSync(schemaFile)) errors.push(`${file}: schema mancante ${family}/${name}.schema.json`);

  const expected = `urn:loyaltyhub:schema:${familyDotName}:1`;
  if (event.dataschema !== expected) {
    errors.push(`${file}: dataschema atteso ${expected}, trovato ${event.dataschema}`);
  }
  if (family === "audit" && !event.lhactor) {
    errors.push(`${file}: audit senza lhactor`);
  }
}

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
