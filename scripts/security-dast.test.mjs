// Test di security-dast.mjs (M8.11c): node --test scripts/security-dast.test.mjs
import test from "node:test";
import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync, existsSync, readdirSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import YAML from "yaml";
import { checkBaseline, gateZap, main, prepareSpecs, summarizeFuzz, validateExclusions, validateZapExceptions } from "./security-dast.mjs";

const TODAY = "2026-09-29";
const made = [];
const tmp = () => {
  const dir = mkdtempSync(join(tmpdir(), "lh-dast-"));
  made.push(dir);
  return dir;
};
const write = (file, content) => {
  mkdirSync(join(file, ".."), { recursive: true });
  writeFileSync(file, typeof content === "string" ? content : `${JSON.stringify(content, null, 2)}\n`);
};
/** Esegue main() catturando l'output. */
const run = (argv) => {
  const out = [];
  const err = [];
  const code = main(argv, { log: (m) => out.push(String(m)), error: (m) => err.push(String(m)) });
  return { code, out: out.join("\n"), err: err.join("\n") };
};

// ================= prepare =================

const SPEC_A = {
  openapi: "3.1.0",
  paths: {
    "/v1/things": { get: { responses: { 200: { content: { "application/json": {} } } } }, post: { responses: { 201: {} } } },
    "/v1/stream/events": { get: { responses: { 200: { content: { "text/event-stream": {} } } } } },
  },
};
const SPEC_B = {
  openapi: "3.1.0",
  paths: {
    "/v1/demo/reset": { post: { responses: { 200: { content: { "application/json": {} } } } } },
    "/v1/ok": { get: { responses: { "200": { $ref: "#/components/responses/Ok" } } }, parameters: [] },
  },
  components: { responses: { Ok: { description: "ok", content: { "application/json": {} } } } },
};
const SPEC_PORTAL = { openapi: "3.1.0", paths: { "/v1/portal/x": { get: { responses: { 200: {} } } } } };
const EXCLUSIONS = {
  specsSkipped: [{ file: "portal.openapi.yaml", reason: "unione dei percorsi già presenti altrove", ref: "Q-533" }],
  operations: [{ operation: "POST /v1/demo/reset", reason: "azzera lo stato: coperto da un'altra riga", ref: "Q-533" }],
  responseContentTypes: [{ contentType: "text/event-stream", reason: "flusso SSE sempre aperto", ref: "Q-533" }],
};

function apiFixture() {
  const api = join(tmp(), "api");
  write(join(api, "a-service.openapi.yaml"), YAML.stringify(SPEC_A));
  write(join(api, "platform.openapi.yaml"), YAML.stringify(SPEC_B));
  write(join(api, "portal.openapi.yaml"), YAML.stringify(SPEC_PORTAL));
  return api;
}

test("prepare: toglie un'operazione SSE, un'operazione esclusa e un file saltato", () => {
  const api = apiFixture();
  const out = join(tmp(), "out");
  const { files, errors } = prepareSpecs({ apiDir: api, outDir: out, exclusions: EXCLUSIONS });
  assert.deepEqual(errors, []);
  assert.deepEqual(readdirSync(out).sort(), ["a-service.openapi.json", "platform.openapi.json"]);
  const a = JSON.parse(readFileSync(join(out, "a-service.openapi.json"), "utf8"));
  assert.deepEqual(Object.keys(a.paths), ["/v1/things"], "il percorso SSE, rimasto senza operazioni, sparisce");
  assert.deepEqual(Object.keys(a.paths["/v1/things"]).sort(), ["get", "post"]);
  const p = JSON.parse(readFileSync(join(out, "platform.openapi.json"), "utf8"));
  assert.deepEqual(Object.keys(p.paths), ["/v1/ok"], "il reset è tolto, il resto resta");
  assert.deepEqual(files.map((f) => [f.file, f.kept, f.removed]), [
    ["a-service.openapi.yaml", 2, ["GET /v1/stream/events"]],
    ["platform.openapi.yaml", 1, ["POST /v1/demo/reset"]],
  ]);
});

