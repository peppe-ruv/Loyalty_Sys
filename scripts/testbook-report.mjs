#!/usr/bin/env node
// Rapporto del testbook (docs/16): unisce le righe documentate (ID TB-XXX-NNN in docs/16 e docs/testbook/*.md) con gli
// esiti dei test (XML JUnit di surefire, failsafe e vitest, casi il cui nome inizia con [TB-XXX-NNN]).
// Uso: node scripts/testbook-report.mjs <cartella-junit> <rapporto.md>   (TESTBOOK_STRICT=0 → esce sempre 0)
import { readFileSync, readdirSync, writeFileSync, existsSync } from "node:fs";
import { join } from "node:path";

const [junitDir = "target/testbook/junit", outFile = "target/testbook/rapporto.md"] = process.argv.slice(2);
// ID riga: TB-<DOMINIO>-NNN oppure TB-<DOMINIO>-<AREA>-NNN (es. TB-WAL-GRT-001).
const ID = /TB-[A-Z]{3}(?:-[A-Z]{2,5})?-\d{3,4}/g;

// 1. Righe documentate.
const docFiles = ["docs/16-TESTBOOK-FUNZIONALE.md"];
if (existsSync("docs/testbook")) {
  for (const f of readdirSync("docs/testbook")) if (f.endsWith(".md")) docFiles.push(join("docs/testbook", f));
}
const documented = new Map(); // id → file
for (const f of docFiles) {
  for (const line of readFileSync(f, "utf8").split("\n")) {
    // Una riga documentata è una riga di tabella che inizia con l'ID.
    const m = line.match(/^\|\s*`?(TB-[A-Z]{3}(?:-[A-Z]{2,5})?-\d{3,4})`?\s*\|/);
    if (m && !documented.has(m[1])) documented.set(m[1], f);
  }
}

// 2. Esiti dei test.
const results = new Map(); // id → {status, where, time, message}
const decode = (s) => s.replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&amp;/g, "&");
const files = existsSync(junitDir) ? readdirSync(junitDir).filter((f) => f.endsWith(".xml")) : [];
for (const f of files) {
  const xml = readFileSync(join(junitDir, f), "utf8");
  const re = /<testcase\b([^>]*?)(\/>|>([\s\S]*?)<\/testcase>)/g;
  let m;
  while ((m = re.exec(xml))) {
    const attrs = m[1];
    const body = m[3] ?? "";
    const name = decode((attrs.match(/\bname="([^"]*)"/) || [])[1] ?? "");
    const cls = decode((attrs.match(/\bclassname="([^"]*)"/) || [])[1] ?? "");
    const time = Number((attrs.match(/\btime="([^"]*)"/) || [])[1] ?? 0);
    // Surefire/failsafe coi nomi visualizzati (usePhrasedTestCaseMethodName) antepongono al caso di un
    // @ParameterizedTest la firma del metodo: "grants(String, …)[TB-WAL-GRT-001] …".
    const head = name.match(/^(?:[\w$]+\([^)]*\))?\[?(TB-[A-Z]{3}(?:-[A-Z]{2,5})?-\d{3,4})\]?/);
    if (!head) continue;
    const id = head[1];
    let status = "OK";
    let message = "";
    const fail = body.match(/<(failure|error)\b([^>]*)>/);
    if (fail) {
      status = "FALLITA";
      const msg = (fail[2].match(/\bmessage="([^"]*)"/) || [])[1];
      message = decode(msg ?? fail[1]).split("\n")[0].slice(0, 160);
    }
    else if (/<skipped\b/.test(body)) status = "SALTATA";
    const prev = results.get(id);
    // Più casi con lo stesso ID: vince l'esito peggiore.
    const rank = { FALLITA: 2, SALTATA: 1, OK: 0 };
    if (!prev || rank[status] > rank[prev.status]) results.set(id, { status, where: `${cls} › ${name}`, time, message });
  }
}

// 3. Rapporto.
const all = [...new Set([...documented.keys(), ...results.keys()])].sort();
const byDomain = new Map();
const rows = [];
for (const id of all) {
  const dom = id.slice(3, 6);
  const d = byDomain.get(dom) ?? { righe: 0, ok: 0, fallite: 0, saltate: 0, nonEseguite: 0, senzaDoc: 0 };
  const r = results.get(id);
  if (documented.has(id)) d.righe++;
  if (!r) { d.nonEseguite++; rows.push([id, "NON ESEGUITA", documented.get(id), ""]); }
  else {
    if (r.status === "OK") d.ok++; else if (r.status === "FALLITA") d.fallite++; else d.saltate++;
    if (!documented.has(id)) { d.senzaDoc++; rows.push([id, `SENZA RIGA NEL TESTBOOK (${r.status})`, r.where, r.message]); }
    else if (r.status !== "OK") rows.push([id, r.status, r.where, r.message]);
  }
  byDomain.set(dom, d);
}
const esc = (s) => String(s ?? "").replace(/\|/g, "\\|");
let md = `# Rapporto del testbook funzionale\n\nGenerato il ${new Date().toISOString()} da \`scripts/testbook.sh\`. Righe documentate: ${documented.size}; casi eseguiti: ${results.size}.\n\n`;
md += "| Dominio | Righe | OK | Fallite | Saltate | Non eseguite | Test senza riga |\n|---|---|---|---|---|---|---|\n";
for (const [dom, d] of [...byDomain].sort()) md += `| TB-${dom} | ${d.righe} | ${d.ok} | ${d.fallite} | ${d.saltate} | ${d.nonEseguite} | ${d.senzaDoc} |\n`;
md += "\n## Righe da guardare\n\n";
md += rows.length ? "| Riga | Esito | Dove | Messaggio |\n|---|---|---|---|\n" + rows.map((r) => `| ${r.map(esc).join(" | ")} |`).join("\n") + "\n" : "_Tutte le righe documentate sono eseguite e verdi._\n";
writeFileSync(outFile, md);
if (process.env.GITHUB_STEP_SUMMARY) writeFileSync(process.env.GITHUB_STEP_SUMMARY, md, { flag: "a" });
console.log(md.split("\n## ")[0]);
// Rosso se una riga fallisce, se una riga documentata non è eseguita o se un test non ha la sua riga.
const bad = rows.length > 0 && rows.some((r) => !r[1].startsWith("SALTATA"));
process.exit(bad && process.env.TESTBOOK_STRICT !== "0" ? 1 : 0);
