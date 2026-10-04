#!/usr/bin/env node
// Verifiche degli overlay di vetrina del compose di riferimento (F2-DIST-03, F2-DIST-09, ADR-049, ADR-050, ADR-051,
// M8.14 V3 e V12; Q-616, Q-621, Q-624). Uso: node --test scripts/check-vetrina.mjs (job `seed` della CI).
// La vetrina gira solo in un GitHub Codespace (V12): nessun residuo dell'host fisso (ACME, systemd, Oracle) in deploy/vetrina/.
//
// - statiche: overlay, Caddyfile del codespace, pg_hba; nessun segreto in chiaro, TLS verso bus e database, porte;
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
const CODESPACE_OVERLAY = path.join(DIR, 'compose.codespace.yml');
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
  LH_VETRINA_WEB_HOST: 'prova-vetrina-8000.app.github.dev',
  LH_VETRINA_IDP_HOST: 'prova-vetrina-8001.app.github.dev',
  LH_VETRINA_ADMIN_HOST: 'prova-vetrina-8180.app.github.dev',
};
const CODESPACE_ENV = EXAMPLE_ENV;

// ---------------------------------------------------------------------------------------------------------------
// Statiche

test('overlay: progetto separato dalla demo e dal riferimento, proxy dichiarato con digest e porte HTTP solo su loopback (ADR-050)', () => {
  assert.equal(overlay.name, 'loyaltyhub-vetrina');
  const proxy = overlay.services.proxy;
  assert.match(proxy.image, /^caddy:\d+\.\d+\.\d+-alpine@sha256:[0-9a-f]{64}$/, 'proxy con tag e digest');
  assert.deepEqual(proxy.ports, ['127.0.0.1:8000:8000', '127.0.0.1:8001:8001']);
  assert.equal(proxy.read_only, true);
  assert.deepEqual(proxy.cap_drop, ['ALL']);
  assert.deepEqual(proxy.cap_add, ['NET_BIND_SERVICE']);
  assert.ok(proxy.security_opt.includes('no-new-privileges:true'));
  assert.ok(proxy.mem_limit, 'limite di memoria del proxy');
  assert.equal(proxy.user, undefined, 'nessun utente root esplicito (LH-DC-0001)');
  assert.equal(proxy.networks, undefined, 'nessun alias: i nomi pubblici puntano all\'inoltro di GitHub (Q-420)');
  assert.ok(proxy.volumes.includes('../vetrina/caddy/Caddyfile.codespace:/etc/caddy/Caddyfile:ro'));
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

test('azzeramento: solo i volumi di Postgres e Kafka (Q-624), a mano con vetrina.sh reset', () => {
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
  return spawnSync('docker', ['compose', '-f', REFERENCE, '-f', OVERLAY, '-f', CODESPACE_OVERLAY, 'config', '--format', 'json'], {
    env: { PATH: process.env.PATH, HOME: process.env.HOME ?? os.tmpdir(), ...env }, encoding: 'utf8',
  });
}

test('compose unito: limiti di memoria per ogni container, porte solo su loopback, nessun servizio in più', { skip: !haveCompose && 'docker compose assente' }, () => {
  const r = composeConfig(EXAMPLE_ENV);
  assert.equal(r.status, 0, r.stderr);
  const cfg = JSON.parse(r.stdout);
  assert.equal(cfg.name, 'loyaltyhub-vetrina');
  const names = Object.keys(cfg.services).sort();
  assert.deepEqual(names, ['hub', 'idp', 'kafka', 'migrate', 'postgres', 'proxy', 'web'], 'nessun servizio di osservabilità né altri');
  for (const [name, svc] of Object.entries(cfg.services)) {
    assert.ok(svc.mem_limit, `${name}: limite di memoria`);
    for (const p of svc.ports ?? []) assert.equal(p.host_ip, '127.0.0.1', `${name}: porta ${p.published} solo su loopback`);
  }
  assert.equal(cfg.services.hub.environment.LH_OIDC_ISSUER, 'https://prova-vetrina-8001.app.github.dev/realms/loyaltyhub');
  // Realm dei membri (ADR-051): stesso emittente pubblico per hub e web, JWKS dal proxy, client portal con il segreto da file.
  assert.equal(cfg.services.hub.environment.LH_OIDC_MEMBER_ISSUER, 'https://prova-vetrina-8001.app.github.dev/realms/loyaltyhub-members');
  assert.equal(cfg.services.hub.environment.LH_OIDC_MEMBER_JWKS_URI, 'https://prova-vetrina-8001.app.github.dev/realms/loyaltyhub-members/protocol/openid-connect/certs');
  assert.equal(cfg.services.web.environment.LH_OIDC_MEMBER_ISSUER, cfg.services.hub.environment.LH_OIDC_MEMBER_ISSUER);
  assert.equal(cfg.services.web.environment.LH_WEB_MEMBER_CLIENT_ID, 'portal');
  assert.equal(cfg.services.web.environment.LH_WEB_MEMBER_CLIENT_SECRET_FILE, '/run/secrets/lh_portal_client_secret');
  assert.equal(cfg.services.idp.environment.LH_PORTAL_CLIENT_SECRET_FILE, '/run/secrets/lh_portal_client_secret');
  assert.ok(cfg.services.idp.volumes.some((v) => v.target === '/opt/keycloak/data/import/loyaltyhub-members-realm.json'), 'realm dei membri importato');
  assert.equal(cfg.services.web.environment.LH_WEB_URL, 'https://prova-vetrina-8000.app.github.dev');
  // Utenti di test (Q-676): senza che vetrina.sh le passi (CODESPACES=true) le variabili sono vuote; idp non le ha.
  for (const svc of ['hub', 'web']) {
    assert.equal(cfg.services[svc].environment.LH_TEST_USERS_ALLOWED ?? '', '', svc);
    assert.equal(cfg.services[svc].environment.LH_ENVIRONMENT ?? '', '', svc);
  }
  assert.equal(cfg.services.idp.environment.LH_TEST_USERS_ALLOWED, undefined);
  assert.equal(cfg.services.idp.environment.LH_ENVIRONMENT, undefined);
  assert.equal(cfg.services.idp.environment.KC_HOSTNAME, 'https://prova-vetrina-8001.app.github.dev');
  assert.equal(cfg.secrets.lh_db_password.file, '/etc/loyaltyhub-vetrina/secrets/db-password');
  // Anche con le variabili di segreto del riferimento esportate, nel compose unito restano vuote.
  const leaked = composeConfig({ ...EXAMPLE_ENV, LH_DB_PASSWORD: 'non-deve-entrare', LH_SUBJECT_KEY: 'non-deve-entrare', LH_PORTAL_CLIENT_SECRET: 'non-deve-entrare' });
  assert.equal(leaked.status, 0, leaked.stderr);
  // Solo i servizi contano: l'estensione x-lh-db-env del riferimento resta un frammento non usato dall'overlay.
  assert.doesNotMatch(JSON.stringify(JSON.parse(leaked.stdout).services), /non-deve-entrare/);
});

test('compose unito: senza nomi o cartella dei segreti non si avvia', { skip: !haveCompose && 'docker compose assente' }, () => {
  for (const missing of ['LH_VETRINA_WEB_HOST', 'LH_VETRINA_IDP_HOST', 'LH_VETRINA_DIR']) {
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

test('vetrina.sh: configurazione rifiutata (uscita 2) con chiavi ignote, bind non loopback, nomi, indirizzo o modalità non validi', () => {
  const d = tmp();
  const cases = [
    ['LH_DB_PASSWORD=x\n', /chiave non ammessa: LH_DB_PASSWORD/],
    ['LH_BIND_ADDRESS=0.0.0.0\n', /LH_BIND_ADDRESS deve essere 127\.0\.0\.1/],
    ['LH_VETRINA_PUBLIC_ADDRESS=0.0.0.0\n', /LH_VETRINA_PUBLIC_ADDRESS, se presente, deve essere 127\.0\.0\.1/],
    ['LH_VETRINA_MODE=host\n', /LH_VETRINA_MODE ammette solo codespace/],
    ['LH_VETRINA_MODE=cloud\n', /LH_VETRINA_MODE ammette solo codespace/],
    ['LH_VETRINA_WEB_HOST=https://web.example.org\n', /LH_VETRINA_WEB_HOST non è un nome DNS valido/],
    ['LH_VETRINA_IDP_HOST=prova-vetrina-8000.app.github.dev\n', /devono essere diversi/],
    ['LH_HUB_DEMO_URL=http://demo.example.org\n', /LH_HUB_DEMO_URL deve essere un'origine https/],
  ];
  for (const [extra, msg] of cases) {
    const r = vetrina(['provision'], writeConfig(d, extra));
    assert.equal(r.status, 2, `${extra.trim()}: ${r.stderr}`);
    assert.match(r.stderr, msg);
  }
  // File già scritti (regola 14): la modalità `codespace` e l'indirizzo 127.0.0.1 si accettano ancora, senza effetto.
  const legacy = vetrina(['preflight', '--offline'], writeConfig(d, 'LH_VETRINA_MODE=codespace\nLH_VETRINA_PUBLIC_ADDRESS=127.0.0.1\n'));
  assert.notEqual(legacy.status, 2, `configurazione valida rifiutata: ${legacy.stderr}`);
  if (has('openssl')) {
    const prov = vetrina(['provision'], writeConfig(d, 'LH_VETRINA_MODE=codespace\nLH_VETRINA_PUBLIC_ADDRESS=127.0.0.1\n'));
    assert.equal(prov.status, 0, `provision con la configurazione vecchia: ${prov.stderr}`);
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

const DEVCONTAINER = path.join(ROOT, '.devcontainer/vetrina/devcontainer.json');
const AVVIO = path.join(ROOT, '.devcontainer/vetrina/avvio.sh');

test('vetrina.sh: utenti di test ammessi solo nel codespace con CODESPACES=true, altrove variabili forzate a vuoto (Q-676)', () => {
  const sh = read(VETRINA_SH);
  const fn = sh.split('\ncompose() {')[1]?.split('\n}\n')[0] ?? '';
  assert.match(fn, /local test_env=\(LH_TEST_USERS_ALLOWED= LH_ENVIRONMENT=\)/, 'default: vuote, anche se esportate');
  assert.match(fn, /if \[ "\$\{CODESPACES:-\}" = true \]; then\n\s+test_env=\(LH_TEST_USERS_ALLOWED=true LH_ENVIRONMENT=test\)/);
  assert.match(fn, /env "\$\{unset_args\[@\]\}" "\$\{test_env\[@\]\}" docker compose/);
  // Né il compose di riferimento né l'overlay di base né il chart le dichiarano.
  for (const f of [REFERENCE, OVERLAY, path.join(ROOT, 'deploy/helm/loyaltyhub/templates/hub.yaml'), path.join(ROOT, 'deploy/helm/loyaltyhub/templates/web.yaml')]) {
    assert.doesNotMatch(read(f), /LH_TEST_USERS_ALLOWED|LH_ENVIRONMENT/, path.relative(ROOT, f));
  }
});

const CADDYFILE_CODESPACE = path.join(DIR, 'caddy/Caddyfile.codespace');
/** Percorsi permessi dal blocco :8001 (Q-670): ogni voce e' documentata nel Caddyfile; qui l'elenco atteso, esatto. */
const IDP_ALLOWED = [
  '/realms/loyaltyhub', '/realms/loyaltyhub/*', '/realms/loyaltyhub-members', '/realms/loyaltyhub-members/*', '/resources/*',
  '/admin/loyaltyhub/console', '/admin/loyaltyhub/console/*', '/admin/loyaltyhub-members/console', '/admin/loyaltyhub-members/console/*',
  '/admin/realms/loyaltyhub', '/admin/realms/loyaltyhub/*', '/admin/realms/loyaltyhub-members', '/admin/realms/loyaltyhub-members/*',
  '/admin/serverinfo',
];
const IDP_MASTER_BLOCKED = ['/admin/master*', '/admin/realms/master*', '/realms/master*', '/resources/master', '/resources/master/*'];
const noComments = (t) => t.replace(/^\s*#.*$/gm, '');

test('Caddyfile del codespace: niente ACME, instradamento per porta; Keycloak con master 404 PRIMA di ogni permesso, trucchi di percorso 404, elenco esatto dei permessi, tutto il resto 404 (Q-661, Q-670, ADR-051)', () => {
  const c = read(CADDYFILE_CODESPACE);
  assert.match(c, /^\s*admin off$/m);
  assert.match(c, /^\s*auto_https off$/m);
  assert.match(c, /^:8000 \{[\s\S]*?reverse_proxy web:3000/m);
  const idp = c.split(/^:8001 \{/m)[1];
  assert.ok(idp, 'blocco della porta 8001');
  const code = noComments(idp);
  // Ordine: master, trucchi, permessi, resto. Il 404 per master viene prima di qualunque percorso che lo lascerebbe passare.
  const at = ['@master path', 'handle @master', '@trucchi path_regexp', 'handle @trucchi', '@permessi {', 'handle @permessi', 'reverse_proxy idp:8080', '\thandle {']
    .map((m) => code.indexOf(m));
  assert.ok(at.every((i) => i >= 0) && at.every((v, i) => i === 0 || v > at[i - 1]), `ordine delle regole di :8001: ${at}`);
  const masterLine = code.match(/@master path (.+)/)[1].trim().split(/\s+/);
  assert.deepEqual(masterLine, IDP_MASTER_BLOCKED, 'tutto cio\' che riguarda master -> 404');
  assert.match(code, /handle @master \{\s*respond 404\s*\}/);
  assert.match(code, /@trucchi path_regexp trucchi \(\\\\\|;\|%\)/, 'barra rovesciata, ;, % residuo (i segmenti .. li pulisce Caddy: caso coperto dalla prova con Caddy)');
  assert.match(code, /handle @trucchi \{\s*respond 404\s*\}/);
  const permessi = code.match(/@permessi \{([\s\S]*?)\n\t\}/)[1].split('\n').map((l) => l.trim()).filter(Boolean);
  assert.ok(permessi.every((l) => l.startsWith('path ')), 'il blocco dei permessi contiene solo `path`');
  assert.deepEqual(permessi.flatMap((l) => l.split(/\s+/).slice(1)), IDP_ALLOWED, 'permessi esatti: i soli due realm, le risorse e le API della console');
  assert.match(code, /handle @permessi \{\s*reverse_proxy idp:8080/);
  assert.match(code, /handle \{\s*respond 404\s*\}\s*\}\s*$/, 'il resto -> 404');
  assert.equal((c.match(/reverse_proxy/g) ?? []).length, 2, 'solo web e idp dietro il proxy');
  assert.equal((code.match(/reverse_proxy/g) ?? []).length, 1, 'un solo inoltro verso idp, solo nel permesso');
  // Nessun permesso per master, per la radice di /admin o per l'elenco dei realm, e nessun prefisso senza barra che
  // lasci passare altri realm (`/realms/loyaltyhub*`).
  const allowed = permessi.flatMap((l) => l.split(/\s+/).slice(1));
  assert.ok(allowed.every((x) => !/master/.test(x) && x !== '/admin' && x !== '/admin/*' && x !== '/admin/realms' && x !== '/admin/realms/*' && !/[^/]\*$/.test(x)), allowed.join(' '));
  assert.deepEqual(code.split('\n').filter((l) => /master/.test(l)).map((l) => l.trim().split(' ')[0]), ['@master', 'handle'], 'master compare solo nella regola 404');
  // Le intestazioni di sicurezza valgono anche per i 404 (import nel blocco).
  assert.match(code, /^\s*import intestazioni$/m);
});

// Prova vera con Caddy (se c'e' un binario `caddy` nel PATH, come nell'immagine del proxy): il Caddyfile del codespace con
// gli upstream puntati a un server finto che risponde "UP" e registra il percorso ricevuto. Casi di Q-670: maiuscole, codifiche,
// doppie barre, `..`, `;`, doppia codifica.
test('Caddyfile del codespace con Caddy: master e trucchi di percorso 404, solo i percorsi permessi arrivano a Keycloak (Q-670)', { skip: !has('caddy') && 'caddy assente (provato a mano con Caddy 2.11.4, vedi runbook)' }, async () => {
  const net = await import('node:net');
  const freePort = () => new Promise((resolve) => { const s = net.createServer(); s.listen(0, '127.0.0.1', () => { const { port } = s.address(); s.close(() => resolve(port)); }); });
  const [upPort, webPort, idpPort] = [await freePort(), await freePort(), await freePort()];
  const received = [];
  const upstream = http.createServer((req, res) => { received.push(req.url); res.end('UP'); });
  await new Promise((r) => upstream.listen(upPort, '127.0.0.1', r));
  const d = tmp();
  const cfg = path.join(d, 'Caddyfile');
  fs.writeFileSync(cfg, read(CADDYFILE_CODESPACE).replace('idp:8080', `127.0.0.1:${upPort}`).replace('web:3000', `127.0.0.1:${upPort}`)
    .replace(/^:8001 \{/m, `:${idpPort} {`).replace(/^:8000 \{/m, `:${webPort} {`).replace(/^\s*admin off$/m, '\tadmin off'));
  const caddy = spawn('caddy', ['run', '--config', cfg, '--adapter', 'caddyfile'], { stdio: 'ignore', env: { PATH: process.env.PATH, HOME: d, XDG_DATA_HOME: d, XDG_CONFIG_HOME: d } });
  const get = (p) => new Promise((resolve, reject) => {
    const req = http.request({ host: '127.0.0.1', port: idpPort, path: p, method: 'GET' }, (res) => { res.resume(); res.on('end', () => resolve(res.statusCode)); });
    req.on('error', reject);
    req.end();
  });
  try {
    for (let i = 0; ; i++) {
      try { await get('/'); break; } catch (e) { if (i > 50) throw e; await new Promise((r) => setTimeout(r, 100)); }
    }
    const ok = [
      '/realms/loyaltyhub/protocol/openid-connect/auth', '/realms/loyaltyhub-members/.well-known/openid-configuration', '/resources/abc/admin/keycloak.v2/x.js',
      '/admin/loyaltyhub/console', '/admin/loyaltyhub/console/', '/admin/loyaltyhub/console/whoami?currentRealm=loyaltyhub', '/admin/loyaltyhub-members/console/',
      '/admin/realms/loyaltyhub', '/admin/realms/loyaltyhub/ui-ext/realms/names', '/admin/realms/loyaltyhub-members/users', '/admin/realms/loyaltyhub/roles/my%20role',
      '/admin/serverinfo', '/resources/loyaltyhub/admin/it',
    ];
    const blocked = [
      '/realms/master', '/realms/master/', '/realms/MASTER/protocol/openid-connect/token', '/Realms/Master/x', '/admin/master/console/', '/admin/Master/console/',
      '/admin/realms/master', '/admin/realms/MASTER/users', '/admin/realms/%4DASTER/users', '/realms/%6Daster/x', '/admin/realms/master%2Fusers', '/resources/master/admin/en',
      '//realms/master', '/realms//master', '//admin//realms//master', '/admin/realms//master/users', '/realms/./master',
      '/realms/loyaltyhub/../master/x', '/realms/loyaltyhub/%2e%2e/master/x', '/realms/loyaltyhub/%2E%2E%2Fmaster', '/resources/../realms/master/x', '/resources/x/..;/..;/realms/master',
      '/realms/loyaltyhub;a=b/x', '/realms/loyaltyhub%2f..%2fmaster', '/realms/loyaltyhub%5c..%5cmaster', '/realms/loyaltyhub/%252e%252e/master',
      '/admin', '/admin/', '/admin/realms', '/js/keycloak.js', '/', '/health', '/metrics', '/realms/loyaltyhubx', '/realms/masterx', '/admin/serverinfo/x',
    ];
    received.length = 0;
    for (const p of ok) assert.equal(await get(p), 200, `permesso: ${p}`);
    assert.deepEqual(received, ok, 'Keycloak riceve il percorso originale, senza riscritture');
    received.length = 0;
    for (const p of blocked) assert.equal(await get(p), 404, `404 atteso: ${p}`);
    assert.deepEqual(received, [], 'nessuna richiesta bloccata arriva a Keycloak');
  } finally {
    caddy.kill();
    upstream.close();
  }
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
  // Q-670: nel codespace KC_HOSTNAME_ADMIN non e' impostato (l'overlay di base lo fissa a 127.0.0.1:8180 e qui lo
  // toglie): la console dei due realm usa l'indirizzo pubblico, quella di master il frontendUrl del suo realm (8180).
  assert.ok((cfg.services.idp.environment.KC_HOSTNAME_ADMIN ?? null) === null, `KC_HOSTNAME_ADMIN impostato: ${cfg.services.idp.environment.KC_HOSTNAME_ADMIN}`);
  assert.deepEqual(cfg.services.idp.ports.map((p) => `${p.host_ip}:${p.published}`), ['127.0.0.1:8180']);
  // TLS verso bus e database invariato.
  assert.equal(cfg.services.hub.environment.KAFKA_SECURITY, 'SSL_PEM');
  assert.match(cfg.services.hub.environment.DB_URL, /sslmode=verify-full/);
});

test('vetrina.sh: configurazione rifiutata se il proxy esce da loopback o i nomi non sono del codespace', () => {
  const d = tmp();
  const write = (env, extra = '') => {
    const cfg = path.join(d, 'vetrina.env');
    const lines = Object.entries({ LH_VETRINA_MODE: 'codespace', ...env, LH_VETRINA_DIR: path.join(d, 'host') }).map(([k, v]) => `${k}=${v}`);
    fs.writeFileSync(cfg, `${lines.join('\n')}\n${extra}`, { mode: 0o600 });
    return cfg;
  };
  const cases = [
    [{ ...CODESPACE_ENV, LH_VETRINA_PUBLIC_ADDRESS: '10.0.0.10' }, /LH_VETRINA_PUBLIC_ADDRESS, se presente, deve essere 127\.0\.0\.1/],
    [{ ...CODESPACE_ENV, LH_VETRINA_WEB_HOST: 'web-vetrina.example.org' }, /LH_VETRINA_WEB_HOST nel codespace/],
    [{ ...CODESPACE_ENV, LH_VETRINA_IDP_HOST: 'prova-vetrina-8443.app.github.dev' }, /LH_VETRINA_IDP_HOST nel codespace/],
    [{ ...CODESPACE_ENV, LH_VETRINA_ADMIN_HOST: '' }, /LH_VETRINA_ADMIN_HOST nel codespace/],
    [{ ...CODESPACE_ENV, LH_VETRINA_ADMIN_HOST: 'prova-vetrina-8001.app.github.dev' }, /LH_VETRINA_ADMIN_HOST nel codespace/],
    [{ ...CODESPACE_ENV, LH_VETRINA_MODE: 'host' }, /LH_VETRINA_MODE ammette solo codespace/],
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
  assert.doesNotMatch(conf, /^LH_VETRINA_MODE=|^LH_VETRINA_PUBLIC_ADDRESS=/m, 'chiavi dell\'host fisso non più scritte (V12)');
  assert.match(conf, /^LH_IMAGE=ghcr\.io\/example\/loyaltyhub:v1\.2\.3$/m);
  assert.match(conf, /^LH_VETRINA_WEB_HOST=prova-vetrina-x5g7-8000\.app\.github\.dev$/m);
  assert.match(conf, /^LH_VETRINA_IDP_HOST=prova-vetrina-x5g7-8001\.app\.github\.dev$/m);
  assert.match(conf, /^LH_VETRINA_ADMIN_HOST=prova-vetrina-x5g7-8180\.app\.github\.dev$/m);
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
  const lines = Object.entries({ ...CODESPACE_ENV, LH_VETRINA_DIR: path.join(d, 'host') }).map(([k, v]) => `${k}=${v}`);
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

test('vetrina.sh: gli utenti di test negli overlay solo con CODESPACES=true, altrimenti --no-test-users (ADR-051 decisione 1, Q-676)', () => {
  const sh = read(VETRINA_SH);
  assert.match(sh, /^test_users_allowed\(\) \{ \[ "\$\{CODESPACES:-\}" = true \]; \}$/m);
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

// ---------------------------------------------------------------------------------------------------------------
// Console di Keycloak (M8.14 V9; ADR-051 decisione 8; Q-670)

test('compose.codespace.yml: KC_HOSTNAME_ADMIN non impostato (valore nullo che toglie quello dell\'overlay di base); la porta 8180 resta privata (Q-670)', () => {
  const text = read(CODESPACE_OVERLAY);
  const cs = YAML.parse(text);
  assert.ok('KC_HOSTNAME_ADMIN' in cs.services.idp.environment && cs.services.idp.environment.KC_HOSTNAME_ADMIN === null, 'valore nullo esplicito (toglie quello dell\'overlay di base)');
  assert.doesNotMatch(noComments(text), /KC_HOSTNAME_ADMIN:\s*["'$h]|LH_VETRINA_ADMIN_HOST/, 'nessun valore per KC_HOSTNAME_ADMIN, nessun riferimento a LH_VETRINA_ADMIN_HOST nell\'overlay');
  assert.equal(cs.services.idp.ports, undefined, 'la porta 8180 la pubblica solo l\'overlay di base su 127.0.0.1, inoltro privato');
  assert.match(read(AVVIO), /gh codespace ports visibility 8000:public 8001:public/);
});

test('vetrina.sh: KC_HOSTNAME_ADMIN si toglie anche dall\'ambiente; master riceve il frontendUrl della porta privata (Q-670)', () => {
  const sh = read(VETRINA_SH);
  const fn = sh.split('\ncompose() {')[1]?.split('\n}\n')[0] ?? '';
  assert.match(fn, /local files=\(-f "\$REFERENCE" -f "\$OVERLAY" -f "\$CODESPACE_OVERLAY"\)/);
  assert.match(fn, /^\s+unset_args\+=\(-u KC_HOSTNAME_ADMIN\)$/m);
  const ap = sh.split('\napply_realm_overlay() {')[1]?.split('\n}\n')[0] ?? '';
  assert.match(ap, /MASTER_FRONTEND_URL="https:\/\/\$LH_VETRINA_ADMIN_HOST" KC_BOOTSTRAP_ADMIN_PASSWORD="\$pw"/);
  assert.doesNotMatch(read(path.join(ROOT, 'deploy/idp/vetrina/master.json')), /frontendUrl":/, 'master.json senza indirizzi (dinamici)');
  // Il messaggio finale distingue le console pubbliche dei due realm da quella privata di master.
  assert.match(fn.length ? sh : '', /console dei realm \(pubbliche[^\n]*\/admin\/loyaltyhub\/console\/[^\n]*\/admin\/loyaltyhub-members\/console\//);
  assert.match(sh, /console del realm master \(Q-670\): https:\/\/\$LH_VETRINA_ADMIN_HOST\/admin\/master\/console\/ \(porta \$CODESPACE_ADMIN_PORT privata/);
});

// ---------------------------------------------------------------------------------------------------------------
// Credenziali di test pubbliche del Demo Hub (web/lib/hub/testUsers.ts) uguali a quelle degli overlay dei realm (Q-670 non
// c'entra: ADR-051 decisione 1, M8.14 V9). Il file e' TypeScript: si leggono i letterali con espressioni regolari.

const TEST_USERS_TS = path.join(ROOT, 'web/lib/hub/testUsers.ts');
const REALM_OVERLAYS = [path.join(ROOT, 'deploy/idp/vetrina/realm-vetrina-overlay.json'), path.join(ROOT, 'deploy/idp/vetrina/realm-members-vetrina-overlay.json')];

function base32Decode(text) {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  let bits = 0, value = 0;
  const out = [];
  for (const ch of text.replace(/=+$/, '').toUpperCase()) {
    const i = alphabet.indexOf(ch);
    assert.ok(i >= 0, `carattere non base32: ${ch}`);
    value = (value << 5) | i;
    bits += 5;
    if (bits >= 8) { bits -= 8; out.push((value >>> bits) & 0xff); }
  }
  return Buffer.from(out);
}

test('web/lib/hub/testUsers.ts: nomi utente, password e seme TOTP uguali agli overlay di Keycloak della vetrina (ADR-051 decisione 1)', { skip: !fs.existsSync(TEST_USERS_TS) && 'web/lib/hub/testUsers.ts assente: lo crea la parte web della fetta V9, il controllo si attiva da solo' }, () => {
  const ts = read(TEST_USERS_TS);
  const overlays = REALM_OVERLAYS.map((f) => JSON.parse(read(f)));
  const users = new Map(overlays.flatMap((o) => o.users).map((u) => [u.username, u]));
  const passwordOf = (u) => u.credentials.find((c) => c.type === 'password').value;
  const otpSecret = overlays[0].users.map((u) => u.credentials.find((c) => c.type === 'otp')).find(Boolean);
  const overlaySeed = JSON.parse(otpSecret.secretData).value;

  // Letterali di stringa tra apici singoli, doppi o backtick, senza interpolazioni.
  const literals = [...ts.matchAll(/(['"`])((?:(?!\1)[^\\\n]|\\.)*)\1/g)].map((m) => m[2]);
  const found = [...users.keys()].filter((u) => literals.includes(u));
  assert.deepEqual(found.sort(), [...users.keys()].sort(), 'ogni utente di test degli overlay compare in testUsers.ts (e nessuno manca)');
  // Nomi utente che sembrano utenti ma non sono negli overlay: `<nome>.<cognome o ruolo>` come stringhe isolate.
  const extra = literals.filter((l) => /^[a-z]+\.[a-z]+$/.test(l) && !users.has(l));
  assert.deepEqual(extra, [], `nomi utente in testUsers.ts senza corrispondenza negli overlay: ${extra}`);

  // Password: ogni password degli overlay e' presente, e ogni password-letterale del file e' di un overlay.
  const overlayPasswords = new Set([...users.values()].map(passwordOf));
  for (const pw of overlayPasswords) assert.ok(literals.includes(pw), `password mancante in testUsers.ts: ${pw.replace(/./g, '*')}`);
  const pwLike = literals.filter((l) => /^Aurora-[A-Za-z]+-\d+!$/.test(l));
  assert.ok(pwLike.length > 0 && pwLike.every((l) => overlayPasswords.has(l)), 'password in testUsers.ts non presenti negli overlay');
  // Associazione utente -> password: nello stesso oggetto/riga o nel gruppo, la password dell'utente e' quella dell'overlay.
  // Il file e' libero nella forma; si controlla che, per ogni utente, la prima password-letterale che lo segue prima del
  // successivo nome utente sia la sua, oppure che il file definisca una costante di gruppo con la stessa password.
  const order = literals.map((l, i) => [l, i]).filter(([l]) => users.has(l));
  for (const [i, [name, pos]] of order.entries()) {
    const end = i + 1 < order.length ? order[i + 1][1] : literals.length;
    const window = literals.slice(pos + 1, end).filter((l) => /^Aurora-[A-Za-z]+-\d+!$/.test(l));
    if (window.length) assert.equal(window[0], passwordOf(users.get(name)), `password di ${name} diversa dall'overlay`);
  }

  // Seme TOTP: base32 che, decodificato, e' il segreto dell'overlay.
  const seeds = literals.filter((l) => /^[A-Z2-7]{16,}$/.test(l));
  assert.ok(seeds.length > 0, 'seme TOTP in base32 assente in testUsers.ts');
  for (const seed of seeds) assert.equal(base32Decode(seed).toString('utf8'), overlaySeed, 'il seme base32 non decodifica al segreto OTP degli overlay');
});

// Le tre password pubbliche per NOME della costante (non solo per valore): ciascuna uguale alla password di ogni utente
// del gruppo corrispondente negli overlay, cosi' due costanti scambiate o un utente con la password sbagliata falliscono.
test('web/lib/hub/testUsers.ts: OPERATORS_PASSWORD, MEMBERS_PASSWORD e KEYCLOAK_ADMIN_PASSWORD uguali alle password dei rispettivi utenti degli overlay', { skip: !fs.existsSync(TEST_USERS_TS) && 'web/lib/hub/testUsers.ts assente: lo crea la parte web della fetta V9, il controllo si attiva da solo' }, () => {
  const ts = read(TEST_USERS_TS);
  const constant = (name) => {
    const m = ts.match(new RegExp(`export const ${name}\\s*=\\s*(["'\`])((?:(?!\\1)[^\\\\\\n])*)\\1`));
    assert.ok(m, `costante ${name} non trovata (stringa letterale) in testUsers.ts`);
    return m[2];
  };
  const [operatorsRealm, membersRealm] = REALM_OVERLAYS.map((f) => JSON.parse(read(f)));
  const passwordOf = (u) => u.credentials.find((c) => c.type === 'password').value;
  const operators = operatorsRealm.users.filter((u) => u.username !== 'vetrina.admin');
  const members = membersRealm.users.filter((u) => u.username !== 'membri.admin');
  const admins = [operatorsRealm.users.find((u) => u.username === 'vetrina.admin'), membersRealm.users.find((u) => u.username === 'membri.admin')];
  assert.ok(operators.length === 5 && members.length === 4 && admins.every(Boolean), 'overlay: 5 operatori, 4 membri e i due amministratori');
  for (const [name, group] of [
    ['OPERATORS_PASSWORD', operators],
    ['MEMBERS_PASSWORD', members],
    ['KEYCLOAK_ADMIN_PASSWORD', admins],
  ]) {
    const value = constant(name);
    for (const u of group) assert.equal(passwordOf(u), value, `${name} diversa dalla password di ${u.username} negli overlay`);
  }
  assert.equal(new Set([constant('OPERATORS_PASSWORD'), constant('MEMBERS_PASSWORD'), constant('KEYCLOAK_ADMIN_PASSWORD')]).size, 3, 'le tre password sono distinte');
});

// ---------------------------------------------------------------------------------------------------------------
// Programma di esempio dal backoffice (V10, ADR-051, Q-722, Q-723): lo snapshot del seed che il web impacchetta (l'immagine
// del ruolo web non contiene seed/) non deriva dal seed, e il nucleo condiviso resta puro.

test('web/lib/vetrina/programma-seed.generated.json uguale a quello che genera `vetrina-programma.mjs --emit-seed` da seed/ (nessuna deriva)', async () => {
  const { loadProgramSeed, serializeSeedSnapshot, SEED_SNAPSHOT_FILE } = await import('./vetrina-programma.mjs');
  const expected = serializeSeedSnapshot(loadProgramSeed(path.join(ROOT, 'seed')));
  assert.ok(fs.existsSync(SEED_SNAPSHOT_FILE), 'snapshot assente: esegui node scripts/vetrina-programma.mjs --emit-seed');
  assert.equal(read(SEED_SNAPSHOT_FILE), expected, 'snapshot in deriva dal seed: esegui node scripts/vetrina-programma.mjs --emit-seed e includi il file nel commit');
});

test('web/lib/vetrina/programma-core.mjs: nucleo puro, senza node:fs, alias @/ né process; usato sia dallo script sia dal BFF', () => {
  const core = read(path.join(ROOT, 'web/lib/vetrina/programma-core.mjs'));
  const code = core.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
  assert.doesNotMatch(code, /from\s+['"]node:|require\(|from\s+['"]@\/|\bprocess\./, 'il nucleo non importa moduli di Node né alias del web e non legge process');
  assert.match(read(path.join(ROOT, 'scripts/vetrina-programma.mjs')), /from '\.\.\/web\/lib\/vetrina\/programma-core\.mjs'/);
  assert.match(read(path.join(ROOT, 'web/lib/vetrina/programma.ts')), /from "\.\/programma-core\.mjs"/);
  assert.match(read(path.join(ROOT, 'web/lib/hub/serverOnly.test.ts')), /"vetrina\/programma"/, 'il modulo server del programma di esempio è registrato in serverOnly.test.ts');
});

// ---------------------------------------------------------------------------------------------------------------
// Nessun residuo della modalità host fisso (V12; ADR-051 decisione 5, F2-DIST-09)

const WORKFLOW = path.join(ROOT, '.github/workflows/smoke-vetrina.yml');
const walk = (dir) => fs.readdirSync(dir, { withFileTypes: true }).flatMap((e) => (e.isDirectory() ? walk(path.join(dir, e.name)) : [path.join(dir, e.name)]));

test('deploy/vetrina/ e smoke-vetrina.yml: nessun residuo dell\'host fisso (Oracle, Ampere, systemd, proxy ACME, tunnel SSH, modalità host)', () => {
  assert.ok(!fs.existsSync(path.join(DIR, 'systemd')), 'deploy/vetrina/systemd/ rimossa');
  assert.ok(!fs.existsSync(path.join(DIR, 'caddy/Caddyfile')), 'Caddyfile ACME rimosso: resta Caddyfile.codespace');
  const residue = /oracle|ampere|systemd|journalctl|\bACME\b|let'?s ?encrypt|host fisso|tunnel ssh|LH_VETRINA_MODE=host|\.timer\b|OnCalendar|:80:80|:443:443/i;
  const files = [...walk(DIR), WORKFLOW].filter((f) => !/\.(pem|key|crt)$/.test(f));
  for (const f of files) {
    const lines = read(f).split('\n');
    // Il README e lo script dichiarano i valori rifiutati e le chiavi di compatibilità: si leggono solo righe non di rifiuto.
    lines.forEach((line, i) => {
      if (/LH_VETRINA_PUBLIC_ADDRESS|regola 14/.test(line) && path.basename(f) === 'vetrina.sh') return;
      assert.doesNotMatch(line, residue, `${path.relative(ROOT, f)}:${i + 1}: residuo dell'host fisso: ${line.trim()}`);
    });
  }
  // Il compose di riferimento non pubblica porte 80 e 443: il proxy ascolta su 8000 e 8001.
  assert.ok(!JSON.stringify(overlay).includes(':443'), 'nessuna porta 443');
});

// Riquadro Enterprise della demo pubblica (HUB-01, ADR-051): le variabili di Vercel valgono solo per un deploy nuovo.
// Un «Redeploy» dello stesso commit (VERCEL_GIT_PREVIOUS_SHA uguale a VERCEL_GIT_COMMIT_SHA) deve fare la build, non
// essere saltato dall'ignoreCommand di web/vercel.json: è il modo di far leggere al web variabili appena impostate.
test('web/vercel.json: l\'ignoreCommand salta solo un commit nuovo senza modifiche al web; il Redeploy dello stesso commit fa la build', { skip: !has('git') && 'git assente' }, () => {
  const { ignoreCommand } = JSON.parse(read(path.join(ROOT, 'web/vercel.json')));
  const repo = tmp();
  const git = (...args) => {
    const r = spawnSync('git', ['-c', 'user.name=t', '-c', 'user.email=t@example.org', ...args], { cwd: repo, encoding: 'utf8' });
    assert.equal(r.status, 0, `git ${args.join(' ')}: ${r.stderr}`);
    return r.stdout.trim();
  };
  git('init', '-q');
  for (const d of ['web', 'seed', 'docs']) fs.mkdirSync(path.join(repo, d));
  fs.writeFileSync(path.join(repo, 'web/a.ts'), '1');
  fs.writeFileSync(path.join(repo, 'docs/a.md'), '1');
  git('add', '.');
  git('commit', '-qm', 'a');
  const a = git('rev-parse', 'HEAD');
  fs.writeFileSync(path.join(repo, 'docs/a.md'), '2');
  git('commit', '-qam', 'b');
  const b = git('rev-parse', 'HEAD');
  const run = (prev, commit) => spawnSync('sh', ['-c', ignoreCommand], {
    cwd: path.join(repo, 'web'), env: { ...process.env, VERCEL_GIT_PREVIOUS_SHA: prev, VERCEL_GIT_COMMIT_SHA: commit },
  }).status;
  assert.equal(run(a, b), 0, 'commit nuovo che tocca solo docs/: build saltata');
  assert.equal(run(b, b), 1, 'Redeploy dello stesso commit (variabili cambiate): build');
  assert.equal(run('', b), 1, 'nessun deploy precedente: build');
  assert.equal(run('0'.repeat(40), b), 1, 'commit del deploy precedente assente dal clone: build');
  fs.writeFileSync(path.join(repo, 'web/a.ts'), '2');
  git('commit', '-qam', 'c');
  assert.equal(run(b, git('rev-parse', 'HEAD')), 1, 'commit che tocca web/: build');
  fs.rmSync(repo, { recursive: true, force: true });
});
