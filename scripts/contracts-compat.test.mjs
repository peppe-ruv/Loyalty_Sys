// Test di contracts-compat.mjs (M8.4c, F2-EVT-01): node --test scripts/contracts-compat.test.mjs
import test, { after } from "node:test";
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import {
  compareTrees, formatReport, isBlocking, kindOf, latestTag, parseArgs, runCompat, schemaChanges, schemasAtRef, schemasOnDisk,
} from "./contracts-compat.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const clone = (o) => structuredClone(o);

/** Schema realistico, sul modello di fact.wallet.points.earned e fact.member.*: ogni campo dichiara x-lh-pii (ADR-032). */
const BASE = {
  $schema: "https://json-schema.org/draft/2020-12/schema",
  $id: "urn:loyaltyhub:schema:fact.wallet.points.earned:1",
  title: "fact.wallet.points.earned — data",
  type: "object",
  required: ["memberId", "status", "amount"],
  properties: {
    memberId: { type: "string", pattern: "^MBR-[0-9]{6}$", "x-lh-pii": false },
    status: { enum: ["ACTIVE", "SUSPENDED", "CLOSED"], "x-lh-pii": false },
    amount: { type: "integer", minimum: 0, "x-lh-pii": false },
    code: { type: "string", minLength: 3, maxLength: 12, "x-lh-pii": false },
    when: { type: "string", format: "date-time", "x-lh-pii": false },
    note: { type: ["string", "null"], "x-lh-pii": true },
    lot: {
      type: "object",
      required: ["id"],
      properties: { id: { type: "string", "x-lh-pii": false }, x: { type: "integer", "x-lh-pii": false } },
      additionalProperties: true,
      "x-lh-pii": false,
    },
    labels: {
      type: "array",
      maxItems: 20,
      items: {
        type: "object",
        required: ["code"],
        properties: { code: { type: "string", "x-lh-pii": false }, x: { type: "string", "x-lh-pii": false } },
        "x-lh-pii": false,
      },
      "x-lh-pii": false,
    },
    attributes: { type: "object", additionalProperties: { type: "string", "x-lh-pii": false }, "x-lh-pii": false },
  },
  additionalProperties: true,
};
const CLOSED = { ...clone(BASE), additionalProperties: false };

/** Copia di `from` con una modifica applicata. */
const mut = (fn, from = BASE) => {
  const c = clone(from);
  fn(c);
  return c;
};
/** Differenze tra `from` e la sua copia modificata da `fn`. */
const changes = (fn, from = BASE) => schemaChanges(from, mut(fn, from));
const dump = (list) => JSON.stringify(list, null, 1);
/** C'è un cambiamento del kind dato il cui messaggio (e, se indicato, il percorso) contiene il testo. */
const has = (list, kind, text, path) =>
  list.some((c) => c.kind === kind && c.message.includes(text) && (path === undefined || c.path === path));
const only = (list, kind, text, path) => {
  assert.ok(has(list, kind, text, path), `atteso ${kind} «${text}»${path ? ` in ${path}` : ""}:\n${dump(list)}`);
  assert.equal(list.length, 1, dump(list));
};
const tree = (obj) => new Map(Object.entries(obj).map(([k, v]) => [k, typeof v === "string" ? v : JSON.stringify(v)]));

// ================= confronto tra schemi (regole R5–R22) =================

test("T01: identico → nessuna differenza", () => {
  assert.deepEqual(schemaChanges(BASE, clone(BASE)), []);
  assert.deepEqual(schemaChanges(CLOSED, clone(CLOSED)), []);
});

test("T02 (R20): solo annotazioni → nessuna differenza", () => {
  const cur = mut((s) => {
    s.title = "altro titolo";
    s.description = "descrizione";
    s.$comment = "nota";
    s.examples = [{ a: 1 }];
    s.default = {};
    s.deprecated = true;
    s.readOnly = true;
    s.writeOnly = false;
    s.$schema = "https://json-schema.org/draft/2019-09/schema";
    s["x-lh-superseded-by"] = "urn:loyaltyhub:schema:fact.wallet.points.earned:2";
    s["x-lh-nota"] = 1;
    s.properties.lot.$id = "urn:nested";
    s.properties.code.description = "il codice";
    s.properties.code["x-altro"] = { qualcosa: true };
    s.properties.labels.items.title = "etichetta";
  });
  assert.deepEqual(schemaChanges(BASE, cur), []);
});

