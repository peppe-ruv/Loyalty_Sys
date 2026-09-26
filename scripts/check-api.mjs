#!/usr/bin/env node
// check-api.mjs — compatibilità all'indietro delle API HTTP (M8.8, F2-API-01, ADR-046, docs/18 §3.6).
// Confronta ogni contracts/api/*.openapi.yaml con la stessa specifica sul ramo base: le aggiunte sono lecite,
// ciò che rompe un client esistente no (exit 1). Le specifiche si generano con OpenApiExportIT (deploy/hub).
//
// Uso:  node scripts/check-api.mjs [base-ref] [--pr-labels=a,b,c]
//   base-ref  default: origin/$GITHUB_BASE_REF in una pull request, altrimenti origin/main. Si confronta con
//             il merge-base tra base-ref e HEAD, così un ramo rimasto indietro non vede come "rimosso" ciò che
//             main ha aggiunto nel frattempo.
//   --pr-labels  con la label "decisione" le rotture sono riportate ma non bloccano (stessa regola del job guard:
//             un cambio incompatibile è una decisione, serve un'ADR e una nuova versione del percorso).
//
// Incompatibile (in ogni file):
//   - file, percorso o operazione rimossi; risposta 2xx o media type rimossi; schema in components rimosso;
//   - proprietà rimossa da uno schema;
//   - richiesta: parametro o campo obbligatorio aggiunto, corpo reso obbligatorio, valore di enum rimosso,
//     tipo ristretto;
//   - risposta: campo prima obbligatorio (quindi garantito) non più obbligatorio, tipo allargato (anche a null);
//   - format cambiato (es. int32 → int64).
// Compatibile: tutto il resto (nuovi percorsi, operazioni, campi facoltativi, valori di enum in risposta…).
import { execFileSync } from "node:child_process";
import { existsSync, readdirSync, readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import YAML from "yaml";

const HTTP_METHODS = ["get", "put", "post", "delete", "options", "head", "patch", "trace"];
const SUFFIX = ".openapi.yaml";
const API_DIR = "contracts/api";

// ================= confronto (puro, testato da check-api.test.mjs) =================

/** Rotture di compatibilità tra due documenti OpenAPI già letti. Ritorna un elenco di messaggi (senza duplicati). */
export function breakingChanges(base, current) {
  const out = new Set();
  const report = (where, message) => out.add(`${where}: ${message}`);
  const basePaths = base?.paths ?? {};
  const curPaths = current?.paths ?? {};

  for (const [path, baseItem] of Object.entries(basePaths)) {
    const curItem = curPaths[path];
    if (!curItem) {
      report(path, "percorso rimosso");
      continue;
    }
    for (const method of HTTP_METHODS) {
      if (!baseItem[method]) continue;
      const where = `${method.toUpperCase()} ${path}`;
      if (!curItem[method]) {
        report(where, "operazione rimossa");
        continue;
      }
      const ctx = { base, current, report, seen: new Set() };
      compareOperation(ctx, where, baseItem[method], curItem[method], baseItem.parameters, curItem.parameters);
    }
  }

  const baseSchemas = base?.components?.schemas ?? {};
  const curSchemas = current?.components?.schemas ?? {};
  for (const [name, schema] of Object.entries(baseSchemas)) {
    const where = `components.schemas.${name}`;
    if (!curSchemas[name]) {
      report(where, "schema rimosso");
      continue;
    }
    for (const prop of Object.keys(schema.properties ?? {})) {
      if (!(prop in (curSchemas[name].properties ?? {}))) report(where, `proprietà «${prop}» rimossa`);
    }
  }
  return [...out];
}

function compareOperation(ctx, where, baseOp, curOp, basePathParams, curPathParams) {
  const params = (doc, pathLevel, opLevel) => {
    const m = new Map();
    for (const p of [...(pathLevel ?? []), ...(opLevel ?? [])]) {
      const r = deref(doc, p) ?? p;
      m.set(`${r.in}:${r.name}`, r);
    }
    return m;
  };
  const baseParams = params(ctx.base, basePathParams, baseOp.parameters);
  for (const [k, p] of params(ctx.current, curPathParams, curOp.parameters)) {
    const b = baseParams.get(k);
    if (p.required && !b) ctx.report(where, `parametro obbligatorio aggiunto «${k}»`);
    else if (p.required && b && !b.required) ctx.report(where, `parametro «${k}» reso obbligatorio`);
    if (b) compareSchema(ctx, `${where} parametro ${k}`, b.schema, p.schema, "request");
  }

  const baseBody = deref(ctx.base, baseOp.requestBody);
  const curBody = deref(ctx.current, curOp.requestBody);
  if (curBody?.required && !baseBody?.required) ctx.report(where, "corpo della richiesta reso obbligatorio");
  if (baseBody && curBody) {
    compareContent(ctx, `${where} richiesta`, baseBody.content, curBody.content, "request");
  }

  const baseResponses = baseOp.responses ?? {};
  const curResponses = curOp.responses ?? {};
  for (const [status, baseResp] of Object.entries(baseResponses)) {
    const curResp = curResponses[status];
    if (!curResp) {
      if (/^2/.test(status)) ctx.report(where, `risposta ${status} rimossa`);
      continue;
    }
    compareContent(ctx, `${where} risposta ${status}`, deref(ctx.base, baseResp)?.content,
      deref(ctx.current, curResp)?.content, "response");
  }
}

function compareContent(ctx, where, baseContent, curContent, direction) {
  for (const [media, b] of Object.entries(baseContent ?? {})) {
    const c = (curContent ?? {})[media];
    if (!c) {
      ctx.report(where, `media type «${media}» rimosso`);
      continue;
    }
    compareSchema(ctx, where, b.schema, c.schema, direction);
  }
}

/**
 * Confronto ricorsivo di due schemi nel verso indicato: "request" (il client invia, il server deve accettare
 * almeno ciò che accettava) o "response" (il server invia, il client deve ricevere almeno ciò che riceveva).
 */
function compareSchema(ctx, where, baseSchema, curSchema, direction) {
  if (!baseSchema || !curSchema) return;
  const baseRef = baseSchema.$ref;
  const curRef = curSchema.$ref;
  const b = deref(ctx.base, baseSchema);
  const c = deref(ctx.current, curSchema);
  if (!b || !c) return;
  const label = baseRef ? `${where} (${refName(baseRef)})` : where;
  if (baseRef || curRef) {
    const visit = `${direction}|${baseRef ?? ""}|${curRef ?? ""}`;
    if (ctx.seen.has(visit)) return;
    ctx.seen.add(visit);
  }

  const bt = types(b);
  const ct = types(c);
  if (bt && ct) {
    const narrowed = bt.filter((t) => !ct.includes(t) && !(t === "integer" && ct.includes("number")));
    const widened = ct.filter((t) => !bt.includes(t) && !(t === "integer" && bt.includes("number")));
    if (direction === "request" && narrowed.length) {
      ctx.report(label, `tipo ristretto da ${bt.join("|")} a ${ct.join("|")}`);
    }
    if (direction === "response" && widened.length) {
      ctx.report(label, `tipo allargato da ${bt.join("|")} a ${ct.join("|")}`);
    }
  }
  if (b.format && c.format && b.format !== c.format) {
    ctx.report(label, `format cambiato da ${b.format} a ${c.format}`);
  }
  if (direction === "request" && Array.isArray(b.enum) && Array.isArray(c.enum)) {
    for (const v of b.enum) {
      if (!c.enum.includes(v)) ctx.report(label, `valore «${v}» rimosso dall'enum`);
    }
  }

  const bProps = b.properties ?? {};
  const cProps = c.properties ?? {};
  for (const prop of Object.keys(bProps)) {
    if (!(prop in cProps)) ctx.report(label, `proprietà «${prop}» rimossa`);
    else compareSchema(ctx, `${where}.${prop}`, bProps[prop], cProps[prop], direction);
  }
  const bReq = new Set(b.required ?? []);
  const cReq = new Set(c.required ?? []);
  if (direction === "request") {
    for (const r of cReq) if (!bReq.has(r)) ctx.report(label, `campo obbligatorio aggiunto nella richiesta «${r}»`);
  } else {
    for (const r of bReq) if (!cReq.has(r) && r in cProps) ctx.report(label, `campo «${r}» non più garantito nella risposta`);
  }

  if (b.items && c.items) compareSchema(ctx, `${where}[]`, b.items, c.items, direction);
  if (isSchema(b.additionalProperties) && isSchema(c.additionalProperties)) {
    compareSchema(ctx, `${where}{}`, b.additionalProperties, c.additionalProperties, direction);
  }
}

function types(schema) {
  const t = schema.type;
  if (t === undefined) return null;
  const list = Array.isArray(t) ? [...t] : [t];
  if (schema.nullable === true && !list.includes("null")) list.push("null");
  return list.sort();
}

const isSchema = (v) => v !== null && typeof v === "object";
const refName = (ref) => ref.split("/").pop();

/** Risolve un $ref locale (#/components/…) nel documento; lascia invariato ciò che non è un riferimento. */
function deref(doc, node, depth = 0) {
  if (!node || typeof node !== "object" || !node.$ref) return node;
  if (depth > 32 || !node.$ref.startsWith("#/")) return undefined;
  let target = doc;
  for (const part of node.$ref.slice(2).split("/")) {
    target = target?.[part.replace(/~1/g, "/").replace(/~0/g, "~")];
  }
  return deref(doc, target, depth + 1);
}

// ================= git e file =================

function git(args) {
  return execFileSync("git", args, { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"], maxBuffer: 64 * 1024 * 1024 });
}

function specsAt(ref) {
  const out = git(["ls-tree", "--name-only", `${ref}:${API_DIR}/`]).split("\n").filter((f) => f.endsWith(SUFFIX));
  return new Map(out.map((f) => [f, git(["show", `${ref}:${API_DIR}/${f}`])]));
}

function main(argv) {
  const args = argv.filter((a) => !a.startsWith("--"));
  const labelsArg = argv.find((a) => a.startsWith("--pr-labels="));
  const labels = labelsArg ? labelsArg.slice("--pr-labels=".length).split(",").map((s) => s.trim()).filter(Boolean) : [];
  const baseRef = args[0] ?? (process.env.GITHUB_BASE_REF ? `origin/${process.env.GITHUB_BASE_REF}` : "origin/main");
  const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
  process.chdir(root);

  let mergeBase;
  try {
    mergeBase = git(["merge-base", baseRef, "HEAD"]).trim();
  } catch {
    console.error(`check-api: impossibile trovare il merge-base tra ${baseRef} e HEAD ` +
      "(in CI serve actions/checkout con fetch-depth: 0).");
    return 2;
  }

  let baseSpecs;
  try {
    baseSpecs = specsAt(mergeBase);
  } catch {
    baseSpecs = new Map(); // la base non ha ancora contracts/api/
  }
  const dir = join(root, API_DIR);
  const currentFiles = existsSync(dir) ? readdirSync(dir).filter((f) => f.endsWith(SUFFIX)).sort() : [];
  console.log(`check-api: ${currentFiles.length} specifiche confrontate con ${baseRef} (merge-base ${mergeBase.slice(0, 12)}).`);

  const problems = [];
  for (const [file, text] of [...baseSpecs].sort()) {
    if (!currentFiles.includes(file)) problems.push(`${file}: file rimosso`);
    else {
      let base, current;
      try {
        base = YAML.parse(text);
        current = YAML.parse(readFileSync(join(dir, file), "utf8"));
      } catch (e) {
        problems.push(`${file}: YAML non valido — ${e.message}`);
        continue;
      }
      for (const p of breakingChanges(base, current)) problems.push(`${file}: ${p}`);
    }
  }
  for (const file of currentFiles) {
    if (!baseSpecs.has(file)) {
      try {
        YAML.parse(readFileSync(join(dir, file), "utf8"));
        console.log(`  + ${file}: nuova specifica (nessun confronto)`);
      } catch (e) {
        problems.push(`${file}: YAML non valido — ${e.message}`);
      }
    }
  }

  if (!problems.length) {
    console.log("✓ check-api: nessuna modifica incompatibile.");
    return 0;
  }
  console.error(`✗ check-api: ${problems.length} modifiche incompatibili delle API HTTP:`);
  problems.forEach((p) => console.error(`    - ${p}`));
  if (labels.includes("decisione")) {
    console.error('  (label "decisione" presente: segnalate ma non bloccanti; serve un\'ADR e una nuova versione del percorso)');
    return 0;
  }
  console.error("  → le API si estendono, non si rompono: aggiungi invece di rimuovere o rinominare. Un cambio " +
    'incompatibile è una decisione (ADR, nuova versione del percorso, label "decisione" sulla PR).');
  return 1;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  process.exit(main(process.argv.slice(2)));
}
