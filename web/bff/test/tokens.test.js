import { test } from "node:test";
import assert from "node:assert/strict";

/**
 * Il token di servizio del BFF (RF-43). Le variabili si leggono all'import del modulo, quindi vanno impostate prima:
 * per questo il modulo si carica dinamicamente dentro ai test.
 */

async function load(env) {
  for (const [k, v] of Object.entries(env)) process.env[k] = v;
  // query string diversa a ogni caricamento: il modulo va riletto con le variabili appena impostate
  return import(`../src/tokens.js?${Math.random()}`);
}

test("senza IAM configurato non si aggiunge nessuna intestazione", async () => {
  const { configured, authHeaders } = await load({ OIDC_TOKEN_URI: "", OIDC_CLIENT_ID: "" });
  assert.equal(configured(), false);
  assert.deepEqual(await authHeaders(), {});
});

test("con IAM configurato si ottiene il token e si riusa finché è valido", async () => {
  const { serviceToken, reset } = await load({ OIDC_TOKEN_URI: "https://iam.example/token", OIDC_CLIENT_ID: "bff", OIDC_CLIENT_SECRET: "s" });
  reset();
  let chiamate = 0;
  const finto = async () => {
    chiamate++;
    return { ok: true, json: async () => ({ access_token: `t-${chiamate}`, expires_in: 300 }) };
  };

  assert.equal(await serviceToken(0, finto), "t-1");
  assert.equal(await serviceToken(1000, finto), "t-1", "il token in cache si riusa");
  assert.equal(chiamate, 1);

  // oltre la scadenza meno il margine: si rinnova
  assert.equal(await serviceToken(300_000, finto), "t-2");
  assert.equal(chiamate, 2);
});

test("se l'IAM non risponde non si inventa un token", async () => {
  const { serviceToken, reset } = await load({ OIDC_TOKEN_URI: "https://iam.example/token", OIDC_CLIENT_ID: "bff", OIDC_CLIENT_SECRET: "s" });
  reset();
  const rotto = async () => ({ ok: false, status: 503, json: async () => ({}) });

  assert.equal(await serviceToken(0, rotto), null);
});