test("T03: riordinare chiavi, enum e required non è un cambiamento", () => {
  const reorder = (v) => {
    if (Array.isArray(v)) return v.map(reorder);
    if (v && typeof v === "object") return Object.fromEntries(Object.keys(v).reverse().map((k) => [k, reorder(v[k])]));
    return v;
  };
  const cur = reorder(BASE);
  cur.required.reverse();
  cur.properties.status.enum.reverse();
  assert.deepEqual(Object.keys(cur), Object.keys(BASE).reverse());
  assert.deepEqual(schemaChanges(BASE, cur), []);
  // enum di oggetti: uguaglianza profonda insensibile all'ordine delle chiavi
  const objBase = { enum: [{ a: 1, b: 2 }] };
  assert.deepEqual(schemaChanges(objBase, { enum: [{ b: 2, a: 1 }] }), []);
});

test("T04 (R6): proprietà facoltativa nuova (radice, annidata, in items, schema chiuso) → ammessa", () => {
  assert.deepEqual(changes((s) => { s.properties.extra = { type: "string", "x-lh-pii": false }; }), []);
  assert.deepEqual(changes((s) => { s.properties.lot.properties.z = { type: "string", "x-lh-pii": false }; }), []);
  assert.deepEqual(changes((s) => { s.properties.labels.items.properties.z = { type: "string", "x-lh-pii": false }; }), []);
  assert.deepEqual(changes((s) => { s.properties.extra = { type: "string", "x-lh-pii": false }; }, CLOSED), []);
  assert.deepEqual(changes((s) => { s.properties.subjectRef = { type: "string", "x-lh-pii": false }; }, CLOSED), []);
});

test("T05 (R5): proprietà rimossa, con il percorso giusto", () => {
  only(changes((s) => { delete s.properties.code; }), "rimozione", "proprietà «code» rimossa", "(radice)");
  only(changes((s) => { delete s.properties.lot.properties.x; }), "rimozione", "proprietà «x» rimossa", "lot");
  only(changes((s) => { delete s.properties.labels.items.properties.x; }), "rimozione", "proprietà «x» rimossa", "labels[]");
  const noProps = changes((s) => { delete s.properties; });
  assert.equal(noProps.length, Object.keys(BASE.properties).length, dump(noProps));
  assert.ok(noProps.every((c) => c.kind === "rimozione" && c.path === "(radice)"), dump(noProps));
});

test("T06 (R7): campo reso obbligatorio → restrizione", () => {
  const added = changes((s) => {
    s.properties.extra = { type: "string", "x-lh-pii": false };
    s.required.push("extra");
  });
  only(added, "restrizione", "campo «extra» reso obbligatorio", "(radice)");
  only(changes((s) => { s.required.push("code"); }), "restrizione", "campo «code» reso obbligatorio", "(radice)");
  only(changes((s) => { s.properties.lot.required.push("x"); }), "restrizione", "campo «x» reso obbligatorio", "lot");
  assert.ok(isBlocking("restrizione"));
});

test("T07 (R8): campo non più obbligatorio → rimozione", () => {
  only(changes((s) => { s.required = ["memberId", "status"]; }), "rimozione", "campo «amount» non più obbligatorio", "(radice)");
  only(changes((s) => { s.properties.lot.required = []; }), "rimozione", "campo «id» non più obbligatorio", "lot");
  assert.ok(isBlocking("rimozione"));
});

test("T08 (R9): tipo ristretto o allargato, come insiemi", () => {
  // integer → number allarga (un produttore potrebbe emettere 1.5)
  const widen = changes((s) => { s.properties.amount.type = "number"; });
  only(widen, "modifica", "tipo allargato da integer a number", "amount");
  // number → integer restringe
  const number = mut((s) => { s.properties.amount.type = "number"; });
  only(schemaChanges(number, BASE), "restrizione", "tipo ristretto da number a integer", "amount");
  // [string,null] → string restringe (null non c'è più)
  only(changes((s) => { s.properties.note.type = "string"; }), "restrizione", "tipo ristretto da [string,null] a string", "note");
  // string → [string,null] allarga (anche a null)
  only(changes((s) => { s.properties.code.type = ["string", "null"]; }), "modifica", "tipo allargato da string a [string,null]", "code");
  // l'ordine dei membri non conta
  assert.deepEqual(changes((s) => { s.properties.note.type = ["null", "string"]; }), []);
  // tipo che cambia del tutto: restringe e allarga insieme
  const swapped = changes((s) => { s.properties.code.type = "integer"; });
  assert.ok(has(swapped, "restrizione", "tipo ristretto", "code") && has(swapped, "modifica", "tipo allargato", "code"), dump(swapped));
});

test("T09 (R9): tipo introdotto → restrizione; tipo tolto → modifica", () => {
  const untyped = mut((s) => { delete s.properties.amount.type; });
  only(schemaChanges(untyped, BASE), "restrizione", "tipo introdotto", "amount");
  only(changes((s) => { delete s.properties.amount.type; }), "modifica", "vincolo di tipo tolto", "amount");
});

