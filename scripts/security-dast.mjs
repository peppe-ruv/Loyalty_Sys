#!/usr/bin/env node
// security-dast.mjs — logica di contorno del fuzzing Schemathesis e dello ZAP API scan (M8.11c, F2-SEC-12, ADR-042,
// docs/18 §3.10 p.12, docs/security/dast.md). Non lancia gli strumenti: prepara le specifiche, valida le eccezioni
// accettate e legge i loro rapporti. Unica dipendenza: `yaml` (npm --prefix scripts ci --omit=dev).
//
// Uso:
//   node scripts/security-dast.mjs prepare --out <cartella> [--api contracts/api] [--exclusions .dast/exclusions.json]
//       Copia in JSON le specifiche di contracts/api tolte le esclusioni motivate; fallisce se una esclusione non ha
//       motivo e riferimento, o se non corrisponde a nulla (esclusione superata).
//   node scripts/security-dast.mjs baseline-check <file>
//       Valida la baseline di Schemathesis: ogni voce accettata ha scadenza (≤ 90 giorni), motivo e ticket.
//   node scripts/security-dast.mjs fuzz-summary <cartella>
//       Riepilogo dei rapporti JSON di Schemathesis (<cartella>/<spec>/report.json); fallisce se ne manca uno o se una
//       specifica non ha provato alcuna operazione.
//   node scripts/security-dast.mjs zap-gate <cartella> --exceptions <file> [--today AAAA-MM-GG]
//       Gate sui rapporti di ZAP (<cartella>/zap-<spec>.json): blocca sugli avvisi High (confidenza ≠ 0) non coperti da
//       un'eccezione con scadenza.
// Con GITHUB_STEP_SUMMARY impostato, i riepiloghi Markdown si aggiungono anche a quel file.
import { appendFileSync, existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from "node:fs";
import { basename, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import YAML from "yaml";

export const HTTP_METHODS = ["get", "put", "post", "delete", "patch", "head", "options", "trace"];
/** Riferimento ammesso per un'eccezione: una domanda aperta (Q-nnn) o una voce del backlog (TOBE-nnn). */
export const REF = /^(Q|TOBE)-\d{2,4}$/;
/** Scadenza massima di una voce accettata, in giorni dalla data di riferimento (docs/security/dast.md). */
export const MAX_DAYS = 90;
const MIN_REASON = 10;
const SPEC_SUFFIX = ".openapi.json";

// ================= utilità pure =================

/** {@code --chiave valore}, {@code --chiave=valore} e argomenti posizionali. */
export function parseArgs(argv) {
  const positional = [];
  const options = {};
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a.startsWith("--")) {
      const eq = a.indexOf("=");
      if (eq > 0) options[a.slice(2, eq)] = a.slice(eq + 1);
      else options[a.slice(2)] = argv[++i];
    } else positional.push(a);
  }
  return { positional, options };
}

export function todayUtc() {
  return new Date().toISOString().slice(0, 10);
}

