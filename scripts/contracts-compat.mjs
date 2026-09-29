// contracts-compat.mjs — compatibilità dei contratti evento con l'ultimo tag `v<numero>` (F2-EVT-01, ADR-028, M8.4c,
// docs/18 §3.3). "Controllo di compatibilità additiva contro l'ultimo tag in CI; nessuno schema registry" (ADR-028).
// Modulo di libreria usato da check-contracts.mjs, senza dipendenze npm (solo moduli integrati di Node).
//
// Idea: ogni `contracts/events/**/*.schema.json` si confronta col medesimo file nel tag. Un consumatore già in
// esecuzione o un evento trattenuto su un topic deve restare valido: i contratti si estendono, non si rompono
// (docs/05 §9). Un cambio incompatibile è una decisione: ADR, versione nuova `<nome>.v<n+1>.schema.json` e
// label "decisione" sulla PR (CLAUDE.md §6).
//
// Regole (kind: rimozione | restrizione | modifica | pii | allargamento; bloccano tutti tranne `allargamento`)
//   File
//     R1  file del tag assente ora                         rimozione   (con indizio "rinominato in" se un file nuovo ha lo stesso $id;
//                                                                        con indizio "versione superata" se ha x-lh-superseded-by, Q-346)
//     R2  `$id` diverso                                    rimozione
//     R3  file nuovo, anche `<nome>.v<n>.schema.json`      ammesso, elencato tra i nuovi
//     R4  JSON non valido (tag o versione corrente)        modifica
//   Nodo (ricorsione in properties, items → `a[]`, additionalProperties-schema → `a{}`)
//     R5  proprietà rimossa                                rimozione
//     R6  proprietà nuova non obbligatoria                 ammessa, anche con additionalProperties:false
//     R7  nome aggiunto a `required`                       restrizione
//     R8  nome tolto da `required`                         rimozione
//     R9  `type` (come insiemi; integer ⊂ number)          tolti membri → restrizione; aggiunti → modifica;
//                                                          introdotto → restrizione; tolto → modifica
//     R10 `enum`                                           valore tolto o enum introdotto → restrizione;
//                                                          valore aggiunto o enum tolto → allargamento (Q-139, Q-541)
//     R11 `const`                                          introdotto o cambiato → restrizione; tolto → allargamento
//     R12 `format`                                         introdotto → restrizione; cambiato → modifica; tolto → allargamento
//     R13 `pattern`                                        introdotto → restrizione; cambiato → modifica; tolto → allargamento
//     R14 minLength, minItems, minProperties, minimum, exclusiveMinimum
//                                                          introdotto o alzato → restrizione; abbassato o tolto → allargamento
//     R15 maxLength, maxItems, maxProperties, maximum, exclusiveMaximum
//                                                          introdotto o abbassato → restrizione; alzato o tolto → allargamento
//     R16 multipleOf (introdotto → restrizione; cambiato → modifica; tolto → allargamento), uniqueItems
//                                                          false → true restrizione; true → false allargamento
//     R17 additionalProperties                             aperto → false: restrizione ("schema chiuso");
//                                                          false → aperto o → schema tipizzato: pii (campi non dichiarati, senza
//                                                          x-lh-pii, entrerebbero nell'evento, ADR-032); schema da entrambi i lati:
//                                                          ricorsione; altro: modifica
//     R18 items                                            introdotto → restrizione; tolto → modifica; altrimenti ricorsione
//     R19 x-lh-pii da true a false                         pii (false → true lo copre già il controllo PII di check-contracts)
//     R20 annotazioni ignorate: title, description, $comment, examples, default, deprecated, readOnly, writeOnly,
//         $schema, $id annidato e ogni chiave `x-*` tranne x-lh-pii (es. x-lh-superseded-by)
//     R21 ogni altra parola chiave che cambia (oneOf, allOf, $ref, if, patternProperties…)   modifica
//     R22 schema booleano che cambia                       modifica
//   Riordinare `required`, `enum` o le chiavi di un oggetto non è mai un cambiamento.
//
// Confronto con il merge-base (Q-542). Oltre che con il tag, ogni schema si confronta con lo stesso file nel merge-base
// col ramo base (`origin/$GITHUB_BASE_REF`, altrimenti `origin/main`), come fa check-api. Serve a tre cose:
//   - una rottura già presente nel merge-base (entrata su `main` con una PR precedente, per esempio con la label
//     "decisione") non blocca di nuovo: è elencata come ereditata (`=`), a meno che la PR non la cambi ancora;
//   - una PR non può rompere ciò che è già su `main`, nemmeno se il tag non lo conosceva (proprietà o file aggiunti
//     dopo il tag, o una parola chiave già cambiata su `main`): ogni rottura del merge-base con la PR blocca;
//   - finché il tag non contiene `contracts/events/` (oggi `v0.6.0`, Q-540) il confronto con il merge-base è il solo
//     attivo, con un avviso: il gate non resta inerte.
// Con la label "decisione" sulla PR le rotture nuove sono riportate ma non bloccano (stessa regola di check-api).
//
// Limiti noti
//   - una proprietà nuova che uno schema aperto già ammetteva non viene segnalata;
//   - i sottoinsiemi di espressioni regolari non si calcolano: ogni cambio di `pattern` blocca;
//   - `$ref` e i combinatori non si interpretano: ogni cambio blocca (R21);
//   - il riferimento è il tag `v<numero>` raggiungibile più vicino a HEAD (`v0.7.0`, `v1.2.3`…; un tag come
//     `vendor-x` non conta, `v0.6.0-ux` sì); se non esiste, o non contiene `contracts/events/`, si confronta solo con
//     il merge-base (Q-540), e se anche quello manca (clone superficiale, ramo base assente) il confronto è saltato;
//   - senza merge-base le proprietà e i file aggiunti dopo il tag non sono protetti e le rotture già su `main` non si
//     riconoscono: il confronto è solo col tag (Q-542);
//   - una PR che ripristina il tag dopo una rottura accettata su `main` è a sua volta una restrizione rispetto a
//     `main` e vuole la label "decisione";
//   - R6 presuppone che la validazione in consumo (M8.10) tolleri i campi sconosciuti anche negli schemi chiusi
//     (docs/05 §9): un consumer che validasse col proprio schema chiuso rifiuterebbe la proprietà nuova durante un
//     aggiornamento progressivo (regola 14).
//
// Uso: node scripts/check-contracts.mjs [--baseline=<ref>] [--base=<ref>] [--pr-labels=a,b]
import { execFileSync } from "node:child_process";
import { existsSync, readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";

const EVENTS_DIR = "contracts/events";
const SUFFIX = ".schema.json";
const ROOT_LABEL = "(radice)";
const KINDS = ["rimozione", "restrizione", "modifica", "pii", "allargamento"];

const ANNOTATIONS = new Set(["title", "description", "$comment", "examples", "default", "deprecated", "readOnly", "writeOnly", "$schema", "$id"]);
const LOWER_BOUNDS = ["minLength", "minItems", "minProperties", "minimum", "exclusiveMinimum"];
const UPPER_BOUNDS = ["maxLength", "maxItems", "maxProperties", "maximum", "exclusiveMaximum"];
const HANDLED = new Set([
  "type", "enum", "const", "format", "pattern", "multipleOf", "uniqueItems",
  "required", "properties", "additionalProperties", "items", "x-lh-pii",
  ...LOWER_BOUNDS, ...UPPER_BOUNDS,
]);
const isIgnored = (key) => ANNOTATIONS.has(key) || (key.startsWith("x-") && key !== "x-lh-pii");

// ================= confronto tra due schemi (puro) =================

const isObj = (v) => v !== null && typeof v === "object" && !Array.isArray(v);
const has = (o, k) => Object.hasOwn(o, k);

/** Forma canonica stabile (chiavi ordinate): due valori sono uguali se non cambia altro che l'ordine delle chiavi. */
function canon(v) {
  if (Array.isArray(v)) return `[${v.map(canon).join(",")}]`;
  if (isObj(v)) return `{${Object.keys(v).sort().map((k) => `${JSON.stringify(k)}:${canon(v[k])}`).join(",")}}`;
  return JSON.stringify(v) ?? "undefined";
}
const show = (v) => (typeof v === "string" ? v : canon(v));
const asList = (t) => (Array.isArray(t) ? t : [t]);
const showType = (t) => (Array.isArray(t) ? `[${t.join(",")}]` : String(t));
/** `integer` è un sottoinsieme di `number`: un insieme che contiene `number` copre anche `integer`. */
const covers = (set, t) => set.includes(t) || (t === "integer" && set.includes("number"));
const openAdditional = (v) => v === undefined || v === true;

/**
 * Differenze tra due versioni dello stesso schema, già lette. Ritorna `{ path, kind, message }[]`; `path` è il nodo
 * in cui cambia la parola chiave (`(radice)`, `a.b`, `a[]`, `a{}`). Vedi la tabella delle regole in testa al file.
 */
export function schemaChanges(base, current) {
  const out = [];
  compareNode(base, current, "", out);
  return out;
}

/** `allargamento` è l'unico kind che non blocca (Q-139, Q-541). */
export const isBlocking = (kind) => kind !== "allargamento";

function compareNode(base, cur, path, out) {
  const b = base === true ? {} : base; // `true` equivale a `{}`
  const c = cur === true ? {} : cur;
  if (!isObj(b) || !isObj(c)) {
    if (canon(b) !== canon(c)) {
      const boolean = typeof b === "boolean" || typeof c === "boolean";
      out.push({ path: path || ROOT_LABEL, kind: "modifica", message: boolean ? "schema booleano cambiato" : "schema cambiato: confronto automatico non supportato" });
    }
    return;
  }
  const add = (kind, message) => out.push({ path: path || ROOT_LABEL, kind, message });

  // R9 type
  if (has(b, "type") || has(c, "type")) {
    if (!has(b, "type")) add("restrizione", `tipo introdotto (${showType(c.type)})`);
    else if (!has(c, "type")) add("modifica", "vincolo di tipo tolto");
    else {
      const before = asList(b.type);
      const after = asList(c.type);
      if (before.some((t) => !covers(after, t))) add("restrizione", `tipo ristretto da ${showType(b.type)} a ${showType(c.type)}`);
      if (after.some((t) => !covers(before, t))) add("modifica", `tipo allargato da ${showType(b.type)} a ${showType(c.type)}`);
    }
  }

  // R10 enum
  if (has(b, "enum") || has(c, "enum")) {
    if (!has(b, "enum")) add("restrizione", "enum introdotto");
    else if (!has(c, "enum")) add("allargamento", "enum tolto"); // SPEC-GAP: Q-541
    else {
      const before = new Map(asList(b.enum).map((v) => [canon(v), v]));
      const after = new Map(asList(c.enum).map((v) => [canon(v), v]));
      for (const [key, v] of before) if (!after.has(key)) add("restrizione", `valore «${show(v)}» tolto dall'enum`);
      for (const [key, v] of after) if (!before.has(key)) add("allargamento", `valore «${show(v)}» aggiunto all'enum`); // SPEC-GAP: Q-541
    }
  }

  // R11 const
  if (has(b, "const") || has(c, "const")) {
    if (!has(b, "const")) add("restrizione", `const introdotto (${show(c.const)})`);
    else if (!has(c, "const")) add("allargamento", "const tolto"); // SPEC-GAP: Q-541
    else if (canon(b.const) !== canon(c.const)) add("restrizione", `const cambiato da ${show(b.const)} a ${show(c.const)}`);
  }

  // R12 format, R13 pattern
  for (const [kw, changed] of [["format", (x, y) => `format cambiato da ${x} a ${y}`], ["pattern", () => "pattern cambiato"]]) {
    if (!has(b, kw) && !has(c, kw)) continue;
    if (!has(b, kw)) add("restrizione", `${kw} introdotto (${show(c[kw])})`);
    else if (!has(c, kw)) add("allargamento", `${kw} tolto`); // SPEC-GAP: Q-541
    else if (canon(b[kw]) !== canon(c[kw])) add("modifica", changed(show(b[kw]), show(c[kw])));
  }

  // R14 / R15 limiti
  for (const kw of LOWER_BOUNDS) compareBound(add, kw, b[kw], c[kw], true);
  for (const kw of UPPER_BOUNDS) compareBound(add, kw, b[kw], c[kw], false);

  // R16 multipleOf, uniqueItems
  if (has(b, "multipleOf") || has(c, "multipleOf")) {
    if (!has(b, "multipleOf")) add("restrizione", `multipleOf introdotto (${show(c.multipleOf)})`);
    else if (!has(c, "multipleOf")) add("allargamento", "multipleOf tolto"); // SPEC-GAP: Q-541
    else if (canon(b.multipleOf) !== canon(c.multipleOf)) add("modifica", `multipleOf cambiato da ${show(b.multipleOf)} a ${show(c.multipleOf)}`);
  }
  if (b.uniqueItems !== true && c.uniqueItems === true) add("restrizione", "uniqueItems introdotto");
  if (b.uniqueItems === true && c.uniqueItems !== true) add("allargamento", "uniqueItems tolto"); // SPEC-GAP: Q-541

  // R7 / R8 required
  const before = new Set(Array.isArray(b.required) ? b.required : []);
  const after = new Set(Array.isArray(c.required) ? c.required : []);
  for (const name of after) if (!before.has(name)) add("restrizione", `campo «${name}» reso obbligatorio`);
  for (const name of before) if (!after.has(name)) add("rimozione", `campo «${name}» non più obbligatorio`);

  // R5 / R6 proprietà, con ricorsione
  const baseProps = isObj(b.properties) ? b.properties : {};
  const curProps = isObj(c.properties) ? c.properties : {};
  for (const key of Object.keys(baseProps)) {
    if (!has(curProps, key)) add("rimozione", `proprietà «${key}» rimossa`);
    else compareNode(baseProps[key], curProps[key], path ? `${path}.${key}` : key, out);
  }

  // R17 additionalProperties
  const ba = b.additionalProperties;
  const ca = c.additionalProperties;
  if (openAdditional(ba) && openAdditional(ca)) {
    // invariato
  } else if (openAdditional(ba) && ca === false) {
    add("restrizione", "schema chiuso");
  } else if (ba === false && openAdditional(ca)) {
    add("pii", "schema riaperto: campi non dichiarati, senza x-lh-pii, potrebbero entrare nell'evento (ADR-032)");
  } else if (ba === false && isObj(ca)) {
    add("pii", "schema riaperto con additionalProperties tipizzato: campi non dichiarati, senza x-lh-pii, potrebbero entrare nell'evento (ADR-032)");
  } else if (isObj(ba) && isObj(ca)) {
    compareNode(ba, ca, `${path}{}`, out);
  } else if (canon(ba) !== canon(ca)) {
    add("modifica", "additionalProperties cambiato");
  }

  // R18 items
  if (has(b, "items") || has(c, "items")) {
    if (!has(b, "items")) add("restrizione", "items introdotto");
    else if (!has(c, "items")) add("modifica", "items tolto");
    else compareNode(b.items, c.items, `${path}[]`, out);
  }

  // R19 x-lh-pii
  if (b["x-lh-pii"] === true && c["x-lh-pii"] !== true) add("pii", "x-lh-pii declassato da true a false (ADR-032)");

  // R21 tutto il resto (R20: le annotazioni non contano)
  const others = [...new Set([...Object.keys(b), ...Object.keys(c)])].filter((k) => !HANDLED.has(k) && !isIgnored(k)).sort();
  for (const key of others) {
    if (canon(b[key]) !== canon(c[key])) add("modifica", `parola chiave «${key}» cambiata: confronto automatico non supportato`);
  }
}

function compareBound(add, kw, before, after, lower) {
  if (before === undefined && after === undefined) return;
  if (before !== undefined && after !== undefined && canon(before) === canon(after)) return;
  if (before === undefined) return add("restrizione", `${kw} introdotto (${show(after)})`);
  if (after === undefined) return add("allargamento", `${kw} tolto`); // SPEC-GAP: Q-541
  if (typeof before === "number" && typeof after === "number") {
    const stricter = lower ? after > before : after < before;
    return add(stricter ? "restrizione" : "allargamento", `${kw} da ${before} a ${after}`);
  }
  add("modifica", `${kw} cambiato da ${show(before)} a ${show(after)}`);
}

// ================= confronto tra due insiemi di file (puro) =================

const FINDING_KIND = new RegExp(`\\((${KINDS.join("|")})\\)$`);
/** Il kind di un finding prodotto da compareTrees (è l'ultimo elemento tra parentesi). */
export const kindOf = (finding) => FINDING_KIND.exec(finding)?.[1] ?? "modifica";

/**
 * Confronta due insiemi di schemi: `Map<percorso relativo a contracts/events, testo>`.
 * Ritorna `{ compared, added, findings }`; ogni finding è `file: percorso: messaggio (kind)` (senza percorso se
 * riguarda il file intero).
 */
export function compareTrees(baseFiles, currentFiles) {
  const findings = [];
  const parse = (text) => {
    try {
      return { value: JSON.parse(text) };
    } catch {
      return { error: true };
    }
  };
  const added = [...currentFiles.keys()].filter((f) => !baseFiles.has(f)).sort();
  const addedIds = new Map();
  for (const file of added) {
    const parsed = parse(currentFiles.get(file));
    if (parsed.error) findings.push(`${file}: JSON non valido nella versione corrente (modifica)`);
    else if (typeof parsed.value?.$id === "string") addedIds.set(parsed.value.$id, file);
  }

  let compared = 0;
  for (const file of [...baseFiles.keys()].sort()) {
    const base = parse(baseFiles.get(file));
    if (!currentFiles.has(file)) {
      let message = "schema rimosso o rinominato";
      const renamed = addedIds.get(base.value?.$id);
      if (renamed) message += ` — rinominato in ${renamed}`;
      if (base.value?.["x-lh-superseded-by"]) {
        message += " — versione superata: la rimozione chiude la doppia lettura (Q-346) ed è una decisione";
      }
      findings.push(`${file}: ${message} (rimozione)`);
      continue;
    }
    compared++;
    const cur = parse(currentFiles.get(file));
    if (base.error) findings.push(`${file}: JSON non valido nel riferimento di confronto (modifica)`);
    if (cur.error) findings.push(`${file}: JSON non valido nella versione corrente (modifica)`);
    if (base.error || cur.error) continue;
    if (base.value?.$id !== cur.value?.$id) {
      findings.push(`${file}: $id cambiato da ${base.value?.$id} a ${cur.value?.$id} (rimozione)`);
    }
    for (const change of schemaChanges(base.value, cur.value)) {
      findings.push(`${file}: ${change.path}: ${change.message} (${change.kind})`);
    }
  }
  return { compared, added, findings };
}

// ================= git e file =================

function git(root, args) {
  return execFileSync("git", ["-C", root, ...args], { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"], maxBuffer: 64 * 1024 * 1024 });
}
function tryGit(root, args) {
  try {
    return git(root, args).trim();
  } catch {
    return null;
  }
}
/** SHA del commit a cui punta `ref`, o null. Un riferimento che comincia con "-" non è mai un riferimento. */
function resolveCommit(root, ref) {
  if (typeof ref !== "string" || ref === "" || ref.startsWith("-")) return null;
  return tryGit(root, ["rev-parse", "--verify", "--quiet", `${ref}^{commit}`]) || null;
}
const isSchemaPath = (rel) => rel.endsWith(SUFFIX) && !rel.split("/").includes("examples");

/** Tutti i `*.schema.json` sotto `<root>/contracts/events`, `examples/` escluso. Vuota se la cartella non c'è. */
export function schemasOnDisk(root) {
  const files = new Map();
  const base = join(root, EVENTS_DIR);
  if (!existsSync(base)) return files;
  const walk = (dir, prefix) => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      if (entry.isDirectory()) {
        if (entry.name !== "examples") walk(join(dir, entry.name), `${prefix}${entry.name}/`);
      } else if (entry.name.endsWith(SUFFIX)) {
        files.set(`${prefix}${entry.name}`, readFileSync(join(dir, entry.name), "utf8"));
      }
    }
  };
  walk(base, "");
  return files;
}

/** Gli stessi file come erano al commit `ref`. Vuota se `contracts/events/` non c'era; lancia se `ref` non si risolve. */
export function schemasAtRef(root, ref) {
  const sha = resolveCommit(root, ref);
  if (!sha) throw new Error(`riferimento git non risolvibile: ${ref}`);
  const names = git(root, ["ls-tree", "-r", "-z", "--name-only", sha, "--", EVENTS_DIR]).split("\0").filter(Boolean);
  const files = new Map();
  for (const name of names) {
    const rel = name.slice(EVENTS_DIR.length + 1);
    if (!name.startsWith(`${EVENTS_DIR}/`) || !isSchemaPath(rel)) continue;
    files.set(rel, git(root, ["show", `${sha}:./${name}`]));
  }
  return files;
}

/** Il tag `v<numero>…` più vicino raggiungibile da HEAD, o null. Un tag come `vendor-x` non è un rilascio. */
export function latestTag(root) {
  return tryGit(root, ["describe", "--tags", "--abbrev=0", "--match", "v[0-9]*", "HEAD"]) || null;
}

/** `--baseline=<ref>`, `--base=<ref>`, `--pr-labels=a,b`; il resto si ignora. */
export function parseArgs(argv) {
  const value = (name) => {
    const arg = [...argv].reverse().find((a) => a.startsWith(`--${name}=`));
    const v = arg ? arg.slice(name.length + 3).trim() : "";
    return v === "" ? undefined : v;
  };
  const labels = (value("pr-labels") ?? "").split(",").map((s) => s.trim()).filter(Boolean);
  return { baseline: value("baseline"), base: value("base"), labels };
}

// ================= esecuzione =================

/**
 * Confronta gli schemi del working tree con l'ultimo tag `v<numero>` (o con `baseline`) e col merge-base col ramo base.
 * Non lancia mai. status: skipped | ok | broken | accepted | error; exitCode: 0 | 1 | 2.
 * code: "" | no-git | no-tag | empty-tag (saltato) | merge-base-only (il tag non c'è o non ha contratti, si confronta
 * solo col merge-base, Q-540).
 */
export function runCompat({ root, baseline, base, labels = [], env = process.env }) {
  const result = {
    status: "skipped", exitCode: 0, code: "", baseline: null, baseRef: null, mergeBase: null, target: "", reason: "",
    compared: 0, added: [], widenings: [], inherited: [], blocking: [], notes: [],
  };
  const fail = (reason) => Object.assign(result, { status: "error", exitCode: 2, code: "error", reason });
  try {
    if (tryGit(root, ["rev-parse", "--is-inside-work-tree"]) !== "true") {
      return Object.assign(result, { code: "no-git", reason: "la cartella non è un repository git: confronto con l'ultimo tag saltato" });
    }

    // Il tag (o --baseline): può non esistere o non avere contratti.
    const ref = baseline ?? latestTag(root);
    let tagFiles = new Map();
    if (ref) {
      const sha = resolveCommit(root, ref);
      if (!sha) return fail(`riferimento --baseline non risolvibile: ${ref}`);
      result.baseline = { ref, sha };
      tagFiles = schemasAtRef(root, sha);
    }

    // Il merge-base col ramo base (Q-542).
    result.baseRef = base ?? (env.GITHUB_BASE_REF ? `origin/${env.GITHUB_BASE_REF}` : "origin/main");
    const mb = typeof result.baseRef === "string" && !result.baseRef.startsWith("-") ? tryGit(root, ["merge-base", result.baseRef, "HEAD"]) : null;
    result.mergeBase = mb || null;
    const mbFiles = mb ? schemasAtRef(root, mb) : new Map();
    const current = schemasOnDisk(root);
    // Le rotture nuove della PR rispetto al merge-base: bloccano anche se il tag non conosceva lo schema o la proprietà.
    const own = compareTrees(mbFiles, current);
    const ownBlocking = own.findings.filter((f) => isBlocking(kindOf(f)));

    if (tagFiles.size > 0) {
      const cmp = compareTrees(tagFiles, current);
      result.compared = cmp.compared;
      result.added = cmp.added;
      // SPEC-GAP: Q-541 — gli allargamenti di valore si segnalano ma non bloccano.
      result.widenings = cmp.findings.filter((f) => !isBlocking(kindOf(f)));
      const candidates = cmp.findings.filter((f) => isBlocking(kindOf(f)));
      result.target = result.baseline.ref;
      if (mb) {
        // SPEC-GAP: Q-542 — una rottura già presente nel merge-base è entrata con una PR precedente e non blocca di
        // nuovo, se la PR non la cambia ancora (stesso testo tra le rotture proprie) e non ne aggiunge altre.
        result.target = `${result.baseline.ref} e a ${result.baseRef}`;
        const already = new Set(compareTrees(tagFiles, mbFiles).findings);
        const ownSet = new Set(ownBlocking);
        for (const finding of candidates) (already.has(finding) && !ownSet.has(finding) ? result.inherited : result.blocking).push(finding);
        for (const finding of ownBlocking) if (!result.blocking.includes(finding)) result.blocking.push(finding);
      } else {
        result.notes.push(
          `merge-base con ${result.baseRef} non trovato: le rotture già presenti sul ramo base non sono riconosciute e ` +
          "le proprietà o i file aggiunti dopo il tag non sono protetti (Q-542)",
        );
        result.blocking = candidates;
      }
    } else {
      // SPEC-GAP: Q-540 — v0.6.0 è un tag storico del bundle UX, senza contracts/events/: finché non esiste un tag con i
      // contratti si confronta con il merge-base col ramo base (stessa semantica di check-api).
      const why = result.baseline
        ? `il tag ${result.baseline.ref} non contiene contracts/events/`
        : "nessun tag v<numero> raggiungibile da HEAD (in CI serve fetch-depth: 0)";
      if (mbFiles.size === 0) {
        return Object.assign(result, result.baseline
          ? { code: "empty-tag", reason: "il tag non contiene contracts/events/, confronto saltato (Q-540)" }
          : { code: "no-tag", reason: "nessun tag v<numero> raggiungibile da HEAD, confronto saltato (in CI serve fetch-depth: 0; Q-540)" });
      }
      result.code = "merge-base-only";
      result.target = result.baseRef;
      result.compared = own.compared;
      result.added = own.added;
      result.widenings = own.findings.filter((f) => !isBlocking(kindOf(f)));
      result.blocking = ownBlocking;
      result.notes.push(`${why}: confronto solo con il merge-base con ${result.baseRef} (Q-540)`);
    }

    if (result.blocking.length === 0) {
      result.status = "ok";
      result.reason = `nessuna modifica incompatibile rispetto a ${result.target}`;
    } else if (labels.includes("decisione")) {
      result.status = "accepted";
      result.reason = `${result.blocking.length} modifiche incompatibili rispetto a ${result.target}, accettate con la label "decisione"`;
    } else {
      result.status = "broken";
      result.exitCode = 1;
      result.reason = `${result.blocking.length} modifiche incompatibili rispetto a ${result.target}`;
    }
    return result;
  } catch (e) {
    return fail(`errore imprevisto: ${e.message}`);
  }
}

/** Righe da stampare: `out` su stdout, `err` su stderr. Con `github` aggiunge le annotazioni del runner. */
export function formatReport(result, { github = false } = {}) {
  const out = [];
  const err = [];
  const short = (sha) => String(sha).slice(0, 12);

  if (result.status === "error") {
    err.push(`✗ check-contracts: ${result.reason}`);
    return { out, err };
  }
  if (result.status === "skipped") {
    const line = result.baseline
      ? `check-contracts: compatibilità con ${result.baseline.ref} (${short(result.baseline.sha)}): ${result.reason}.`
      : `check-contracts: ${result.reason}.`;
    out.push(line);
    if (github) out.push(`::${result.code === "empty-tag" ? "notice" : "warning"} title=check-contracts::${line}`);
    return { out, err };
  }

  const mbOnly = result.code === "merge-base-only";
  const target = result.target || result.baseline?.ref;
  const at = result.mergeBase ? `merge-base con ${result.baseRef} ${short(result.mergeBase)}` : "";
  const head = mbOnly
    ? `compatibilità con ${result.baseRef} (${short(result.mergeBase)})`
    : `compatibilità con ${result.baseline.ref} (${short(result.baseline.sha)})${at ? `, ${at}` : ""}`;
  out.push(`check-contracts: ${head}: ${result.compared} schemi confrontati, ${result.added.length} nuovi.`);
  for (const note of result.notes) out.push(`  (${note})`);
  // Con il solo merge-base il tag non ha contratti: in CI l'avviso è visibile nella pagina del job (Q-540).
  if (github && mbOnly) out.push(`::${result.baseline ? "notice" : "warning"} title=check-contracts::${result.notes[0]}`);
  for (const file of result.added) out.push(`  + ${file}: schema nuovo`);
  for (const finding of result.widenings) out.push(`  ⚠ ${finding} — non blocca (Q-139, Q-541)`);
  for (const finding of result.inherited) out.push(`  = ${finding} — già presente su ${result.baseRef}, non blocca (Q-542)`);

  if (result.status === "ok") {
    out.push(`✓ check-contracts: nessuna modifica incompatibile rispetto a ${target}.`);
    return { out, err };
  }
  const n = result.blocking.length;
  err.push(`✗ check-contracts: ${n} ${n === 1 ? "modifica incompatibile" : "modifiche incompatibili"} rispetto a ${target}:`);
  for (const finding of result.blocking) err.push(`    - ${finding}`);
  if (result.status === "accepted") {
    err.push('  (label "decisione" presente: segnalate ma non bloccanti)');
  } else {
    err.push("  → i contratti evento si estendono, non si rompono: proprietà facoltative o <nome>.v<n+1>.schema.json (docs/05 §9). " +
      'Un cambio incompatibile è una decisione: ADR e label "decisione" sulla PR.');
  }
  return { out, err };
}
