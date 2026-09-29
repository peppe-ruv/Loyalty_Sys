#!/usr/bin/env node
// Verifica statica del chart Helm e dei compose (F2-DIST-02, F2-DIST-03, F2-EVT-04, M8.3; immagini condivise: Q-483;
// osservabilità: F2-OBS-01, M8.6a).
// Uso: node --test scripts/check-helm.mjs   (nessuna dipendenza; con `helm` nel PATH esegue anche lint e template).
// In CI (CI=true) helm e kubeconform sono obbligatori: senza, la prova fallisce invece di passare senza verificare nulla.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const CHART = path.join(ROOT, 'deploy/helm/loyaltyhub');
const TEMPLATES = path.join(CHART, 'templates');
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8');

const REALM = 'deploy/idp/realm.json';
const COMPOSE = 'deploy/compose/reference.yml';
const TOPICS = ['lh.actions.v1', 'lh.effects.v1', 'lh.facts.v1', 'lh.audit.v1', 'lh.dlq.v1'];
const placeholders = () => [...new Set(read(REALM).match(/\$\{LH_[A-Z0-9_]+\}/g).map((p) => p.slice(2, -1)))].sort();

test('la copia del realm nel chart è identica a deploy/idp/realm.json (Helm non legge file fuori dal chart)', () => {
  assert.equal(read('deploy/helm/loyaltyhub/files/realm.json'), read(REALM),
    'aggiornare con: cp deploy/idp/realm.json deploy/helm/loyaltyhub/files/realm.json');
});

test('ogni segnaposto ${LH_*} del realm arriva a Keycloak nel chart e nel compose di riferimento', () => {
  const idp = read('deploy/helm/loyaltyhub/templates/idp.yaml');
  const sources = (idp.match(/range \$src := list ([^}]+)}}/) || [])[1] || '';
  const compose = read(COMPOSE);
  for (const v of placeholders()) {
    const src = v.match(/^LH_SOURCE_([A-Z]+)_JWKS_URL$/);
    const inChart = new RegExp(`name: ${v}\\b`).test(idp)
      || (src && idp.includes('LH_SOURCE_{{ upper $src }}_JWKS_URL') && sources.includes(`"${src[1].toLowerCase()}"`));
    assert.ok(inChart, `${v} manca nel Deployment idp del chart`);
    assert.match(compose, new RegExp(`^\\s+${v}:`, 'm'), `${v} manca nel servizio idp del compose di riferimento`);
  }
});

test('i topic restano 5 con i nomi di sempre (ADR-004, ADR-028)', () => {
  const strimzi = read('deploy/helm/loyaltyhub/templates/kafka-strimzi.yaml');
  const declared = [...strimzi.matchAll(/"(lh\.[a-z]+\.v1)"/g)].map((m) => m[1]).sort();
  assert.deepEqual(declared, [...TOPICS].sort());
  const props = read('libs/lh-common/src/main/java/io/loyaltyhub/common/kafka/LoyaltyHubProperties.java');
  const defaults = [...props.matchAll(/= "(lh\.[a-z]+\.v1)";/g)].map((m) => m[1]).sort();
  assert.deepEqual(defaults, [...TOPICS].sort(), 'nomi di default in LoyaltyHubProperties');
});

test('values.yaml non contiene segreti in chiaro: solo riferimenti {name, key} o nomi di Secret', () => {
  const lines = read('deploy/helm/loyaltyhub/values.yaml').split('\n');
  const offenders = lines
    .map((l, i) => [i + 1, l])
    .filter(([, l]) => !l.trimStart().startsWith('#'))
    .filter(([, l]) => {
      const m = l.match(/^\s*-?\s*([A-Za-z0-9]+)\s*:\s*(.*)$/);
      if (!m || !/(password|secret|token|apikey|sessionkey|subjectkey)/i.test(m[1]) || /Name$/.test(m[1])) return false;
      const value = m[2].trim();
      // Ammessi: vuoto (mappa annidata), riferimento {name, key}, elenco di nomi (pullSecrets: []).
      return value !== '' && !value.startsWith('{') && !value.startsWith('[') && !value.startsWith('#');
    });
  assert.deepEqual(offenders, [], `valori in chiaro: ${offenders.map(([n, l]) => `${n}: ${l.trim()}`).join('; ')}`);
});