test("prepare: il media type dietro un $ref a components/responses è riconosciuto", () => {
  const api = join(tmp(), "api");
  write(join(api, "x.openapi.yaml"), YAML.stringify({
    paths: { "/v1/s": { get: { responses: { 200: { $ref: "#/components/responses/Sse" } } } }, "/v1/t": { get: { responses: { 200: {} } } } },
    components: { responses: { Sse: { content: { "text/event-stream; charset=utf-8": {} } } } },
  }));
  const { files, errors } = prepareSpecs({
    apiDir: api,
    outDir: join(tmp(), "out"),
    exclusions: { responseContentTypes: [{ contentType: "text/event-stream", reason: "flusso SSE sempre aperto", ref: "Q-533" }] },
  });
  assert.deepEqual(errors, []);
  assert.deepEqual(files[0].removed, ["GET /v1/s"]);
});

test("prepare: un'esclusione che non corrisponde a nulla è superata e fallisce", () => {
  const api = apiFixture();
  const stale = structuredClone(EXCLUSIONS);
  stale.operations.push({ operation: "DELETE /v1/non-esiste", reason: "operazione che non c'è più", ref: "Q-533" });
  const { errors } = prepareSpecs({ apiDir: api, outDir: join(tmp(), "out"), exclusions: stale });
  assert.equal(errors.length, 1);
  assert.match(errors[0], /esclusione superata operations «DELETE \/v1\/non-esiste»/);
});

test("prepare: un file saltato o un media type superati falliscono", () => {
  const api = apiFixture();
  const stale = structuredClone(EXCLUSIONS);
  stale.specsSkipped.push({ file: "vecchio.openapi.yaml", reason: "specifica rimossa da tempo", ref: "Q-533" });
  stale.responseContentTypes.push({ contentType: "application/x-ndjson", reason: "nessuna risposta lo usa", ref: "Q-533" });
  const { errors } = prepareSpecs({ apiDir: api, outDir: join(tmp(), "out"), exclusions: stale });
  assert.equal(errors.length, 2);
});

test("prepare: senza ref o senza motivo non scrive nulla e fallisce", () => {
  const api = apiFixture();
  const out = join(tmp(), "out");
  const noRef = structuredClone(EXCLUSIONS);
  delete noRef.operations[0].ref;
  const first = prepareSpecs({ apiDir: api, outDir: out, exclusions: noRef });
  assert.match(first.errors.join("\n"), /operations\[0\].*ref assente/);
  assert.equal(existsSync(out), false, "nessun file scritto se le esclusioni non sono valide");

  const badRef = structuredClone(EXCLUSIONS);
  badRef.operations[0].ref = "Q-5";
  assert.match(validateExclusions(badRef).join("\n"), /ref assente o non del tipo/);
  const noReason = structuredClone(EXCLUSIONS);
  noReason.responseContentTypes[0].reason = " ";
  assert.match(validateExclusions(noReason).join("\n"), /responseContentTypes\[0\].*motivo assente/);
});

test("prepare (riga di comando): esce con 0 e con 1", () => {
  const api = apiFixture();
  const base = tmp();
  write(join(base, "ok.json"), EXCLUSIONS);
  const ok = run(["prepare", "--out", join(base, "o"), "--api", api, "--exclusions", join(base, "ok.json")]);
  assert.equal(ok.code, 0, ok.err);
  assert.match(ok.out, /a-service\.openapi\.json: 2 operazioni da provare, 1 escluse \(GET \/v1\/stream\/events\)/);
  const bad = structuredClone(EXCLUSIONS);
  bad.operations[0].ref = "boh";
  write(join(base, "bad.json"), bad);
  assert.equal(run(["prepare", "--out", join(base, "o2"), "--api", api, "--exclusions", join(base, "bad.json")]).code, 1);
  assert.equal(run(["prepare"]).code, 1, "manca --out");
});

test("prepare sui contratti veri: 9 file, senza SSE e senza reset", { skip: !existsSync("contracts/api") }, () => {
  const out = join(tmp(), "out");
  const { files, errors } = prepareSpecs({
    apiDir: "contracts/api",
    outDir: out,
    exclusions: JSON.parse(readFileSync(".dast/exclusions.json", "utf8")),
  });
  assert.deepEqual(errors, []);
  assert.equal(files.length, 9);
  assert.deepEqual(files.flatMap((f) => f.removed).sort(), ["GET /v1/stream/events", "POST /v1/demo/reset"]);
});

