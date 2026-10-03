// @vitest-environment node
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextRequest } from "next/server";
import { GET, POST } from "./route";
import { bffFor } from "@/lib/auth/bff";
import { getAuthConfig, type EnterpriseAuthConfig } from "@/lib/auth/config";
import { csrfTokenFor } from "@/lib/auth/csrf";
import type { SessionUser } from "@/lib/auth/sessionStore";
import { resetJobsForTests, setAuditTimingForTests, type JobView, type PreviewView } from "@/lib/vetrina/programma";
import seedSnapshot from "@/lib/vetrina/programma-seed.generated.json";
import { fetchFromHub, makeHub, type FakeHubOptions } from "@/test/vetrinaHub";

// V10 (F2-DIST-09, ADR-051, Q-617, Q-722, Q-723): route «Carica il programma di esempio».
// Guardie in ordine (404 fuori dal test, 401, CSRF, 403), token fresco a ogni chiamata all'hub, lavoro in memoria
// con concorrenza (409), riprova, storie, nessun token né dato di membro nelle risposte.

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
const ADMIN: SessionUser = { sub: "kc-admin", sid: "s1", username: "marta.admin", name: "Marta Villa", roles: ["ADMIN"], role: "ADMIN", kind: "operator" };
const ADMIN2: SessionUser = { sub: "kc-admin2", sid: "s9", username: "altro.admin", name: "Altro Admin", roles: ["ADMIN"], role: "ADMIN", kind: "operator" };
const MARKETING: SessionUser = { sub: "kc-mkt", sid: "s2", username: "luca.marketing", name: "Luca Serra", roles: ["MARKETING"], role: "MARKETING", kind: "operator" };
const MEMBER: SessionUser = { sub: "kc-m", sid: "s3", username: "anna.rossi", name: null, roles: ["MEMBER"], role: "ANALYST", kind: "member" };

const EVENT_TYPES = (seedSnapshot["event-types"] as { code: string }[]).map((t) => ({ code: t.code }));

function useHub(opts: FakeHubOptions = {}, hooks: Parameters<typeof fetchFromHub>[1] = {}) {
  opts.members ??= MEMBERS;
  const hub = makeHub(opts);
  hub.lists["/v1/event-types"].push(...EVENT_TYPES);
  const { fetchImpl, authorizations } = fetchFromHub(hub, hooks);
  const fetchMock = vi.fn(fetchImpl);
  vi.stubGlobal("fetch", fetchMock);
  return { hub, authorizations, fetchMock };
}

async function login(user: SessionUser, tokens: Partial<{ accessToken: string; accessExpiresAt: number }> = {}) {
  const bff = bffFor(getAuthConfig() as EnterpriseAuthConfig);
  const id = await bff.store.create(user, {
    accessToken: tokens.accessToken ?? `AT-${user.sub}`,
    accessExpiresAt: tokens.accessExpiresAt ?? Math.floor(Date.now() / 1000) + 300,
    refreshToken: "RT",
    idToken: "ID",
  });
  return { cookie: `__Host-lh_session=${id}`, csrf: csrfTokenFor(id, bff.csrfKey), bff };
}

const url = `${ORIGIN}/api/vetrina/programma`;
const get = (cookie?: string, query = "") => GET(new NextRequest(url + query, { headers: cookie ? { cookie } : {} }));
const post = (cookie: string, csrf: string | null, body: unknown = { scope: "program" }, extra: Record<string, string> = {}) =>
  POST(
    new NextRequest(url, {
      method: "POST",
      headers: { cookie, "content-type": "application/json", origin: ORIGIN, "sec-fetch-site": "same-origin", ...(csrf ? { "x-lh-csrf": csrf } : {}), ...extra },
      body: JSON.stringify(body),
    }),
  );

async function runToEnd(cookie: string, jobId: string): Promise<JobView> {
  let last: JobView | undefined;
  await vi.waitFor(
    async () => {
      const res = await get(cookie, `?job=${jobId}`);
      last = (await res.json()) as JobView;
      expect(last.status).not.toBe("running");
    },
    { timeout: 8000, interval: 20 },
  );
  return last as JobView;
}