test("T10 (R10): enum — valore tolto/introdotto restringe; valore aggiunto/enum tolto allarga (Q-139, Q-541)", () => {
  only(changes((s) => { s.properties.status.enum = ["ACTIVE", "SUSPENDED"]; }), "restrizione", "valore «CLOSED» tolto dall'enum", "status");

  const added = changes((s) => { s.properties.status.enum.push("INACTIVE"); });
  only(added, "allargamento", "valore «INACTIVE» aggiunto all'enum", "status");
  assert.equal(isBlocking(added[0].kind), false);

  const noEnum = mut((s) => { delete s.properties.status.enum; s.properties.status.type = "string"; });
  const introduced = schemaChanges(noEnum, BASE);
  assert.ok(has(introduced, "restrizione", "enum introdotto", "status"), dump(introduced));

  const removed = changes((s) => { delete s.properties.status.enum; });
  only(removed, "allargamento", "enum tolto", "status");
  assert.equal(isBlocking(removed[0].kind), false);
  assert.equal(isBlocking("pii"), true);
  assert.equal(isBlocking("modifica"), true);
});

test("T11 (R11): const introdotto o cambiato → restrizione; tolto → allargamento", () => {
  const withConst = mut((s) => { s.properties.code.const = "A"; });
  only(schemaChanges(BASE, withConst), "restrizione", "const introdotto", "code");
  only(schemaChanges(withConst, mut((s) => { s.properties.code.const = "B"; }, withConst)), "restrizione", "const cambiato da A a B", "code");
  only(schemaChanges(withConst, mut((s) => { delete s.properties.code.const; }, withConst)), "allargamento", "const tolto", "code");
});

test("T12 (R12): format introdotto → restrizione; cambiato → modifica; tolto → allargamento", () => {
  only(changes((s) => { s.properties.code.format = "uuid"; }), "restrizione", "format introdotto", "code");
  only(changes((s) => { s.properties.when.format = "date"; }), "modifica", "format cambiato da date-time a date", "when");
  only(changes((s) => { delete s.properties.when.format; }), "allargamento", "format tolto", "when");
});

test("T13 (R13): pattern introdotto → restrizione; cambiato → modifica; tolto → allargamento", () => {
  only(changes((s) => { s.properties.code.pattern = "^[A-Z]+$"; }), "restrizione", "pattern introdotto", "code");
  only(changes((s) => { s.properties.memberId.pattern = "^MBR-[0-9]{8}$"; }), "modifica", "pattern cambiato", "memberId");
  only(changes((s) => { delete s.properties.memberId.pattern; }), "allargamento", "pattern tolto", "memberId");
});

test("T14 (R14): limiti inferiori", () => {
  only(changes((s) => { s.properties.amount.minimum = 1; }), "restrizione", "minimum da 0 a 1", "amount");
  only(changes((s) => { s.properties.amount.minimum = -5; }), "allargamento", "minimum da 0 a -5", "amount");
  only(changes((s) => { delete s.properties.amount.minimum; }), "allargamento", "minimum tolto", "amount");
  only(changes((s) => { s.properties.when.minLength = 10; }), "restrizione", "minLength introdotto", "when");
  only(changes((s) => { s.properties.code.minLength = 5; }), "restrizione", "minLength da 3 a 5", "code");
  only(changes((s) => { s.properties.code.minLength = 1; }), "allargamento", "minLength da 3 a 1", "code");
  only(changes((s) => { s.properties.labels.minItems = 1; }), "restrizione", "minItems introdotto", "labels");
  only(changes((s) => { s.properties.amount.exclusiveMinimum = 0; }), "restrizione", "exclusiveMinimum introdotto", "amount");
  only(changes((s) => { s.properties.lot.minProperties = 1; }), "restrizione", "minProperties introdotto", "lot");
});

test("T15 (R15): limiti superiori", () => {
  only(changes((s) => { s.properties.code.maxLength = 8; }), "restrizione", "maxLength da 12 a 8", "code");
  only(changes((s) => { s.properties.code.maxLength = 20; }), "allargamento", "maxLength da 12 a 20", "code");
  only(changes((s) => { delete s.properties.code.maxLength; }), "allargamento", "maxLength tolto", "code");
  only(changes((s) => { s.properties.amount.maximum = 100; }), "restrizione", "maximum introdotto", "amount");
  only(changes((s) => { s.properties.labels.maxItems = 5; }), "restrizione", "maxItems da 20 a 5", "labels");
  only(changes((s) => { s.properties.labels.maxItems = 50; }), "allargamento", "maxItems da 20 a 50", "labels");
  only(changes((s) => { s.properties.amount.exclusiveMaximum = 10; }), "restrizione", "exclusiveMaximum introdotto", "amount");
  only(changes((s) => { s.properties.lot.maxProperties = 3; }), "restrizione", "maxProperties introdotto", "lot");
});