// ================= baseline-check =================

const ENTRY = {
  id: "a1b2c3",
  operation: "POST /v1/members",
  check: "not_a_server_error",
  failure: "ServerError",
  signature: "500",
  first_seen: "2026-09-29",
  last_seen: "2026-09-29",
  expires: "2026-12-01",
  reason: "eccezione non gestita su input NUL: causa verificata nel log",
  ticket: "Q-532",
};
const baseline = (...entries) => ({ format_version: 1, schemathesis_version: "4.28.0", entries });

test("baseline-check: una baseline vuota e una voce completa sono valide", () => {
  assert.deepEqual(checkBaseline(baseline(), TODAY), []);
  assert.deepEqual(checkBaseline(baseline(ENTRY), TODAY), []);
});

test("baseline-check: manca expires", () => {
  const { expires, ...noExpires } = ENTRY;
  const errors = checkBaseline(baseline(noExpires), TODAY);
  assert.equal(errors.length, 1);
  assert.match(errors[0], /voce a1b2c3.*expires assente/);
});

test("baseline-check: ticket sbagliato, scadenza impossibile o troppo lontana, motivo corto", () => {
  assert.match(checkBaseline(baseline({ ...ENTRY, ticket: "issue-7" }), TODAY).join("\n"), /ticket assente o non del tipo/);
  assert.match(checkBaseline(baseline({ ...ENTRY, expires: "2026-02-30" }), TODAY).join("\n"), /non una data reale/);
  assert.match(checkBaseline(baseline({ ...ENTRY, expires: "2027-06-01" }), TODAY).join("\n"), /oltre 90 giorni/);
  assert.match(checkBaseline(baseline({ ...ENTRY, reason: "boh" }), TODAY).join("\n"), /motivo assente o più corto/);
  assert.match(checkBaseline(baseline({ ...ENTRY, signature: "" }), TODAY).join("\n"), /signature assente/);
  assert.match(checkBaseline({ format_version: 2, entries: [] }, TODAY).join("\n"), /format_version 1/);
  assert.match(checkBaseline({ format_version: 1 }, TODAY).join("\n"), /elenco entries/);
});

test("baseline-check: elenca l'id di ogni voce non valida (riga di comando)", () => {
  const base = tmp();
  write(join(base, "b.json"), baseline(ENTRY, { ...ENTRY, id: "ffffff", ticket: "x" }, { ...ENTRY, id: "eeeeee", reason: "" }));
  const r = run(["baseline-check", join(base, "b.json"), "--today", TODAY]);
  assert.equal(r.code, 1);
  assert.match(r.err, /voce ffffff/);
  assert.match(r.err, /voce eeeeee/);
  assert.doesNotMatch(r.err, /voce a1b2c3/);
  write(join(base, "ok.json"), baseline(ENTRY));
  assert.equal(run(["baseline-check", join(base, "ok.json"), "--today", TODAY]).code, 0);
});

test("baseline committata: valida oggi", () => {
  if (!existsSync(".dast/schemathesis-baseline.json")) return;
  assert.deepEqual(checkBaseline(JSON.parse(readFileSync(".dast/schemathesis-baseline.json", "utf8"))), []);
});

// ================= fuzz-summary =================

const report = (over = {}) => ({
  exit_code: 0,
  seed: 4242,
  operations: { selected: 12, tested: 12, errored: 0 },
  failures: [],
  errors: [],
  baseline: { known: 1, new: 0, expired_ids: [], unobserved: 0 },
  ...over,
});

function fuzzFixture(reports) {
  const dir = tmp();
  for (const [name, r] of Object.entries(reports)) {
    write(join(dir, "specs", `${name}.openapi.json`), "{}");
    if (r) write(join(dir, name, "report.json"), r);
  }
  return dir;
}

test("fuzz-summary: caso normale", () => {
  const dir = fuzzFixture({ "member-service": report(), platform: report({ operations: { selected: 5, tested: 4, errored: 1 }, errors: [{ title: "timeout", count: 2 }] }) });
  const { problems, markdown } = summarizeFuzz(dir);
  assert.deepEqual(problems, []);
  assert.match(markdown, /\| member-service \| 12\/12 \| 0 \| 1 \| 0 \| 0 \| 4242 \|/);
  assert.match(markdown, /\| platform \| 4\/5 \| 0 \| 1 \| 0 \| 2 \| 4242 \|/);
});

