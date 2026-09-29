#!/usr/bin/env node
// Verifica statica del chart Helm e dei compose (F2-DIST-02, F2-DIST-03, F2-EVT-04, M8.3; immagini condivise: Q-483).
// Uso: node --test scripts/check-helm.mjs   (nessuna dipendenza; con `helm` nel PATH esegue anche lint e template).
// In CI (CI=true) helm è obbligatorio: senza, la prova fallisce invece di passare senza verificare nulla.
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
      if (!m || !/(password|secret|token|apikey|sessionkey)/i.test(m[1]) || /Name$/.test(m[1])) return false;
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
  const secretLines = compose.split('\n').filter((l) => /^\s+[A-Z_]*(PASSWORD|SECRET|SESSION_KEY)[A-Z_]*:\s/.test(l));
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
  };
  for (const name of SHARED_IMAGES) {
    for (const file of [LOCAL_COMPOSE, COMPOSE]) {
      assert.ok(sources[file].some((r) => r.name === name && r.tag), `${file}: manca ${name} con un tag`);
    }
  }
  assert.ok(sources[VALUES].some((r) => r.name === 'quay.io/keycloak/keycloak'), `${VALUES}: manca l'immagine di Keycloak`);
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
    + `kafka:\n  strimzi:\n    apiVersion: kafka.strimzi.io/v1beta2\n    # commento\n    version: "${strimzi}"\n`);
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