test("T16 (R16): multipleOf e uniqueItems", () => {
  const five = mut((s) => { s.properties.amount.multipleOf = 5; });
  only(schemaChanges(BASE, five), "restrizione", "multipleOf introdotto", "amount");
  only(schemaChanges(five, mut((s) => { s.properties.amount.multipleOf = 10; }, five)), "modifica", "multipleOf cambiato da 5 a 10", "amount");
  only(schemaChanges(five, BASE), "allargamento", "multipleOf tolto", "amount");

  only(changes((s) => { s.properties.labels.uniqueItems = true; }), "restrizione", "uniqueItems introdotto", "labels");
  const unique = mut((s) => { s.properties.labels.uniqueItems = true; });
  only(schemaChanges(unique, mut((s) => { s.properties.labels.uniqueItems = false; }, unique)), "allargamento", "uniqueItems tolto", "labels");
  const off = mut((s) => { s.properties.labels.uniqueItems = false; });
  only(schemaChanges(off, mut((s) => { s.properties.labels.uniqueItems = true; }, off)), "restrizione", "uniqueItems introdotto", "labels");
});

test("T17 (R17): additionalProperties — chiuso, riaperto (ADR-032), ricorsione", () => {
  only(schemaChanges(BASE, CLOSED), "restrizione", "schema chiuso", "(radice)");
  const reopenedTrue = schemaChanges(CLOSED, mut((s) => { s.additionalProperties = true; }, CLOSED));
  only(reopenedTrue, "pii", "schema riaperto", "(radice)");
  assert.ok(reopenedTrue[0].message.includes("ADR-032"));
  only(schemaChanges(CLOSED, mut((s) => { delete s.additionalProperties; }, CLOSED)), "pii", "schema riaperto", "(radice)");
  // invariato: false → false, assente → true
  assert.deepEqual(schemaChanges(CLOSED, clone(CLOSED)), []);
  assert.deepEqual(changes((s) => { delete s.additionalProperties; }), []);
  // ricorsione nello schema di additionalProperties
  only(changes((s) => { s.properties.attributes.additionalProperties.type = ["string", "null"]; }), "modifica", "tipo allargato", "attributes{}");
  const strict = changes((s) => { s.properties.attributes.additionalProperties["x-lh-pii"] = false; delete s.properties.attributes.additionalProperties.type; });
  only(strict, "modifica", "vincolo di tipo tolto", "attributes{}");
  const strictBase = mut((s) => { s.properties.attributes.additionalProperties.minLength = 1; });
  only(schemaChanges(BASE, strictBase), "restrizione", "minLength introdotto", "attributes{}");
  // schema ↔ aperto e altri cambi → modifica
  only(changes((s) => { s.properties.attributes.additionalProperties = true; }), "modifica", "additionalProperties cambiato", "attributes");
  only(changes((s) => { s.properties.lot.additionalProperties = { type: "string" }; }), "modifica", "additionalProperties cambiato", "lot");
});

test("T18 (R18): items introdotto → restrizione; tolto → modifica; altrimenti ricorsione", () => {
  const noItems = mut((s) => { delete s.properties.labels.items; });
  only(schemaChanges(noItems, BASE), "restrizione", "items introdotto", "labels");
  only(schemaChanges(BASE, noItems), "modifica", "items tolto", "labels");
  const nested = changes((s) => { s.properties.labels.items.properties.code.type = "integer"; });
  assert.ok(has(nested, "restrizione", "tipo ristretto da string a integer", "labels[].code"), dump(nested));
});

test("T19 (R19): x-lh-pii declassato → pii; false → true lo copre l'altro controllo", () => {
  const down = changes((s) => { s.properties.note["x-lh-pii"] = false; });
  only(down, "pii", "x-lh-pii declassato da true a false (ADR-032)", "note");
  const gone = changes((s) => { delete s.properties.note["x-lh-pii"]; });
  only(gone, "pii", "x-lh-pii declassato", "note");
  assert.deepEqual(changes((s) => { s.properties.code["x-lh-pii"] = true; }), []);
});

test("T20 (R21): parole chiave non interpretate → modifica; invariate → niente", () => {
  const oneOf = changes((s) => { s.properties.code.oneOf = [{ minLength: 1 }]; });
  only(oneOf, "modifica", "parola chiave «oneOf» cambiata: confronto automatico non supportato", "code");
  const ref = mut((s) => { s.properties.lot = { $ref: "#/$defs/a", "x-lh-pii": false }; s.$defs = { a: { type: "object" }, b: { type: "object" } }; });
  const refs = schemaChanges(ref, mut((s) => { s.properties.lot.$ref = "#/$defs/b"; }, ref));
  only(refs, "modifica", "parola chiave «$ref» cambiata", "lot");
  const other = mut((s) => { s.patternProperties = { "^x-": { type: "string" } }; s.if = { required: ["a"] }; });
  assert.deepEqual(schemaChanges(other, clone(other)), []);
  const changed = schemaChanges(other, mut((s) => { s.patternProperties["^x-"].type = "integer"; }, other));
  only(changed, "modifica", "parola chiave «patternProperties» cambiata", "(radice)");
  assert.ok(has(changes((s) => { s.unevaluatedProperties = false; }), "modifica", "«unevaluatedProperties»"));
  assert.ok(has(changes((s) => { s.allOf = []; }), "modifica", "«allOf»"));
});