test("fuzz-summary: tested 0 fallisce", () => {
  const dir = fuzzFixture({ "member-service": report({ operations: { selected: 12, tested: 0, errored: 12 } }) });
  const { problems } = summarizeFuzz(dir);
  assert.equal(problems.length, 1);
  assert.match(problems[0], /member-service: nessuna operazione provata/);
});

test("fuzz-summary: un rapporto mancante o una cartella senza specifiche fallisce", () => {
  const dir = fuzzFixture({ "member-service": report(), "wallet-service": null });
  const { problems } = summarizeFuzz(dir);
  assert.equal(problems.length, 1);
  assert.match(problems[0], /wallet-service: manca il rapporto/);
  assert.match(summarizeFuzz(tmp()).problems.join("\n"), /nessuna specifica/);
  assert.equal(run(["fuzz-summary", dir]).code, 1);
});

test("fuzz-summary: conta i 5xx nuovi e le voci scadute, senza bloccare da solo", () => {
  const dir = fuzzFixture({ x: report({ exit_code: 1, baseline: { known: 0, new: 3, expired_ids: ["a1b2c3"], unobserved: 0 } }) });
  const { problems, markdown } = summarizeFuzz(dir);
  assert.deepEqual(problems, [], "il codice di uscita di Schemathesis è il gate dei 5xx; il riepilogo verifica che sia stato provato qualcosa");
  assert.match(markdown, /\| x \| 12\/12 \| 3 \| 0 \| 1 \| 0 \| 4242 \|/);
  assert.equal(run(["fuzz-summary", fuzzFixture({ x: report() })]).code, 0);
});

// ================= zap-gate =================

const alert = (over = {}) => ({
  pluginid: "40018",
  alert: "SQL Injection",
  riskcode: "3",
  confidence: "2",
  instances: [{ uri: "http://lh-hub:8080/v1/members", method: "GET", param: "q" }],
  ...over,
});
const zapReport = (...alerts) => ({ site: [{ "@name": "http://lh-hub:8080", alerts }] });
const exception = (over = {}) => ({
  pluginId: "40018",
  method: "GET",
  uriRegex: "^http://lh-hub:8080/v1/members$",
  reason: "falso positivo: la risposta riporta il valore ma non lo esegue",
  ref: "Q-532",
  expires: "2026-12-15",
  ...over,
});

function zapFixture(reports) {
  const dir = tmp();
  for (const [name, r] of Object.entries(reports)) {
    write(join(dir, "specs", `${name}.openapi.json`), "{}");
    if (r) write(join(dir, `zap-${name}.json`), r);
  }
  return dir;
}
const gate = (dir, ...exceptions) => gateZap(dir, { exceptions }, TODAY);

test("zap-gate: un High senza eccezione blocca", () => {
  const { problems, markdown } = gate(zapFixture({ a: zapReport(alert()) }));
  assert.equal(problems.length, 1);
  assert.match(problems[0], /a: 1 avvisi High non eccettuati/);
  assert.match(markdown, /\| 40018 \| SQL Injection \| GET \| `http:\/\/lh-hub:8080\/v1\/members` \| q \|/);
});

test("zap-gate: un High con eccezione valida passa, e l'eccezione risulta usata", () => {
  const { problems, warnings } = gate(zapFixture({ a: zapReport(alert()) }), exception());
  assert.deepEqual(problems, []);
  assert.deepEqual(warnings, []);
});

test("zap-gate: un'eccezione scaduta non copre più e avvisa", () => {
  const { problems, warnings } = gate(zapFixture({ a: zapReport(alert()) }), exception({ expires: "2026-09-28" }));
  assert.equal(problems.length, 1);
  assert.match(warnings.join("\n"), /eccezione scaduta il 2026-09-28/);
});

test("zap-gate: Medium, Low e Info non bloccano", () => {
  const dir = zapFixture({
    a: zapReport(alert({ riskcode: "2" }), alert({ riskcode: "1", pluginid: "10021" }), alert({ riskcode: "0", pluginid: "10049" })),
  });
  const { problems, markdown } = gate(dir);
  assert.deepEqual(problems, []);
  assert.match(markdown, /\| a \| 0 \| 1 \| 1 \| 1 \| 0 \|/);
});