beforeEach(() => {
  setAuditTimingForTests(0.05, 0.01);
  for (const s of SERVICES) process.env[`LH_SVC_${s}_URL`] = `http://${s.toLowerCase()}.test`;
  Object.assign(process.env, ENV);
});
afterEach(() => {
  vi.unstubAllGlobals();
  for (const key of [...Object.keys(ENV), "LH_PROFILE", ...SERVICES.map((s) => `LH_SVC_${s}_URL`)]) delete process.env[key];
  delete (globalThis as Record<symbol, unknown>)[Symbol.for("io.loyaltyhub.web.bff")];
  resetJobsForTests();
});

describe("guardie", () => {
  it("profilo demo: 404, nessuna chiamata", async () => {
    delete process.env.LH_PROFILE;
    for (const k of Object.keys(ENV)) delete process.env[k];
    const { fetchMock } = useHub();
    const res = await get();
    expect(res.status).toBe(404);
    expect((await post("a=b", "x")).status).toBe(404);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("enterprise senza ambiente di test dichiarato: 404, anche con sessione ADMIN valida", async () => {
    delete process.env.LH_TEST_USERS_ALLOWED;
    delete process.env.LH_ENVIRONMENT;
    const { fetchMock } = useHub();
    const { cookie, csrf } = await login(ADMIN);
    expect((await get(cookie)).status).toBe(404);
    expect((await post(cookie, csrf)).status).toBe(404);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("senza sessione: 401 UNAUTHENTICATED; sessione inventata: 401", async () => {
    const { fetchMock } = useHub();
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
  ])("POST con CSRF non valido (%s): 403 CSRF_REJECTED, nessun lavoro, nessuna chiamata", async (_, token, extra) => {
    const { fetchMock } = useHub();
    const { cookie, csrf } = await login(ADMIN);
    const res = await post(cookie, token === "ok" ? csrf : token, { scope: "program" }, extra);
    expect(res.status).toBe(403);
    expect((await res.json()).code).toBe("CSRF_REJECTED");
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("solo ADMIN: un operatore MARKETING e un membro ricevono 403 FORBIDDEN_ROLE, in GET e in POST", async () => {
    const { fetchMock } = useHub();
    for (const user of [MARKETING, MEMBER]) {
      const { cookie, csrf } = await login(user);
      const g = await get(cookie);
      expect(g.status).toBe(403);
      expect((await g.json()).code).toBe("FORBIDDEN_ROLE");
      expect((await post(cookie, csrf)).status).toBe(403);
    }
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("richiesta con scope non valido: 400; le risposte non si mettono mai in cache", async () => {
    useHub();
    const { cookie, csrf } = await login(ADMIN);
    const bad = await post(cookie, csrf, { scope: "tutto" });
    expect(bad.status).toBe(400);
    expect(bad.headers.get("cache-control")).toBe("no-store");
    expect((await get(cookie)).headers.get("cache-control")).toBe("no-store");
    expect((await get("")).headers.get("cache-control")).toBe("no-store");
  });
});

describe("anteprima", () => {
  it("ADMIN: piano da creare, presenti, esclusi; nessun token, id di membro né e-mail nella risposta", async () => {
    const { authorizations, hub } = useHub();
    const { cookie } = await login(ADMIN);
    const res = await get(cookie);
    expect(res.status).toBe(200);
    const text = await res.text();
    const body = JSON.parse(text) as PreviewView;
    expect(body.box).toBe("pending");
    expect(body.summary.actor).toBe("marta.admin");
    expect(body.summary.create).toBeGreaterThan(60);
    expect(body.summary.campaignsCreate).toBe(13);
    expect(body.summary.rewardsCreate).toBeGreaterThan(0);
    expect(body.summary.stories.state).toBe("waiting");
    expect(body.summary.excluded.length).toBeGreaterThan(5);
    expect(text).not.toMatch(/AT-|MBR-\d|@example\.org|RT|Bearer/);
    expect(hub.writes()).toHaveLength(0);
    expect(authorizations.every((a) => a === "Bearer AT-kc-admin")).toBe(true);
  });

  it("servizi che non rispondono: 503 HUB_UNAVAILABLE (stato degradato della UI)", async () => {
    useHub({ fail: ["/v1/currencies"] });
    const { cookie } = await login(ADMIN);
    const res = await get(cookie);
    expect(res.status).toBe(503);
    expect((await res.json()).code).toBe("HUB_UNAVAILABLE");
  });

  it("anteprima: richieste concorrenti e ripetute entro pochi secondi leggono l'hub una volta sola", async () => {
    const { fetchMock } = useHub();
    const { cookie } = await login(ADMIN);
    const [a, b] = await Promise.all([get(cookie), get(cookie)]);
    expect(a.status).toBe(200);
    expect(b.status).toBe(200);
    const after = fetchMock.mock.calls.length;
    expect((await get(cookie)).status).toBe(200);
    expect(fetchMock.mock.calls.length).toBe(after);
    const currencies = fetchMock.mock.calls.filter((c) => String(c[0]).includes("/v1/currencies")).length;
    expect(currencies).toBe(1);
  });

  it("lo stato di un lavoro lo legge solo chi l'ha avviato", async () => {
    let release: () => void = () => undefined;
    const gate = new Promise<void>((r) => (release = r));
    useHub({}, { gate: () => gate });
    const owner = await login(ADMIN);
    const other = await login(ADMIN2);
    const first = await post(owner.cookie, owner.csrf);
    const { jobId } = (await first.json()) as { jobId: string };
    const conflict = await post(other.cookie, other.csrf);
    expect(conflict.status).toBe(409);
    expect((await get(other.cookie, `?job=${jobId}`)).status).toBe(404);
    expect((await get(owner.cookie, `?job=${jobId}`)).status).toBe(200);
    release();
    await runToEnd(owner.cookie, jobId);
  });

  it("lavoro sconosciuto: 404 JOB_NOT_FOUND", async () => {
    useHub();
    const { cookie } = await login(ADMIN);
    const res = await get(cookie, "?job=non-esiste");
    expect(res.status).toBe(404);
    expect((await res.json()).code).toBe("JOB_NOT_FOUND");
  });
});

describe("caricamento del programma", () => {
  it("avvio 202, poi done: scritture solo di configurazione, tutto in DRAFT, audit verificato a nome dell'operatore del token", async () => {
    const { hub } = useHub();
    const { cookie, csrf } = await login(ADMIN);
    const res = await post(cookie, csrf);
    expect(res.status).toBe(202);
    const { jobId } = (await res.json()) as { jobId: string };
    const job = await runToEnd(cookie, jobId);
    expect(job.status).toBe("done");
    expect(job.actor).toBe("marta.admin");
    expect(job.result?.created).toBeGreaterThan(60);
    expect(job.result?.auditVerified).toBe(job.result?.created);
    expect(job.result?.auditTotal).toBe(job.result?.created);
    expect(job.result?.campaignsDraft).toBe(13);
    expect(job.result?.rewardsDraft).toBeGreaterThan(0);
    expect(hub.writes().every((c) => c.method === "POST" || c.method === "PUT")).toBe(true);
    expect(hub.writes().some((c) => c.path.includes("transitions") || c.path === "/v1/imports")).toBe(false);
    expect(JSON.stringify(hub.writes().map((c) => c.body))).not.toMatch(/MBR-\d/);
    // Ora il riquadro offre le storie? No: le campagne sono DRAFT, le storie aspettano (regola 22: nessuna approvazione da qui).
    const after = (await (await get(cookie)).json()) as PreviewView;
    expect(after.summary.create).toBe(0);
    expect(after.box).toBe("stories-waiting");
    expect(after.summary.stories.waitingFor).toEqual(["CMP-APP-DAILY", "CMP-PURCHASE-BASE", "CMP-SELF-READING"]);
    expect(after.lastRun?.by).toBe("marta.admin");
  });

  it("token fresco a OGNI chiamata all'hub: con un access token sempre scaduto ogni richiesta rinnova e porta un Bearer diverso", async () => {
    const { hub, authorizations } = useHub();
    const { cookie, csrf, bff } = await login(ADMIN, { accessExpiresAt: 0 });
    let n = 0;
    const refresh = vi.spyOn(bff.oidc, "refresh").mockImplementation(async () => ({
      accessToken: `AT-RINNOVATO-${++n}`,
      accessExpiresAt: 0, // scade subito: il token successivo sarà di nuovo da rinnovare
      refreshToken: "RT",
      idToken: null,
      claims: null,
    }));
    const { jobId } = (await (await post(cookie, csrf)).json()) as { jobId: string };
    const job = await runToEnd(cookie, jobId);
    expect(job.status).toBe("done");
    expect(refresh.mock.calls.length).toBeGreaterThan(50);
    expect(new Set(authorizations).size).toBe(authorizations.length);
    expect(authorizations.every((a) => a.startsWith("Bearer AT-RINNOVATO-"))).toBe(true);
    expect(hub.writes().length).toBeGreaterThan(60);
    expect(JSON.stringify(job)).not.toContain("AT-");
  });

  it("un secondo POST con un lavoro in corso: 409 JOB_RUNNING con l'id di quello in corso, una sola serie di scritture", async () => {
    let release: () => void = () => undefined;
    const gate = new Promise<void>((r) => (release = r));
    const { hub } = useHub({}, { gate: () => gate });
    const { cookie, csrf } = await login(ADMIN);
    const first = await post(cookie, csrf);
    expect(first.status).toBe(202);
    const { jobId } = (await first.json()) as { jobId: string };
    const second = await post(cookie, csrf, { scope: "stories" });
    expect(second.status).toBe(409);
    const conflict = await second.json();
    expect(conflict.code).toBe("JOB_RUNNING");
    expect(conflict.jobId).toBe(jobId);
    expect(((await (await get(cookie, `?job=${jobId}`)).json()) as JobView).status).toBe("running");
    release();
    expect((await runToEnd(cookie, jobId)).status).toBe("done");
    const writes = hub.writes().length;
    expect(writes).toBeGreaterThan(60);
    // Finito: un nuovo POST riparte (e non ha niente da creare).
    const third = await post(cookie, csrf);
    expect(third.status).toBe(202);
    await runToEnd(cookie, ((await third.json()) as { jobId: string }).jobId);
    expect(hub.writes().length).toBe(writes);
  });

  it("errore a metà: «Caricati N di M, riprova»; la riprova crea solo ciò che manca (nessun doppione)", async () => {
    const opts: FakeHubOptions = { failPost: ["/v1/reward-bands"] };
    const { hub } = useHub(opts);
    const { cookie, csrf } = await login(ADMIN);
    const first = await runToEnd(cookie, ((await (await post(cookie, csrf)).json()) as { jobId: string }).jobId);
    expect(first.status).toBe("error");
    expect(first.message).toMatch(/^Caricati \d+ di \d+\. Riprova per completare/);
    expect(first.result?.failed).toBeGreaterThan(0);
    expect(first.result?.problems.join(" ")).toMatch(/reward-bands/);
    const createdFirst = first.result?.created ?? 0;
    expect(createdFirst).toBeGreaterThan(0);
    // L'hub torna a funzionare: la riprova completa il resto.
    opts.failPost = [];
    const second = await runToEnd(cookie, ((await (await post(cookie, csrf)).json()) as { jobId: string }).jobId);
    expect(second.status).toBe("done");
    expect(second.result?.created).toBeGreaterThan(0);
    const keys = hub.writes().filter((c) => c.method === "POST").map((c) => `${c.path}:${(c.body as { code?: string })?.code}`);
    // Nessuna creazione ripetuta: ogni (percorso, codice) parte una sola volta con esito positivo.
    expect(hub.lists["/v1/rewards"].length).toBe(new Set(hub.lists["/v1/rewards"].map((r) => r.code)).size);
    expect(keys.length).toBeGreaterThan(createdFirst);
  });

  it("audit mancante: errore esplicito con l'elenco (regola 21), non un successo", async () => {
    useHub({ audit: "none" });
    const { cookie, csrf, bff } = await login(ADMIN);
    expect(bff).toBeDefined();
    const { jobId } = (await (await post(cookie, csrf)).json()) as { jobId: string };
    const job = await runToEnd(cookie, jobId);
    expect(job.status).toBe("error");
    expect(job.result?.auditVerified).toBe(0);
    expect(job.result?.problems.some((p) => /voce di audit mancante/.test(p))).toBe(true);
  });

  it("audit di un altro attore: errore, non vale", async () => {
    useHub({ audit: "other" });
    const { cookie, csrf } = await login(ADMIN);
    const job = await runToEnd(cookie, ((await (await post(cookie, csrf)).json()) as { jobId: string }).jobId);
    expect(job.status).toBe("error");
    expect(job.result?.problems.some((p) => /altro attore/.test(p))).toBe(true);
  });

  it("sessione scaduta a metà lavoro: errore dichiarato, nessun token nel lavoro", async () => {
    let release: () => void = () => undefined;
    const gate = new Promise<void>((r) => (release = r));
    const { hub } = useHub({}, { gate: () => gate });
    const { cookie, csrf, bff } = await login(ADMIN);
    const { jobId } = (await (await post(cookie, csrf)).json()) as { jobId: string };
    // Logout o back-channel logout mentre il lavoro è in corso: alla chiamata successiva non c'è più sessione.
    await bff.store.deleteMatching({ sub: ADMIN.sub });
    release();
    // Un nuovo accesso dello stesso operatore (altra sessione) per leggere l'esito.
    const again = await login(ADMIN);
    const job = await runToEnd(again.cookie, jobId);
    expect(job.status).toBe("error");
    expect(job.result?.problems.join(" ")).toMatch(/sessione dell'operatore è scaduta/);
    expect(hub.writes()).toHaveLength(0);
  });
});

describe("storie", () => {
  async function loadProgram(cookie: string, csrf: string) {
    const job = await runToEnd(cookie, ((await (await post(cookie, csrf)).json()) as { jobId: string }).jobId);
    expect(job.status).toBe("done");
  }

  it("finché le campagne sono DRAFT le storie non partono: errore con il motivo, nessun import", async () => {
    const { hub } = useHub();
    const { cookie, csrf } = await login(ADMIN);
    await loadProgram(cookie, csrf);
    const job = await runToEnd(cookie, ((await (await post(cookie, csrf, { scope: "stories" })).json()) as { jobId: string }).jobId);
    expect(job.status).toBe("error");
    expect(job.result?.problems.join(" ")).toMatch(/in attesa delle campagne attive/);
    expect(hub.imports.size).toBe(0);
  });

  it("campagne attive: un solo import dalla fonte di test, audit dell'import verificato, riquadro «completo»", async () => {
    const { hub } = useHub({ importStep: 13 });
    const { cookie, csrf } = await login(ADMIN);
    await loadProgram(cookie, csrf);
    hub.state.campaignsLive.push("CMP-APP-DAILY", "CMP-PURCHASE-BASE", "CMP-SELF-READING");
    const before = (await (await get(cookie)).json()) as PreviewView;
    expect(before.box).toBe("stories-ready");
    expect(before.summary.stories.rows).toBe(38);
    const res = await post(cookie, csrf, { scope: "stories" });
    expect(res.status).toBe(202);
    const job = await runToEnd(cookie, ((await res.json()) as { jobId: string }).jobId);
    expect(job.status).toBe("done");
    expect(job.result?.storyRows).toBe(38);
    expect(job.result?.auditVerified).toBe(1);
    expect(hub.imports.size).toBe(1);
    const imports = hub.writes().filter((c) => c.path === "/v1/imports");
    expect(imports).toHaveLength(1);
    expect(imports[0].multipart?.fields).toEqual({ kind: "EVENTS", source: "vetrina-test" });
    const after = (await (await get(cookie)).json()) as PreviewView;
    expect(after.box).toBe("complete");
  });

  it("membri non registrati: le storie aspettano, con il motivo", async () => {
    const { hub } = useHub({ members: {} });
    const { cookie, csrf } = await login(ADMIN);
    await loadProgram(cookie, csrf);
    hub.state.campaignsLive.push("CMP-APP-DAILY", "CMP-PURCHASE-BASE", "CMP-SELF-READING");
    const preview = (await (await get(cookie)).json()) as PreviewView;
    expect(preview.box).toBe("stories-waiting");
    expect(preview.summary.stories.reasons.join(" ")).toMatch(/nessun membro di test registrato/);
    expect(preview.summary.stories.members.every((m) => m.state === "missing")).toBe(true);
  });
});