test("T21 (R22): schema booleano cambiato → modifica", () => {
  only(schemaChanges(true, false), "modifica", "schema booleano cambiato", "(radice)");
  only(changes((s) => { s.properties.labels.items = false; }), "modifica", "schema booleano cambiato", "labels[]");
  // `true` equivale a `{}`
  assert.deepEqual(schemaChanges(true, {}), []);
  assert.deepEqual(schemaChanges({}, true), []);
});

// ================= confronto tra insiemi di file (R1–R4) =================

test("T22 (R1): schema rimosso, con l'indizio di rinomina", () => {
  const a = { $id: "urn:x:a:1", type: "object" };
  const removed = compareTrees(tree({ "fact/a.schema.json": a, "fact/b.schema.json": { $id: "urn:x:b:1" } }), tree({ "fact/b.schema.json": { $id: "urn:x:b:1" } }));
  assert.deepEqual(removed.findings, ["fact/a.schema.json: schema rimosso o rinominato (rimozione)"]);
  assert.equal(removed.compared, 1);
  const renamed = compareTrees(tree({ "fact/a.schema.json": a }), tree({ "fact/c.schema.json": a }));
  assert.deepEqual(renamed.findings, ["fact/a.schema.json: schema rimosso o rinominato — rinominato in fact/c.schema.json (rimozione)"]);
  assert.deepEqual(renamed.added, ["fact/c.schema.json"]);
});

test("T23 (R1): rimuovere una versione superata chiude la doppia lettura (Q-346)", () => {
  const v1 = { $id: "urn:x:a:1", "x-lh-superseded-by": "urn:x:a:2" };
  const { findings } = compareTrees(tree({ "fact/a.schema.json": v1, "fact/a.v2.schema.json": { $id: "urn:x:a:2" } }), tree({ "fact/a.v2.schema.json": { $id: "urn:x:a:2" } }));
  assert.equal(findings.length, 1, findings.join("\n"));
  assert.match(findings[0], /schema rimosso o rinominato — versione superata: la rimozione chiude la doppia lettura \(Q-346\) ed è una decisione \(rimozione\)$/);
});

test("T24 (R2): $id cambiato", () => {
  const { findings } = compareTrees(tree({ "fact/a.schema.json": { $id: "urn:x:a:1" } }), tree({ "fact/a.schema.json": { $id: "urn:x:a:2" } }));
  assert.deepEqual(findings, ["fact/a.schema.json: $id cambiato da urn:x:a:1 a urn:x:a:2 (rimozione)"]);
});

test("T25 (R3): file nuovi e versioni nuove sono ammessi ed elencati", () => {
  const files = { "fact/a.schema.json": BASE };
  const result = compareTrees(tree(files), tree({ ...files, "fact/z.schema.json": { $id: "urn:x:z:1" }, "fact/a.v2.schema.json": { $id: "urn:x:a:2" } }));
  assert.deepEqual(result.findings, []);
  assert.deepEqual(result.added, ["fact/a.v2.schema.json", "fact/z.schema.json"]);
  assert.equal(result.compared, 1);
});

test("T26 (R4): JSON non valido da una delle due parti → modifica", () => {
  const ok = { $id: "urn:x:a:1" };
  const badCurrent = compareTrees(tree({ "fact/a.schema.json": ok }), tree({ "fact/a.schema.json": "{ non json" }));
  assert.equal(badCurrent.findings.length, 1);
  assert.match(badCurrent.findings[0], /^fact\/a\.schema\.json: JSON non valido.* \(modifica\)$/);
  const badBase = compareTrees(tree({ "fact/a.schema.json": "{ non json" }), tree({ "fact/a.schema.json": ok }));
  assert.match(badBase.findings[0], /JSON non valido/);
  const badNew = compareTrees(tree({}), tree({ "fact/n.schema.json": "{ non json" }));
  assert.match(badNew.findings[0], /^fact\/n\.schema\.json: JSON non valido/);
  assert.equal(kindOf(badNew.findings[0]), "modifica");
});

test("T27: formato dei finding `file: percorso: messaggio (kind)`", () => {
  const cur = mut((s) => { delete s.properties.lot.properties.x; s.properties.status.enum.push("NEW"); });
  const { findings } = compareTrees(tree({ "fact/w.schema.json": BASE }), tree({ "fact/w.schema.json": cur }));
  assert.deepEqual(findings, [
    "fact/w.schema.json: status: valore «NEW» aggiunto all'enum (allargamento)",
    "fact/w.schema.json: lot: proprietà «x» rimossa (rimozione)",
  ]);
  assert.deepEqual(findings.map(kindOf), ["allargamento", "rimozione"]);
});