test("zap-gate: confidenza 0 (falso positivo dichiarato da ZAP) non blocca", () => {
  assert.deepEqual(gate(zapFixture({ a: zapReport(alert({ confidence: "0" })) })).problems, []);
});

test("zap-gate: metodo o uriRegex diversi non coprono", () => {
  const dir = zapFixture({ a: zapReport(alert()) });
  assert.equal(gate(dir, exception({ method: "POST" })).problems.length, 1);
  assert.equal(gate(dir, exception({ uriRegex: "^http://lh-hub:8080/v1/campaigns$" })).problems.length, 1);
  assert.equal(gate(dir, exception({ pluginId: "40012" })).problems.length, 1);
  assert.deepEqual(gate(dir, exception({ method: undefined, uriRegex: undefined })).problems, [], "senza metodo né uriRegex vale per tutto il plugin");
});

test("zap-gate: un'eccezione copre le sole istanze che corrispondono", () => {
  const two = alert({
    instances: [
      { uri: "http://lh-hub:8080/v1/members", method: "GET", param: "q" },
      { uri: "http://lh-hub:8080/v1/campaigns", method: "GET", param: "q" },
    ],
  });
  const { problems, markdown } = gate(zapFixture({ a: zapReport(two) }), exception());
  assert.equal(problems.length, 1);
  assert.match(markdown, /v1\/campaigns/);
  assert.doesNotMatch(markdown.split("avvisi High bloccanti")[1] ?? "", /v1\/members/);
});

test("zap-gate: rapporto mancante, senza sito o illeggibile blocca", () => {
  assert.match(gate(zapFixture({ a: null })).problems.join("\n"), /a: manca il rapporto/);
  assert.match(gate(zapFixture({ a: { site: [] } })).problems.join("\n"), /alcun sito/);
  assert.match(gate(zapFixture({ a: {} })).problems.join("\n"), /alcun sito/);
  assert.match(gate(tmp()).problems.join("\n"), /nessuna specifica/);
});

test("zap-gate: eccezioni non valide bloccano anche senza avvisi", () => {
  const dir = zapFixture({ a: zapReport() });
  for (const bad of [
    exception({ pluginId: "40x18" }),
    exception({ reason: "corto" }),
    exception({ ref: "Q-1" }),
    exception({ expires: "domani" }),
    exception({ expires: "2027-03-31" }),
    exception({ uriRegex: "([" }),
  ]) {
    assert.ok(gate(dir, bad).problems.length >= 1, JSON.stringify(bad));
  }
  assert.deepEqual(gate(dir, exception()).problems, [], "un'eccezione mai usata è solo un avviso");
  assert.match(gate(dir, exception()).warnings.join("\n"), /mai usata/);
});

test("zap-gate (riga di comando): esce con 0 e con 1", () => {
  const base = tmp();
  write(join(base, "ex.json"), { exceptions: [] });
  const clean = zapFixture({ a: zapReport(alert({ riskcode: "1" })) });
  assert.equal(run(["zap-gate", clean, "--exceptions", join(base, "ex.json"), "--today", TODAY]).code, 0);
  const dirty = zapFixture({ a: zapReport(alert()) });
  const r = run(["zap-gate", dirty, "--exceptions", join(base, "ex.json"), "--today", TODAY]);
  assert.equal(r.code, 1);
  assert.match(r.err, /::error::a: 1 avvisi High non eccettuati/);
  assert.equal(run(["zap-gate", dirty]).code, 1, "manca --exceptions");
  assert.equal(run(["zap-gate", dirty, "--exceptions", join(base, "ex.json"), "--today", "ieri"]).code, 1);
  assert.equal(run(["boh"]).code, 1);
});

test("eccezioni di ZAP committate: valide oggi", () => {
  if (!existsSync(".dast/zap-exceptions.json")) return;
  assert.deepEqual(validateZapExceptions(JSON.parse(readFileSync(".dast/zap-exceptions.json", "utf8"))), []);
});

test.after(() => {
  for (const dir of made) rmSync(dir, { recursive: true, force: true });
});
