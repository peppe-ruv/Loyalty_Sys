// Test di check-api.mjs (M8.8): node --test scripts/check-api.test.mjs
import test from "node:test";
import assert from "node:assert/strict";
import { breakingChanges } from "./check-api.mjs";

const clone = (o) => structuredClone(o);

/** Specifica minima: un elenco (risposta) e una creazione (richiesta) che condividono lo schema Reward. */
const BASE = {
  openapi: "3.1.0",
  paths: {
    "/v1/rewards": {
      get: {
        parameters: [{ in: "query", name: "page", required: false, schema: { type: "integer", format: "int32" } }],
        responses: {
          200: { content: { "application/json": { schema: { type: "array", items: { $ref: "#/components/schemas/Reward" } } } } },
          404: { content: { "application/problem+json": { schema: { type: "object" } } } },
        },
      },
      post: {
        requestBody: {
          required: true,
          content: { "application/json": { schema: { $ref: "#/components/schemas/RewardRequest" } } },
        },
        responses: { 201: { content: { "application/json": { schema: { $ref: "#/components/schemas/Reward" } } } } },
      },
    },
    "/v1/rewards/{code}": {
      get: {
        parameters: [{ in: "path", name: "code", required: true, schema: { type: "string" } }],
        responses: { 200: { content: { "application/json": { schema: { $ref: "#/components/schemas/Reward" } } } } },
      },
    },
  },
  components: {
    schemas: {
      Reward: {
        type: "object",
        required: ["code", "status"],
        properties: {
          code: { type: "string" },
          name: { type: "string" },
          status: { type: "string", enum: ["DRAFT", "LIVE"] },
          points: { type: "integer", format: "int64" },
        },
      },
      RewardRequest: {
        type: "object",
        required: ["code"],
        properties: {
          code: { type: "string" },
          name: { type: "string" },
          band: { type: "string", enum: ["F1", "F2"] },
        },
      },
    },
  },
};

const has = (errors, ...parts) => errors.some((e) => parts.every((p) => e.includes(p)));

test("identica: nessuna rottura", () => {
  assert.deepEqual(breakingChanges(BASE, clone(BASE)), []);
});

test("aggiunte: percorsi, operazioni, campi facoltativi, parametri facoltativi, enum in risposta", () => {
  const cur = clone(BASE);
  cur.paths["/v1/rewards/{code}"].delete = { responses: { 204: {} } };
  cur.paths["/v1/coupons"] = { get: { responses: { 200: {} } } };
  cur.paths["/v1/rewards"].get.parameters.push({ in: "query", name: "q", required: false, schema: { type: "string" } });
  cur.components.schemas.Reward.properties.category = { type: "string" };
  cur.components.schemas.Reward.required.push("name"); // più garanzie in risposta: lecito
  cur.components.schemas.Reward.properties.status.enum.push("ARCHIVED");
  cur.components.schemas.RewardRequest.properties.note = { type: "string" };
  cur.components.schemas.RewardRequest.properties.band.enum.push("F3");
  cur.components.schemas.Coupon = { type: "object" };
  assert.deepEqual(breakingChanges(BASE, cur), []);
});

test("rimozioni: percorso, operazione, risposta 2xx, media type, schema", () => {
  const cur = clone(BASE);
  delete cur.paths["/v1/rewards/{code}"];
  delete cur.paths["/v1/rewards"].post;
  cur.paths["/v1/rewards"].get.responses = { 404: BASE.paths["/v1/rewards"].get.responses[404] };
  const errors = breakingChanges(BASE, cur);
  assert.ok(has(errors, "/v1/rewards/{code}", "percorso rimosso"), errors.join("\n"));
  assert.ok(has(errors, "POST /v1/rewards", "operazione rimossa"), errors.join("\n"));
  assert.ok(has(errors, "GET /v1/rewards", "risposta 200 rimossa"), errors.join("\n"));
  assert.equal(errors.length, 3, errors.join("\n"));

  const noMedia = clone(BASE);
  noMedia.paths["/v1/rewards"].get.responses[200].content = { "application/xml": {} };
  assert.ok(has(breakingChanges(BASE, noMedia), "media type «application/json» rimosso"));

  const noSchema = clone(BASE);
  delete noSchema.components.schemas.RewardRequest;
  noSchema.paths["/v1/rewards"].post.requestBody.content["application/json"].schema = { type: "object" };
  assert.ok(has(breakingChanges(BASE, noSchema), "components.schemas.RewardRequest", "schema rimosso"));
});

