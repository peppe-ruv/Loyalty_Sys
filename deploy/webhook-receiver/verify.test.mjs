// Test del ricevitore d'esempio: `node --test deploy/webhook-receiver/` (nessuna dipendenza).
// Il vettore di fixtures/firma-esempio.json è lo stesso verificato in Java da WebhookSignatureTest (engagement-service):
// se le due implementazioni divergessero, fallirebbe almeno uno dei due test.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { sign, verifySignature } from "./verify.mjs";
import { createReceiver } from "./receiver.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const fixture = JSON.parse(readFileSync(join(here, "fixtures", "firma-esempio.json"), "utf8"));

test("il vettore condiviso con engagement-service si verifica", () => {
  assert.equal(sign(fixture.secret, fixture.body), fixture.signature);
  assert.equal(verifySignature(fixture.secret, Buffer.from(fixture.body, "utf8"), fixture.signature), true);
});

test("corpo alterato, segreto sbagliato o header assente → firma non valida", () => {
  assert.equal(verifySignature(fixture.secret, fixture.body.replace("162", "163"), fixture.signature), false);
  assert.equal(verifySignature("whsec_altro", fixture.body, fixture.signature), false);
  assert.equal(verifySignature(fixture.secret, fixture.body, undefined), false);
  assert.equal(verifySignature(fixture.secret, fixture.body, "sha256=abc"), false);
});

test("il ricevitore risponde 204 alla firma valida e 401 a quella falsa", async () => {
  const seen = [];
  const server = createReceiver({ secret: fixture.secret, onEvent: (e) => seen.push(e) });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const { port } = server.address();
  try {
    const post = (signature) =>
      fetch(`http://127.0.0.1:${port}/hook`, {
        method: "POST",
        headers: { "content-type": "application/cloudevents+json", "x-lh-signature": signature, "x-lh-event-id": "evt-1" },
        body: fixture.body,
      });
    assert.equal((await post(fixture.signature)).status, 204);
    assert.equal((await post("sha256=" + "0".repeat(64))).status, 401);
    assert.equal(seen[0].valid, true);
    assert.equal(seen[0].event.type, "io.loyaltyhub.fact.wallet.points.earned");
    assert.equal(seen[1].valid, false);
  } finally {
    server.close();
  }
});
