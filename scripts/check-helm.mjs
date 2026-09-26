#!/usr/bin/env node
// Verifica statica del chart Helm e del compose di riferimento (F2-DIST-02, F2-DIST-03, F2-EVT-04, M8.3).
// Uso: node --test scripts/check-helm.mjs   (nessuna dipendenza; con `helm` nel PATH esegue anche lint e template)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
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
      if (!m || !/(password|secret|token|apikey)/i.test(m[1]) || /Name$/.test(m[1])) return false;
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
  const secretLines = compose.split('\n').filter((l) => /^\s+[A-Z_]*(PASSWORD|SECRET)[A-Z_]*:\s/.test(l));
  assert.ok(secretLines.length > 0);
  for (const l of secretLines) {
    assert.match(l, /:\s*"\$\{[A-Z_]+:-\}"\s*$/, `segreto non preso dall'ambiente: ${l.trim()}`);
  }
  assert.doesNotMatch(compose, /LH_KAFKA_TOPIC_PARTITIONS: "?[0-9]/, 'le partizioni vengono da ${LH_KAFKA_TOPIC_PARTITIONS}');
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
  assert.match(compose, new RegExp(`"${webPort}:${webPort}"`), 'porta del web nel compose');
});

const helm = spawnSync('helm', ['version', '--short'], { encoding: 'utf8' });
test('helm lint e helm template (solo se helm è installato)', { skip: helm.status !== 0 && 'helm non installato' }, () => {
  const lint = spawnSync('helm', ['lint', CHART, '--strict'], { encoding: 'utf8' });
  assert.equal(lint.status, 0, lint.stdout + lint.stderr);
  const tpl = spawnSync('helm', ['template', 'lh', CHART, '--kube-version', '1.31.0'], { encoding: 'utf8' });
  assert.equal(tpl.status, 0, tpl.stderr);
  const topics = [...tpl.stdout.matchAll(/topicName: (\S+)/g)].map((m) => m[1]).sort();
  assert.deepEqual(topics, [...TOPICS].sort());
  const refused = spawnSync('helm', ['template', 'lh', CHART, '--kube-version', '1.31.0', '--set', 'roles.cms.enabled=true'],
    { encoding: 'utf8' });
  assert.notEqual(refused.status, 0, 'il chart deve rifiutare un ruolo non ancora nell\'immagine');
});