// ================= git: tag, merge-base, label =================

const dirs = [];
after(() => {
  for (const d of dirs) rmSync(d, { recursive: true, force: true });
});
const git = (dir, ...args) =>
  execFileSync("git", ["-C", dir, "-c", "user.name=lh-test", "-c", "user.email=lh-test@example.invalid", "-c", "commit.gpgsign=false", "-c", "tag.gpgsign=false", ...args], {
    encoding: "utf8", stdio: ["ignore", "pipe", "pipe"],
  }).trim();
const repo = () => {
  const dir = mkdtempSync(join(tmpdir(), "lh-contracts-compat-"));
  dirs.push(dir);
  git(dir, "init", "-q", "-b", "main");
  return dir;
};
const NAME = "fact/x.schema.json";
const schemaX = (props = ["a", "b", "c"], extra = {}) => ({
  $id: "urn:loyaltyhub:schema:fact.x:1",
  type: "object",
  required: ["a"],
  properties: Object.fromEntries(props.map((p) => [p, { type: "string", "x-lh-pii": false }])),
  additionalProperties: true,
  ...extra,
});
const put = (dir, rel, content) => {
  const file = join(dir, "contracts", "events", rel);
  mkdirSync(dirname(file), { recursive: true });
  writeFileSync(file, typeof content === "string" ? content : `${JSON.stringify(content, null, 2)}\n`);
};
const commit = (dir, message) => {
  git(dir, "add", "-A");
  git(dir, "commit", "-q", "-m", message);
  return git(dir, "rev-parse", "HEAD");
};
/** Repo con un commit contenente `schemaX()` e il tag v1.0.0. */
const taggedRepo = () => {
  const dir = repo();
  put(dir, NAME, schemaX());
  commit(dir, "contratti v1");
  git(dir, "tag", "v1.0.0");
  return dir;
};
const run = (dir, extra = {}) => runCompat({ root: dir, env: {}, ...extra });

test("T28: nessun tag (o nessun repository git) → confronto saltato, exit 0", () => {
  const dir = repo();
  put(dir, NAME, schemaX());
  commit(dir, "senza tag");
  const result = run(dir);
  assert.equal(result.status, "skipped");
  assert.equal(result.exitCode, 0);
  assert.equal(result.baseline, null);
  assert.match(result.reason, /nessun tag v\*/);
  assert.equal(latestTag(dir), null);

  const plain = mkdtempSync(join(tmpdir(), "lh-contracts-compat-"));
  dirs.push(plain);
  const skipped = run(plain);
  if (skipped.status === "skipped") {
    assert.equal(skipped.exitCode, 0);
    assert.match(skipped.reason, /repository git/);
  }
});

