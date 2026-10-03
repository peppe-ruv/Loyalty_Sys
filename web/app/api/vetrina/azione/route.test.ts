// @vitest-environment node
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextRequest } from "next/server";
import { GET, POST } from "./route";
import { bffFor } from "@/lib/auth/bff";
import { getAuthConfig, type EnterpriseAuthConfig } from "@/lib/auth/config";
import { csrfTokenFor } from "@/lib/auth/csrf";
import type { SessionUser } from "@/lib/auth/sessionStore";
import { actionTiming, resetActionsForTests, type ActionContext, type ActionResult } from "@/lib/vetrina/azione";
import { fetchFromHub, makeHub, type FakeHubOptions } from "@/test/vetrinaHub";

// V11 (F2-ING-02, BO-32, ADR-051, Q-675): route «Invia un'azione».
// Guardie in ordine (404 fuori dal test, 401, CSRF, 403 per i ruoli diversi da ADMIN e CARE), membro solo tra quelli di
// test, riga con la fonte in forma di URN e file che non si chiama come quello delle storie, esito con punti e audit.

const ORIGIN = "https://loyalty.lh.test";
const ENV = {
  LH_PROFILE: "enterprise",
  LH_OIDC_ISSUER: "https://idp.lh.test/realms/loyaltyhub",
  LH_WEB_CLIENT_SECRET: "s3cr3t-generato-dall-idp-0123456789",
  LH_WEB_URL: ORIGIN,
  LH_WEB_SESSION_KEY: Buffer.from(Array.from({ length: 32 }, (_, i) => 100 + i)).toString("base64"),
  LH_TEST_USERS_ALLOWED: "true",
  LH_ENVIRONMENT: "test",
};
const SERVICES = ["INGESTION", "MEMBER", "CAMPAIGN", "WALLET", "REWARD", "GAMIFICATION", "ENGAGEMENT", "INSIGHT"];
const MEMBERS = { "anna.rossi": "MBR-000101", "marco.bianchi": "MBR-000102", "giulia.ferri": "MBR-000103" };
const user = (role: SessionUser["role"], name: string, kind: SessionUser["kind"] = "operator"): SessionUser => ({
  sub: `kc-${name}`, sid: `s-${name}`, username: name, name, roles: [role], role, kind,
});
const ADMIN = user("ADMIN", "marta.admin");
const CARE = user("CARE", "carla.care");
const OTHERS = [user("MARKETING", "luca.marketing"), user("LEGAL", "elena.legal"), user("ANALYST", "ada.analyst"), user("ANALYST", "anna.rossi", "member")];

function setupHub(opts: FakeHubOptions = {}, { source = true }: { source?: boolean } = {}) {
  opts.members ??= MEMBERS;
  const hub = makeHub(opts);
  if (source) hub.lists["/v1/sources"].push({ code: "vetrina-test" });
  hub.wallets.set("MBR-000103", { points: 5000, tier: "Silver" });
  hub.wallets.set("MBR-000101", { points: 100, tier: "Base" });
  const { fetchImpl, authorizations } = fetchFromHub(hub);
  const fetchMock = vi.fn(fetchImpl);
  vi.stubGlobal("fetch", fetchMock);
  return { hub, authorizations, fetchMock };
}

async function login(u: SessionUser) {
  const bff = bffFor(getAuthConfig() as EnterpriseAuthConfig);
  const id = await bff.store.create(u, { accessToken: `AT-${u.sub}`, accessExpiresAt: Math.floor(Date.now() / 1000) + 300, refreshToken: "RT", idToken: "ID" });
  return { cookie: `__Host-lh_session=${id}`, csrf: csrfTokenFor(id, bff.csrfKey) };
}

const url = `${ORIGIN}/api/vetrina/azione`;
const get = (cookie?: string, query = "") => GET(new NextRequest(url + query, { headers: cookie ? { cookie } : {} }));
const post = (cookie: string, csrf: string | null, body: unknown, extra: Record<string, string> = {}) =>
  POST(
    new NextRequest(url, {
      method: "POST",
      headers: { cookie, "content-type": "application/json", origin: ORIGIN, "sec-fetch-site": "same-origin", ...(csrf ? { "x-lh-csrf": csrf } : {}), ...extra },
      body: JSON.stringify(body),
    }),
  );