test('ogni Pod del chart usa la sicurezza comune (restricted) e dichiara le risorse', () => {
  for (const f of fs.readdirSync(TEMPLATES)) {
    const t = fs.readFileSync(path.join(TEMPLATES, f), 'utf8');
    if (!/^kind: (Deployment|Job|StatefulSet|DaemonSet)$/m.test(t)) continue;
    assert.match(t, /include "loyaltyhub\.podCommon"/, `${f}: manca podCommon (securityContext, token non montato)`);
    assert.match(t, /include "loyaltyhub\.containerSecurityContext"/, `${f}: manca containerSecurityContext`);
    assert.match(t, /resources:\s*\n\s*\{\{- toYaml [^|]+\.resources/, `${f}: mancano requests e limits`);
    assert.doesNotMatch(t, /privileged: true|hostNetwork|hostPID|hostPath/, `${f}: campo vietato da restricted`);
  }
  const values = read('deploy/helm/loyaltyhub/values.yaml');
  for (const k of ['runAsNonRoot: true', 'allowPrivilegeEscalation: false', 'type: RuntimeDefault', 'drop: ["ALL"]']) {
    assert.ok(values.includes(k), `values.yaml: manca ${k}`);
  }
});

test('compose di riferimento: ruoli dell\'immagine unica, nessun segreto in chiaro', () => {
  const compose = read(COMPOSE);
  for (const role of ['hub', 'web']) {
    assert.match(compose, new RegExp(`LH_ROLE: "${role}"`), `manca il ruolo ${role}`);
  }
  assert.match(compose, /LH_MODE: "external"/);
  const secretLines = compose.split('\n').filter((l) => /^\s+[A-Z_]*(PASSWORD|SECRET|SESSION_KEY|SUBJECT_KEY)[A-Z_]*:\s/.test(l));
  assert.ok(secretLines.length > 0);
  for (const l of secretLines) {
    assert.match(l, /:\s*"\$\{[A-Z_]+:-\}"\s*$/, `segreto non preso dall'ambiente: ${l.trim()}`);
  }
  assert.doesNotMatch(compose, /LH_KAFKA_TOPIC_PARTITIONS: "?[0-9]/, 'le partizioni vengono da ${LH_KAFKA_TOPIC_PARTITIONS}');
  // Immagine obbligatoria: nessun nome inventato come default.
  const images = [...compose.matchAll(/image: "\$\{LH_IMAGE([^}]*)\}"/g)].map((m) => m[1]);
  assert.equal(images.length, 3, 'migrate, hub e web usano ${LH_IMAGE}');
  assert.ok(images.every((m) => m.startsWith(':?')), `LH_IMAGE senza default: ${images}`);
  // Porte pubblicate solo sull'indirizzo scelto (default 127.0.0.1): HTTP in chiaro, console di Keycloak (Q-392).
  const ports = [...compose.matchAll(/^\s+- "([^"]+:\d+:\d+)"$/gm)].map((m) => m[1]);
  assert.ok(ports.length >= 2 && ports.every((p) => p.startsWith('${LH_BIND_ADDRESS:-127.0.0.1}:')), `porte: ${ports}`);
  // Keycloak con un ruolo proprio, non con il superutente di Postgres.
  assert.match(compose, /KC_DB_USERNAME: "idp"/);
  assert.match(compose, /KC_DB_PASSWORD: "\$\{LH_IDP_DB_PASSWORD:-\}"/);
  assert.match(read('deploy/compose/postgres-init/10-idp.sh'), /CREATE ROLE idp LOGIN PASSWORD :'pw'/);
  // BFF del web (F2-SEC-06, Q-412): stesso emittente dell'hub, stessa origine e stesso segreto del client `web` dati a
  // Keycloak, chiave delle sessioni dall'ambiente; la guardia ferma il web con un emittente non https (Q-420).
  const service = (name) => compose.split(new RegExp(`\\n  ${name}:\\n`))[1].split(/\n  [a-z]+:\n/)[0];
  const envOf = (block, v) => (block.match(new RegExp(`^\\s+${v}: (.+)$`, 'm')) || [])[1];
  const [web, hub, idp] = ['web', 'hub', 'idp'].map(service);
  assert.ok(envOf(web, 'LH_OIDC_ISSUER'), 'LH_OIDC_ISSUER del web');
  assert.equal(envOf(web, 'LH_OIDC_ISSUER'), envOf(hub, 'LH_OIDC_ISSUER'), 'emittente del web e dell\'hub');
  assert.equal(envOf(web, 'LH_WEB_URL'), envOf(idp, 'LH_WEB_URL'), 'LH_WEB_URL del web e di Keycloak');
  assert.equal(envOf(web, 'LH_WEB_CLIENT_SECRET'), envOf(idp, 'LH_WEB_CLIENT_SECRET'), 'segreto del client web');
  assert.equal(envOf(web, 'LH_WEB_CLIENT_ID'), '"web"', 'client del realm');
  assert.equal(envOf(web, 'LH_WEB_SESSION_KEY'), '"${LH_WEB_SESSION_KEY:-}"');
  // Chiave dello pseudonimo subjectRef (F2-SEC-09, ADR-048, Q-552): solo l'hub, dall'ambiente, richiesta in enterprise.
  assert.equal(envOf(hub, 'LH_SUBJECT_KEY'), '"${LH_SUBJECT_KEY:-}"');
  assert.equal(envOf(hub, 'LH_REQUIRED_ENV_ENTERPRISE'), '"LH_SUBJECT_KEY"');
  assert.equal(envOf(web, 'LH_SUBJECT_KEY'), undefined, 'il web non vede la chiave dello pseudonimo');
  assert.equal(envOf(idp, 'LH_SUBJECT_KEY'), undefined, 'Keycloak non vede la chiave dello pseudonimo');
  assert.doesNotMatch(service('migrate'), /SUBJECT_KEY/, 'le migrazioni non usano la chiave dello pseudonimo');
  assert.match(web, /entrypoint: \*lh-web-oidc-guard\n\s+command: \["lh-web-oidc-guard", "\/opt\/lh\/entrypoint\.sh"\]/);
});

test('compose di riferimento: la guardia del web rifiuta emittente e origine non https o malformati, solo in enterprise', () => {
  const block = read(COMPOSE).split('x-lh-web-oidc-guard: &lh-web-oidc-guard\n')[1].split('\n\n')[0];
  // Testo dello script come lo passa Compose: `$$` diventa `$`.
  const script = block.split('  - |\n')[1].split('\n').map((l) => l.replace(/^ {4}/, '')).join('\n').replaceAll('$$', '$');
  const run = (env) => spawnSync('sh', ['-c', script, 'lh-web-oidc-guard', 'printf', 'avviato'],
    { encoding: 'utf8', env: { PATH: process.env.PATH, ...env } });
  const loopback = 'http://localhost:8180/realms/loyaltyhub';
  const tls = 'https://idp.example.org/realms/loyaltyhub';
  const web = 'https://loyalty.example.org';
  const refused = (env, message) => {
    const r = run({ LH_PROFILE: 'enterprise', ...env });
    assert.equal(r.status, 1, JSON.stringify(env));
    assert.equal(r.stdout, '', JSON.stringify(env));
    assert.match(r.stderr, message, JSON.stringify(env));
  };
  const badIssuer = /LH_IDP_PUBLIC_URL deve essere l'URL https/;
  const badWeb = /LH_WEB_URL deve essere l'origine https/;
  // Emittente: https://<host>[:porta]/realms/<realm>, senza `//` (LH_IDP_PUBLIC_URL con barra finale) né percorsi.
  for (const iss of [loopback, 'http://idp.example.org/realms/loyaltyhub', undefined, 'https://idp.example.org//realms/loyaltyhub',
    'https://idp.example.org/realms/loyaltyhub/', 'https://idp.example.org/auth/realms/loyaltyhub', 'https://idp.example.org/realms/',
    'https://user@idp.example.org/realms/loyaltyhub', 'https:///realms/loyaltyhub']) {
    refused({ LH_OIDC_ISSUER: iss, LH_WEB_URL: web }, badIssuer);
  }
  // Origine del web: https, senza percorso né barra finale (Keycloak confronta ${LH_WEB_URL}/api/auth/callback).
  for (const origin of ['http://localhost:3000', undefined, 'https://loyalty.example.org/', 'https://loyalty.example.org/app',
    'https://']) {
    refused({ LH_OIDC_ISSUER: tls, LH_WEB_URL: origin }, badWeb);
  }
  // Entrambi sbagliati: entrambe le cause in un solo avvio.
  const both = run({ LH_PROFILE: 'enterprise', LH_OIDC_ISSUER: loopback, LH_WEB_URL: 'http://localhost:3000' });
  assert.match(both.stderr, badIssuer);
  assert.match(both.stderr, badWeb);
  assert.equal(run({ LH_PROFILE: 'enterprise', LH_OIDC_ISSUER: tls, LH_WEB_URL: web }).stdout, 'avviato');
  assert.equal(run({ LH_PROFILE: 'enterprise', LH_OIDC_ISSUER: 'https://idp.example.org:8443/realms/loyaltyhub',
    LH_WEB_URL: 'https://loyalty.example.org:8443' }).stdout, 'avviato');
  assert.equal(run({ LH_PROFILE: 'demo', LH_OIDC_ISSUER: loopback, LH_WEB_URL: 'http://localhost:3000' }).stdout, 'avviato');
});

test('compose di riferimento: l\'hub richiede LH_SUBJECT_KEY (o LH_SUBJECT_KEY_FILE) solo in enterprise, senza stamparla', () => {
  const block = read(COMPOSE).split('x-lh-require-env-or-file: &lh-require-env-or-file\n')[1].split('\n\n')[0];
  const script = block.split('  - |\n')[1].split('\n').map((l) => l.replace(/^ {4}/, '')).join('\n').replaceAll('$$', '$');
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'lh-subject-'));
  const keyFile = path.join(dir, 'subject-key');
  const secret = 'chiave-di-prova-da-non-stampare';
  fs.writeFileSync(keyFile, secret);
  const base = { DB_PASSWORD: 'x', LH_REQUIRED_ENV: 'DB_PASSWORD', LH_REQUIRED_ENV_ENTERPRISE: 'LH_SUBJECT_KEY' };
  const run = (env) => spawnSync('sh', ['-c', script, 'lh-require-env', 'printf', 'avviato'],
    { encoding: 'utf8', env: { PATH: process.env.PATH, ...base, ...env } });
  // Enterprise senza chiave (assente o vuota): il container si ferma e dice quale variabile manca.
  for (const env of [{ LH_PROFILE: 'enterprise' }, { LH_PROFILE: 'enterprise', LH_SUBJECT_KEY: '' }]) {
    const r = run(env);
    assert.equal(r.status, 1, JSON.stringify(env));
    assert.equal(r.stdout, '');
    assert.match(r.stderr, /Variabile obbligatoria mancante: LH_SUBJECT_KEY \(o LH_SUBJECT_KEY_FILE\)/);
  }
  // Con la chiave, dall'ambiente o da file (una variabile vuota non oscura il file); il valore non compare mai.
  for (const env of [{ LH_SUBJECT_KEY: secret }, { LH_SUBJECT_KEY_FILE: keyFile }, { LH_SUBJECT_KEY: '', LH_SUBJECT_KEY_FILE: keyFile }]) {
    const r = run({ LH_PROFILE: 'enterprise', ...env });
    assert.equal(r.stdout, 'avviato', JSON.stringify(env));
    assert.doesNotMatch(r.stdout + r.stderr, new RegExp(secret));
  }
  // Demo (e profilo assente): la chiave non serve; le altre variabili obbligatorie restano tali.
  assert.equal(run({ LH_PROFILE: 'demo' }).stdout, 'avviato');
  assert.equal(run({}).stdout, 'avviato');
  const noDb = run({ LH_PROFILE: 'demo', DB_PASSWORD: '' });
  assert.equal(noDb.status, 1);
  assert.match(noDb.stderr, /Variabile obbligatoria mancante: DB_PASSWORD/);
  fs.rmSync(dir, { recursive: true, force: true });
});

test('entrypoint dell\'immagine: una variabile vuota non oscura <VAR>_FILE, una valorizzata vince', () => {
  const src = read('deploy/image/entrypoint.sh');
  const loop = `${src.split('# JVM per percorso assoluto')[0]}\nprintf '%s' "\${DB_PASSWORD-<assente>}"\n`;
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'lh-entry-'));
  const secret = path.join(dir, 'pw');
  fs.writeFileSync(secret, 'dal-file');
  const run = (env) => spawnSync('sh', ['-c', loop], { encoding: 'utf8', env: { PATH: process.env.PATH, ...env } });
  assert.equal(run({ DB_PASSWORD: '', DB_PASSWORD_FILE: secret }).stdout, 'dal-file');
  assert.equal(run({ DB_PASSWORD: 'dall-ambiente', DB_PASSWORD_FILE: secret }).stdout, 'dall-ambiente');
  assert.equal(run({ DB_PASSWORD_FILE: secret }).stdout, 'dal-file');
  assert.equal(run({}).stdout, '<assente>');
});

