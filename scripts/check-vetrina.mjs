#!/usr/bin/env node
// Verifiche dell'overlay di vetrina del compose di riferimento (F2-DIST-03, F2-DIST-09, ADR-049, M8.14 V3;
// Q-616, Q-620, Q-621, Q-624). Uso: node --test scripts/check-vetrina.mjs (job `seed` della CI).
//
// - statiche: overlay, Caddyfile, pg_hba, timer; nessun segreto in chiaro, TLS verso bus e database, porte;
// - compose unito (solo se c'è `docker compose`): limiti di memoria, porte pubblicate, ambiente dei servizi;
// - entrypoint dell'overlay eseguito con sh: segreti solo da file, guardie TLS, nessun valore stampato;
// - vetrina.sh: configurazione, provisioning in una cartella temporanea (permessi, idempotenza, certificati);
// - operatori.py contro un Keycloak simulato: ruolo e MFA_REQUIRED_ROLE, password solo nel file 0600.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';
import { spawn, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import YAML from 'yaml';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
// Intestazione PEM composta a runtime: il marcatore letterale nel sorgente farebbe scattare gitleaks (job `security`).
const pemHeader = (label) => `-----BEGIN ${label}-----`;
const DIR = path.join(ROOT, 'deploy/vetrina');
const OVERLAY = path.join(DIR, 'compose.vetrina.yml');
const REFERENCE = path.join(ROOT, 'deploy/compose/reference.yml');
const VETRINA_SH = path.join(DIR, 'vetrina.sh');
const OPERATORI = path.join(DIR, 'operatori.py');
const read = (p) => fs.readFileSync(p, 'utf8');
const overlay = YAML.parse(read(OVERLAY));
const has = (cmd) => spawnSync('sh', ['-c', `command -v ${cmd}`]).status === 0;
const tmp = () => fs.mkdtempSync(path.join(os.tmpdir(), 'lh-vetrina-'));

// Segreti generati da vetrina.sh (array SECRETS, nome:forma).
function shellArray(name) {
  const m = read(VETRINA_SH).match(new RegExp(`^${name}=\\(([^)]*)\\)`, 'm'));
  assert.ok(m, `${name} non trovato in vetrina.sh`);
  return m[1].split(/\s+/).filter(Boolean);
}
const SECRET_FILES = shellArray('SECRETS').map((e) => e.split(':')[0]);

const EXAMPLE_ENV = {
  LH_IMAGE: 'ghcr.io/example/loyaltyhub:ci',
  LH_VETRINA_DIR: '/etc/loyaltyhub-vetrina',
  LH_VETRINA_WEB_HOST: 'web-vetrina.example.org',
  LH_VETRINA_IDP_HOST: 'idp-vetrina.example.org',
  LH_VETRINA_PUBLIC_ADDRESS: '10.0.0.10',
};

// ---------------------------------------------------------------------------------------------------------------
// Statiche

test('overlay: progetto separato dalla demo e dal riferimento, proxy dichiarato con digest e porte sull\'indirizzo dell\'host', () => {
  assert.equal(overlay.name, 'loyaltyhub-vetrina');
  const proxy = overlay.services.proxy;
  assert.match(proxy.image, /^caddy:\d+\.\d+\.\d+-alpine@sha256:[0-9a-f]{64}$/, 'proxy con tag e digest');
  assert.deepEqual(proxy.ports, [
    '${LH_VETRINA_PUBLIC_ADDRESS:?LH_VETRINA_PUBLIC_ADDRESS obbligatoria, indirizzo privato dell\'host}:80:80',
    '${LH_VETRINA_PUBLIC_ADDRESS:?LH_VETRINA_PUBLIC_ADDRESS obbligatoria, indirizzo privato dell\'host}:443:443',
  ]);
  assert.equal(proxy.read_only, true);
  assert.deepEqual(proxy.cap_drop, ['ALL']);
  assert.deepEqual(proxy.cap_add, ['NET_BIND_SERVICE']);
  assert.ok(proxy.security_opt.includes('no-new-privileges:true'));
  assert.ok(proxy.mem_limit, 'limite di memoria del proxy');
  assert.equal(proxy.user, undefined, 'nessun utente root esplicito (LH-DC-0001)');
  assert.deepEqual(proxy.networks.default.aliases, [
    '${LH_VETRINA_WEB_HOST:?LH_VETRINA_WEB_HOST obbligatoria}',
    '${LH_VETRINA_IDP_HOST:?LH_VETRINA_IDP_HOST obbligatoria}',
  ], 'i nomi pubblici risolvono al proxy anche dentro la rete compose (Q-420)');
  // Solo l'hub ha in più una porta, e solo su loopback.
  assert.deepEqual(overlay.services.hub.ports, ['127.0.0.1:8080:8080']);
  for (const [name, svc] of Object.entries(overlay.services)) {
    if (name !== 'proxy' && name !== 'hub') assert.equal(svc.ports, undefined, `${name} non pubblica porte nell'overlay`);
  }
});

test('overlay: ogni segreto arriva da un file di /run/secrets, la variabile in chiaro è vuota (regola 20)', () => {
  const declared = new Set(Object.keys(overlay.secrets));
  for (const [name, svc] of Object.entries(overlay.services)) {
    const env = svc.environment ?? {};
    const mounted = new Set(svc.secrets ?? []);
    for (const s of mounted) assert.ok(declared.has(s), `${name}: secret ${s} non dichiarato`);
    for (const [k, v] of Object.entries(env)) {
      if (k.endsWith('_FILE')) {
        const base = k.slice(0, -5);
        assert.equal(env[base], '', `${name}: ${base} deve essere vuota accanto a ${k}`);
        const m = String(v).match(/^\/run\/secrets\/([a-z0-9_]+)$/);
        assert.ok(m, `${name}: ${k} deve puntare a /run/secrets/<nome>`);
        assert.ok(mounted.has(m[1]), `${name}: ${k} punta a un secret non montato (${m[1]})`);
      } else if (/(PASSWORD|SECRET|SESSION_KEY|SUBJECT_KEY|_B64)$/.test(k)) {
        assert.equal(v, '', `${name}: ${k} in chiaro nell'overlay`);
        assert.ok(env[`${k}_FILE`], `${name}: ${k} senza ${k}_FILE`);
      }
    }
    if (env.LH_SECRET_FILE_ENV !== undefined) {
      const listed = env.LH_SECRET_FILE_ENV.split(/\s+/).sort();
      const files = Object.keys(env).filter((k) => k.endsWith('_FILE')).map((k) => k.slice(0, -5)).sort();
      assert.deepEqual(listed, files, `${name}: LH_SECRET_FILE_ENV deve elencare tutte e sole le variabili con _FILE`);
      assert.equal(svc.entrypoint?.[2], overlay['x-lh-vetrina-env'][2], `${name}: entrypoint dell'overlay`);
    }
  }
  // Stesso elenco di segreti in vetrina.sh e nell'overlay; i file sono nelle cartelle generate da provision.
  const files = Object.values(overlay.secrets).map((s) => s.file);
  for (const f of SECRET_FILES) {
    assert.ok(files.includes(`\${LH_VETRINA_DIR:?LH_VETRINA_DIR obbligatoria}/secrets/${f}`), `secret ${f} di vetrina.sh assente nell'overlay`);
  }
  for (const f of files) {
    assert.match(f, /^\$\{LH_VETRINA_DIR:\?LH_VETRINA_DIR obbligatoria\}\/(secrets|tls)\//);
    const rel = f.split('}/')[1];
    if (rel.startsWith('secrets/')) assert.ok(SECRET_FILES.includes(rel.slice(8)), `${rel} non generato da vetrina.sh`);
    assert.doesNotMatch(rel, /ca\.key|^ca\//, 'la chiave della CA non entra mai in un container');
  }
});

test('overlay: TLS verso database e bus, profilo enterprise fisso, nessuna telemetria (Q-621, ADR-044)', () => {
  const s = overlay.services;
  for (const [svc, key] of [['migrate', 'DB_URL'], ['hub', 'DB_URL'], ['idp', 'KC_DB_URL']]) {
    assert.match(s[svc].environment[key], /^jdbc:postgresql:\/\/postgres:5432\/[a-z]+\?sslmode=verify-full&sslrootcert=\/run\/secrets\/lh_tls_ca$/);
    assert.equal(s[svc].environment.LH_VETRINA_REQUIRE_DB_TLS, key, `${svc}: guardia TLS sul database`);
    assert.ok(s[svc].secrets.includes('lh_tls_ca'));
  }
  assert.equal(s.hub.environment.KAFKA_SECURITY, 'SSL_PEM');
  assert.equal(s.hub.environment.LH_VETRINA_REQUIRE_KAFKA_TLS, 'true');
  assert.equal(s.hub.environment.LH_PROFILE, 'enterprise');
  assert.equal(s.hub.environment.LH_IDENTITY_MODE, 'oidc');
  assert.equal(s.hub.environment.LH_OTEL_METRICS_ENABLED, 'false');
  assert.equal(s.web.environment.LH_PROFILE, 'enterprise');
  assert.match(s.hub.environment.LH_OIDC_JWKS_URI, /^https:\/\//, 'JWKS via proxy in https, non in chiaro');
  const k = s.kafka.environment;
  assert.equal(k.KAFKA_LISTENER_SECURITY_PROTOCOL_MAP, 'CONTROLLER:PLAINTEXT,BROKER:SSL');
  assert.equal(k.KAFKA_LISTENERS, 'BROKER://0.0.0.0:9092,CONTROLLER://127.0.0.1:9093', 'controller in chiaro solo su loopback');
  assert.equal(k.KAFKA_ADVERTISED_LISTENERS, 'BROKER://kafka:9092');
  assert.doesNotMatch(k.KAFKA_ADVERTISED_LISTENERS, /SSL:\/\//, 'il nome SSL:// attiva il ramo JKS dell\'immagine');
  assert.equal(k.KAFKA_INTER_BROKER_LISTENER_NAME, 'BROKER');
  assert.equal(k.KAFKA_SSL_CLIENT_AUTH, 'required');
  assert.equal(k.KAFKA_SSL_KEYSTORE_TYPE, 'PEM');
  assert.equal(k.KAFKA_SSL_TRUSTSTORE_TYPE, 'PEM');
  const pg = s.postgres.command.join(' ');
  for (const opt of ['ssl=on', 'ssl_cert_file=/run/secrets/lh_pg_tls_cert', 'ssl_key_file=/run/secrets/lh_pg_tls_key', 'hba_file=/etc/lh/pg_hba.conf']) {
    assert.ok(pg.includes(opt), `postgres: -c ${opt}`);
  }
  const client = read(path.join(DIR, 'kafka/client-ssl.properties'));
  assert.match(client, /^security\.protocol=SSL$/m);
  assert.match(s.kafka.healthcheck.test[1], /--command-config \/etc\/lh\/kafka-client-ssl\.properties/);
});

test('pg_hba: dalla rete solo TLS, connessioni in chiaro rifiutate', () => {
  const rules = read(path.join(DIR, 'postgres/pg_hba.conf')).split('\n').filter((l) => l.trim() && !l.startsWith('#')).map((l) => l.trim().split(/\s+/));
  for (const r of rules) {
    assert.ok(['local', 'hostssl', 'hostnossl'].includes(r[0]), `regola non ammessa: ${r.join(' ')}`);
    if (r[0] === 'hostssl') assert.equal(r.at(-1), 'scram-sha-256');
    if (r[0] === 'hostnossl') assert.equal(r.at(-1), 'reject');
  }
  assert.ok(rules.some((r) => r[0] === 'hostnossl' && r[3] === '0.0.0.0/0'));
  assert.ok(rules.some((r) => r[0] === 'hostnossl' && r[3] === '::/0'));
});

test('Caddyfile: API di amministrazione spenta, Keycloak esposto solo per i realm loyaltyhub e loyaltyhub-members e le risorse', () => {
  const c = read(path.join(DIR, 'caddy/Caddyfile'));
  assert.match(c, /^\s*admin off$/m);
  assert.match(c, /^\{\$LH_VETRINA_WEB_HOST\} \{[\s\S]*?reverse_proxy web:3000/m);
  const idp = c.split('{$LH_VETRINA_IDP_HOST} {')[1];
  assert.ok(idp, 'blocco del nome idp');
  // I due realm (ADR-051): operatori e membri; nessun altro realm, nessuna console.
  assert.match(idp, /@realm path \/realms\/loyaltyhub \/realms\/loyaltyhub\/\* \/realms\/loyaltyhub-members \/realms\/loyaltyhub-members\/\* \/resources\/\*\n/);
  assert.match(idp, /handle @realm \{\s*reverse_proxy idp:8080\s*\}/);
  assert.match(idp, /handle \{\s*respond 404\s*\}/);
  assert.equal((c.match(/reverse_proxy/g) ?? []).length, 2, 'solo web e idp dietro il proxy');
  assert.doesNotMatch(c.replace(/^\s*#.*$/gm, ''), /\/admin|realms\/master/);
});

test('timer: azzeramento settimanale con vetrina.sh reset (Q-624)', () => {
  const timer = read(path.join(DIR, 'systemd/loyaltyhub-vetrina-reset.timer'));
  assert.match(timer, /^OnCalendar=Mon \*-\*-\* 03:00:00 UTC$/m);
  assert.match(timer, /^Persistent=true$/m);
  const svc = read(path.join(DIR, 'systemd/loyaltyhub-vetrina-reset.service'));
  assert.match(svc, /^ExecStart=\/opt\/loyaltyhub\/deploy\/vetrina\/vetrina\.sh reset$/m);
  assert.match(svc, /^Type=oneshot$/m);
  // Azzeramento: solo i volumi di Postgres e Kafka, mai quelli del proxy (certificati ACME).
  assert.deepEqual(shellArray('RESET_VOLUMES'), ['lh-ref-postgres', 'lh-ref-kafka']);
});

test('script: sintassi di vetrina.sh e operatori.py', () => {
  assert.equal(spawnSync('bash', ['-n', VETRINA_SH]).status, 0);
  if (has('python3')) {
    const r = spawnSync('python3', ['-c', 'import ast,sys; ast.parse(open(sys.argv[1]).read())', OPERATORI]);
    assert.equal(r.status, 0, String(r.stderr));
  }
});

// ---------------------------------------------------------------------------------------------------------------
// Compose unito

const haveCompose = has('docker') && spawnSync('docker', ['compose', 'version']).status === 0;

function composeConfig(env) {
  return spawnSync('docker', ['compose', '-f', REFERENCE, '-f', OVERLAY, 'config', '--format', 'json'], {
    env: { PATH: process.env.PATH, HOME: process.env.HOME ?? os.tmpdir(), ...env }, encoding: 'utf8',
  });
}

test('compose unito: limiti di memoria per ogni container, porte solo su loopback salvo il proxy', { skip: !haveCompose && 'docker compose assente' }, () => {
  const r = composeConfig(EXAMPLE_ENV);
  assert.equal(r.status, 0, r.stderr);
  const cfg = JSON.parse(r.stdout);
  assert.equal(cfg.name, 'loyaltyhub-vetrina');
  const names = Object.keys(cfg.services).sort();
  assert.deepEqual(names, ['hub', 'idp', 'kafka', 'migrate', 'postgres', 'proxy', 'web'], 'nessun servizio di osservabilità né altri');
  for (const [name, svc] of Object.entries(cfg.services)) {
    assert.ok(svc.mem_limit, `${name}: limite di memoria`);
    for (const p of svc.ports ?? []) {
      if (name === 'proxy') {
        assert.equal(p.host_ip, '10.0.0.10');
        assert.ok(['80', '443'].includes(String(p.published)));
      } else {
        assert.equal(p.host_ip, '127.0.0.1', `${name}: porta ${p.published} solo su loopback`);
      }
    }
  }
  assert.equal(cfg.services.hub.environment.LH_OIDC_ISSUER, 'https://idp-vetrina.example.org/realms/loyaltyhub');
  // Realm dei membri (ADR-051): stesso emittente pubblico per hub e web, JWKS dal proxy, client portal con il segreto da file.
  assert.equal(cfg.services.hub.environment.LH_OIDC_MEMBER_ISSUER, 'https://idp-vetrina.example.org/realms/loyaltyhub-members');
  assert.equal(cfg.services.hub.environment.LH_OIDC_MEMBER_JWKS_URI, 'https://idp-vetrina.example.org/realms/loyaltyhub-members/protocol/openid-connect/certs');
  assert.equal(cfg.services.web.environment.LH_OIDC_MEMBER_ISSUER, cfg.services.hub.environment.LH_OIDC_MEMBER_ISSUER);
  assert.equal(cfg.services.web.environment.LH_WEB_MEMBER_CLIENT_ID, 'portal');
  assert.equal(cfg.services.web.environment.LH_WEB_MEMBER_CLIENT_SECRET_FILE, '/run/secrets/lh_portal_client_secret');
  assert.equal(cfg.services.idp.environment.LH_PORTAL_CLIENT_SECRET_FILE, '/run/secrets/lh_portal_client_secret');
  assert.ok(cfg.services.idp.volumes.some((v) => v.target === '/opt/keycloak/data/import/loyaltyhub-members-realm.json'), 'realm dei membri importato');
  assert.equal(cfg.services.web.environment.LH_WEB_URL, 'https://web-vetrina.example.org');
  // Utenti di test (Q-676): fuori dal codespace l'overlay non dichiara le variabili.
  for (const svc of ['hub', 'web', 'idp']) {
    assert.equal(cfg.services[svc].environment.LH_TEST_USERS_ALLOWED, undefined, svc);
    assert.equal(cfg.services[svc].environment.LH_ENVIRONMENT, undefined, svc);
  }
  assert.equal(cfg.services.idp.environment.KC_HOSTNAME, 'https://idp-vetrina.example.org');
  assert.equal(cfg.secrets.lh_db_password.file, '/etc/loyaltyhub-vetrina/secrets/db-password');
  // Anche con le variabili di segreto del riferimento esportate, nel compose unito restano vuote.
  const leaked = composeConfig({ ...EXAMPLE_ENV, LH_DB_PASSWORD: 'non-deve-entrare', LH_SUBJECT_KEY: 'non-deve-entrare', LH_PORTAL_CLIENT_SECRET: 'non-deve-entrare' });
  assert.equal(leaked.status, 0, leaked.stderr);
  // Solo i servizi contano: l'estensione x-lh-db-env del riferimento resta un frammento non usato dall'overlay.
  assert.doesNotMatch(JSON.stringify(JSON.parse(leaked.stdout).services), /non-deve-entrare/);
});

test('compose unito: senza indirizzo, nomi o cartella dei segreti non si avvia', { skip: !haveCompose && 'docker compose assente' }, () => {
  for (const missing of ['LH_VETRINA_PUBLIC_ADDRESS', 'LH_VETRINA_IDP_HOST', 'LH_VETRINA_DIR']) {
    const env = { ...EXAMPLE_ENV };
    delete env[missing];
    const r = composeConfig(env);
    assert.notEqual(r.status, 0, `${missing} assente: config deve fallire`);
    assert.match(r.stderr, new RegExp(missing));
  }
});

// ---------------------------------------------------------------------------------------------------------------
// Entrypoint dell'overlay (segreti da file e guardie TLS)

const ENTRYPOINT = overlay['x-lh-vetrina-env'][2].replaceAll('$$', '$');

function runEntrypoint(env, cmd = ['sh', '-c', 'env']) {
  return spawnSync('sh', ['-c', ENTRYPOINT, 'lh-require-env', ...cmd], {
    env: { PATH: process.env.PATH, ...env }, encoding: 'utf8',
  });
}

test('entrypoint: legge i segreti dai file, toglie <VAR>_FILE e non stampa i valori', () => {
  const d = tmp();
  fs.writeFileSync(path.join(d, 'pw'), 'valore-segreto-1\n', { mode: 0o600 });
  fs.writeFileSync(path.join(d, 'b64'), 'QUJD', { mode: 0o600 }); // senza a capo finale
  const ok = runEntrypoint({ DB_PASSWORD: '', DB_PASSWORD_FILE: path.join(d, 'pw'), K_B64_FILE: path.join(d, 'b64'), LH_SECRET_FILE_ENV: 'DB_PASSWORD K_B64', LH_REQUIRED_ENV: 'DB_PASSWORD' });
  assert.equal(ok.status, 0, ok.stderr);
  assert.match(ok.stdout, /^DB_PASSWORD=valore-segreto-1$/m);
  assert.match(ok.stdout, /^K_B64=QUJD$/m);
  assert.doesNotMatch(ok.stdout, /DB_PASSWORD_FILE|K_B64_FILE/);
  // Variabile in chiaro piena: errore, senza il valore nel messaggio.
  const plain = runEntrypoint({ DB_PASSWORD: 'valore-in-chiaro', DB_PASSWORD_FILE: path.join(d, 'pw'), LH_SECRET_FILE_ENV: 'DB_PASSWORD' });
  assert.equal(plain.status, 1);
  assert.match(plain.stderr, /Segreto in chiaro nell'ambiente: DB_PASSWORD/);
  assert.doesNotMatch(plain.stderr + plain.stdout, /valore-in-chiaro|valore-segreto-1/);
  // File mancante o vuoto: errore.
  const missing = runEntrypoint({ DB_PASSWORD_FILE: path.join(d, 'assente'), LH_SECRET_FILE_ENV: 'DB_PASSWORD' });
  assert.equal(missing.status, 1);
  assert.match(missing.stderr, /File del segreto mancante o non leggibile: DB_PASSWORD_FILE/);
  fs.writeFileSync(path.join(d, 'vuoto'), '', { mode: 0o600 });
  const empty = runEntrypoint({ DB_PASSWORD_FILE: path.join(d, 'vuoto'), LH_SECRET_FILE_ENV: 'DB_PASSWORD' });
  assert.equal(empty.status, 1);
  assert.match(empty.stderr, /File del segreto vuoto: DB_PASSWORD_FILE/);
});

test('entrypoint: database senza verify-full o Kafka non SSL_PEM fermano il container (INSECURE_CONFIG, Q-621)', () => {
  const tlsUrl = 'jdbc:postgresql://postgres:5432/loyaltyhub?sslmode=verify-full&sslrootcert=/run/secrets/lh_tls_ca';
  const ok = runEntrypoint({ DB_URL: tlsUrl, LH_VETRINA_REQUIRE_DB_TLS: 'DB_URL', KAFKA_SECURITY: 'SSL_PEM', LH_VETRINA_REQUIRE_KAFKA_TLS: 'true' }, ['true']);
  assert.equal(ok.status, 0, ok.stderr);
  for (const url of ['jdbc:postgresql://postgres:5432/loyaltyhub', 'jdbc:postgresql://postgres:5432/loyaltyhub?sslmode=require', 'jdbc:postgresql://postgres:5432/loyaltyhub?sslmode=disable']) {
    const r = runEntrypoint({ DB_URL: url, LH_VETRINA_REQUIRE_DB_TLS: 'DB_URL' }, ['true']);
    assert.equal(r.status, 1, url);
    assert.match(r.stderr, /INSECURE_CONFIG: DB_URL senza sslmode=verify-full/);
  }
  const kafka = runEntrypoint({ KAFKA_SECURITY: 'PLAINTEXT', LH_VETRINA_REQUIRE_KAFKA_TLS: 'true' }, ['true']);
  assert.equal(kafka.status, 1);
  assert.match(kafka.stderr, /INSECURE_CONFIG: KAFKA_SECURITY deve essere SSL_PEM/);
  const required = runEntrypoint({ LH_REQUIRED_ENV: 'DB_PASSWORD' }, ['true']);
  assert.equal(required.status, 1);
  assert.match(required.stderr, /Variabile obbligatoria mancante: DB_PASSWORD/);
});

// ---------------------------------------------------------------------------------------------------------------
// vetrina.sh

function writeConfig(dir, extra = '') {
  const cfg = path.join(dir, 'vetrina.env');
  const lines = Object.entries({ ...EXAMPLE_ENV, LH_VETRINA_DIR: path.join(dir, 'host') }).map(([k, v]) => `${k}=${v}`);
  fs.writeFileSync(cfg, `# prova\n${lines.join('\n')}\n${extra}`, { mode: 0o600 });
  return cfg;
}

function vetrina(args, cfg, env = {}) {
  return spawnSync('bash', [VETRINA_SH, ...args], {
    env: { PATH: process.env.PATH, HOME: process.env.HOME ?? os.tmpdir(), LH_VETRINA_CONFIG: cfg, LH_VETRINA_TEST_NO_CHOWN: '1', ...env },
    encoding: 'utf8',
  });
}

test('vetrina.sh: configurazione rifiutata (uscita 2) con chiavi ignote, bind non loopback, nomi o indirizzi non validi', () => {
  const d = tmp();
  const cases = [
    ['LH_DB_PASSWORD=x\n', /chiave non ammessa: LH_DB_PASSWORD/],
    ['LH_BIND_ADDRESS=0.0.0.0\n', /LH_BIND_ADDRESS deve essere 127\.0\.0\.1/],
    ['LH_VETRINA_PUBLIC_ADDRESS=0.0.0.0\n', /non può essere 0\.0\.0\.0/],
    ['LH_VETRINA_WEB_HOST=https://web.example.org\n', /LH_VETRINA_WEB_HOST non è un nome DNS valido/],
    ['LH_VETRINA_IDP_HOST=web-vetrina.example.org\n', /devono essere diversi/],
    ['LH_HUB_DEMO_URL=http://demo.example.org\n', /LH_HUB_DEMO_URL deve essere un'origine https/],
  ];
  for (const [extra, msg] of cases) {
    const r = vetrina(['provision'], writeConfig(d, extra));
    assert.equal(r.status, 2, `${extra.trim()}: ${r.stderr}`);
    assert.match(r.stderr, msg);
  }
  const cfg = writeConfig(d);
  fs.chmodSync(cfg, 0o666);
  const loose = vetrina(['provision'], cfg);
  assert.equal(loose.status, 2);
  assert.match(loose.stderr, /scrivibile dal gruppo o da altri/);
  const unknown = vetrina(['boh'], writeConfig(d));
  assert.equal(unknown.status, 2);
});

test('vetrina.sh provision: segreti 0600 mai stampati, CA e certificati validi, idempotente', { skip: !has('openssl') && 'openssl assente' }, () => {
  const d = tmp();
  const cfg = writeConfig(d);
  const host = path.join(d, 'host');
  const first = vetrina(['provision'], cfg);
  assert.equal(first.status, 0, first.stderr);
  const snapshot = {};
  for (const sub of ['secrets', 'tls', 'ca']) {
    assert.equal((fs.statSync(path.join(host, sub)).mode & 0o777).toString(8), '700', `${sub}: cartella 0700`);
    for (const f of fs.readdirSync(path.join(host, sub))) {
      const p = path.join(host, sub, f);
      snapshot[`${sub}/${f}`] = read(p);
      const mode = (fs.statSync(p).mode & 0o777).toString(8);
      if (sub === 'tls' && f.endsWith('.crt')) assert.equal(mode, '644', `${sub}/${f}`);
      else assert.equal(mode, '600', `${sub}/${f}`);
    }
  }
  for (const f of SECRET_FILES) assert.ok(snapshot[`secrets/${f}`]?.trim(), `secrets/${f} generato`);
  assert.ok(!('tls/ca.key' in snapshot), 'la chiave della CA resta in ca/');
  // Nessun valore su stdout o stderr.
  const out = first.stdout + first.stderr;
  for (const [name, value] of Object.entries(snapshot)) {
    if (name.endsWith('.crt')) continue;
    for (const line of value.split('\n').filter((l) => l.length >= 16 && !l.startsWith('-----'))) {
      assert.ok(!out.includes(line), `${name}: valore stampato`);
    }
  }
  // Forme: password url-safe di 32 caratteri, chiavi di 32 byte in base64.
  assert.match(snapshot['secrets/db-password'].trim(), /^[A-Za-z0-9_-]{32}$/);
  assert.equal(Buffer.from(snapshot['secrets/subject-key'].trim(), 'base64').length, 32);
  assert.equal(Buffer.from(snapshot['secrets/web-session-key'].trim(), 'base64').length, 32);
  assert.notEqual(snapshot['secrets/db-password'], snapshot['secrets/idp-db-password']);
  // Certificati: firmati dalla CA locale, nome giusto, uso giusto; chiavi PKCS#8 (Kafka PEM).
  const ca = path.join(host, 'tls/ca.crt');
  for (const [leaf, san, eku] of [['postgres', 'DNS:postgres', /TLS Web Server Authentication/], ['kafka', 'DNS:kafka', /Server Authentication, TLS Web Client Authentication/], ['hub-kafka-client', 'DNS:hub', /TLS Web Client Authentication/]]) {
    const crt = path.join(host, `tls/${leaf}.crt`);
    assert.equal(spawnSync('openssl', ['verify', '-CAfile', ca, crt]).status, 0, `${leaf}: verifica con la CA`);
    const text = spawnSync('openssl', ['x509', '-in', crt, '-noout', '-text'], { encoding: 'utf8' }).stdout;
    assert.ok(text.includes(san), `${leaf}: ${san}`);
    assert.match(text, eku);
    assert.ok(snapshot[`tls/${leaf}.key`].startsWith(pemHeader('PRIVATE KEY')), `${leaf}: chiave PKCS#8`);
  }
  const keystore = snapshot['tls/kafka-keystore.pem'];
  assert.ok(keystore.startsWith(pemHeader('PRIVATE KEY')), 'keystore: chiave PKCS#8 in testa');
  assert.ok(keystore.indexOf(pemHeader('CERTIFICATE')) > 0, 'keystore: certificato dopo la chiave');
  assert.equal(Buffer.from(snapshot['tls/kafka-client-cert.b64'], 'base64').toString(), snapshot['tls/hub-kafka-client.crt']);
  assert.equal(Buffer.from(snapshot['tls/kafka-client-key.b64'], 'base64').toString(), snapshot['tls/hub-kafka-client.key']);
  assert.equal(Buffer.from(snapshot['tls/kafka-client-ca.b64'], 'base64').toString(), snapshot['tls/ca.crt']);
  // Seconda esecuzione: nulla cambia.
  const second = vetrina(['provision'], cfg);
  assert.equal(second.status, 0, second.stderr);
  assert.doesNotMatch(second.stdout, /generato|emesso|generata/);
  for (const [name, value] of Object.entries(snapshot)) assert.equal(read(path.join(host, name)), value, `${name} riscritto`);
  // Un certificato cancellato si riemette senza toccare segreti e CA.
  fs.rmSync(path.join(host, 'tls/postgres.crt'));
  const third = vetrina(['provision'], cfg);
  assert.equal(third.status, 0, third.stderr);
  assert.match(third.stdout, /emesso: tls\/postgres\.crt/);
  assert.equal(read(path.join(host, 'ca/ca.key')), snapshot['ca/ca.key']);
  assert.equal(read(path.join(host, 'secrets/subject-key')), snapshot['secrets/subject-key']);
  // Preflight: un segreto con permessi larghi o nell'ambiente fa fallire, senza stampare il valore.
  const arch = spawnSync('uname', ['-m'], { encoding: 'utf8' }).stdout.trim();
  const leakedEnv = vetrina(['preflight', '--offline'], cfg, { LH_VETRINA_EXPECTED_ARCH: arch, LH_SUBJECT_KEY: 'valore-in-chiaro' });
  assert.equal(leakedEnv.status, 1);
  assert.match(leakedEnv.stderr, /segreti nell'ambiente \(LH_SUBJECT_KEY\)/);
  assert.doesNotMatch(leakedEnv.stderr + leakedEnv.stdout, /valore-in-chiaro/);
  fs.chmodSync(path.join(host, 'secrets/db-password'), 0o644);
  const loose = vetrina(['preflight', '--offline'], cfg, { LH_VETRINA_EXPECTED_ARCH: arch });
  assert.equal(loose.status, 1);
  assert.match(loose.stderr, /permessi 644 invece di 600: .*secrets\/db-password/);
  fs.chmodSync(path.join(host, 'secrets/db-password'), 0o600);
  const wrongArch = vetrina(['preflight', '--offline'], cfg, { LH_VETRINA_EXPECTED_ARCH: 'aarch64-finto' });
  assert.equal(wrongArch.status, 1);
  assert.match(wrongArch.stderr, /architettura dell'host .*attesa aarch64-finto/);
});

// ---------------------------------------------------------------------------------------------------------------
// operatori.py contro un Keycloak simulato

function fakeKeycloak() {
  const state = { users: [], mappings: {}, passwords: {}, adminLogins: 0 };
  const roles = Object.fromEntries(['MFA_REQUIRED_ROLE', 'ADMIN', 'MARKETING', 'LEGAL', 'CARE', 'ANALYST'].map((n, i) => [n, { id: `r${i}`, name: n }]));
  const server = http.createServer((req, res) => {
    let body = '';
    req.on('data', (c) => { body += c; });
    req.on('end', () => {
      const url = new URL(req.url, 'http://x');
      const send = (code, obj) => { res.writeHead(code, { 'Content-Type': 'application/json' }); res.end(obj === undefined ? '' : JSON.stringify(obj)); };
      if (req.method === 'POST' && url.pathname === '/realms/master/protocol/openid-connect/token') {
        const form = new URLSearchParams(body);
        if (form.get('password') !== 'pw-admin-di-prova' || form.get('client_id') !== 'admin-cli') return send(401, {});
        state.adminLogins++;
        return send(200, { access_token: 'tok' });
      }
      if (req.headers.authorization !== 'Bearer tok') return send(401, {});
      const base = '/admin/realms/loyaltyhub';
      let m;
      if (req.method === 'GET' && (m = url.pathname.match(`^${base}/roles/([A-Z_]+)$`))) return roles[m[1]] ? send(200, roles[m[1]]) : send(404, {});
      if (req.method === 'GET' && url.pathname === `${base}/users`) return send(200, state.users.filter((u) => u.username === url.searchParams.get('username')));
      if (req.method === 'POST' && url.pathname === `${base}/users`) {
        const u = JSON.parse(body);
        state.users.push({ ...u, id: `u${state.users.length}` });
        return send(201);
      }
      if (req.method === 'PUT' && (m = url.pathname.match(`^${base}/users/(u\\d+)/reset-password$`))) {
        const p = JSON.parse(body);
        state.passwords[m[1]] = p;
        return send(204);
      }
      if (req.method === 'POST' && (m = url.pathname.match(`^${base}/users/(u\\d+)/role-mappings/realm$`))) {
        state.mappings[m[1]] = JSON.parse(body).map((r) => r.name);
        return send(204);
      }
      return send(404, {});
    });
  });
  return new Promise((resolve) => server.listen(0, '127.0.0.1', () => resolve({ server, state, url: `http://127.0.0.1:${server.address().port}` })));
}

function runOperatori(args) {
  return new Promise((resolve) => {
    const p = spawn('python3', [OPERATORI, ...args], { env: { PATH: process.env.PATH } });
    let stdout = '';
    let stderr = '';
    p.stdout.on('data', (c) => { stdout += c; });
    p.stderr.on('data', (c) => { stderr += c; });
    p.on('close', (status) => resolve({ status, stdout, stderr }));
  });
}

test('operatori.py: account nominativi con ruolo e MFA_REQUIRED_ROLE, password solo nel file 0600, idempotente (Q-618)', { skip: !has('python3') && 'python3 assente' }, async () => {
  const kc = await fakeKeycloak();
  try {
    const d = tmp();
    const pwFile = path.join(d, 'admin');
    fs.writeFileSync(pwFile, 'pw-admin-di-prova\n', { mode: 0o600 });
    const list = path.join(d, 'operators.list');
    fs.writeFileSync(list, '# elenco\nanna.admin ADMIN anna.admin@example.org\nbruno.care CARE bruno.care@example.org\n', { mode: 0o600 });
    const out = path.join(d, 'operator-passwords.txt');
    const args = ['--keycloak', kc.url, '--admin-password-file', pwFile, '--list', list, '--out', out];
    const r = await runOperatori(args);
    assert.equal(r.status, 0, r.stderr);
    assert.equal(kc.state.users.length, 2);
    for (const u of kc.state.users) {
      assert.deepEqual(u.requiredActions, ['UPDATE_PASSWORD', 'CONFIGURE_TOTP']);
      assert.equal(u.enabled, true);
      assert.equal(u.emailVerified, false);
      assert.equal(kc.state.passwords[u.id].temporary, true);
    }
    assert.deepEqual(kc.state.mappings.u0, ['ADMIN', 'MFA_REQUIRED_ROLE']);
    assert.deepEqual(kc.state.mappings.u1, ['CARE', 'MFA_REQUIRED_ROLE']);
    assert.equal((fs.statSync(out).mode & 0o777).toString(8), '600');
    const written = read(out);
    for (const u of kc.state.users) {
      const pw = kc.state.passwords[u.id].value;
      assert.ok(pw.length >= 20);
      assert.ok(written.includes(`${u.username}: ${pw}`));
      assert.ok(!(r.stdout + r.stderr).includes(pw), 'password temporanea stampata');
    }
    assert.ok(!(r.stdout + r.stderr).includes('pw-admin-di-prova'));
    // Seconda esecuzione: utenti presenti, nulla cambia e il file non si riscrive.
    const before = written;
    const again = await runOperatori(args);
    assert.equal(again.status, 0, again.stderr);
    assert.match(again.stdout, /presente: anna\.admin \(non modificato\)/);
    assert.equal(kc.state.users.length, 2);
    assert.equal(read(out), before);
    // Rifiuti: http non loopback, elenco leggibile da altri, ruolo non operatore, admin password con permessi larghi.
    const remote = await runOperatori(['--keycloak', 'http://idp.example.org', '--admin-password-file', pwFile, '--list', list, '--out', out]);
    assert.equal(remote.status, 2);
    assert.match(remote.stderr, /serve https/);
    fs.chmodSync(list, 0o644);
    const looseList = await runOperatori(args);
    assert.equal(looseList.status, 2);
    assert.match(looseList.stderr, /permessi 0600/);
    fs.writeFileSync(list, 'carlo SOURCE carlo@example.org\n', { mode: 0o600 });
    fs.chmodSync(list, 0o600);
    const badRole = await runOperatori(args);
    assert.equal(badRole.status, 2);
    assert.match(badRole.stderr, /ruolo non ammesso SOURCE/);
    fs.writeFileSync(list, 'anna.admin ADMIN anna.admin@example.org\n', { mode: 0o600 });
    fs.chmodSync(pwFile, 0o644);
    const loosePw = await runOperatori(args);
    assert.equal(loosePw.status, 2);
    assert.match(loosePw.stderr, /permessi troppo larghi/);
  } finally {
    kc.server.close();
  }
});

// ---------------------------------------------------------------------------------------------------------------
// Codespace (ADR-050, M8.14 V7; Q-660…Q-663)

const CODESPACE_OVERLAY = path.join(DIR, 'compose.codespace.yml');
const DEVCONTAINER = path.join(ROOT, '.devcontainer/vetrina/devcontainer.json');
const AVVIO = path.join(ROOT, '.devcontainer/vetrina/avvio.sh');
const CODESPACE_ENV = {
  ...EXAMPLE_ENV,
  LH_VETRINA_WEB_HOST: 'prova-vetrina-8000.app.github.dev',
  LH_VETRINA_IDP_HOST: 'prova-vetrina-8001.app.github.dev',
  LH_VETRINA_ADMIN_HOST: 'prova-vetrina-8180.app.github.dev',
  LH_VETRINA_PUBLIC_ADDRESS: '127.0.0.1',
};

test('vetrina.sh: utenti di test ammessi solo nel codespace con CODESPACES=true, altrove variabili forzate a vuoto (Q-676)', () => {
  const sh = read(VETRINA_SH);
  const fn = sh.split('\ncompose() {')[1]?.split('\n}\n')[0] ?? '';
  assert.match(fn, /local test_env=\(LH_TEST_USERS_ALLOWED= LH_ENVIRONMENT=\)/, 'default: vuote, anche se esportate');
  assert.match(fn, /if \[ "\$\{LH_VETRINA_MODE:-host\}" = codespace \] && \[ "\$\{CODESPACES:-\}" = true \]; then\n\s+test_env=\(LH_TEST_USERS_ALLOWED=true LH_ENVIRONMENT=test\)/);
  assert.match(fn, /env "\$\{unset_args\[@\]\}" "\$\{test_env\[@\]\}" docker compose/);
  // Né il compose di riferimento né l'overlay dell'host fisso né il chart le dichiarano.
  for (const f of [REFERENCE, OVERLAY, path.join(ROOT, 'deploy/helm/loyaltyhub/templates/hub.yaml'), path.join(ROOT, 'deploy/helm/loyaltyhub/templates/web.yaml')]) {
    assert.doesNotMatch(read(f), /LH_TEST_USERS_ALLOWED|LH_ENVIRONMENT/, path.relative(ROOT, f));
  }
});

test('Caddyfile del codespace: niente ACME, instradamento per porta, Keycloak solo per i realm loyaltyhub e loyaltyhub-members (Q-661, ADR-051)', () => {
  const c = read(path.join(DIR, 'caddy/Caddyfile.codespace'));
  assert.match(c, /^\s*admin off$/m);
  assert.match(c, /^\s*auto_https off$/m);
  assert.match(c, /^:8000 \{[\s\S]*?reverse_proxy web:3000/m);
  const idp = c.split(':8001 {')[1];
  assert.ok(idp, 'blocco della porta 8001');
  // I due realm (ADR-051): operatori e membri; nessun altro realm, nessuna console.
  assert.match(idp, /@realm path \/realms\/loyaltyhub \/realms\/loyaltyhub\/\* \/realms\/loyaltyhub-members \/realms\/loyaltyhub-members\/\* \/resources\/\*\n/);
  assert.match(idp, /handle @realm \{\s*reverse_proxy idp:8080/);
  assert.match(idp, /handle \{\s*respond 404\s*\}/);
  assert.equal((c.match(/reverse_proxy/g) ?? []).length, 2, 'solo web e idp dietro il proxy');
  assert.doesNotMatch(c.replace(/^\s*#.*$/gm, ''), /\/admin|realms\/master/);
});

test('dev container della vetrina: macchina di Q-660, avvio automatico, configurazione fuori dal repository', () => {
  const dc = JSON.parse(read(DEVCONTAINER).replace(/^\s*\/\/.*$/gm, ''));
  assert.deepEqual(dc.hostRequirements, { cpus: 4, memory: '16gb', storage: '32gb' });
  assert.ok(Object.keys(dc.features).some((f) => f.startsWith('ghcr.io/devcontainers/features/docker-in-docker:')));
  assert.equal(dc.postStartCommand, 'bash .devcontainer/vetrina/avvio.sh');
  assert.match(dc.containerEnv.LH_VETRINA_CONFIG, /^\/workspaces\/\.[^/]+\/vetrina\.env$/, 'configurazione e segreti fuori dal repository');
  assert.deepEqual(dc.forwardPorts, [8000, 8001]);
  // Nessuna porta pubblica dichiarata qui: la visibilità la imposta avvio.sh solo per 8000 e 8001; hub e web diretto ignorati.
  assert.equal(JSON.stringify(dc).includes('visibility'), false);
  assert.equal(dc.portsAttributes['8080'].onAutoForward, 'ignore');
  assert.equal(dc.portsAttributes['3000'].onAutoForward, 'ignore');
  assert.equal(dc.otherPortsAttributes.onAutoForward, 'ignore');
  for (const p of Object.values(dc.portsAttributes)) assert.equal(p.protocol, undefined, 'il proxy del codespace parla HTTP');
  const avvio = read(AVVIO);
  assert.equal(spawnSync('bash', ['-n', AVVIO]).status, 0);
  assert.match(avvio, /gh codespace ports visibility 8000:public 8001:public/);
  assert.doesNotMatch(avvio, /ports visibility [^\n]*(8080|8180|3000)/, 'solo web e Keycloak pubblici');
  assert.match(avvio, /--password-stdin/, 'il token del codespace solo da stdin');
  assert.match(avvio, /bash deploy\/vetrina\/vetrina\.sh codespace/);
});

test('compose unito nel codespace: proxy HTTP su 127.0.0.1:8000 e 8001, nessun alias, stesso emittente pubblico', { skip: !haveCompose && 'docker compose assente' }, () => {
  const r = spawnSync('docker', ['compose', '-f', REFERENCE, '-f', OVERLAY, '-f', CODESPACE_OVERLAY, 'config', '--format', 'json'], {
    env: { PATH: process.env.PATH, HOME: process.env.HOME ?? os.tmpdir(), ...CODESPACE_ENV, LH_TEST_USERS_ALLOWED: 'true', LH_ENVIRONMENT: 'test' }, encoding: 'utf8',
  });
  assert.equal(r.status, 0, r.stderr);
  const cfg = JSON.parse(r.stdout);
  assert.deepEqual(Object.keys(cfg.services).sort(), ['hub', 'idp', 'kafka', 'migrate', 'postgres', 'proxy', 'web']);
  const proxy = cfg.services.proxy;
  assert.deepEqual(proxy.ports.map((p) => `${p.host_ip}:${p.published}:${p.target}`), ['127.0.0.1:8000:8000', '127.0.0.1:8001:8001']);
  // Porte non privilegiate, ma NET_BIND_SERVICE resta nell'insieme limite: senza, il binario caddy (file capability) non parte.
  assert.deepEqual(proxy.cap_add, ['NET_BIND_SERVICE']);
  assert.deepEqual(proxy.cap_drop, ['ALL']);
  assert.deepEqual(proxy.networks.default ?? {}, {}, 'nessun alias: i nomi pubblici puntano all\'inoltro di GitHub');
  assert.ok(proxy.volumes.some((v) => v.source.endsWith('/caddy/Caddyfile.codespace') && v.target === '/etc/caddy/Caddyfile'));
  for (const [name, svc] of Object.entries(cfg.services)) {
    assert.ok(svc.mem_limit, `${name}: limite di memoria`);
    for (const p of svc.ports ?? []) assert.equal(p.host_ip, '127.0.0.1', `${name}: porta ${p.published} solo su loopback`);
  }
  assert.equal(cfg.services.hub.environment.LH_OIDC_ISSUER, 'https://prova-vetrina-8001.app.github.dev/realms/loyaltyhub');
  for (const svc of ['hub', 'web']) {
    assert.equal(cfg.services[svc].environment.LH_OIDC_MEMBER_ISSUER, 'https://prova-vetrina-8001.app.github.dev/realms/loyaltyhub-members', svc);
  }
  assert.equal(cfg.services.web.environment.LH_WEB_URL, 'https://prova-vetrina-8000.app.github.dev');
  // Utenti di test (Q-676): nel codespace hub e web ricevono le due variabili da vetrina.sh (qui dall'ambiente del test).
  for (const svc of ['hub', 'web']) {
    assert.equal(cfg.services[svc].environment.LH_TEST_USERS_ALLOWED, 'true', svc);
    assert.equal(cfg.services[svc].environment.LH_ENVIRONMENT, 'test', svc);
  }
  assert.equal(cfg.services.idp.environment.KC_HOSTNAME, 'https://prova-vetrina-8001.app.github.dev');
  // Console sull'inoltro privato della porta 8180 (solo il proprietario), non su 127.0.0.1 come sull'host fisso.
  assert.equal(cfg.services.idp.environment.KC_HOSTNAME_ADMIN, 'https://prova-vetrina-8180.app.github.dev');
  assert.deepEqual(cfg.services.idp.ports.map((p) => `${p.host_ip}:${p.published}`), ['127.0.0.1:8180']);
  // TLS verso bus e database invariato.
  assert.equal(cfg.services.hub.environment.KAFKA_SECURITY, 'SSL_PEM');
  assert.match(cfg.services.hub.environment.DB_URL, /sslmode=verify-full/);
});

test('vetrina.sh in modalità codespace: configurazione rifiutata se il proxy esce da loopback o i nomi non sono del codespace', () => {
  const d = tmp();
  const write = (env, extra = '') => {
    const cfg = path.join(d, 'vetrina.env');
    const lines = Object.entries({ LH_VETRINA_MODE: 'codespace', ...env, LH_VETRINA_DIR: path.join(d, 'host') }).map(([k, v]) => `${k}=${v}`);
    fs.writeFileSync(cfg, `${lines.join('\n')}\n${extra}`, { mode: 0o600 });
    return cfg;
  };
  const cases = [
    [{ ...CODESPACE_ENV, LH_VETRINA_PUBLIC_ADDRESS: '10.0.0.10' }, /deve essere 127\.0\.0\.1 nel codespace/],
    [{ ...CODESPACE_ENV, LH_VETRINA_WEB_HOST: 'web-vetrina.example.org' }, /LH_VETRINA_WEB_HOST nel codespace/],
    [{ ...CODESPACE_ENV, LH_VETRINA_IDP_HOST: 'prova-vetrina-8443.app.github.dev' }, /LH_VETRINA_IDP_HOST nel codespace/],
    [{ ...CODESPACE_ENV, LH_VETRINA_ADMIN_HOST: '' }, /LH_VETRINA_ADMIN_HOST nel codespace/],
    [{ ...CODESPACE_ENV, LH_VETRINA_ADMIN_HOST: 'prova-vetrina-8001.app.github.dev' }, /LH_VETRINA_ADMIN_HOST nel codespace/],
    [{ ...CODESPACE_ENV, LH_VETRINA_MODE: 'cloud' }, /LH_VETRINA_MODE deve essere host o codespace/],
  ];
  for (const [env, msg] of cases) {
    const r = vetrina(['provision'], write(env));
    assert.equal(r.status, 2, r.stderr);
    assert.match(r.stderr, msg);
  }
  // Fuori da un codespace il comando non parte.
  const outside = vetrina(['codespace'], path.join(d, 'cs.env'), { LH_IMAGE: 'ghcr.io/example/loyaltyhub:ci' });
  assert.equal(outside.status, 2);
  assert.match(outside.stderr, /solo dentro un GitHub Codespace/);
});

test('vetrina.sh codespace: configurazione dall\'ambiente, segreti generati, elenco operatori 0600 mai stampato (Q-663)', { skip: !has('openssl') && 'openssl assente' }, () => {
  const d = tmp();
  // docker finto che fallisce: il comando si ferma ai controlli preliminari, dopo configurazione e provisioning.
  const bin = path.join(d, 'bin');
  fs.mkdirSync(bin);
  fs.writeFileSync(path.join(bin, 'docker'), '#!/bin/sh\nexit 1\n', { mode: 0o755 });
  const cfg = path.join(d, 'cs', 'vetrina.env');
  const r = vetrina(['codespace'], cfg, {
    PATH: `${bin}:${process.env.PATH}`,
    CODESPACES: 'true',
    CODESPACE_NAME: 'prova-vetrina-x5g7',
    GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN: 'app.github.dev',
    LH_IMAGE: 'ghcr.io/example/loyaltyhub:v1.2.3',
    LH_VETRINA_OPERATORS: 'anna.admin ADMIN anna.admin@example.org; bruno.legal LEGAL bruno.legal@example.org',
  });
  assert.equal(r.status, 1, r.stderr);
  assert.match(r.stderr, /docker compose config fallito/);
  assert.doesNotMatch(r.stdout + r.stderr, /example\.org/, 'l\'elenco degli operatori non si stampa');
  const conf = read(cfg);
  assert.equal(fs.statSync(cfg).mode & 0o777, 0o600);
  assert.match(conf, /^LH_VETRINA_MODE=codespace$/m);
  assert.match(conf, /^LH_IMAGE=ghcr\.io\/example\/loyaltyhub:v1\.2\.3$/m);
  assert.match(conf, /^LH_VETRINA_WEB_HOST=prova-vetrina-x5g7-8000\.app\.github\.dev$/m);
  assert.match(conf, /^LH_VETRINA_IDP_HOST=prova-vetrina-x5g7-8001\.app\.github\.dev$/m);
  assert.match(conf, /^LH_VETRINA_ADMIN_HOST=prova-vetrina-x5g7-8180\.app\.github\.dev$/m);
  assert.match(conf, /^LH_VETRINA_PUBLIC_ADDRESS=127\.0\.0\.1$/m);
  assert.match(conf, new RegExp(`^LH_VETRINA_DIR=${path.join(d, 'cs').replaceAll('/', '\\/')}$`, 'm'));
  for (const s of SECRET_FILES) assert.equal(fs.statSync(path.join(d, 'cs', 'secrets', s)).mode & 0o777, 0o600, s);
  const list = path.join(d, 'cs', 'operators.list');
  assert.equal(fs.statSync(list).mode & 0o777, 0o600);
  assert.equal(read(list), 'anna.admin ADMIN anna.admin@example.org\nbruno.legal LEGAL bruno.legal@example.org\n');
  // Nome del codespace non valido: uso errato.
  const bad = vetrina(['codespace'], cfg, { CODESPACES: 'true', CODESPACE_NAME: 'Nome Con Spazi', GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN: 'app.github.dev', LH_IMAGE: 'x' });
  assert.equal(bad.status, 2);
  assert.match(bad.stderr, /CODESPACE_NAME assente o non valido/);
});

// ---------------------------------------------------------------------------------------------------------------
// Keycloak da zero a ogni avvio e membri di test (M8.14 V8; ADR-051 decisioni 1 e 10; Q-672, Q-673, Q-676)

/** Configurazione del codespace in una cartella temporanea, con un `docker` e un `node` finti che registrano gli argomenti. */
function fakeCodespace() {
  const d = tmp();
  const bin = path.join(d, 'bin');
  fs.mkdirSync(bin);
  const log = path.join(d, 'calls.log');
  for (const cmd of ['docker', 'node']) {
    fs.writeFileSync(path.join(bin, cmd), `#!/bin/sh\nprintf '${cmd} %s\\n' "$*" >> "${log}"\nexit 0\n`, { mode: 0o755 });
  }
  const cfg = path.join(d, 'vetrina.env');
  const lines = Object.entries({ ...CODESPACE_ENV, LH_VETRINA_MODE: 'codespace', LH_VETRINA_DIR: path.join(d, 'host') }).map(([k, v]) => `${k}=${v}`);
  fs.writeFileSync(cfg, `${lines.join('\n')}\n`, { mode: 0o600 });
  return { cfg, bin, log, run: (args, env = {}) => vetrina(args, cfg, { PATH: `${bin}:${process.env.PATH}`, ...env }) };
}

test('vetrina.sh idp-reset: ferma Keycloak e ricrea il solo database idp (non quello dell\'hub, nessun volume toccato) (Q-672)', () => {
  const cs = fakeCodespace();
  const r = cs.run(['idp-reset']);
  assert.equal(r.status, 0, r.stderr);
  const calls = read(cs.log);
  assert.match(calls, /docker compose --project-name loyaltyhub-vetrina .* stop idp web hub proxy/);
  assert.match(calls, /up -d --wait --wait-timeout 300 postgres/);
  assert.match(calls, /exec -T postgres psql -v ON_ERROR_STOP=1 -U loyaltyhub -d loyaltyhub -c DROP DATABASE IF EXISTS idp WITH \(FORCE\) -c CREATE DATABASE idp OWNER idp -c REVOKE ALL ON DATABASE idp FROM PUBLIC/);
  assert.doesNotMatch(calls, /DROP DATABASE IF EXISTS loyaltyhub|volume rm| down/, 'il database dell\'hub e i volumi restano');
  assert.ok(calls.indexOf(' stop idp') < calls.indexOf('DROP DATABASE'), 'Keycloak è fermo prima di ricreare il database');
});

test('vetrina.sh codespace: a ogni avvio database di Keycloak da zero PRIMA dell\'avvio, poi overlay, operatori e membri di test, senza marcatore (Q-672, Q-673)', () => {
  const sh = read(VETRINA_SH);
  const fn = sh.split('\ncmd_codespace() {')[1]?.split('\n}\n')[0] ?? '';
  const order = ['cmd_preflight', 'cmd_idp_reset', 'compose up -d --wait --wait-timeout 900', 'apply_realm_overlay', 'cmd_operators', 'cmd_membri --if-reachable'];
  const at = order.map((s) => fn.indexOf(s));
  assert.ok(at.every((i) => i > 0) && at.every((v, i) => i === 0 || v > at[i - 1]), `ordine dei passi in cmd_codespace: ${at}`);
  assert.doesNotMatch(sh, /\.realm-overlay/, 'nessun marcatore del primo avvio: l\'overlay si riapplica a ogni avvio');
  assert.match(sh, /DROP DATABASE IF EXISTS idp WITH \(FORCE\)/);
  assert.doesNotMatch(sh, /DROP DATABASE[^\n]*loyaltyhub\b/);
  // Anche l'azzeramento a mano ricrea i membri di test (il database dell'hub riparte vuoto).
  assert.match(sh.split('\ncmd_reset() {')[1]?.split('\n}\n')[0] ?? '', /cmd_membri --if-reachable/);
});

test('vetrina.sh: gli utenti di test negli overlay solo nel codespace; sull\'host fisso --no-test-users (ADR-051 decisione 1, Q-676)', () => {
  const sh = read(VETRINA_SH);
  assert.match(sh, /^test_users_allowed\(\) \{ \[ "\$\{LH_VETRINA_MODE:-host\}" = codespace \] && \[ "\$\{CODESPACES:-\}" = true \]; \}$/m);
  assert.match(sh, /test_users_allowed \|\| args\+=\(--no-test-users\)/);
  // Lo stesso criterio di LH_TEST_USERS_ALLOWED in compose().
  assert.match(sh, /test_env=\(LH_TEST_USERS_ALLOWED=true LH_ENVIRONMENT=test\)/);
});

test('vetrina.sh membri: solo nel codespace con CODESPACES=true; lancia vetrina-membri.mjs sull\'indirizzo pubblico del web, senza credenziali negli argomenti (Q-673)', () => {
  const cs = fakeCodespace();
  // Senza CODESPACES=true (anche con la modalità codespace nel file): saltato, node non parte.
  const outside = cs.run(['membri']);
  assert.equal(outside.status, 0, outside.stderr);
  assert.match(outside.stdout, /solo nel codespace/);
  assert.ok(!fs.existsSync(cs.log), 'nessun comando lanciato');
  const inside = cs.run(['membri'], { CODESPACES: 'true' });
  assert.equal(inside.status, 0, inside.stderr);
  const calls = read(cs.log);
  const script = path.join(ROOT, 'scripts/vetrina-membri.mjs');
  assert.equal(calls.trim(), `node ${script} --web https://prova-vetrina-8000.app.github.dev`);
  assert.doesNotMatch(calls, /password|secret|Aurora/i);
  assert.match(read(AVVIO), /vetrina\.sh membri/, 'avvio.sh registra i membri dopo aver reso pubbliche le porte');
});