const GIULIA_PURCHASE = { username: "giulia.ferri", type: "purchase.completed", amount: "150" };

beforeEach(() => {
  for (const s of SERVICES) process.env[`LH_SVC_${s}_URL`] = `http://${s.toLowerCase()}.test`;
  Object.assign(process.env, ENV);
});
afterEach(() => {
  vi.unstubAllGlobals();
  for (const key of [...Object.keys(ENV), "LH_PROFILE", ...SERVICES.map((s) => `LH_SVC_${s}_URL`)]) delete process.env[key];
  delete (globalThis as Record<symbol, unknown>)[Symbol.for("io.loyaltyhub.web.bff")];
  resetActionsForTests();
  actionTiming.settleMs = 20_000;
  actionTiming.auditMs = 60_000;
});

describe("guardie", () => {
  it("profilo demo: 404, nessuna chiamata", async () => {
    delete process.env.LH_PROFILE;
    for (const k of Object.keys(ENV)) delete process.env[k];
    const { fetchMock } = setupHub();
    expect((await get()).status).toBe(404);
    expect((await post("a=b", "x", GIULIA_PURCHASE)).status).toBe(404);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("enterprise senza ambiente di test dichiarato: 404 anche con una sessione ADMIN valida", async () => {
    delete process.env.LH_TEST_USERS_ALLOWED;
    delete process.env.LH_ENVIRONMENT;
    const { fetchMock } = setupHub();
    const { cookie, csrf } = await login(ADMIN);
    expect((await get(cookie)).status).toBe(404);
    expect((await post(cookie, csrf, GIULIA_PURCHASE)).status).toBe(404);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("senza sessione: 401 UNAUTHENTICATED; sessione inventata: 401", async () => {
    const { fetchMock } = setupHub();
    const res = await get();
    expect(res.status).toBe(401);
    expect((await res.json()).code).toBe("UNAUTHENTICATED");
    expect((await get("__Host-lh_session=inventata")).status).toBe(401);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it.each([
    ["token assente", null, {}],
    ["token sbagliato", "abc", {}],
    ["Origin di un altro sito", "ok", { origin: "https://attaccante.example" }],
    ["Sec-Fetch-Site cross-site", "ok", { "sec-fetch-site": "cross-site" }],
  ])("POST con CSRF non valido (%s): 403 CSRF_REJECTED, nessuna chiamata", async (_, token, extra) => {
    const { fetchMock } = setupHub();
    const { cookie, csrf } = await login(ADMIN);
    const res = await post(cookie, token === "ok" ? csrf : token, GIULIA_PURCHASE, extra);
    expect(res.status).toBe(403);
    expect((await res.json()).code).toBe("CSRF_REJECTED");
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("ruoli diversi da ADMIN e CARE (e un membro): 403 FORBIDDEN_ROLE in GET e in POST, nessuna scrittura", async () => {
    const { hub } = setupHub();
    for (const u of OTHERS) {
      const { cookie, csrf } = await login(u);
      const g = await get(cookie);
      expect(g.status, u.role).toBe(403);
      expect((await g.json()).code).toBe("FORBIDDEN_ROLE");
      expect((await post(cookie, csrf, GIULIA_PURCHASE)).status, u.role).toBe(403);
    }
    expect(hub.writes()).toEqual([]);
  });

  it("ADMIN e CARE passano, con Cache-Control: no-store", async () => {
    setupHub();
    for (const u of [ADMIN, CARE]) {
      const { cookie } = await login(u);
      const res = await get(cookie);
      expect(res.status, u.role).toBe(200);
      expect(res.headers.get("cache-control")).toBe("no-store");
    }
  });
});

describe("contesto del modulo (GET)", () => {
  it("fonte presente: membri con livello, azioni in italiano, nessun id di membro né e-mail né token", async () => {
    setupHub();
    const { cookie } = await login(ADMIN);
    const res = await get(cookie);
    const ctx = (await res.json()) as ActionContext;
    expect(ctx.ready).toBe(true);
    expect(ctx.members).toEqual([
      { key: "anna.rossi", name: "Anna Rossi", registered: true, tier: "Base" },
      { key: "marco.bianchi", name: "Marco Bianchi", registered: true, tier: null },
      { key: "giulia.ferri", name: "Giulia Ferri", registered: true, tier: "Silver" },
    ]);
    expect(ctx.actions.find((a) => a.type === "purchase.completed")).toEqual({ type: "purchase.completed", label: "Acquisto completato", valued: true });
    expect(ctx.actions.filter((a) => a.valued).map((a) => a.type)).toEqual(["purchase.completed", "purchase.returned"]);
    const text = JSON.stringify(ctx);
    expect(text).not.toMatch(/MBR-\d|@example|AT-kc|Bearer/);
  });

  it("senza la fonte di test (programma non caricato): ready=false", async () => {
    setupHub({}, { source: false });
    const { cookie } = await login(ADMIN);
    expect(((await (await get(cookie)).json()) as ActionContext).ready).toBe(false);
  });

  it("membro non registrato: registered=false", async () => {
    setupHub({ members: { "anna.rossi": "MBR-000101" } });
    const { cookie } = await login(ADMIN);
    const ctx = (await (await get(cookie)).json()) as ActionContext;
    expect(ctx.members.map((m) => m.registered)).toEqual([true, false, false]);
  });

  it("servizio che non risponde: 503 HUB_UNAVAILABLE (degradato)", async () => {
    setupHub({ fail: ["/v1/sources"] });
    const { cookie } = await login(ADMIN);
    const res = await get(cookie);
    expect(res.status).toBe(503);
    expect((await res.json()).code).toBe("HUB_UNAVAILABLE");
  });
});

describe("invio (POST)", () => {
  it("richieste non valide: 400, nessuna scrittura", async () => {
    const { hub } = setupHub();
    const { cookie, csrf } = await login(ADMIN);
    const bad: [string, unknown][] = [
      ["membro fuori dai tre di test", { username: "mallory", type: "app.login.daily" }],
      ["id di membro dal client", { username: "giulia.ferri", type: "app.login.daily", memberId: "MBR-999999" }],
      ["soggetto dal client", { username: "giulia.ferri", type: "app.login.daily", subject: "member:MBR-999999" }],
      ["tipo fuori dall'elenco", { username: "giulia.ferri", type: "member.registered" }],
      ["tipo assente", { username: "giulia.ferri" }],
      ["importo mancante", { username: "giulia.ferri", type: "purchase.completed" }],
      ["importo negativo", { username: "giulia.ferri", type: "purchase.completed", amount: "-5" }],
      ["importo oltre il limite", { username: "giulia.ferri", type: "purchase.completed", amount: "10001" }],
      ["importo con tre decimali", { username: "giulia.ferri", type: "purchase.completed", amount: "1.234" }],
      ["importo non numerico", { username: "giulia.ferri", type: "purchase.completed", amount: "abc" }],
      ["importo su un'azione senza importo", { username: "giulia.ferri", type: "app.login.daily", amount: "10" }],
      ["corpo non oggetto", [1, 2]],
    ];
    for (const [name, body] of bad) {
      const res = await post(cookie, csrf, body);
      expect(res.status, name).toBe(400);
      expect((await res.json()).code).toBe("BAD_REQUEST");
    }
    expect(hub.writes()).toEqual([]);
  });

  it("programma non caricato: 409 PROGRAM_MISSING, nessuna scrittura", async () => {
    const { hub } = setupHub({}, { source: false });
    const { cookie, csrf } = await login(ADMIN);
    const res = await post(cookie, csrf, GIULIA_PURCHASE);
    expect(res.status).toBe(409);
    expect((await res.json()).code).toBe("PROGRAM_MISSING");
    expect(hub.writes()).toEqual([]);
  });

  it("membro di test non registrato: 422 MEMBER_NOT_REGISTERED, nessuna scrittura", async () => {
    const { hub } = setupHub({ members: { "anna.rossi": "MBR-000101" } });
    const { cookie, csrf } = await login(ADMIN);
    const res = await post(cookie, csrf, GIULIA_PURCHASE);
    expect(res.status).toBe(422);
    expect((await res.json()).code).toBe("MEMBER_NOT_REGISTERED");
    expect(hub.writes()).toEqual([]);
  });

  it("acquisto di Giulia: UN import multipart, fonte in forma di URN sulla riga, file `vetrina-azione.ndjson` (non quello delle storie), una sola scrittura", async () => {
    const { hub, authorizations } = setupHub();
    const { cookie, csrf } = await login(CARE);
    const res = await post(cookie, csrf, GIULIA_PURCHASE);
    expect(res.status).toBe(202);
    const { importId } = await res.json();
    expect(importId).toMatch(/^01J/);

    const writes = hub.writes();
    expect(writes).toHaveLength(1);
    const w = writes[0];
    expect([w.service, w.method, w.path]).toEqual(["ingestion", "POST", "/v1/imports"]);
    expect(w.multipart?.fields).toEqual({ kind: "EVENTS", source: "vetrina-test" });
    expect(w.multipart?.file.name).toBe("vetrina-azione.ndjson");
    expect(w.multipart?.file.name).not.toBe("vetrina-storie.ndjson");
    const lines = (w.multipart?.file.text ?? "").split("\n").filter(Boolean);
    expect(lines).toHaveLength(1);
    const row = JSON.parse(lines[0]);
    expect(row.source).toBe("urn:loyaltyhub:source:vetrina-test");
    expect(row.subject).toBe("member:MBR-000103");
    expect(row.type).toBe("purchase.completed");
    expect(row.id).toMatch(/^vt-act-[a-f0-9]{32}$/);
    expect(row.data).toMatchObject({ amount: 150, currency: "EUR" });
    expect(Math.abs(Date.now() - Date.parse(row.time))).toBeLessThan(5000);
    expect(w.headers?.["Idempotency-Key"]).toMatch(/^vt-act-/);
    // Token dell'operatore, sempre fresco, mai in una risposta.
    expect(new Set(authorizations)).toEqual(new Set(["Bearer AT-kc-carla.care"]));
    expect(JSON.stringify(await (await get(cookie, `?import=${importId}`)).json())).not.toMatch(/AT-kc|Bearer|MBR-\d/);
  });

  it("due invii: chiavi di idempotenza e id di riga diversi (un invio = un import)", async () => {
    const { hub } = setupHub();
    const { cookie, csrf } = await login(ADMIN);
    await post(cookie, csrf, { username: "anna.rossi", type: "app.login.daily" });
    await post(cookie, csrf, { username: "anna.rossi", type: "app.login.daily" });
    const [a, b] = hub.writes();
    expect(a.headers?.["Idempotency-Key"]).not.toBe(b.headers?.["Idempotency-Key"]);
    expect(JSON.parse(a.multipart?.file.text ?? "").id).not.toBe(JSON.parse(b.multipart?.file.text ?? "").id);
    expect(hub.imports.size).toBe(2);
  });

  it("errore del servizio: 503 senza dettagli di rete", async () => {
    setupHub({ failPost: ["/v1/imports"] });
    const { cookie, csrf } = await login(ADMIN);
    const res = await post(cookie, csrf, GIULIA_PURCHASE);
    expect(res.status).toBe(503);
    expect((await res.json()).code).toBe("HUB_UNAVAILABLE");
  });
});

describe("esito (GET ?import=)", () => {
  async function send(opts: FakeHubOptions = {}, body: unknown = GIULIA_PURCHASE, who: SessionUser = ADMIN) {
    const ctx = setupHub(opts);
    const session = await login(who);
    const res = await post(session.cookie, session.csrf, body);
    const { importId } = await res.json();
    const status = async () => ((await (await get(session.cookie, `?import=${importId}`)).json()) as ActionResult);
    return { ...ctx, ...session, importId: importId as string, status };
  }

  it("in elaborazione: status running, non finale, niente punti", async () => {
    const { status } = await send({ importStep: 0.4 });
    const r = await status();
    expect(r).toMatchObject({ status: "running", final: false, outcome: "pending", pointsDelta: null });
    expect(r.member).toEqual({ key: "giulia.ferri", name: "Giulia Ferri" });
  });

  it("elaborato: +150 punti e nuovo livello dal portafoglio, audit verificato a nome dell'operatore", async () => {
    const { hub, status } = await send();
    hub.wallets.set("MBR-000103", { points: 5150, tier: "Gold" });
    const r = await status();
    expect(r).toMatchObject({ status: "done", final: true, outcome: "accepted", pointsDelta: 150, tierChanged: true, amount: 150, label: "Acquisto completato" });
    expect(r.before).toEqual({ points: 5000, tier: "Silver" });
    expect(r.after).toEqual({ points: 5150, tier: "Gold" });
    expect(r.audit).toEqual({ state: "verified", actor: "marta.admin", reason: null });
    expect(r.problems).toEqual([]);
  });

  it("punti non ancora arrivati: non finale; scaduta la finestra: finale senza variazione (nessun numero inventato)", async () => {
    const { status } = await send();
    const early = await status();
    expect(early).toMatchObject({ status: "done", outcome: "accepted", pointsDelta: null, final: false });
    actionTiming.settleMs = -1;
    const late = await status();
    expect(late).toMatchObject({ pointsDelta: null, tierChanged: false, final: true });
  });

  it("audit assente: in attesa, poi errore esplicito (regola 21)", async () => {
    const { hub, status } = await send({ audit: "none" });
    hub.wallets.set("MBR-000103", { points: 5150, tier: "Silver" });
    expect((await status()).audit.state).toBe("pending");
    actionTiming.auditMs = -1;
    const r = await status();
    expect(r.audit).toMatchObject({ state: "missing", reason: "voce di audit mancante" });
    expect(r).toMatchObject({ status: "error", final: true });
    expect(r.problems.join(" ")).toMatch(/Audit/);
  });

  it("audit di un altro attore: non vale", async () => {
    const { hub, status } = await send({ audit: "other" });
    hub.wallets.set("MBR-000103", { points: 5150, tier: "Silver" });
    actionTiming.auditMs = -1;
    const r = await status();
    expect(r.audit.state).toBe("missing");
    expect(r.audit.reason).toMatch(/altro attore/);
  });

  it("righe non accettate: errore con il rinvio al rapporto, nessun punto letto", async () => {
    const { status } = await send({ importCounts: { accepted: 0, duplicate: 0, rejected: 1, unmatched: 0, invalid: 0 } });
    const r = await status();
    expect(r).toMatchObject({ status: "error", outcome: "invalid", after: null, pointsDelta: null });
    expect(r.problems[0]).toMatch(/dettaglio dell'import/);
  });

  it("import fallito", async () => {
    const { status } = await send({ importStatus: "FAILED" });
    expect(await status()).toMatchObject({ status: "error", outcome: "failed" });
  });

  it("riga già presente (duplicato): esito «duplicate», senza errore", async () => {
    const { status } = await send({ importCounts: { accepted: 0, duplicate: 1, rejected: 0, unmatched: 0, invalid: 0 } });
    expect(await status()).toMatchObject({ status: "done", outcome: "duplicate", final: true });
  });

  it("l'esito lo legge solo chi ha inviato; un id sconosciuto è 404", async () => {
    const { importId } = await send();
    const other = await login(CARE);
    expect((await get(other.cookie, `?import=${importId}`)).status).toBe(404);
    expect((await get(other.cookie, "?import=01JNONESISTE")).status).toBe(404);
  });

  it("portafoglio non leggibile: l'esito dell'import resta, i punti sono «non letti»", async () => {
    const { status } = await send({ fail: ["/v1/wallets/MBR-000103"] });
    const r = await status();
    expect(r).toMatchObject({ status: "done", outcome: "accepted", before: null, after: null, pointsDelta: null, final: true });
  });
});