/** {@code true} per una data AAAA-MM-GG che esiste davvero nel calendario. */
export function isRealDate(value) {
  if (typeof value !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const d = new Date(`${value}T00:00:00Z`);
  return !Number.isNaN(d.getTime()) && d.toISOString().slice(0, 10) === value;
}

/** Giorni interi da {@code from} a {@code to} (date AAAA-MM-GG, positivo se {@code to} è dopo). */
export function daysBetween(from, to) {
  return Math.round((Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86_400_000);
}

const isText = (v, min = 1) => typeof v === "string" && v.trim().length >= min;

/** Errori comuni a ogni voce con scadenza: motivo, riferimento, data reale entro MAX_DAYS. */
function expiryErrors(entry, refField, today) {
  const errors = [];
  if (!isText(entry.reason, MIN_REASON)) errors.push(`motivo assente o più corto di ${MIN_REASON} caratteri`);
  if (typeof entry[refField] !== "string" || !REF.test(entry[refField])) errors.push(`${refField} assente o non del tipo Q-nnn/TOBE-nnn`);
  if (!isRealDate(entry.expires)) errors.push("expires assente o non una data reale AAAA-MM-GG");
  else if (daysBetween(today, entry.expires) > MAX_DAYS) errors.push(`expires oltre ${MAX_DAYS} giorni da ${today}`);
  return errors;
}

// ================= prepare =================

/** Errori di forma del file delle esclusioni (elenco vuoto = valido). */
export function validateExclusions(ex) {
  const errors = [];
  if (!ex || typeof ex !== "object" || Array.isArray(ex)) return ["il file delle esclusioni non è un oggetto"];
  const groups = [
    ["specsSkipped", "file", (v) => /^[\w.-]+\.openapi\.yaml$/.test(v)],
    ["operations", "operation", (v) => /^[A-Z]+ \/\S*$/.test(v)],
    ["responseContentTypes", "contentType", (v) => /^[\w.+-]+\/[\w.+-]+$/.test(v)],
  ];
  for (const [group, key, valid] of groups) {
    const list = ex[group] ?? [];
    if (!Array.isArray(list)) {
      errors.push(`${group}: non è un elenco`);
      continue;
    }
    list.forEach((e, i) => {
      const where = `${group}[${i}]${e && typeof e[key] === "string" ? ` (${e[key]})` : ""}`;
      if (!e || typeof e[key] !== "string" || !valid(e[key])) errors.push(`${where}: «${key}» assente o non valido`);
      if (!e || !isText(e.reason, MIN_REASON)) errors.push(`${where}: motivo assente o più corto di ${MIN_REASON} caratteri`);
      if (!e || typeof e.ref !== "string" || !REF.test(e.ref)) errors.push(`${where}: ref assente o non del tipo Q-nnn/TOBE-nnn`);
    });
  }
  return errors;
}

function resolveRef(doc, node) {
  let cur = node;
  for (let hops = 0; cur && typeof cur === "object" && typeof cur.$ref === "string" && hops < 8; hops++) {
    if (!cur.$ref.startsWith("#/")) return undefined;
    cur = cur.$ref
      .slice(2)
      .split("/")
      .reduce((o, k) => (o == null ? undefined : o[k.replace(/~1/g, "/").replace(/~0/g, "~")]), doc);
  }
  return cur;
}

const mediaType = (key) => key.split(";")[0].trim().toLowerCase();

/** Media type di tutte le risposte di un'operazione (anche dietro un $ref a components/responses). */
function responseContentTypes(doc, operation) {
  const out = new Set();
  for (const response of Object.values(operation.responses ?? {})) {
    const r = resolveRef(doc, response);
    for (const key of Object.keys(r?.content ?? {})) out.add(mediaType(key));
  }
  return out;
}

/**
 * Prepara le specifiche per gli strumenti: JSON, senza le operazioni escluse. Ritorna {@code files} (una riga per
 * specifica scritta), {@code errors} (esclusioni non valide o superate). Non scrive nulla se le esclusioni non sono valide.
 */
export function prepareSpecs({ apiDir, outDir, exclusions }) {
  const errors = validateExclusions(exclusions);
  if (errors.length) return { files: [], errors };
  const skipped = exclusions.specsSkipped ?? [];
  const operations = exclusions.operations ?? [];
  const contentTypes = exclusions.responseContentTypes ?? [];
  const used = { specsSkipped: new Set(), operations: new Set(), responseContentTypes: new Set() };

  const all = readdirSync(apiDir).filter((f) => f.endsWith(".openapi.yaml")).sort();
  const skipNames = new Set(skipped.map((s) => s.file));
  skipped.forEach((s, i) => {
    if (all.includes(s.file)) used.specsSkipped.add(i);
  });
  mkdirSync(outDir, { recursive: true });

  const files = [];
  for (const file of all) {
    if (skipNames.has(file)) continue;
    const doc = YAML.parse(readFileSync(join(apiDir, file), "utf8"));
    const removed = [];
    let kept = 0;
    for (const [path, item] of Object.entries(doc?.paths ?? {})) {
      for (const method of HTTP_METHODS) {
        const op = item?.[method];
        if (!op) continue;
        const key = `${method.toUpperCase()} ${path}`;
        let excluded = false;
        operations.forEach((o, i) => {
          if (o.operation === key) {
            used.operations.add(i);
            excluded = true;
          }
        });
        const types = responseContentTypes(doc, op);
        contentTypes.forEach((c, i) => {
          if (types.has(mediaType(c.contentType))) {
            used.responseContentTypes.add(i);
            excluded = true;
          }
        });
        if (excluded) {
          delete item[method];
          removed.push(key);
        } else kept++;
      }
      if (!HTTP_METHODS.some((m) => item?.[m])) delete doc.paths[path];
    }
    const out = join(outDir, file.replace(/\.yaml$/, ".json"));
    writeFileSync(out, `${JSON.stringify(doc, null, 2)}\n`);
    files.push({ file, out, kept, removed });
  }

  const stale = [
    ["specsSkipped", skipped, "file", "nessun file in contracts/api"],
    ["operations", operations, "operation", "nessuna operazione nelle specifiche"],
    ["responseContentTypes", contentTypes, "contentType", "nessuna risposta con quel media type"],
  ];
  for (const [group, list, key, why] of stale) {
    list.forEach((e, i) => {
      if (!used[group].has(i)) errors.push(`esclusione superata ${group} «${e[key]}» (${e.ref}): ${why}; toglila da .dast/exclusions.json`);
    });
  }
  return { files, errors };
}

// ================= baseline-check =================

/** Errori delle voci della baseline di Schemathesis (elenco vuoto = valida). */
export function checkBaseline(doc, today = todayUtc()) {
  if (!doc || typeof doc !== "object" || doc.format_version !== 1 || !Array.isArray(doc.entries)) {
    return ["baseline: serve format_version 1 e un elenco entries"];
  }
  const errors = [];
  doc.entries.forEach((e, i) => {
    const id = e && typeof e.id === "string" ? e.id : `#${i}`;
    const problems = [];
    for (const field of ["operation", "check", "failure", "signature"]) {
      if (!e || !isText(e[field])) problems.push(`${field} assente o vuoto`);
    }
    if (e) {
      // La scadenza sta nel formato di Schemathesis: una voce scaduta torna a essere un errore da sola.
      const rest = expiryErrors({ ...e, ref: e.ticket }, "ref", today).map((m) => m.replace(/^ref\b/, "ticket"));
      problems.push(...rest);
    }
    if (problems.length) errors.push(`voce ${id}: ${problems.join("; ")}`);
  });
  return errors;
}

// ================= fuzz-summary =================

const specNames = (dir) =>
  existsSync(join(dir, "specs"))
    ? readdirSync(join(dir, "specs")).filter((f) => f.endsWith(SPEC_SUFFIX)).sort().map((f) => f.slice(0, -SPEC_SUFFIX.length))
    : [];

const sum = (list, key) => (Array.isArray(list) ? list.reduce((n, x) => n + (Number(x?.[key]) || 0), 0) : 0);

/** Riepilogo dei rapporti di Schemathesis: {@code problems} (bloccanti) e {@code markdown}. */
export function summarizeFuzz(dir) {
  const problems = [];
  const names = specNames(dir);
  if (!names.length) problems.push(`nessuna specifica in ${join(dir, "specs")}: il fuzzing non ha provato nulla`);
  const rows = [];
  for (const name of names) {
    const file = join(dir, name, "report.json");
    if (!existsSync(file)) {
      problems.push(`${name}: manca il rapporto ${file}`);
      rows.push(`| ${name} | manca il rapporto | | | | | |`);
      continue;
    }
    let report;
    let text;
    try {
      text = readFileSync(file, "utf8");
      report = JSON.parse(text);
    } catch (e) {
      problems.push(`${name}: rapporto illeggibile (${e.message})`);
      rows.push(`| ${name} | rapporto illeggibile | | | | | |`);
      continue;
    }
    // Il seme di Schemathesis è un intero di ~128 bit: JSON.parse lo arrotonderebbe, quindi lo si legge come testo.
    const seed = text.match(/"seed"\s*:\s*(-?\d+)/)?.[1] ?? "—";
    const ops = report.operations ?? {};
    const tested = Number(ops.tested ?? 0);
    const selected = Number(ops.selected ?? 0);
    if (!(tested > 0)) problems.push(`${name}: nessuna operazione provata (tested = ${tested} su ${selected})`);
    const baseline = report.baseline ?? null;
    const newFailures = baseline ? Number(baseline.new ?? 0) : sum(report.failures, "count");
    const known = baseline ? Number(baseline.known ?? 0) : 0;
    const expired = Array.isArray(baseline?.expired_ids) ? baseline.expired_ids.length : 0;
    const errorCount = sum(report.errors, "count");
    rows.push(`| ${name} | ${tested}/${selected} | ${newFailures} | ${known} | ${expired} | ${errorCount} | ${seed} |`);
  }
  const markdown = [
    "### Fuzzing API (Schemathesis)",
    "",
    "| Specifica | Operazioni provate | 5xx nuovi | 5xx noti | Voci scadute | Errori | Seme |",
    "|---|---|---|---|---|---|---|",
    ...rows,
    "",
    ...(problems.length ? ["Problemi:", ...problems.map((p) => `- ${p}`), ""] : []),
  ].join("\n");
  return { problems, markdown };
}

// ================= zap-gate =================

/** Errori di forma delle eccezioni accettate di ZAP (elenco vuoto = valide). */
export function validateZapExceptions(doc, today = todayUtc()) {
  if (!doc || typeof doc !== "object" || !Array.isArray(doc.exceptions)) return ["zap-exceptions: serve un oggetto con l'elenco exceptions"];
  const errors = [];
  doc.exceptions.forEach((e, i) => {
    const problems = [];
    if (!e || typeof e.pluginId !== "string" || !/^\d+$/.test(e.pluginId)) problems.push("pluginId assente o non numerico");
    if (e && e.method !== undefined && (typeof e.method !== "string" || !/^[A-Z]+$/.test(e.method))) problems.push("method non in maiuscolo");
    if (e && e.uriRegex !== undefined) {
      try {
        new RegExp(e.uriRegex);
      } catch {
        problems.push("uriRegex non è un'espressione regolare valida");
      }
    }
    problems.push(...expiryErrors(e ?? {}, "ref", today));
    if (problems.length) errors.push(`eccezione #${i}${e?.pluginId ? ` (plugin ${e.pluginId})` : ""}: ${problems.join("; ")}`);
  });
  return errors;
}

const RISKS = { 3: "High", 2: "Medium", 1: "Low", 0: "Info" };

function matches(ex, alert, instance) {
  if (String(alert.pluginid ?? alert.alertRef ?? "").split("-")[0] !== ex.pluginId) return false;
  if (ex.method && instance.method !== ex.method) return false;
  if (ex.uriRegex && !new RegExp(ex.uriRegex).test(instance.uri ?? "")) return false;
  return true;
}

/**
 * Gate sui rapporti di ZAP. Bloccante: avviso High con confidenza ≠ 0 (falso positivo dichiarato da ZAP) non coperto da
 * un'eccezione valida e non scaduta. Ritorna {@code problems}, {@code warnings} e {@code markdown}.
 */
export function gateZap(dir, exceptionsDoc, today = todayUtc()) {
  const problems = validateZapExceptions(exceptionsDoc, today);
  const warnings = [];
  const exceptions = problems.length ? [] : exceptionsDoc.exceptions;
  const active = exceptions.map((e) => ({ ...e, expired: e.expires < today, used: false }));
  active.filter((e) => e.expired).forEach((e) => warnings.push(`eccezione scaduta il ${e.expires} (plugin ${e.pluginId}, ${e.ref}): non copre più nulla`));

  const names = specNames(dir);
  if (!names.length) problems.push(`nessuna specifica in ${join(dir, "specs")}: lo scan non ha provato nulla`);
  const sections = [];
  for (const name of names) {
    const file = join(dir, `zap-${name}.json`);
    if (!existsSync(file)) {
      problems.push(`${name}: manca il rapporto ${file}`);
      continue;
    }
    let report;
    try {
      report = JSON.parse(readFileSync(file, "utf8"));
    } catch (e) {
      problems.push(`${name}: rapporto illeggibile (${e.message})`);
      continue;
    }
    if (!Array.isArray(report.site) || report.site.length === 0) {
      problems.push(`${name}: il rapporto non ha alcun sito (nessun URL importato dalla specifica?)`);
      continue;
    }
    const counts = { High: 0, Medium: 0, Low: 0, Info: 0 };
    const blocking = [];
    for (const site of report.site) {
      for (const alert of site.alerts ?? []) {
        counts[RISKS[Number(alert.riskcode)] ?? "Info"]++;
        if (String(alert.riskcode) !== "3" || String(alert.confidence) === "0") continue;
        const instances = alert.instances?.length ? alert.instances : [{ uri: "", method: "", param: "" }];
        for (const instance of instances) {
          const covering = active.filter((e) => !e.expired && matches(e, alert, instance));
          covering.forEach((e) => {
            e.used = true;
          });
          if (!covering.length) blocking.push({ pluginid: alert.pluginid, alert: alert.alert ?? alert.name, method: instance.method, uri: instance.uri, param: instance.param });
        }
      }
    }
    if (blocking.length) problems.push(`${name}: ${blocking.length} avvisi High non eccettuati`);
    sections.push({ name, counts, blocking });
  }
  active.filter((e) => !e.expired && !e.used).forEach((e) => warnings.push(`eccezione mai usata (plugin ${e.pluginId}, ${e.ref}): se il difetto è corretto, toglila`));

  const lines = [
    "### DAST (ZAP API scan)",
    "",
    "| Specifica | High | Medium | Low | Info | High bloccanti |",
    "|---|---|---|---|---|---|",
    ...sections.map((s) => `| ${s.name} | ${s.counts.High} | ${s.counts.Medium} | ${s.counts.Low} | ${s.counts.Info} | ${s.blocking.length} |`),
    "",
  ];
  for (const s of sections.filter((x) => x.blocking.length)) {
    lines.push(`**${s.name}: avvisi High bloccanti**`, "", "| Plugin | Avviso | Metodo | URI | Parametro |", "|---|---|---|---|---|");
    for (const b of s.blocking) lines.push(`| ${b.pluginid} | ${b.alert} | ${b.method} | \`${b.uri}\` | ${b.param ?? ""} |`);
    lines.push("");
  }
  if (problems.length) lines.push("Problemi:", ...problems.map((p) => `- ${p}`), "");
  if (warnings.length) lines.push("Avvisi:", ...warnings.map((w) => `- ${w}`), "");
  return { problems, warnings, markdown: lines.join("\n") };
}

// ================= riga di comando =================

function appendSummary(markdown) {
  if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, `${markdown}\n`);
}

const readJson = (file) => JSON.parse(readFileSync(file, "utf8"));

const USAGE = `Uso:
  security-dast.mjs prepare --out <cartella> [--api contracts/api] [--exclusions .dast/exclusions.json]
  security-dast.mjs baseline-check <file> [--today AAAA-MM-GG]
  security-dast.mjs fuzz-summary <cartella>
  security-dast.mjs zap-gate <cartella> --exceptions <file> [--today AAAA-MM-GG]`;

export function main(argv, io = { log: console.log, error: console.error }) {
  const [command, ...rest] = argv;
  const { positional, options } = parseArgs(rest);
  const today = options.today ?? todayUtc();
  if (options.today !== undefined && !isRealDate(options.today)) {
    io.error("--today vuole una data AAAA-MM-GG");
    return 1;
  }
  try {
    switch (command) {
      case "prepare": {
        if (!options.out) {
          io.error(USAGE);
          return 1;
        }
        const exclusionsFile = options.exclusions ?? ".dast/exclusions.json";
        const { files, errors } = prepareSpecs({
          apiDir: options.api ?? "contracts/api",
          outDir: options.out,
          exclusions: readJson(exclusionsFile),
        });
        for (const f of files) {
          io.log(`${basename(f.out)}: ${f.kept} operazioni da provare, ${f.removed.length} escluse${f.removed.length ? ` (${f.removed.join(", ")})` : ""}`);
        }
        if (errors.length) {
          errors.forEach((e) => io.error(`::error::${e}`));
          return 1;
        }
        io.log(`${files.length} specifiche pronte in ${options.out}`);
        return 0;
      }
      case "baseline-check": {
        if (!positional[0]) {
          io.error(USAGE);
          return 1;
        }
        const errors = checkBaseline(readJson(positional[0]), today);
        errors.forEach((e) => io.error(`::error::${e}`));
        if (!errors.length) {
          const n = readJson(positional[0]).entries.length;
          io.log(n ? `baseline valida: ${n} voci, tutte con scadenza, motivo e ticket` : "baseline valida: nessuna voce accettata");
        }
        return errors.length ? 1 : 0;
      }
      case "fuzz-summary": {
        if (!positional[0]) {
          io.error(USAGE);
          return 1;
        }
        const { problems, markdown } = summarizeFuzz(positional[0]);
        io.log(markdown);
        appendSummary(markdown);
        problems.forEach((p) => io.error(`::error::${p}`));
        return problems.length ? 1 : 0;
      }
      case "zap-gate": {
        if (!positional[0] || !options.exceptions) {
          io.error(USAGE);
          return 1;
        }
        const { problems, warnings, markdown } = gateZap(positional[0], readJson(options.exceptions), today);
        io.log(markdown);
        appendSummary(markdown);
        warnings.forEach((w) => io.error(`::warning::${w}`));
        problems.forEach((p) => io.error(`::error::${p}`));
        return problems.length ? 1 : 0;
      }
      default:
        io.error(USAGE);
        return 1;
    }
  } catch (e) {
    io.error(`::error::${e.message}`);
    return 1;
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  process.exit(main(process.argv.slice(2)));
}
