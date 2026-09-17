import { test } from "node:test";
import assert from "node:assert/strict";

import { KEY_PATTERN, idempotencyKey, reference } from "../src/keys.js";

test("la chiave rispetta la convenzione a tre segmenti di RI-01", () => {
  const key = idempotencyKey("web", ["demo-member", "sku-12"], "PRODUCT_VIEWED");
  assert.match(key, KEY_PATTERN);
  assert.equal(key, "web:demo-member.sku-12:PRODUCT_VIEWED");
});

test("i caratteri non ammessi nel riferimento diventano trattini", () => {
  assert.equal(reference(["utente/1", "ordine 42"]), "utente-1.ordine-42");
});

test("le parti vuote non lasciano separatori a vuoto", () => {
  assert.equal(reference(["a", "", null, undefined, "b"]), "a.b");
});

test("il tipo di evento viene normalizzato", () => {
  assert.match(idempotencyKey("operator", ["op1", "m-1", "a3f9"], "award-points"), KEY_PATTERN);
});

test("una chiave che non rispetterebbe la convenzione è un errore qui, non a valle", () => {
  assert.throws(() => idempotencyKey("W", ["m-1"], "X"), /non valida/);
});

test("il riferimento non supera il segmento consentito", () => {
  const key = idempotencyKey("web", ["x".repeat(500)], "PRODUCT_VIEWED");
  assert.match(key, KEY_PATTERN);
});