test("T29: tag su un commit senza contracts/events → saltato, exit 0 (Q-540)", () => {
  const dir = repo();
  writeFileSync(join(dir, "README.md"), "x\n");
  commit(dir, "solo readme");
  git(dir, "tag", "v0.6.0");
  put(dir, NAME, schemaX());
  commit(dir, "contratti");
  const result = run(dir);
  assert.equal(result.status, "skipped");
  assert.equal(result.exitCode, 0);
  assert.equal(result.baseline.ref, "v0.6.0");
  assert.match(result.reason, /il tag non contiene contracts\/events\//);
  assert.equal(schemasAtRef(dir, "v0.6.0").size, 0);
});

test("T30: proprietà facoltativa e file nuovo rispetto al tag → ok, exit 0", () => {
  const dir = taggedRepo();
  put(dir, NAME, schemaX(["a", "b", "c", "d"]));
  put(dir, "fact/x.v2.schema.json", schemaX(["a"], { $id: "urn:loyaltyhub:schema:fact.x:2" }));
  const result = run(dir);
  assert.equal(result.status, "ok", dump(result));
  assert.equal(result.exitCode, 0);
  assert.equal(result.baseline.ref, "v1.0.0");
  assert.equal(result.compared, 1);
  assert.deepEqual(result.added, ["fact/x.v2.schema.json"]);
  assert.deepEqual(result.blocking, []);
  assert.equal(result.mergeBase, null); // origin/main non esiste: confronto rigoroso
  assert.equal(result.notes.length, 1);
});

test("T31: proprietà rimossa rispetto al tag → broken, exit 1", () => {
  const dir = taggedRepo();
  put(dir, NAME, schemaX(["a", "b"]));
  const result = run(dir);
  assert.equal(result.status, "broken");
  assert.equal(result.exitCode, 1);
  assert.deepEqual(result.blocking, [`${NAME}: (radice): proprietà «c» rimossa (rimozione)`]);
  const { out, err } = formatReport(result);
  assert.ok(err.some((l) => l.startsWith("✗ check-contracts: 1 modifica incompatibile rispetto a v1.0.0:")), err.join("\n"));
  assert.ok(err.some((l) => l.includes("proprietà «c» rimossa")));
  assert.ok(err.some((l) => l.includes("i contratti evento si estendono, non si rompono") && l.includes('label "decisione"')));
  assert.ok(out[0].startsWith("check-contracts: compatibilità con v1.0.0 ("), out[0]);
});

test("T32: con la label «decisione» le rotture sono riportate ma non bloccano", () => {
  const dir = taggedRepo();
  put(dir, NAME, schemaX(["a", "b"]));
  const result = run(dir, { labels: ["altra", "decisione"] });
  assert.equal(result.status, "accepted");
  assert.equal(result.exitCode, 0);
  assert.equal(result.blocking.length, 1);
  const { err } = formatReport(result);
  assert.ok(err.some((l) => l.includes('(label "decisione" presente: segnalate ma non bloccanti)')), err.join("\n"));
  assert.equal(run(dir, { labels: ["altra"] }).exitCode, 1);
});

test("T33: una rottura già su main non blocca di nuovo (Q-542); una nuova sì", () => {
  const dir = taggedRepo();
  put(dir, NAME, schemaX(["a", "c"])); // «b» rimossa su main (PR precedente)
  commit(dir, "rimozione di b");
  git(dir, "checkout", "-q", "-b", "feature");

  const inherited = run(dir, { base: "main" });
  assert.equal(inherited.status, "ok", dump(inherited));
  assert.equal(inherited.exitCode, 0);
  assert.equal(inherited.blocking.length, 0);
  assert.deepEqual(inherited.inherited, [`${NAME}: (radice): proprietà «b» rimossa (rimozione)`]);
  assert.ok(inherited.mergeBase);
  const { out } = formatReport(inherited);
  assert.ok(out.some((l) => l.startsWith("  = ") && l.includes("già presente su main, non blocca (Q-542)")), out.join("\n"));

  put(dir, NAME, schemaX(["a"])); // seconda rimozione, nuova
  const fresh = run(dir, { base: "main" });
  assert.equal(fresh.status, "broken");
  assert.equal(fresh.exitCode, 1);
  assert.equal(fresh.blocking.length, 1);
  assert.match(fresh.blocking[0], /proprietà «c» rimossa/);
  assert.equal(fresh.inherited.length, 1);

  // il ramo base si legge anche da GITHUB_BASE_REF (qui: un ramo locale non prefissato non esiste → rigoroso)
  const viaEnv = runCompat({ root: dir, env: { GITHUB_BASE_REF: "main" } });
  assert.equal(viaEnv.baseRef, "origin/main");
  assert.equal(viaEnv.mergeBase, null);
});

test("T34: --baseline esplicito prevale sul tag; un riferimento inesistente è un errore (exit 2)", () => {
  const dir = repo();
  put(dir, NAME, schemaX(["a", "b", "c", "d"]));
  const first = commit(dir, "primo");
  put(dir, NAME, schemaX(["a", "b", "c"]));
  commit(dir, "secondo");
  git(dir, "tag", "v1.0.0"); // il tag vede solo a, b, c: il working tree è compatibile
  assert.equal(run(dir).status, "ok");
  const explicit = run(dir, { baseline: first });
  assert.equal(explicit.baseline.ref, first);
  assert.equal(explicit.status, "broken");
  assert.match(explicit.blocking[0], /proprietà «d» rimossa/);
  const bad = run(dir, { baseline: "nope" });
  assert.equal(bad.status, "error");
  assert.equal(bad.exitCode, 2);
  assert.equal(bad.baseline, null);
  assert.ok(formatReport(bad).err[0].startsWith("✗ check-contracts:"));
  assert.equal(run(dir, { baseline: "--upload-pack=x" }).exitCode, 2);
  assert.throws(() => schemasAtRef(dir, "nope"), /non risolvibile/);
});

test("T35: con più tag vince il più vicino a HEAD", () => {
  const dir = taggedRepo();
  put(dir, NAME, schemaX(["a", "b"]));
  commit(dir, "1.1");
  git(dir, "tag", "v1.1.0");
  assert.equal(latestTag(dir), "v1.1.0");
  const result = run(dir);
  assert.equal(result.baseline.ref, "v1.1.0");
  assert.equal(result.status, "ok", dump(result));
  // un tag non `v*` non conta
  put(dir, NAME, schemaX(["a"]));
  commit(dir, "1.2");
  git(dir, "tag", "release-x");
  assert.equal(latestTag(dir), "v1.1.0");
});

test("T36: solo allargamenti → exit 0 e segnalati (Q-541)", () => {
  const dir = repo();
  put(dir, NAME, schemaX(["a"], { properties: { a: { enum: ["X"], "x-lh-pii": false } } }));
  commit(dir, "v1");
  git(dir, "tag", "v1.0.0");
  put(dir, NAME, schemaX(["a"], { properties: { a: { enum: ["X", "Y"], "x-lh-pii": false } } }));
  const result = run(dir);
  assert.equal(result.status, "ok", dump(result));
  assert.equal(result.exitCode, 0);
  assert.equal(result.widenings.length, 1);
  assert.match(result.widenings[0], /valore «Y» aggiunto all'enum/);
  const { out } = formatReport(result);
  assert.ok(out.some((l) => l.startsWith("  ⚠ ") && l.includes("allargamento, non blocca (Q-139, Q-541)")), out.join("\n"));
  assert.ok(out.some((l) => l.startsWith("✓ check-contracts")));
});

test("T37: --base inesistente → nessun merge-base, confronto rigoroso", () => {
  const dir = taggedRepo();
  put(dir, NAME, schemaX(["a", "b"]));
  const result = run(dir, { base: "nope" });
  assert.equal(result.mergeBase, null);
  assert.equal(result.status, "broken");
  assert.equal(result.exitCode, 1);
  assert.equal(result.inherited.length, 0);
  assert.ok(result.notes[0].includes("merge-base con nope non trovato"));
});

test("T38: gli schemi del repository, confrontati con sé stessi, non differiscono", () => {
  const root = resolve(here, "..");
  const files = schemasOnDisk(root);
  assert.ok(files.has("envelope.schema.json"), "manca envelope.schema.json");
  assert.ok(files.has("fact/member.registered.v2.schema.json"), "manca fact/member.registered.v2.schema.json");
  assert.ok([...files.keys()].every((k) => k.endsWith(".schema.json") && !k.startsWith("examples/")));
  const result = compareTrees(files, schemasOnDisk(root));
  assert.deepEqual(result.findings, []);
  assert.deepEqual(result.added, []);
  assert.equal(result.compared, files.size);
  // cartella assente: mappa vuota
  assert.equal(schemasOnDisk(join(tmpdir(), "lh-contracts-compat-inesistente")).size, 0);
});

test("T38b: schemasOnDisk esclude examples/ e i file che non sono schemi", () => {
  const dir = repo();
  put(dir, NAME, schemaX());
  put(dir, "examples/fact.x.json", { a: 1 });
  put(dir, "examples/ignorato.schema.json", { a: 1 });
  put(dir, "README.md", "x");
  assert.deepEqual([...schemasOnDisk(dir).keys()], [NAME]);
  commit(dir, "tutto");
  assert.deepEqual([...schemasAtRef(dir, "HEAD").keys()], [NAME]);
});

test("T39: parseArgs", () => {
  assert.deepEqual(parseArgs([]), { baseline: undefined, base: undefined, labels: [] });
  assert.deepEqual(parseArgs(["--baseline=v1.0.0", "--base=origin/dev", "--pr-labels=a, decisione ,,b"]), {
    baseline: "v1.0.0", base: "origin/dev", labels: ["a", "decisione", "b"],
  });
  assert.deepEqual(parseArgs(["--pr-labels=", "--baseline=", "--altro", "x"]), { baseline: undefined, base: undefined, labels: [] });
});

test("T40: formatReport — saltato con annotazioni GitHub", () => {
  const empty = { status: "skipped", code: "empty-tag", baseline: { ref: "v0.6.0", sha: "9cd45f3f8e0e9cd45f3f8e0e9cd45f3f8e0e9cd4" }, reason: "il tag non contiene contracts/events/, confronto saltato (Q-540)" };
  const plain = formatReport(empty, { github: false });
  assert.deepEqual(plain.out, ["check-contracts: compatibilità con v0.6.0 (9cd45f3f8e0e): il tag non contiene contracts/events/, confronto saltato (Q-540)."]);
  const gh = formatReport(empty, { github: true });
  assert.ok(gh.out.some((l) => l.startsWith("::notice title=check-contracts::")), gh.out.join("\n"));
  const noTag = formatReport({ status: "skipped", code: "no-tag", baseline: null, reason: "nessun tag v* raggiungibile da HEAD, confronto saltato (Q-540)" }, { github: true });
  assert.ok(noTag.out.some((l) => l.startsWith("::warning title=check-contracts::") && l.includes("nessun tag v*")));
  const noGit = formatReport({ status: "skipped", code: "no-git", baseline: null, reason: "la cartella non è un repository git: confronto con l'ultimo tag saltato" }, { github: true });
  assert.ok(noGit.out.some((l) => l.startsWith("::warning title=check-contracts::") && l.includes("repository git")));
  assert.deepEqual(formatReport({ status: "error", reason: "boom" }).err, ["✗ check-contracts: boom"]);
});