test("la rimozione di una risposta di errore non è una rottura", () => {
  const cur = clone(BASE);
  delete cur.paths["/v1/rewards"].get.responses[404];
  assert.deepEqual(breakingChanges(BASE, cur), []);
});

test("proprietà rimossa, anche in uno schema condiviso", () => {
  const cur = clone(BASE);
  delete cur.components.schemas.Reward.properties.name;
  const errors = breakingChanges(BASE, cur);
  assert.ok(has(errors, "components.schemas.Reward", "proprietà «name» rimossa"), errors.join("\n"));
  assert.ok(has(errors, "GET /v1/rewards risposta 200", "(Reward)", "proprietà «name» rimossa"), errors.join("\n"));
});

test("richiesta: campo obbligatorio aggiunto, parametro reso obbligatorio, corpo obbligatorio, enum ristretto", () => {
  const cur = clone(BASE);
  cur.components.schemas.RewardRequest.required.push("name");
  cur.paths["/v1/rewards"].get.parameters[0].required = true;
  cur.paths["/v1/rewards"].get.parameters.push({ in: "header", name: "X-Tenant", required: true, schema: { type: "string" } });
  cur.components.schemas.RewardRequest.properties.band.enum = ["F1"];
  const errors = breakingChanges(BASE, cur);
  assert.ok(has(errors, "POST /v1/rewards richiesta", "campo obbligatorio aggiunto nella richiesta «name»"), errors.join("\n"));
  assert.ok(has(errors, "GET /v1/rewards", "parametro «query:page» reso obbligatorio"), errors.join("\n"));
  assert.ok(has(errors, "GET /v1/rewards", "parametro obbligatorio aggiunto «header:X-Tenant»"), errors.join("\n"));
  assert.ok(has(errors, "valore «F2» rimosso dall'enum"), errors.join("\n"));
  assert.equal(errors.length, 4, errors.join("\n"));

  const body = clone(BASE);
  body.paths["/v1/rewards/{code}"].put = { responses: { 200: {} } };
  const baseWithOptionalBody = clone(body);
  baseWithOptionalBody.paths["/v1/rewards/{code}"].put.requestBody = { content: { "application/json": { schema: { type: "object" } } } };
  const curRequired = clone(baseWithOptionalBody);
  curRequired.paths["/v1/rewards/{code}"].put.requestBody.required = true;
  assert.ok(has(breakingChanges(baseWithOptionalBody, curRequired), "corpo della richiesta reso obbligatorio"));
});

test("risposta: campo non più garantito; tipo allargato; nullable", () => {
  const cur = clone(BASE);
  cur.components.schemas.Reward.required = ["code"];
  cur.components.schemas.Reward.properties.code.type = ["string", "null"];
  const errors = breakingChanges(BASE, cur);
  assert.ok(has(errors, "(Reward)", "campo «status» non più garantito nella risposta"), errors.join("\n"));
  assert.ok(has(errors, "tipo allargato da string a null|string"), errors.join("\n"));
});

test("tipo e format: ristretto in richiesta, cambiato ovunque", () => {
  const cur = clone(BASE);
  cur.components.schemas.RewardRequest.properties.code.type = "integer";
  cur.components.schemas.Reward.properties.points.format = "int32";
  const errors = breakingChanges(BASE, cur);
  assert.ok(has(errors, "POST /v1/rewards richiesta.code", "tipo ristretto da string a integer"), errors.join("\n"));
  assert.ok(has(errors, "format cambiato da int64 a int32"), errors.join("\n"));
});

test("integer → number è lecito in richiesta e incompatibile in risposta", () => {
  const cur = clone(BASE);
  cur.paths["/v1/rewards"].get.parameters[0].schema = { type: "number" };
  cur.components.schemas.Reward.properties.points = { type: "number", format: "int64" };
  const errors = breakingChanges(BASE, cur);
  assert.ok(!has(errors, "parametro query:page", "tipo"), errors.join("\n"));
  assert.ok(has(errors, ".points", "tipo allargato da integer a number"), errors.join("\n"));
});

test("schemi ricorsivi: il confronto termina", () => {
  const base = clone(BASE);
  base.components.schemas.Reward.properties.children = { type: "array", items: { $ref: "#/components/schemas/Reward" } };
  const cur = clone(base);
  delete cur.components.schemas.Reward.properties.name;
  const errors = breakingChanges(base, cur);
  assert.ok(has(errors, "proprietà «name» rimossa"), errors.join("\n"));
});

test("specifica base vuota: tutto è un'aggiunta", () => {
  assert.deepEqual(breakingChanges({}, BASE), []);
});