test('porte dei ruoli allineate all\'immagine unica: hub 8080, web 3000 (deploy/image/entrypoint.sh)', () => {
  const entrypoint = read('deploy/image/entrypoint.sh');
  const hubPort = (entrypoint.match(/ROLE" = "hub"[\s\S]*?export PORT="(\d+)"/) || [])[1];
  const webPort = (entrypoint.match(/ROLE" = "web"[\s\S]*?export PORT="(\d+)"/) || [])[1];
  assert.equal(hubPort, '8080', 'porta dell\'hub nell\'entrypoint');
  assert.equal(webPort, '3000', 'porta del web nell\'entrypoint');
  const helpers = read('deploy/helm/loyaltyhub/templates/_helpers.tpl');
  assert.match(helpers, new RegExp(`define "loyaltyhub\\.port\\.hub" -}}${hubPort}\\{`));
  assert.match(helpers, new RegExp(`define "loyaltyhub\\.port\\.web" -}}${webPort}\\{`));
  assert.doesNotMatch(read('deploy/helm/loyaltyhub/values.yaml'), /^\s+port: (8080|3000)\s*$/m,
    'le porte dei ruoli non sono valori: l\'immagine le impone');
  const compose = read(COMPOSE);
  const svcUrls = [...compose.matchAll(/LH_SVC_[A-Z]+_URL: "([^"]+)"/g)].map((m) => m[1]);
  assert.equal(svcUrls.length, 8);
  assert.ok(svcUrls.every((u) => u === `http://hub:${hubPort}`), `URL dei moduli nel compose: ${svcUrls}`);
  assert.match(compose, new RegExp(`:${webPort}:${webPort}"`), 'porta del web nel compose');
});

// ---- Immagini condivise (Q-483) ----
// Dependabot aggiorna solo deploy/docker-compose.yml: compose di riferimento e values del chart si allineano a mano.
// Kafka, Postgres e Keycloak devono avere la stessa versione ovunque compaiono; con due digest, anche lo stesso digest.
const LOCAL_COMPOSE = 'deploy/docker-compose.yml';
const VALUES = 'deploy/helm/loyaltyhub/values.yaml';
// Valori del job `helm install (kind)`: Kafka di Strimzi e Postgres di CloudNativePG fissati sulle linee dei compose.
const KIND_VALUES = 'deploy/helm/loyaltyhub/ci/kind-values.yaml';
const SHARED_IMAGES = ['apache/kafka', 'postgres', 'quay.io/keycloak/keycloak'];

/** `nome[:tag][@digest]` → { name, tag, digest }. Il tag segue l'ultimo `:` dopo l'ultima `/` (registro con porta). */
export function parseImageRef(ref) {
  const [nameTag, digest = ''] = ref.split('@');
  const colon = nameTag.lastIndexOf(':');
  const hasTag = colon > nameTag.lastIndexOf('/');
  return { name: hasTag ? nameTag.slice(0, colon) : nameTag, tag: hasTag ? nameTag.slice(colon + 1) : '', digest };
}

/** Immagini letterali di un file compose (virgolette doppie, singole o nessuna; commento in coda ammesso); l'immagine
 * unica (`${LH_IMAGE…}`, interpolata) non conta. */
export function composeImageRefs(text) {
  return [...text.matchAll(/^\s+image:\s*["']?([^\s"'#]+)["']?\s*(?:#.*)?$/gm)].map((m) => m[1])
    .filter((r) => !r.startsWith('${')).map(parseImageRef);
}

/** Immagini del chart legate a quelle dei compose: repository e tag (anche `tag@sha256:…`), Kafka di Strimzi, immagine
 * di CloudNativePG. Virgolette doppie, singole o nessuna. */
export function valuesImageRefs(text) {
  const refs = [...text.matchAll(/^\s+repository: ["']?([^\s"'#]+)["']?\s*(?:#.*)?\n\s+tag: ["']?([^\s"'#]+)["']?/gm)]
    .map((m) => parseImageRef(`${m[1]}:${m[2]}`));
  // Strimzi supporta solo alcune patch di Kafka: si confronta la linea major.minor. Vuoto = sceglie l'operatore.
  const strimzi = (text.match(/^ {2}strimzi:\n(?: {4}.*\n|\s*#.*\n)*? {4}version: ["']?([^\s"'#]*)["']?/m) || [])[1];
  if (strimzi) refs.push({ name: 'apache/kafka', tag: strimzi, digest: '', line: true });
  // CloudNativePG ha immagini proprie di Postgres: si confronta la versione. Vuoto = immagine di default dell'operatore.
  const cnpg = (text.match(/^\s+imageName: ["']?([^\s"'#]*)["']?/m) || [])[1];
  if (cnpg) refs.push({ ...parseImageRef(cnpg), name: 'postgres', numeric: true });
  return refs;
}

const numericVersion = (tag) => (tag.match(/^\d+(?:\.\d+)*/) || [''])[0];
const versionLine = (tag) => numericVersion(tag).split('.').slice(0, 2).join('.');

/** Divergenze fra le fonti ({ file: [riferimenti] }) sulle immagini condivise; vuoto = tutto allineato. */
export function sharedImageDrift(sources, shared = SHARED_IMAGES) {
  const problems = [];
  for (const name of shared) {
    const found = Object.entries(sources)
      .flatMap(([file, refs]) => refs.filter((r) => r.name === name).map((r) => ({ file, ...r })));
    const ref = found.find((r) => !r.line && !r.numeric);
    if (!ref) continue;
    for (const r of found.filter((x) => x !== ref)) {
      const at = `${name}: ${r.file} ha ${r.tag}${r.digest ? `@${r.digest}` : ''}, ${ref.file} ha ${ref.tag}`;
      if (r.line) {
        if (versionLine(r.tag) !== versionLine(ref.tag)) {
          problems.push(`${at} (linea ${versionLine(r.tag)} invece di ${versionLine(ref.tag)})`);
        }
      } else if (r.numeric) {
        if (numericVersion(r.tag) !== numericVersion(ref.tag)) problems.push(at);
      } else if (r.tag !== ref.tag) {
        problems.push(at);
      } else if (r.digest && ref.digest && r.digest !== ref.digest) {
        problems.push(`${name}: stesso tag ${r.tag} con digest diversi in ${ref.file} e ${r.file}`);
      }
    }
  }
  return problems;
}

test('immagini condivise: stessa versione nei due compose e nei values del chart (Q-483)', () => {
  const sources = {
    [LOCAL_COMPOSE]: composeImageRefs(read(LOCAL_COMPOSE)),
    [COMPOSE]: composeImageRefs(read(COMPOSE)),
    [VALUES]: valuesImageRefs(read(VALUES)),
    [KIND_VALUES]: valuesImageRefs(read(KIND_VALUES)),
  };
  for (const name of SHARED_IMAGES) {
    for (const file of [LOCAL_COMPOSE, COMPOSE]) {
      assert.ok(sources[file].some((r) => r.name === name && r.tag), `${file}: manca ${name} con un tag`);
    }
  }
  assert.ok(sources[VALUES].some((r) => r.name === 'quay.io/keycloak/keycloak'), `${VALUES}: manca l'immagine di Keycloak`);
  // Il job kind fissa entrambe: senza, userebbe le versioni di default degli operatori (Kafka 4.3, Postgres 18).
  const pinned = sources[KIND_VALUES].filter((r) => r.line || r.numeric).map((r) => r.name).sort();
  assert.deepEqual(pinned, ['apache/kafka', 'postgres'], `${KIND_VALUES}: kafka.strimzi.version e postgres.cloudnativepg.imageName`);
  assert.deepEqual(sharedImageDrift(sources), [], 'allineare tag e digest a deploy/docker-compose.yml');
});

test('immagini condivise: il controllo trova tag, digest e linee divergenti', () => {
  assert.deepEqual(parseImageRef('registry.example.org:5000/lh/kafka:4.2.2@sha256:abc'),
    { name: 'registry.example.org:5000/lh/kafka', tag: '4.2.2', digest: 'sha256:abc' });
  assert.deepEqual(parseImageRef('postgres'), { name: 'postgres', tag: '', digest: '' });
  // Commento in coda e virgolette singole nei compose; `tag@digest` e virgolette singole nei values.
  assert.deepEqual(composeImageRefs("  a:\n    image: apache/kafka:4.2.2 # nota\n  b:\n    image: 'postgres:17.11'\n"),
    [{ name: 'apache/kafka', tag: '4.2.2', digest: '' }, { name: 'postgres', tag: '17.11', digest: '' }]);
  assert.deepEqual(valuesImageRefs("    image:\n      repository: 'quay.io/keycloak/keycloak'\n      tag: '26.7.4@sha256:abc'\n"
    + "kafka:\n  strimzi:\n    version: '4.2.1'\n"), [
    { name: 'quay.io/keycloak/keycloak', tag: '26.7.4', digest: 'sha256:abc' },
    { name: 'apache/kafka', tag: '4.2.1', digest: '', line: true },
  ]);
  const compose = (kafka, pg) => composeImageRefs(
    `services:\n  kafka:\n    image: ${kafka}\n  pg:\n    image: "${pg}"\n  hub:\n    image: "\${LH_IMAGE:?obbligatoria}"\n`);
  const values = (tag, strimzi = '', cnpg = '') => valuesImageRefs(
    `roles:\n  idp:\n    image:\n      repository: quay.io/keycloak/keycloak\n      tag: "${tag}"\n`
    + `postgres:\n  cloudnativepg:\n    imageName: "${cnpg}"\n`
    + `kafka:\n  strimzi:\n    apiVersion: kafka.strimzi.io/v1\n    # commento\n    version: "${strimzi}"\n`);
  const same = {
    a: compose('apache/kafka:4.2.2@sha256:1', 'postgres:17.11@sha256:2'),
    b: compose('apache/kafka:4.2.2@sha256:1', 'postgres:17.11@sha256:2'),
    v: values('26.7.4'),
    k: [{ name: 'quay.io/keycloak/keycloak', tag: '26.7.4', digest: '' }],
  };
  assert.equal(same.a.length, 2, 'l\'immagine unica interpolata non conta');
  assert.deepEqual(sharedImageDrift(same), []);
  assert.match(sharedImageDrift({ ...same, b: compose('apache/kafka:3.9.2@sha256:1', 'postgres:17.11@sha256:2') })[0],
    /^apache\/kafka: b ha 3\.9\.2/);
  assert.match(sharedImageDrift({ ...same, b: compose('apache/kafka:4.2.2@sha256:9', 'postgres:17.11@sha256:2') })[0],
    /stesso tag 4\.2\.2 con digest diversi/);
  // Il riferimento è la prima fonte (nel controllo reale deploy/docker-compose.yml, quello aggiornato da Dependabot).
  assert.deepEqual(sharedImageDrift({ ...same, v: values('26.7.5') }),
    ['quay.io/keycloak/keycloak: k ha 26.7.4, v ha 26.7.5']);
  // Strimzi: conta la linea (4.2.1 va con 4.2.2, 4.3.1 no); CloudNativePG: la versione di Postgres.
  assert.deepEqual(sharedImageDrift({ ...same, v: values('26.7.4', '4.2.1', 'ghcr.io/cloudnative-pg/postgresql:17.11') }), []);
  assert.match(sharedImageDrift({ ...same, v: values('26.7.4', '4.3.1') })[0], /linea 4\.3 invece di 4\.2/);
  assert.match(sharedImageDrift({ ...same, v: values('26.7.4', '', 'ghcr.io/cloudnative-pg/postgresql:18.1') })[0],
    /^postgres: v ha 18\.1/);
});

const helm = spawnSync('helm', ['version', '--short'], { encoding: 'utf8' });
const hasHelm = helm.status === 0;
const inCi = /^(1|true)$/i.test(process.env.CI || '');
const CI_VALUES = path.join(CHART, 'ci/lint-values.yaml');
const template = (...sets) => spawnSync('helm', ['template', 'lh', CHART, '--kube-version', '1.31.0', '-f', CI_VALUES,
  ...sets.flatMap((s) => ['--set', s])], { encoding: 'utf8' });
const refuses = (message, ...sets) => {
  const r = template(...sets);
  assert.notEqual(r.status, 0, `dovrebbe fallire: ${sets.join(' ')}`);
  assert.match(r.stderr, message, `${sets.join(' ')}: ${r.stderr}`);
};

test('helm presente in CI', () => {
  // Fuori dalla CI la prova di helm si salta (sotto); in CI una prova saltata passerebbe senza verificare nulla.
  assert.ok(hasHelm || !inCi, 'CI=true ma helm non è nel PATH: installarlo nel job (azure/setup-helm)');
});

test('helm lint e helm template: valori di default, servizi gestiti, rifiuti', { skip: !hasHelm && 'helm non installato' }, () => {
  const lint = spawnSync('helm', ['lint', CHART, '--strict', '-f', CI_VALUES], { encoding: 'utf8' });
  assert.equal(lint.status, 0, lint.stdout + lint.stderr);

  const tpl = template();
  assert.equal(tpl.status, 0, tpl.stderr);
  const topics = [...tpl.stdout.matchAll(/topicName: (\S+)/g)].map((m) => m[1]).sort();
  assert.deepEqual(topics, [...TOPICS].sort());
  // Risorse Strimzi con l'API v1 (Strimzi 0.51 o successivo, Q-490): niente v1beta2, niente annotazioni KRaft e node pool
  // (ignorate da Strimzi 0.48).
  const strimziDocs = tpl.stdout.split(/^---$/m).filter((d) => /^kind: (Kafka|KafkaNodePool|KafkaTopic)$/m.test(d));
  assert.deepEqual(strimziDocs.map((d) => d.match(/^kind: (\S+)$/m)[1]).sort(),
    ['Kafka', 'KafkaNodePool', ...Array(5).fill('KafkaTopic')]);
  for (const d of strimziDocs) assert.match(d, /^apiVersion: kafka\.strimzi\.io\/v1$/m, d);
  assert.doesNotMatch(tpl.stdout, /kafka\.strimzi\.io\/v1beta2|strimzi\.io\/(kraft|node-pools)/);
  assert.match(tpl.stdout, /jdbc:postgresql:\/\/lh-loyaltyhub-pg-rw:5432\/loyaltyhub\?sslmode=require/);
  assert.match(tpl.stdout, /- path: \/realms\/loyaltyhub\//);
  assert.doesNotMatch(tpl.stdout, /- path: \/realms\/\s/);
  const webDocs = (out) => out.split(/^---$/m).filter((d) => /^  name: lh-loyaltyhub-web$/m.test(d));
  const webDeployment = webDocs(tpl.stdout).find((d) => /^kind: Deployment$/m.test(d));
  assert.ok(webDeployment, 'Deployment web');
  assert.match(webDeployment, /livenessProbe:\s*\n\s*tcpSocket:/, 'liveness del web senza giro verso l\'hub');
  // BFF OIDC (F2-SEC-06, Q-412): emittente dell'hub, origine pubblica, segreti solo da Secret, una replica (Q-409).
  const env = (doc, name) => (doc.match(new RegExp(`- name: ${name}\\n\\s+(value: .+|valueFrom:\\n\\s+secretKeyRef: .+)`)) || [])[1];
  assert.equal(env(webDeployment, 'LH_OIDC_ISSUER'), 'value: "https://idp.example.org/realms/loyaltyhub"');
  const hubDeployment = tpl.stdout.split(/^---$/m)
    .find((d) => /^kind: Deployment$/m.test(d) && /^  name: lh-loyaltyhub-hub$/m.test(d));
  assert.equal(env(webDeployment, 'LH_OIDC_ISSUER'), env(hubDeployment, 'LH_OIDC_ISSUER'), 'stesso emittente dell\'hub');
  assert.equal(env(webDeployment, 'LH_WEB_URL'), 'value: "https://loyalty.example.org"');
  assert.equal(env(webDeployment, 'LH_WEB_CLIENT_ID'), 'value: "web"');
  assert.equal(env(webDeployment, 'LH_WEB_CLIENT_SECRET'),
    'valueFrom:\n                secretKeyRef: { name: lh-idp-clients, key: web-client-secret }', 'stesso Secret di Keycloak');
  assert.equal(env(webDeployment, 'LH_WEB_SESSION_KEY'),
    'valueFrom:\n                secretKeyRef: { name: lh-web-session, key: session-key }');
  assert.doesNotMatch(webDeployment, /NODE_EXTRA_CA_CERTS/);
  assert.match(webDeployment, /^  replicas: 1$/m);
  assert.deepEqual(webDocs(tpl.stdout).map((d) => d.match(/^kind: (\S+)$/m)[1]).sort(), ['Deployment', 'Service'],
    'web senza HPA né PDB nel profilo enterprise');
  const idpDeployment = tpl.stdout.split(/^---$/m)
    .find((d) => /^kind: Deployment$/m.test(d) && /^  name: lh-loyaltyhub-idp$/m.test(d));
  assert.equal(env(idpDeployment, 'LH_WEB_URL'), env(webDeployment, 'LH_WEB_URL'), 'stessa origine nel realm');
  // CA privata dell'emittente e segreto proprio del web (IdP aziendale).
  const ca = template('roles.web.bff.issuerCaBundle.name=corp-ca', 'roles.web.bff.clientSecret.name=lh-web-oidc',
    'publicUrls.web=https://loyalty.example.org/');
  assert.equal(ca.status, 0, ca.stderr);
  const caWeb = webDocs(ca.stdout).find((d) => /^kind: Deployment$/m.test(d));
  assert.equal(env(caWeb, 'NODE_EXTRA_CA_CERTS'), 'value: /etc/lh/issuer-ca/ca.crt');
  assert.match(caWeb, /- name: issuer-ca\n\s+configMap:\n\s+name: corp-ca\n\s+items:\n\s+- key: ca\.crt\n\s+path: ca\.crt/);
  assert.match(caWeb, /mountPath: \/etc\/lh\/issuer-ca\n\s+readOnly: true/);
  assert.match(env(caWeb, 'LH_WEB_CLIENT_SECRET'), /name: lh-web-oidc, key: web-client-secret/);
  assert.equal(env(caWeb, 'LH_WEB_URL'), 'value: "https://loyalty.example.org"', 'barra finale tolta');
  // Origine normalizzata come la ricava il BFF (new URL): host minuscolo, niente :443. Keycloak confronta alla lettera.
  const norm = template('publicUrls.web=https://Loyalty.Example.ORG:443/');
  assert.equal(norm.status, 0, norm.stderr);
  const deployment = (out, name) => out.split(/^---$/m)
    .find((d) => /^kind: Deployment$/m.test(d) && new RegExp(`^  name: ${name}$`, 'm').test(d));
  for (const role of ['web', 'idp']) {
    assert.equal(env(deployment(norm.stdout, `lh-loyaltyhub-${role}`), 'LH_WEB_URL'), 'value: "https://loyalty.example.org"', role);
  }
  // Profilo demo: nessuna variabile del BFF, repliche e HPA liberi (niente sessioni).
  const demo = template('global.profile=demo', 'roles.web.autoscaling.enabled=true', 'roles.web.autoscaling.maxReplicas=6');
  assert.equal(demo.status, 0, demo.stderr);
  assert.doesNotMatch(webDocs(demo.stdout).join('\n'), /LH_OIDC_ISSUER|LH_WEB_|secretKeyRef|NODE_EXTRA_CA_CERTS/);
  assert.ok(webDocs(demo.stdout).some((d) => /^kind: HorizontalPodAutoscaler$/m.test(d)));

  const managed = ['postgres.mode=external', 'postgres.external.host=pg.example.internal', 'kafka.mode=external',
    'kafka.external.bootstrapServers=kafka.example.internal:9093', 'kafka.external.sasl.username.name=lh-kafka',
    'kafka.external.sasl.password.name=lh-kafka'];
  const m = template(...managed);
  assert.equal(m.status, 0, m.stderr);
  assert.doesNotMatch(m.stdout, /kind: (Kafka|KafkaTopic|Cluster)\n/);
  assert.match(m.stdout, /name: LH_KAFKA_TOPICS_MODIFY_CONFIGS\n\s+value: "true"/);

  refuses(/roles\.cms/, 'roles.cms.enabled=true');
  refuses(/image\.tag è obbligatorio/, 'image.tag=');
  refuses(/image\.repository è obbligatorio/, 'image.repository=');
  refuses(/INSECURE_CONFIG: kafka\.external\.security=PLAINTEXT/, ...managed, 'kafka.external.security=PLAINTEXT');
  refuses(/INSECURE_CONFIG: postgres\.external\.jdbcParams/, ...managed, 'postgres.external.jdbcParams=');
  refuses(/INSECURE_CONFIG: postgres\.external\.jdbcParams/, ...managed, 'postgres.external.jdbcParams=sslmode=prefer');
  refuses(/non può superare i broker/, 'kafka.topics.replicas=3', 'kafka.strimzi.replicas=1');
  refuses(/kafka\.strimzi\.apiVersion/, 'kafka.strimzi.apiVersion=kafka.strimzi.io/v1beta2');
  // Anche senza lo schema dei values il chart rifiuta v1beta2 con il motivo.
  const noSchema = spawnSync('helm', ['template', 'lh', CHART, '--kube-version', '1.31.0', '-f', CI_VALUES,
    '--skip-schema-validation', '--set', 'kafka.strimzi.apiVersion=kafka.strimzi.io/v1beta2'], { encoding: 'utf8' });
  assert.notEqual(noSchema.status, 0);
  assert.match(noSchema.stderr, /kafka\.strimzi\.io\/v1beta2 non è supportata.*Strimzi 0\.51 o successivo \(Q-490\)/);
  // Identità nel profilo enterprise (regola 22, F2-SEC-06, Q-409, Q-419, Q-420).
  refuses(/INSECURE_CONFIG: emittente OIDC "http:\/\/idp\.example\.org\/realms\/loyaltyhub" non https/,
    'publicUrls.idp=http://idp.example.org');
  refuses(/INSECURE_CONFIG: emittente OIDC/, 'oidc.issuer=http://localhost:8180/realms/loyaltyhub');
  refuses(/INSECURE_CONFIG: publicUrls\.web/, 'publicUrls.web=http://loyalty.example.org');
  refuses(/INSECURE_CONFIG: publicUrls\.web/, 'publicUrls.web=https://loyalty.example.org/portale');
  refuses(/publicUrls\.web è obbligatorio/, 'publicUrls.web=');
  refuses(/ingress\.hosts\.web/, 'publicUrls.web=https://altro.example.org');
  refuses(/ingress\.hosts\.idp/, 'publicUrls.idp=https://altro-idp.example.org');
  refuses(/roles\.web\.bff\.sessionKey\.name è obbligatorio/, 'roles.web.bff.sessionKey.name=');
  // Chiave dello pseudonimo subjectRef (F2-SEC-09, ADR-048, Q-552, regola 22): solo l'hub, solo enterprise, solo da Secret.
  assert.equal(env(hubDeployment, 'LH_SUBJECT_KEY'),
    'valueFrom:\n                secretKeyRef: { name: lh-subject-key, key: subject-key }');
  const ownKey = template('roles.hub.subjectKey.name=lh-pseudonyms', 'roles.hub.subjectKey.key=subject');
  assert.equal(ownKey.status, 0, ownKey.stderr);
  assert.equal(env(deployment(ownKey.stdout, 'lh-loyaltyhub-hub'), 'LH_SUBJECT_KEY'),
    'valueFrom:\n                secretKeyRef: { name: lh-pseudonyms, key: subject }');
  const notHub = tpl.stdout.split(/^---$/m).filter((d) => /LH_SUBJECT_KEY/.test(d));
  assert.equal(notHub.length, 1, 'LH_SUBJECT_KEY solo nel Deployment dell\'hub (né web, né idp, né Job di migrazione)');
  assert.doesNotMatch(tpl.stdout, /LH_SUBJECT_KEY\s*\n\s+value:/, 'mai un valore in chiaro');
  refuses(/INSECURE_CONFIG: roles\.hub\.subjectKey\.name e \.key sono obbligatori.*openssl rand -base64 32.*Q-552/, 'roles.hub.subjectKey.name=');
  refuses(/roles\.hub\.subjectKey\.key: String length must be greater than or equal to 1/, 'roles.hub.subjectKey.key=');
  refuses(/Additional property value is not allowed/, 'roles.hub.subjectKey.value=in-chiaro');
  // Anche con --skip-schema-validation la verifica sta nel chart.
  for (const blank of ['roles.hub.subjectKey.name=', 'roles.hub.subjectKey.key=']) {
    const skipSchema = spawnSync('helm', ['template', 'lh', CHART, '--kube-version', '1.31.0', '-f', CI_VALUES,
      '--skip-schema-validation', '--set', blank], { encoding: 'utf8' });
    assert.notEqual(skipSchema.status, 0, blank);
    assert.match(skipSchema.stderr, /INSECURE_CONFIG: roles\.hub\.subjectKey/, blank);
  }
  // Profilo demo (e hub spento): nessuna variabile, nessun riferimento a un Secret che potrebbe non esistere.
  const demoHub = template('global.profile=demo', 'roles.hub.subjectKey.name=');
  assert.equal(demoHub.status, 0, demoHub.stderr);
  assert.doesNotMatch(demoHub.stdout, /LH_SUBJECT_KEY|lh-subject-key/);
  const noHub = template('roles.hub.enabled=false', 'roles.hub.subjectKey.name=');
  assert.equal(noHub.status, 0, noHub.stderr);
  refuses(/roles\.web\.bff\.clientSecret\.name è obbligatorio/, 'roles.idp.enabled=false',
    'oidc.issuer=https://sso.example.org/realms/loyaltyhub');
  refuses(/WEB_SINGLE_REPLICA/, 'roles.web.replicas=2');
  refuses(/WEB_SINGLE_REPLICA/, 'roles.web.autoscaling.enabled=true', 'roles.web.autoscaling.maxReplicas=6');
  refuses(/sessionIdleSeconds non può superare/, 'roles.web.bff.sessionIdleSeconds=40000');
  refuses(/Additional property/, 'roles.web.bff.clientSecret.value=in-chiaro');
  refuses(/WEB_SINGLE_REPLICA: roles\.web\.pdb\.enabled .*kubectl drain.*Q-419/, 'roles.web.pdb.enabled=true');
  // Con il ruolo idp: emittente e client del BFF sono quelli di Keycloak e del realm.
  refuses(/oidc\.issuer "https:\/\/sso\.example\.org\/realms\/loyaltyhub" diverso dall'emittente del ruolo idp/,
    'oidc.issuer=https://sso.example.org/realms/loyaltyhub');
  refuses(/roles\.web\.bff\.clientId "portale" non è il client del BFF nel realm del ruolo idp \("web"/,
    'roles.web.bff.clientId=portale');
  // Il realm usa publicUrls.web anche con il web spento.
  refuses(/INSECURE_CONFIG: publicUrls\.web/, 'roles.web.enabled=false', 'publicUrls.web=http://loyalty.example.org');
  assert.equal(template('oidc.issuer=https://idp.example.org/realms/loyaltyhub').status, 0, 'oidc.issuer uguale a quello di idp');
  // IdP aziendale (ruolo idp spento): emittente e client propri.
  assert.equal(template('roles.idp.enabled=false', 'oidc.issuer=https://sso.example.org/realms/loyaltyhub',
    'roles.web.bff.clientSecret.name=lh-web-oidc', 'roles.web.bff.clientId=portale').status, 0);
  // Profilo demo: nessuna di queste verifiche (niente BFF).
  assert.equal(template('global.profile=demo', 'roles.web.pdb.enabled=true', 'roles.web.bff.clientId=portale').status, 0);
  // Deroghe esplicite e documentate; il profilo demo non le richiede.
  assert.equal(template(...managed, 'kafka.external.security=PLAINTEXT', 'kafka.external.allowInsecure=true').status, 0);
  assert.equal(template(...managed, 'postgres.external.jdbcParams=', 'postgres.external.allowInsecure=true').status, 0);
  assert.equal(template(...managed, 'global.profile=demo', 'kafka.external.security=PLAINTEXT',
    'postgres.external.jdbcParams=').status, 0);
});

test('valori del job kind: un broker, un\'istanza, profilo demo, API tramite il gateway', { skip: !hasHelm && 'helm non installato' }, () => {
  const r = spawnSync('helm', ['template', 'lh', CHART, '--kube-version', '1.36.4', '-n', 'lh',
    '-f', path.join(CHART, 'ci/kind-values.yaml')], { encoding: 'utf8' });
  assert.equal(r.status, 0, r.stderr);
  const docs = r.stdout.split(/^---$/m);
  const doc = (kind, name) => docs.find((d) => new RegExp(`^kind: ${kind}$`, 'm').test(d)
    && new RegExp(`^  name: ${name}$`, 'm').test(d));
  // I nomi che il job aspetta (kubectl wait, diagnostica).
  assert.match(doc('Kafka', 'lh-loyaltyhub-kafka'), /^\s+version: 4\.2\.1$/m);
  assert.match(doc('KafkaNodePool', 'lh-loyaltyhub-kafka-dual'), /^  replicas: 1$/m);
  assert.match(doc('Cluster', 'lh-loyaltyhub-pg'), /^  instances: 1$/m);
  assert.match(doc('HTTPRoute', 'lh-loyaltyhub-api'), /- name: lh-gateway[\s\S]*- "api\.example\.org"/);
  for (const role of ['hub', 'web', 'idp']) {
    const d = doc('Deployment', `lh-loyaltyhub-${role}`);
    assert.ok(d, `Deployment ${role}`);
    assert.match(d, /^  replicas: 1$/m, role);
  }
  // Immagine caricata con `kind load`: nessun pull.
  assert.match(doc('Deployment', 'lh-loyaltyhub-hub'), /image: lh-image:ci\n\s+imagePullPolicy: Never/);
  assert.doesNotMatch(r.stdout, /^kind: (Ingress|HorizontalPodAutoscaler|PodDisruptionBudget)$/m);
  assert.match(r.stdout, /name: LH_PROFILE\n\s+value: "demo"/);
});

// ---- Osservabilità (F2-OBS-01, M8.6a; ADR-012, ADR-036, ADR-044) ----
// Metriche dell'hub in OTLP → collector → Prometheus di chi installa; dashboard SLO e regole condivise tra chart e compose.
const OBS = 'deploy/helm/loyaltyhub/files/observability';
const RULES = `${OBS}/slo-rules.json`;
const DASHBOARD = `${OBS}/dashboards/loyaltyhub-slo.json`;
const OBS_VALUES = path.join(CHART, 'ci/observability-values.yaml');
const PORTAL_CONTESTS = 'services/gamification-service/src/main/java/io/loyaltyhub/gamification/api/PortalContestsController.java';
/** Selettore dell'installazione: il job è <service.namespace>/<service.name>, con namespace che inizia per loyaltyhub (Q-527). */
const SEL = 'job=~"loyaltyhub[^/]*/hub"';
const RAW_METRICS = ['http_server_requests_seconds_count', 'http_server_requests_seconds_bucket', 'lh_action_to_points_seconds_count',
  'lh_action_to_points_seconds_bucket', 'lh_events_consumed_total', 'lh_events_dlq_total', 'lh_outbox_pending', 'process_uptime_seconds'];

const readJson = (p) => JSON.parse(read(p));
/** URI della giocata come la costruisce Spring dal controller: è l'etichetta `uri` di http.server.requests. */
const playUri = () => {
  const controller = read(PORTAL_CONTESTS);
  const base = controller.match(/@RequestMapping\("([^"]+)"\)/)[1];
  const play = controller.match(/@PostMapping\("([^"]*\/play)"\)/)[1];
  return `${base}${play}`;
};
/** Corpo del servizio `name` del compose (fino al servizio successivo); i nomi non hanno trattini. */
const composeService = (compose, name) => compose.split(new RegExp(`\\n  ${name}:\\n`))[1].split(/\n  [a-z]+:\n/)[0];
const composeEnv = (block, v) => (block.match(new RegExp(`^\\s+${v}: (.+)$`, 'm')) || [])[1];
const allRules = (rules) => rules.groups.flatMap((g) => g.rules);

test('osservabilità: hub.yml spegne OTLP di default, in secondi, con i bucket degli SLO (Q-520)', () => {
  const hub = read('deploy/hub/src/main/resources/hub.yml');
  assert.ok(hub.includes('enabled: ${LH_OTEL_METRICS_ENABLED:false}'), 'esportazione OTLP spenta di default');
  assert.ok(hub.includes('base-time-unit: seconds'), 'base in secondi: le regole e la dashboard leggono *_seconds_*');
  // Le OTEL_* standard (iniettate da operatori o webhook di piattaforma) non comandano l'hub: Boot le mappa in una fonte
  // con precedenza su hub.yml e accenderebbero l'invio, aggirando INSECURE_CONFIG, con temporalità delta e un altro `job`.
  assert.ok(hub.includes('map-environment-variables: false'), 'OTEL_* non devono aggirare LH_OTEL_* e i controlli del chart (ADR-044, regola 22)');
  assert.ok(hub.includes('"[service.name]": hub'), 'service.name fissato: OTEL_SERVICE_NAME cambierebbe il job e ogni regola');
  assert.ok(hub.includes('aggregation-temporality: cumulative'), 'temporalità cumulativa: rate() e increase() la richiedono');
  const slo = (hub.match(/"\[http\.server\.requests\]": (.+)/) || [])[1];
  assert.ok(slo, 'bucket di http.server.requests');
  const bounds = slo.split(',').map((b) => b.trim());
  assert.ok(bounds.includes('500ms') && bounds.includes('5s'), `soglie 500 ms e 5 s tra i bucket: ${slo}`);
  const sli = read('services/insight-service/src/main/java/io/loyaltyhub/insight/application/ActionToPointsSli.java');
  assert.ok(sli.includes('lh_action_to_points_seconds'), 'SLI azione → punti in insight (Q-523)');
  assert.ok(sli.includes('Duration.ofSeconds(5)'), 'l\'obiettivo di 5 s è un bucket esplicito');
});

test('osservabilità: regole SLO e allarmi coprono portale, giocata e azione → punti, con selettori coerenti al codice', () => {
  const uri = playUri();
  assert.equal(uri, '/v1/portal/contests/{code}/play', 'URI della giocata: se il controller cambia, regole e dashboard vanno aggiornate');
  const rules = readJson(RULES);
  assert.deepEqual(rules.groups.map((g) => g.name), ['loyaltyhub-slo-portal', 'loyaltyhub-slo-play', 'loyaltyhub-slo-points',
    'loyaltyhub-slo-alerts', 'loyaltyhub-operations']);
  for (const g of rules.groups) assert.equal(g.interval, '30s', g.name);
  for (const r of allRules(rules)) {
    assert.equal(['record', 'alert'].filter((k) => k in r).length, 1, `una sola tra record e alert: ${JSON.stringify(r)}`);
    assert.ok(typeof r.expr === 'string' && r.expr.length > 0, `expr: ${JSON.stringify(r)}`);
    assert.doesNotMatch(r.expr, /\\/, `nessuna barra rovesciata nelle espressioni (usare [.]): ${r.expr}`);
    // Ogni selettore di una metrica grezza inizia col selettore dell'installazione.
    for (const m of RAW_METRICS) {
      for (const part of r.expr.split(`${m}{`).slice(1)) {
        assert.ok(part.startsWith(SEL), `${m}{ senza ${SEL} in ${r.record || r.alert}`);
      }
    }
  }
  const text = allRules(rules).map((r) => r.expr).join('\n');
  assert.ok(text.includes(`uri="${uri}"`), 'le regole leggono l\'URI della giocata del controller');
  assert.ok(text.includes('le=~"0[.]50*"') && text.includes('le=~"5|5[.]0+"'), 'bucket di 500 ms e 5 s');
  for (const budget of ['0.001', '0.01', '0.05']) assert.ok(text.includes(`* ${budget})`), `budget ${budget} negli allarmi`);
  const named = new Map(allRules(rules).map((r) => [r.record || r.alert, r]));
  for (const slo of ['portal_availability', 'play_latency', 'action_to_points']) {
    for (const w of ['5m', '30m', '1h', '6h']) assert.ok(named.has(`loyaltyhub:slo_${slo}:error_ratio_rate${w}`), `${slo} ${w}`);
  }
  assert.ok(named.has('loyaltyhub:play_latency_seconds:p99_rate5m') && named.has('loyaltyhub:action_to_points_seconds:p95_rate5m'));
  for (const x of ['PortalAvailability', 'PlayLatency', 'ActionToPoints']) {
    assert.equal(named.get(`LoyaltyHub${x}BurnRateFast`).labels.severity, 'critical', x);
    assert.equal(named.get(`LoyaltyHub${x}BurnRateSlow`).labels.severity, 'warning', x);
    assert.match(named.get(`LoyaltyHub${x}BurnRateFast`).expr, /14\.4/);
    assert.match(named.get(`LoyaltyHub${x}BurnRateSlow`).expr, /\(6 \* /);
  }
  // docs/18 §3.10 punto 11: DLQ, firma non valida, picchi di 401/403; più outbox e telemetria assente.
  for (const a of ['LoyaltyHubDlqMessages', 'LoyaltyHubMessageRejected', 'LoyaltyHubAuthFailureSpike', 'LoyaltyHubOutboxBacklog',
    'LoyaltyHubTelemetryAbsent']) assert.ok(named.has(a), a);
  assert.match(named.get('LoyaltyHubMessageRejected').expr, /SIGNATURE_INVALID\|PRODUCER_NOT_ALLOWED/);
  // Un contatore Micrometer nasce alla prima occorrenza con valore 1: increase() da solo non la vede, quindi gli allarmi
  // sui contatori DLQ contano anche le serie nate nella finestra (`unless … offset`); ma dopo un buco di telemetria più
  // lungo della finestra i contatori vecchi riapparirebbero «nuovi»: si contano solo le istanze che già riportavano a
  // `offset` o partite da meno della finestra (provato con `promtool test rules`, ci/slo-rules.test.yaml).
  const firstSeen = (window, seconds) => new RegExp(
    `^sum by \\(job, errorCode\\) \\(increase\\(lh_events_dlq_total\\{[^ ]+\\}\\[${window}\\]\\)\\) > 0 or `
    + `sum by \\(job, errorCode\\) \\(\\(lh_events_dlq_total\\{[^ ]+\\} unless lh_events_dlq_total\\{[^ ]+\\} offset ${window}\\) `
    + `and on \\(job, instance\\) \\(process_uptime_seconds\\{[^ ]+\\} < ${seconds} or process_uptime_seconds\\{[^ ]+\\} offset ${window}\\)\\) > 0$`);
  assert.match(named.get('LoyaltyHubDlqMessages').expr, firstSeen('10m', 600));
  assert.match(named.get('LoyaltyHubMessageRejected').expr, firstSeen('5m', 300));
  // Telemetria assente per installazione: con più installazioni su uno stesso Prometheus (Q-527) absent_over_time() da solo
  // scatterebbe solo se tacessero tutte; `unless` sulle finestre di 1 h e 10 min vale per ogni `job`, absent_over_time il caso globale.
  const up = `process_uptime_seconds{${SEL}}`;
  assert.equal(named.get('LoyaltyHubTelemetryAbsent').expr,
    `(max by (job) (max_over_time(${up}[1h])) unless max by (job) (max_over_time(${up}[10m]))) or absent_over_time(${up}[10m])`);
  assert.equal(named.get('LoyaltyHubTelemetryAbsent').for, '5m');
  assert.match(named.get('LoyaltyHubTelemetryAbsent').annotations.description, /\{\{ \$labels\.job \}\}/);
  assert.match(named.get('LoyaltyHubAuthFailureSpike').expr, /status=~"401\|403"/);
  for (const r of allRules(rules).filter((x) => x.alert)) {
    assert.ok(r.annotations?.summary && r.annotations?.description, `annotazioni di ${r.alert}`);
  }
});

test('osservabilità: la dashboard SLO usa solo metriche note e la variabile job, con le soglie di ADR-036', () => {
  const dash = readJson(DASHBOARD);
  assert.equal(dash.uid, 'loyaltyhub-slo');
  assert.equal(dash.title, 'Loyalty Hub — SLO');
  assert.equal(dash.editable, false);
  const vars = Object.fromEntries(dash.templating.list.map((v) => [v.name, v]));
  assert.equal(vars.datasource.type, 'datasource');
  assert.equal(vars.job.allValue, 'loyaltyhub[^/]*/hub');
  assert.ok(vars.job.query.includes(SEL), 'la variabile job elenca solo le installazioni loyaltyhub');
  assert.deepEqual(dash.panels.map((p) => p.id), [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13]);
  const thresholds = [];
  let exprs = '';
  for (const panel of dash.panels.filter((p) => p.type !== 'text')) {
    assert.deepEqual(panel.datasource, { type: 'prometheus', uid: '${datasource}' }, `origine dati del pannello ${panel.id}`);
    assert.ok(panel.targets.length > 0, `pannello ${panel.id}`);
    for (const t of panel.targets) {
      assert.ok(t.expr.includes('job=~"$job"'), `pannello ${panel.id}: ${t.expr}`);
      assert.doesNotMatch(t.expr, /\\|loyaltyhub:/, `pannello ${panel.id}: solo PromQL grezzo, senza regole di registrazione`);
      exprs += `${t.expr}\n`;
    }
    thresholds.push(...(panel.fieldConfig.defaults.thresholds?.steps ?? []).map((st) => st.value).filter((v) => v !== null));
  }
  const names = new Set([...exprs.matchAll(/([a-zA-Z_:][a-zA-Z0-9_:]*)\{/g)].map((m) => m[1]));
  assert.deepEqual([...names].filter((n) => !RAW_METRICS.includes(n)), [], 'metriche fuori dall\'elenco noto');
  for (const v of [0.999, 0.5, 5, 6, 14.4, 0.001]) assert.ok(thresholds.includes(v), `soglia ${v}`);
  assert.ok(exprs.includes(`uri="${playUri()}"`), 'la dashboard legge l\'URI della giocata del controller');
  assert.ok(exprs.includes('le=~"0[.]50*"') && exprs.includes('le=~"5|5[.]0+"'));
  assert.match(dash.panels.find((p) => p.id === 13).options.content, /RPO 15 min e RTO 1 h.*M15\.2.*M8\.6b \(Q-525\)/);
});

test('osservabilità: values.yaml spento di default, senza destinazione, con l\'immagine del collector fissata per digest', () => {
  const values = read(VALUES);
  const block = values.split(/^observability:\n/m)[1].split(/^\S/m)[0];
  assert.match(block, /^  enabled: false$/m);
  assert.match(block, /^ {4}otlpEndpoint: ""$/m, 'nessuna destinazione di default (ADR-044)');
  assert.match(block, /^ {6}tag: "[0-9.]+@sha256:[0-9a-f]{64}"$/m, 'immagine del collector per tag e digest');
  assert.match(block, /^ {4}networkPolicy: \{ enabled: true \}$/m);
  assert.match(block, /^ {4}enabled: false$/m, 'PrometheusRule spenta di default');
});

test('osservabilità: il compose di riferimento ha il profilo observability con immagini fissate e Grafana protetto', () => {
  const compose = read(COMPOSE);
  const [otelcol, prometheus, grafana, hub] = ['otelcol', 'prometheus', 'grafana', 'hub'].map((n) => composeService(compose, n));
  for (const [name, block] of [['otelcol', otelcol], ['prometheus', prometheus], ['grafana', grafana]]) {
    assert.match(block, /^ {4}profiles: \["observability"\]$/m, `${name}: profilo observability`);
    assert.match(block, /^ {4}image: \S+:\S+@sha256:[0-9a-f]{64}$/m, `${name}: tag leggibile e digest`);
  }
  // Stessa immagine del collector nel chart (values.yaml) e nel compose.
  const collector = valuesImageRefs(read(VALUES)).find((r) => r.name === 'otel/opentelemetry-collector');
  const inCompose = composeImageRefs(compose).find((r) => r.name === 'otel/opentelemetry-collector');
  assert.ok(collector && inCompose, 'immagine del collector in values.yaml e nel compose');
  assert.deepEqual(inCompose, collector, 'stessa versione e digest del collector nel chart e nel compose');
  // Grafana: password obbligatoria e presa dall'ambiente, niente accesso anonimo, niente comunicazioni verso Grafana Labs.
  assert.equal(composeEnv(grafana, 'GF_SECURITY_ADMIN_PASSWORD'), '"${LH_GRAFANA_ADMIN_PASSWORD:-}"');
  assert.match(composeEnv(grafana, 'LH_REQUIRED_ENV'), /GF_SECURITY_ADMIN_PASSWORD/);
  assert.match(grafana, /entrypoint: \*lh-require-env\n\s+command: \["lh-require-env", "\/run\.sh"\]/);
  for (const v of ['GF_ANALYTICS_REPORTING_ENABLED', 'GF_ANALYTICS_CHECK_FOR_UPDATES', 'GF_ANALYTICS_CHECK_FOR_PLUGIN_UPDATES',
    'GF_AUTH_ANONYMOUS_ENABLED', 'GF_USERS_ALLOW_SIGN_UP', 'GF_NEWS_NEWS_FEED_ENABLED']) {
    assert.equal(composeEnv(grafana, v), '"false"', v);
  }
  // Gli altri contatti con grafana.com (default di Grafana 13, conf/defaults.ini): chiave di firma dei plugin (all'avvio e
  // ogni 10 giorni), catalogo dei plugin, plugin preinstallati, snapshot pubblicabili su snapshots.raintank.io (le metriche
  // di produzione potrebbero uscire). Senza queste il «nessuna comunicazione verso Grafana Labs» della documentazione è falso.
  for (const v of ['GF_PLUGINS_PUBLIC_KEY_RETRIEVAL_DISABLED', 'GF_PLUGINS_PREINSTALL_DISABLED']) assert.equal(composeEnv(grafana, v), '"true"', v);
  for (const v of ['GF_PLUGINS_PLUGIN_ADMIN_ENABLED', 'GF_SNAPSHOTS_EXTERNAL_ENABLED']) assert.equal(composeEnv(grafana, v), '"false"', v);
  // La dashboard ha riquadri a 30 giorni: la conservazione di Prometheus non può essere più corta.
  assert.match(prometheus, /--storage\.tsdb\.retention\.time=\$\{LH_PROMETHEUS_RETENTION:-31d\}/, 'retention predefinita di almeno 30 giorni');
  assert.match(grafana, /^ {6}- "\$\{LH_BIND_ADDRESS:-127\.0\.0\.1\}:3001:3000"$/m, 'porta di Grafana solo su LH_BIND_ADDRESS');
  assert.doesNotMatch(otelcol + prometheus, /^\s+ports:/m, 'collector e Prometheus senza porte sull\'host');
  assert.match(prometheus, /^ {6}- --web\.enable-otlp-receiver$/m);
  // L'hub invia le metriche solo se acceso; niente depends_on dai servizi con profilo.
  assert.equal(composeEnv(hub, 'LH_OTEL_METRICS_ENABLED'), '"${LH_OTEL_METRICS_ENABLED:-false}"');
  assert.equal(composeEnv(hub, 'LH_OTEL_METRICS_URL'), '"http://otelcol:4318/v1/metrics"');
  assert.doesNotMatch(hub, /\b(otelcol|prometheus|grafana):\n\s+condition/, 'l\'hub non dipende dai servizi con profilo');
  // Ogni file montato dal compose esiste.
  const sources = [...compose.matchAll(/^\s+- (\.\/observability\/[^:]+|\.\.\/helm\/[^:]+):/gm)].map((m) => m[1]);
  assert.ok(sources.length >= 5, `mount dell'osservabilità: ${sources}`);
  for (const src of sources) {
    assert.ok(fs.existsSync(path.join(ROOT, 'deploy/compose', src)), `manca il file montato ${src}`);
  }
  assert.ok(sources.includes('../helm/loyaltyhub/files/observability/slo-rules.json'), 'regole del chart montate in Prometheus');
  assert.ok(compose.includes('- lh-ref-prometheus:/prometheus') && compose.includes('- lh-ref-grafana:/var/lib/grafana'));
  assert.match(compose, /^volumes:\n(?:\s+lh-ref-\w+:\n)*\s+lh-ref-prometheus:\n(?:\s+lh-ref-\w+:\n)*\s+lh-ref-grafana:/m);
});

test('osservabilità: pipeline del collector e configurazione di Prometheus e Grafana coerenti tra chart e compose', () => {
  const composeCollector = read('deploy/compose/observability/otel-collector.yaml');
  assert.match(composeCollector, /^ {4}endpoint: "http:\/\/prometheus:9090\/api\/v1\/otlp"$/m, 'destinazione: il Prometheus del compose');
  // Stessi processori e stessa pipeline nel helper del chart e nel file del compose.
  const helper = read('deploy/helm/loyaltyhub/templates/_helpers.tpl').split('define "loyaltyhub.otelCollector.config"')[1]
    .split('{{- end -}}')[0];
  const processors = (t) => t.slice(t.indexOf('processors:'), t.indexOf('exporters:'));
  const service = (t) => t.slice(t.indexOf('service:')).trim();
  assert.equal(processors(composeCollector), processors(helper), 'processori');
  assert.equal(service(composeCollector), service(helper), 'pipeline');
  assert.doesNotMatch(composeCollector + helper, /4317|debug/, 'solo il ricevitore HTTP, nessun esportatore di debug');
  const prom = read('deploy/compose/observability/prometheus.yml');
  assert.match(prom, /^ {2}translation_strategy: UnderscoreEscapingWithSuffixes$/m);
  assert.match(prom, /^ {4}out_of_order_time_window: 30m$/m);
  assert.match(prom, /^ {2}- \/etc\/prometheus\/rules\/\*\.json$/m);
  assert.doesNotMatch(prom, /^scrape_configs:/m, 'nessuno scrape: l\'hub spinge (Q-520)');
  const ds = read('deploy/compose/observability/grafana/provisioning/datasources/prometheus.yaml');
  assert.match(ds, /uid: prometheus\n\s+type: prometheus\n\s+access: proxy\n\s+url: http:\/\/prometheus:9090/);
  const providers = read('deploy/compose/observability/grafana/provisioning/dashboards/loyaltyhub.yaml');
  assert.match(providers, /path: \/etc\/lh\/dashboards$/m);
  assert.ok(read(COMPOSE).includes('/etc/lh/dashboards:ro'), 'la cartella dei dashboard è montata dove il provider la cerca');
});

const OBS_HELM_SKIP = !hasHelm && 'helm non installato';
const obsTemplate = (...sets) => spawnSync('helm', ['template', 'lh', CHART, '--kube-version', '1.31.0', '-f', CI_VALUES,
  '-f', OBS_VALUES, ...sets.flatMap((s) => ['--set', s])], { encoding: 'utf8' });
const obsRefuses = (message, ...sets) => {
  const r = obsTemplate(...sets);
  assert.notEqual(r.status, 0, `dovrebbe fallire: ${sets.join(' ')}`);
  assert.match(r.stderr, message, `${sets.join(' ')}: ${r.stderr}`);
};
const docsOf = (out) => out.split(/^---$/m);
const docOf = (out, kind, name) => docsOf(out).find((d) => new RegExp(`^kind: ${kind}$`, 'm').test(d)
  && new RegExp(`^  name: ${name}$`, 'm').test(d));

test('osservabilità: spenta il chart non rende nulla (collector, dashboard, regole, variabili LH_OTEL_*)', { skip: OBS_HELM_SKIP }, () => {
  const r = template();
  assert.equal(r.status, 0, r.stderr);
  assert.doesNotMatch(r.stdout, /otel-collector|LH_OTEL_|kind: PrometheusRule|grafana-dashboards|checksum\/config/);
  const demo = template('global.profile=demo');
  assert.equal(demo.status, 0, demo.stderr);
  assert.doesNotMatch(demo.stdout, /otel-collector|LH_OTEL_/);
});

test('osservabilità: accesa il chart rende collector, dashboard, regole e variabili dell\'hub', { skip: OBS_HELM_SKIP }, () => {
  const lint = spawnSync('helm', ['lint', CHART, '--strict', '-f', CI_VALUES, '-f', OBS_VALUES], { encoding: 'utf8' });
  assert.equal(lint.status, 0, lint.stdout + lint.stderr);
  const r = obsTemplate();
  assert.equal(r.status, 0, r.stderr);
  const kinds = docsOf(r.stdout).filter((d) => /observability|otel-collector|grafana-dashboards|-slo$/m.test(d.split('\n').slice(0, 12).join('\n'))
    || /^  name: lh-loyaltyhub-(otel-collector|grafana-dashboards|slo)$/m.test(d))
    .map((d) => `${d.match(/^kind: (\S+)$/m)[1]}/${d.match(/^  name: (\S+)$/m)[1]}`).sort();
  assert.deepEqual(kinds, [
    'ConfigMap/lh-loyaltyhub-grafana-dashboards', 'ConfigMap/lh-loyaltyhub-otel-collector',
    'Deployment/lh-loyaltyhub-otel-collector', 'NetworkPolicy/lh-loyaltyhub-otel-collector',
    'PodDisruptionBudget/lh-loyaltyhub-otel-collector', 'PrometheusRule/lh-loyaltyhub-slo',
    'Service/lh-loyaltyhub-otel-collector']);
  // Hub: metriche verso il collector del chart, istanza dal nome del Pod.
  const hub = docOf(r.stdout, 'Deployment', 'lh-loyaltyhub-hub');
  const env = (doc, name) => (doc.match(new RegExp(`- name: ${name}\n\\s+(value: .+|valueFrom:\n\\s+fieldRef:\n\\s+fieldPath: .+)`)) || [])[1];
  assert.equal(env(hub, 'LH_OTEL_METRICS_ENABLED'), 'value: "true"');
  assert.equal(env(hub, 'LH_OTEL_METRICS_URL'), 'value: "http://lh-loyaltyhub-otel-collector:4318/v1/metrics"');
  assert.equal(env(hub, 'LH_OTEL_METRICS_STEP'), 'value: "30s"');
  assert.equal(env(hub, 'LH_OTEL_SERVICE_NAMESPACE'), 'value: "loyaltyhub"');
  assert.equal(env(hub, 'LH_OTEL_INSTANCE_ID'), 'valueFrom:\n                fieldRef:\n                  fieldPath: metadata.name');
  // Collector: solo HTTP sull'IP del Pod, destinazione scelta da chi installa, nessuna credenziale se non configurata.
  const config = docOf(r.stdout, 'ConfigMap', 'lh-loyaltyhub-otel-collector');
  assert.match(config, /endpoint: "http:\/\/prometheus-operated\.monitoring\.svc:9090\/api\/v1\/otlp"/);
  assert.ok(config.includes('endpoint: ${env:LH_POD_IP}:4318') && config.includes('endpoint: ${env:LH_POD_IP}:13133'));
  assert.doesNotMatch(config, /Authorization|ca_file|4317|debug/);
  const deployment = docOf(r.stdout, 'Deployment', 'lh-loyaltyhub-otel-collector');
  assert.match(deployment, /image: "otel\/opentelemetry-collector:[0-9.]+@sha256:[0-9a-f]{64}"/);
  assert.match(deployment, /^  replicas: 2$/m);
  assert.match(deployment, /checksum\/config: [0-9a-f]{64}/);
  assert.match(deployment, /readOnlyRootFilesystem: true/);
  assert.doesNotMatch(deployment, /LH_PROMETHEUS_TOKEN|prometheus-ca/);
  // NetworkPolicy: solo i Pod hub della release sulla 4318 (Q-521).
  const np = docOf(r.stdout, 'NetworkPolicy', 'lh-loyaltyhub-otel-collector');
  assert.match(np, /podSelector:\n\s+matchLabels:\n(?:\s+app\.kubernetes\.io\/\S+: \S+\n)*?\s+app\.kubernetes\.io\/component: otel-collector\n/);
  assert.match(np, /ingress:\n\s+- from:\n\s+- podSelector:\n\s+matchLabels:\n(?:\s+app\.kubernetes\.io\/\S+: \S+\n)*?\s+app\.kubernetes\.io\/component: hub\n[\s\S]*port: 4318/);
  assert.match(np, /policyTypes:\n\s+- Ingress/);
  // Regole: stessi gruppi e stesso numero di regole del file condiviso.
  const rules = readJson(RULES);
  const rule = docOf(r.stdout, 'PrometheusRule', 'lh-loyaltyhub-slo');
  assert.equal((rule.match(/^ {2}- interval: 30s$/gm) || []).length, rules.groups.length);
  assert.equal((rule.match(/^\s+record: /gm) || []).length, allRules(rules).filter((x) => x.record).length);
  assert.equal((rule.match(/^\s+(?:- )?alert: /gm) || []).length, allRules(rules).filter((x) => x.alert).length);
  // Dashboard: etichetta per il sidecar e JSON identico al file condiviso.
  const cm = docOf(r.stdout, 'ConfigMap', 'lh-loyaltyhub-grafana-dashboards');
  assert.match(cm, /^ {4}grafana_dashboard: "1"$/m);
  assert.match(cm, /^ {4}grafana_folder: Loyalty Hub$/m);
  const embedded = cm.split('  loyaltyhub-slo.json: |-\n')[1].split('\n').map((l) => l.replace(/^ {4}/, '')).join('\n');
  assert.deepEqual(JSON.parse(embedded), readJson(DASHBOARD));
  // Con la dashboard e le regole spente non si rende né l'una né le altre.
  const off = obsTemplate('observability.grafana.dashboards.enabled=false', 'observability.prometheusRule.enabled=false',
    'observability.collector.networkPolicy.enabled=false', 'observability.collector.pdb.enabled=false');
  assert.equal(off.status, 0, off.stderr);
  assert.doesNotMatch(off.stdout, /grafana-dashboards|kind: PrometheusRule|kind: NetworkPolicy/);
  assert.equal(docOf(off.stdout, 'PodDisruptionBudget', 'lh-loyaltyhub-otel-collector'), undefined);
  // Namespace di servizio per installazione (Q-527), e nome accorciato per restare entro i 63 caratteri.
  const ns = obsTemplate('observability.serviceNamespace=loyaltyhub-prod');
  assert.equal(ns.status, 0, ns.stderr);
  assert.equal(env(docOf(ns.stdout, 'Deployment', 'lh-loyaltyhub-hub'), 'LH_OTEL_SERVICE_NAMESPACE'), 'value: "loyaltyhub-prod"');
  const long = obsTemplate(`fullnameOverride=${'a'.repeat(63)}`);
  assert.equal(long.status, 0, long.stderr);
  assert.ok([...long.stdout.matchAll(/^  name: (\S*otel-collector)$/gm)].every((m) => m[1].length <= 63), 'nome del collector ≤ 63');
});

test('osservabilità: token e CA di Prometheus solo da Secret e ConfigMap, montati nel collector', { skip: OBS_HELM_SKIP }, () => {
  const r = obsTemplate('observability.prometheus.bearerToken.name=lh-prom', 'observability.prometheus.caBundle.name=prom-ca',
    'observability.prometheus.otlpEndpoint=https://prom.example.org/api/v1/otlp');
  assert.equal(r.status, 0, r.stderr);
  const config = docOf(r.stdout, 'ConfigMap', 'lh-loyaltyhub-otel-collector');
  assert.ok(config.includes('Authorization: "Bearer ${env:LH_PROMETHEUS_TOKEN}"'), 'intestazione con il token dall\'ambiente');
  assert.match(config, /tls:\n\s+ca_file: \/etc\/lh\/prometheus-ca\/ca\.crt/);
  assert.doesNotMatch(config, /lh-prom\b/, 'nessun valore del Secret nel ConfigMap');
  const deployment = docOf(r.stdout, 'Deployment', 'lh-loyaltyhub-otel-collector');
  assert.match(deployment, /- name: LH_PROMETHEUS_TOKEN\n\s+valueFrom:\n\s+secretKeyRef:\n\s+name: lh-prom\n\s+key: token/);
  assert.match(deployment, /- name: prometheus-ca\n\s+configMap:\n\s+name: prom-ca\n\s+items:\n\s+- key: ca\.crt\n\s+path: ca\.crt/);
  assert.match(deployment, /mountPath: \/etc\/lh\/prometheus-ca\n\s+readOnly: true/);
});

test('osservabilità: il chart rifiuta destinazioni mancanti, malformate o in http fuori dal cluster (enterprise, regola 22)', { skip: OBS_HELM_SKIP }, () => {
  obsRefuses(/otlpEndpoint è obbligatorio con observability\.enabled=true.*ADR-044/, 'observability.prometheus.otlpEndpoint=');
  obsRefuses(/INSECURE_CONFIG: observability\.prometheus\.otlpEndpoint "http:\/\/prometheus\.example\.org:9090\/api\/v1\/otlp" in http fuori dal cluster/,
    'observability.prometheus.otlpEndpoint=http://prometheus.example.org:9090/api/v1/otlp');
  obsRefuses(/INSECURE_CONFIG/, 'observability.prometheus.otlpEndpoint=http://p.monitoring.svc.evil.example:9090/api/v1/otlp');
  obsRefuses(/senza \/v1\/metrics/, 'observability.prometheus.otlpEndpoint=http://prometheus-operated.monitoring.svc:9090/api/v1/otlp/v1/metrics');
  obsRefuses(/non è un URL http\(s\) valido/, 'observability.prometheus.otlpEndpoint=ftp://x');
  obsRefuses(/non è un URL http\(s\) valido/, 'observability.prometheus.otlpEndpoint=https://utente@prom.example.org/api/v1/otlp');
  obsRefuses(/observability\.serviceNamespace: Does not match pattern/, 'observability.serviceNamespace=clubaurora');
  obsRefuses(/Additional property/, 'observability.prometheus.bearerToken.value=in-chiaro');
  // Una replica con il PDB (minAvailable 1) blocca `kubectl drain` senza proteggere nulla: come WEB_SINGLE_REPLICA per il web.
  obsRefuses(/COLLECTOR_SINGLE_REPLICA/, 'observability.collector.replicas=1');
  const single = obsTemplate('observability.collector.replicas=1', 'observability.collector.pdb.enabled=false');
  assert.equal(single.status, 0, single.stderr);
  assert.doesNotMatch(single.stdout, /kind: PodDisruptionBudget\nmetadata:\n\s+name: lh-loyaltyhub-otel-collector/);
  // Ammesse: deroga esplicita, http dentro il cluster (nome, <ns>.svc, <ns>.svc.cluster.local) e https.
  for (const ok of ['http://prometheus:9090/api/v1/otlp', 'http://p.monitoring.svc:9090/api/v1/otlp',
    'http://p.monitoring.svc.cluster.local:9090/api/v1/otlp', 'https://prom.example.org/api/v1/otlp']) {
    const r = obsTemplate(`observability.prometheus.otlpEndpoint=${ok}`);
    assert.equal(r.status, 0, `${ok}: ${r.stderr}`);
  }
  const waiver = obsTemplate('observability.prometheus.otlpEndpoint=http://prom.example.org:9090/api/v1/otlp',
    'observability.prometheus.allowInsecure=true');
  assert.equal(waiver.status, 0, waiver.stderr);
  // Profilo demo: nessun obbligo di cifratura (ma la destinazione resta obbligatoria).
  const demo = obsTemplate('global.profile=demo', 'observability.prometheus.otlpEndpoint=http://prom.example.org:9090/api/v1/otlp');
  assert.equal(demo.status, 0, demo.stderr);
  obsRefuses(/otlpEndpoint è obbligatorio/, 'global.profile=demo', 'observability.prometheus.otlpEndpoint=');
});

// Le regole si provano anche con promtool (Prometheus 3): sintassi e casi di ci/slo-rules.test.yaml. Facoltativo: se non è
// nel PATH la prova è saltata (il job `helm` di ci.yml non lo installa ancora: seguito, dopo lo sblocco di .github).
const promtool = spawnSync('promtool', ['--version'], { encoding: 'utf8' });
const hasPromtool = promtool.status === 0;

test('osservabilità: promtool accetta le regole e i casi di prova passano (buco di telemetria, prima occorrenza, per installazione)',
  { skip: !hasPromtool && 'promtool non installato' }, () => {
    const check = spawnSync('promtool', ['check', 'rules', path.join(ROOT, RULES)], { encoding: 'utf8' });
    assert.equal(check.status, 0, check.stdout + check.stderr);
    const run = spawnSync('promtool', ['test', 'rules', path.join(CHART, 'ci/slo-rules.test.yaml')], { encoding: 'utf8' });
    assert.equal(run.status, 0, run.stdout + run.stderr);
  });

const kubeconform = spawnSync('kubeconform', ['-v'], { encoding: 'utf8' });
const hasKubeconform = kubeconform.status === 0;

test('kubeconform presente in CI', () => {
  assert.ok(hasKubeconform || !inCi, 'CI=true ma kubeconform non è nel PATH: installarlo nel job (go install, come fa il job helm)');
});

test('kubeconform: scenario con osservabilità (collector, PrometheusRule, NetworkPolicy)', { skip: (!hasHelm || !hasKubeconform) && 'helm o kubeconform non installati' }, () => {
  // Schemi dei CRD (PrometheusRule): lo stesso catalogo e lo stesso commit del job `helm` di ci.yml, senza duplicare l'URL.
  const crd = process.env.CRD_SCHEMAS || (read('.github/workflows/ci.yml').match(/CRD_SCHEMAS: (\S+)/) || [])[1];
  assert.ok(crd, 'CRD_SCHEMAS: variabile d\'ambiente o riga in .github/workflows/ci.yml');
  const manifests = obsTemplate();
  assert.equal(manifests.status, 0, manifests.stderr);
  const r = spawnSync('kubeconform', ['-strict', '-summary', '-kubernetes-version', '1.31.0', '-schema-location', 'default',
    '-schema-location', crd, '-'], { input: manifests.stdout, encoding: 'utf8' });
  assert.equal(r.status, 0, r.stdout + r.stderr);
  assert.match(r.stdout, /Invalid: 0, Errors: 0/);
});
