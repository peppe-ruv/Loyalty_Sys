// @vitest-environment node
import { describe, expect, it } from "vitest";
import {
  Api,
  ApiError,
  applyPlan,
  applyStories,
  assertAllowedRequest,
  assertStoryImport,
  buildPlan,
  buildStoryFile,
  campaignsNeededByStories,
  CLI_SCOPE,
  expectedSts,
  PROGRAM_SCOPE,
  planProgram,
  planStories,
  describeError,
  STORY_FILE_NAME,
  resolveStoryTime,
  STORY_SOURCE_URN,
  summarizeProgram,
  verifyAudit,
  type PlanItem,
  type ProgramSeed,
} from "./programma-core.mjs";
import seedSnapshot from "./programma-seed.generated.json";
import { makeHub } from "@/test/vetrinaHub";

// V10 (F2-DIST-09, ADR-051, Q-722, Q-723): nucleo puro del programma di esempio. Hub finto in memoria, nessuna rete.

const SEED = seedSnapshot as unknown as ProgramSeed;
const NOW = Date.parse("2026-10-02T12:00:00Z");
const MEMBERS = { "anna.rossi": "MBR-000101", "marco.bianchi": "MBR-000102", "giulia.ferri": "MBR-000103" };
const api = (hub: ReturnType<typeof makeHub>) => new Api({ transport: hub.transport, scope: PROGRAM_SCOPE });
const sleep = async () => undefined;

describe("storie del seed (seed/vetrina-test.json)", () => {
  const stories = SEED.vetrinaTest.stories;
  const byUser = Object.fromEntries(stories.map((s) => [s.username, s]));

  it("STS attesi dalle regole delle campagne: Anna 0, Marco 1.420 (Silver), Giulia 2.880 (120 sotto Gold)", () => {
    expect(expectedSts(byUser["anna.rossi"].rows, NOW)).toBe(0);
    expect(expectedSts(byUser["marco.bianchi"].rows, NOW)).toBe(1420);
    expect(expectedSts(byUser["giulia.ferri"].rows, NOW)).toBe(3000 - 120);
    for (const s of stories) expect(expectedSts(s.rows, NOW)).toBe(s.expectedSts);
  });

  it("righe: 2 + 14 + 22, date da 1 a 29 giorni fa, al più 3 acquisti al giorno, un solo accesso al giorno, un'autolettura al mese", () => {
    expect(stories.map((s) => s.rows.length)).toEqual([2, 14, 22]);
    for (const s of stories) {
      const perDay = new Map<string, number>();
      const logins = new Set<string>();
      const readings = new Set<string>();
      for (const r of s.rows) {
        const at = resolveStoryTime(r.at, NOW);
        const age = (NOW - Date.parse(at)) / 86400000;
        expect(age).toBeGreaterThan(0);
        expect(age).toBeLessThan(30);
        const day = at.slice(0, 10);
        if (r.type === "purchase.completed") perDay.set(day, (perDay.get(day) ?? 0) + 1);
        if (r.type === "app.login.daily") {
          expect(logins.has(day)).toBe(false);
          logins.add(day);
        }
        if (r.type === "selfreading.submitted") {
          expect(readings.has(at.slice(0, 7))).toBe(false);
          readings.add(at.slice(0, 7));
        }
      }
      for (const n of perDay.values()) expect(n).toBeLessThanOrEqual(3);
    }
    // Marco: nessuna bolletta digitale né domiciliazione (SCN-DIGITAL resta da fare); Anna: nessun profilo completato (SCN-ONBOARDING).
    const types = new Set(stories.flatMap((s) => s.rows.map((r) => r.type)));
    expect(types.has("ebill.activated")).toBe(false);
    expect(types.has("directdebit.activated")).toBe(false);
    expect(types.has("member.profile.completed")).toBe(false);
  });

  it("ogni tipo usato è ammesso dalla fonte di test, che ha un elenco esplicito e non vuoto", () => {
    const allowed = SEED.vetrinaTest.source.allowedTypes as string[];
    expect(allowed.length).toBeGreaterThan(0);
    for (const s of stories) for (const r of s.rows) expect(allowed).toContain(r.type);
  });

  it("le campagne necessarie alle storie sono LIVE nel seed (la recensione, in pausa, non lo è)", () => {
    expect(campaignsNeededByStories(SEED)).toEqual(["CMP-APP-DAILY", "CMP-PURCHASE-BASE", "CMP-SELF-READING"]);
  });
});

describe("date delle storie", () => {
  it("@today-NdTHH:MM in UTC, 1..29 giorni", () => {
    expect(resolveStoryTime("@today-1dT08:05", NOW)).toBe("2026-10-01T08:05:00Z");
    expect(resolveStoryTime("@today-29d", NOW)).toBe("2026-09-03T12:00:00Z");
  });
  it.each(["@today", "@today-0d", "@today-30dT10:00", "@now", "ieri", "@today-5dT25:00"])("rifiuta %s", (v) => {
    expect(() => resolveStoryTime(v, NOW)).toThrow();
  });
});

describe("lista bianca delle richieste", () => {
  it("ambito CLI invariato: niente campagne, membri, import", () => {
    expect(() => assertAllowedRequest("POST", "/v1/campaigns", {}, CLI_SCOPE)).toThrow(/non ammessa/);
    expect(() => assertAllowedRequest("GET", "/v1/members", undefined, CLI_SCOPE)).toThrow(/non ammessa/);
    expect(() => assertAllowedRequest("GET", "/v1/imports", undefined, CLI_SCOPE)).toThrow(/non ammessa/);
  });
  it("ambito del programma: campagne in POST, letture di campagne, membri (solo l'elenco) e import; mai transizioni né un membro per id", () => {
    expect(() => assertAllowedRequest("POST", "/v1/campaigns", { code: "CMP-X" }, PROGRAM_SCOPE)).not.toThrow();
    expect(() => assertAllowedRequest("GET", "/v1/campaigns", undefined, PROGRAM_SCOPE)).not.toThrow();
    expect(() => assertAllowedRequest("GET", "/v1/members", undefined, PROGRAM_SCOPE)).not.toThrow();
    expect(() => assertAllowedRequest("GET", "/v1/imports/01JABCDEFGHJKMNPQRSTVWXYZ0", undefined, PROGRAM_SCOPE)).not.toThrow();
    for (const [m, p] of [["POST", "/v1/campaigns/CMP-X/transitions"], ["POST", "/v1/campaigns/validate"], ["PUT", "/v1/campaigns/CMP-X"], ["GET", "/v1/members/MBR-000002"],
      ["POST", "/v1/members"], ["POST", "/v1/rewards/RWD-X/transitions"], ["DELETE", "/v1/imports/x"], ["POST", "/v1/events"], ["POST", "/v1/demo/reset"]]) {
      expect(() => assertAllowedRequest(m, p, {}, PROGRAM_SCOPE), `${m} ${p}`).toThrow(/non ammessa/);
    }
  });
  it("la guardia /MBR-\\d/ resta sulle scritture di configurazione anche nell'ambito del programma", () => {
    expect(() => assertAllowedRequest("POST", "/v1/campaigns", { description: "per MBR-000002" }, PROGRAM_SCOPE)).toThrow(/membro/);
    expect(() => assertAllowedRequest("POST", "/v1/segments", { memberIds: ["MBR-000004"] }, PROGRAM_SCOPE)).toThrow(/membro/);
  });
});

describe("assertStoryImport: lista bianca propria", () => {
  const subjects = new Set(Object.values(MEMBERS));
  const line = (o: Record<string, unknown> = {}) =>
    JSON.stringify({ specversion: "1.0", id: "vt-anna.rossi-01", source: STORY_SOURCE_URN, type: "app.login.daily", subject: "member:MBR-000101", time: "2026-10-01T08:00:00Z", data: {}, ...o });
  const mp = (text: string, fields: Record<string, string> = { kind: "EVENTS", source: "vetrina-test" }, name = "storie.ndjson") => ({ file: { name, type: "application/x-ndjson", text }, fields });

  it("ammette la forma giusta (anche con subject member:MBR-…, che la guardia delle scritture rifiuterebbe)", () => {
    expect(() => assertStoryImport(mp(`${line()}\n`), subjects)).not.toThrow();
  });
  it.each([
    ["fonte in forma breve", line({ source: "vetrina-test" })],
    ["fonte di un'altra origine", line({ source: "urn:loyaltyhub:source:ecommerce" })],
    ["soggetto con e-mail", line({ subject: "email:anna.rossi@example.org" })],
    ["membro non risolto", line({ subject: "member:MBR-000002" })],
    ["id fuori forma", line({ id: "x-1" })],
    ["una riga fuori allowlist fra le valide", `${line()}\n${line({ subject: "member:MBR-000002" })}`],
    ["riga non JSON", "non json"],
  ])("rifiuta: %s", (_, text) => {
    expect(() => assertStoryImport(mp(text), subjects)).toThrow(/import delle storie non ammesso/);
  });
  it("rifiuta campi del modulo e nome file sbagliati, e l'insieme di membri vuoto", () => {
    expect(() => assertStoryImport(mp(line(), { kind: "EVENTS", source: "ecommerce" }), subjects)).toThrow();
    expect(() => assertStoryImport(mp(line(), { kind: "ATTRIBUTES", source: "vetrina-test" }), subjects)).toThrow();
    expect(() => assertStoryImport(mp(line(), undefined, "../x.csv"), subjects)).toThrow();
    expect(() => assertStoryImport(mp(line()), new Set())).toThrow();
    expect(() => assertStoryImport(undefined, subjects)).toThrow();
  });
  it("Api: POST /v1/imports senza ambito delle storie o con soggetti non risolti non parte", async () => {
    const hub = makeHub({ members: MEMBERS });
    const cli = new Api({ transport: hub.transport, scope: CLI_SCOPE });
    await expect(cli.request("ingestion", "POST", "/v1/imports", { multipart: mp(line()) })).rejects.toThrow(/non ammessa/);
    const prog = api(hub);
    await expect(prog.request("ingestion", "POST", "/v1/imports", { multipart: mp(line()) })).rejects.toThrow(/nessun membro/);
    expect(hub.writes()).toHaveLength(0);
  });
});

describe("file NDJSON delle storie", () => {
  it("un CloudEvent per riga, fonte URN, soggetto member:<id> (mai e-mail), id deterministico, hash stabile", async () => {
    const members = new Map(Object.entries(MEMBERS));
    const a = await buildStoryFile(SEED.vetrinaTest, members, NOW);
    const b = await buildStoryFile(SEED.vetrinaTest, members, NOW);
    expect(a.rows).toBe(38);
    expect(a.sha256).toBe(b.sha256);
    expect(a.sha256).toMatch(/^[0-9a-f]{64}$/);
    const events = a.text.trimEnd().split("\n").map((l) => JSON.parse(l));
    expect(events).toHaveLength(38);
    expect(new Set(events.map((e) => e.id)).size).toBe(38);
    for (const e of events) {
      expect(e.source).toBe(STORY_SOURCE_URN);
      expect(e.subject).toMatch(/^member:MBR-0001\d\d$/);
      expect(e.id).toMatch(/^vt-(anna\.rossi|marco\.bianchi|giulia\.ferri)-\d{2}$/);
      expect(e.specversion).toBe("1.0");
    }
    expect(a.text).not.toContain("@example.org");
    // Un membro non registrato non produce righe.
    const partial = await buildStoryFile(SEED.vetrinaTest, new Map([["giulia.ferri", "MBR-000103"]]), NOW);
    expect(partial.rows).toBe(22);
  });
});

describe("piano della configurazione", () => {
  it("senza opzioni è il piano della riga di comando: nessuna campagna, nessuna fonte di test", async () => {
    const hub = makeHub();
    const { plan } = await buildPlan({ seed: SEED, api: new Api({ transport: hub.transport, scope: CLI_SCOPE }), now: NOW });
    expect(plan.some((i) => i.entity === "campaigns")).toBe(false);
    expect(plan.some((i) => i.entity === "sources" && i.key === "vetrina-test")).toBe(false);
  });

  it("con le opzioni: 13 campagne LIVE non di sistema da creare, le altre escluse col motivo; fonte vetrina-test con elenco esplicito", async () => {
    const hub = makeHub();
    const { plan } = await planProgram({ seed: SEED, api: api(hub), now: NOW });
    const campaigns = plan.filter((i) => i.entity === "campaigns");
    expect(campaigns.filter((i) => i.action === "create")).toHaveLength(13);
    expect(campaigns.filter((i) => i.action === "skip").map((i) => i.key).sort()).toEqual(["CMP-BLACK-FRIDAY", "CMP-GOLD-PURCHASE-PLAY", "CMP-IW-PRIZE-COUPON", "CMP-IW-PRIZE-POINTS", "CMP-REVIEW", "CMP-SUMMER-QUIZ", "CMP-SURVEY"]);
    // Le giocate richiamano un concorso che qui non si crea: pubblicate finirebbero in DLQ (CONTEST_NOT_FOUND).
    const survey = campaigns.find((i) => i.key === "CMP-SURVEY");
    expect(survey?.reason).toMatch(/dipendenza non disponibile: contests:IW-AUTUNNO/);
    for (const c of campaigns.filter((i) => i.action === "create")) {
      const body = c.body as Record<string, unknown>;
      expect(body.status).toBeUndefined(); // sempre DRAFT: la POST non accetta lo stato
      expect(c.method).toBe("POST");
      expect(c.path).toBe("/v1/campaigns");
    }
    const source = plan.find((i) => i.entity === "sources" && i.key === "vetrina-test");
    expect(source?.action).toBe("skip"); // senza i tipi azione sul servizio la fonte si salta: mai aperta a tutti i tipi
    // Con i tipi azione presenti la fonte si crea, con l'elenco esplicito.
    const hub2 = makeHub();
    hub2.lists["/v1/event-types"].push(...(SEED["event-types"] as { code: string }[]).map((t) => ({ code: t.code })));
    const { plan: plan2 } = await planProgram({ seed: SEED, api: api(hub2), now: NOW });
    const src = plan2.find((i) => i.entity === "sources" && i.key === "vetrina-test");
    expect(src?.action).toBe("create");
    expect((src?.body as { allowedTypes: string[] }).allowedTypes.length).toBeGreaterThan(5);
  });

  it("applicazione: scritture solo POST/PUT di configurazione, nessun /MBR-, premi e campagne senza stato, riprova crea solo ciò che manca", async () => {
    const hub = makeHub();
    hub2Types(hub);
    const first = await planProgram({ seed: SEED, api: api(hub), now: NOW });
    const progress: number[] = [];
    const results = await applyPlan({ plan: first.plan, ctx: first.ctx, api: api(hub), onProgress: (p) => progress.push(p.done) });
    expect(results.filter((r) => r.result === "failed")).toHaveLength(0);
    expect(progress[progress.length - 1]).toBe(first.plan.filter((i) => i.action === "create").length);
    expect(hub.writes().every((c) => c.method === "POST" || c.method === "PUT")).toBe(true);
    expect(JSON.stringify(hub.writes().map((c) => c.body))).not.toMatch(/MBR-\d/);
    expect(hub.writes().some((c) => c.path.includes("transitions"))).toBe(false);
    // Seconda esecuzione: niente da creare, nessuna nuova scrittura.
    const before = hub.writes().length;
    const second = await planProgram({ seed: SEED, api: api(hub), now: NOW });
    expect(second.plan.filter((i) => i.action === "create")).toHaveLength(0);
    await applyPlan({ plan: second.plan, ctx: second.ctx, api: api(hub) });
    expect(hub.writes().length).toBe(before);
  });

  it("riprova dopo un errore a metà: 409 di concorrenza vale «già presente», un errore 500 si riporta e le voci dipendenti si saltano", async () => {
    const hub = makeHub({ conflictOn: ["RWD-COFFEE-5"] });
    hub2Types(hub);
    const { plan, ctx } = await planProgram({ seed: SEED, api: api(hub), now: NOW });
    const results = await applyPlan({ plan, ctx, api: api(hub) });
    expect(results.find((r) => r.key === "RWD-COFFEE-5")?.result).toBe("already");
    const broken = makeHub({ failPost: ["/v1/reward-categories"] });
    const p = await planProgram({ seed: SEED, api: api(broken), now: NOW });
    const r = await applyPlan({ plan: p.plan, ctx: p.ctx, api: api(broken) });
    expect(r.some((x) => x.result === "failed")).toBe(true);
    expect(p.plan.some((i) => i.action === "skip" && i.reason?.startsWith("dipendenza non creata"))).toBe(true);
  });
});

function hub2Types(hub: ReturnType<typeof makeHub>) {
  hub.lists["/v1/event-types"].push(...(SEED["event-types"] as { code: string }[]).map((t) => ({ code: t.code })));
}

describe("storie: stato e caricamento", () => {
  async function ready(opts: Parameters<typeof makeHub>[0] = {}) {
    const hub = makeHub({ members: MEMBERS, ...opts });
    hub2Types(hub);
    hub.lists["/v1/sources"].push({ code: "vetrina-test" });
    for (const code of ["CMP-APP-DAILY", "CMP-PURCHASE-BASE", "CMP-SELF-READING"]) {
      hub.lists["/v1/campaigns"].push({ code });
      hub.state.campaignsLive.push(code);
    }
    return hub;
  }

  it("in attesa delle campagne attive (DRAFT) e della fonte: motivi elencati, nessuna scrittura", async () => {
    const hub = makeHub({ members: MEMBERS });
    for (const code of ["CMP-APP-DAILY", "CMP-PURCHASE-BASE", "CMP-SELF-READING"]) hub.lists["/v1/campaigns"].push({ code });
    const planned = await planProgram({ seed: SEED, api: api(hub), now: NOW });
    expect(planned.stories.state).toBe("waiting");
    expect(planned.stories.waitingFor).toEqual(["CMP-APP-DAILY", "CMP-PURCHASE-BASE", "CMP-SELF-READING"]);
    expect(planned.stories.reasons.join(" ")).toMatch(/in attesa delle campagne attive \(CMP-APP-DAILY, CMP-PURCHASE-BASE, CMP-SELF-READING\)/);
    expect(hub.writes()).toHaveLength(0);
    const summary = summarizeProgram({ plan: planned.plan, stories: planned.stories, actor: "marta.admin" });
    expect(summary.stories.state).toBe("waiting");
    expect(JSON.stringify(summary)).not.toMatch(/MBR-\d|@example\.org/);
  });

  it("membri non registrati: esclusi con il motivo; nessun membro → in attesa", async () => {
    const hub = await ready({ members: { "marco.bianchi": "MBR-000102" } });
    const planned = await planProgram({ seed: SEED, api: api(hub), now: NOW });
    expect(planned.stories.state).toBe("ready");
    expect(planned.stories.rows).toBe(14);
    expect(planned.stories.members.map((m) => m.state)).toEqual(["missing", "ready", "missing"]);
    const none = await ready({ members: {} });
    expect((await planProgram({ seed: SEED, api: api(none), now: NOW })).stories.state).toBe("waiting");
  });

  it("caricamento: un solo import multipart con Idempotency-Key = sha256, avanzamento righe, esito", async () => {
    const hub = await ready({ importStep: 10 });
    const planned = await planProgram({ seed: SEED, api: api(hub), now: NOW });
    expect(planned.stories.state).toBe("ready");
    const progress: [number, number][] = [];
    const a = api(hub);
    const res = await applyStories({ seed: SEED, api: a, stories: planned.stories, now: NOW, sleep, onProgress: ({ done, total }) => progress.push([done, total]) });
    expect(res.status).toBe("DONE");
    expect(res.rows).toBe(38);
    expect(res.problems).toEqual([]);
    expect(progress.length).toBeGreaterThan(1);
    expect(progress[progress.length - 1]).toEqual([38, 38]);
    const posts = hub.writes();
    expect(posts).toHaveLength(1);
    expect(posts[0].path).toBe("/v1/imports");
    expect(posts[0].multipart?.fields).toEqual({ kind: "EVENTS", source: "vetrina-test" });
    expect(posts[0].headers?.["Idempotency-Key"]).toMatch(/^[0-9a-f]{64}$/);
    // Lo stesso file → lo stesso lavoro (idempotenza), nessun secondo import.
    const again = await applyStories({ seed: SEED, api: api(hub), stories: planned.stories, now: NOW, sleep });
    expect(again.jobId).toBe(res.jobId);
    expect(hub.imports.size).toBe(1);
    // Dopo il caricamento lo stato è «present».
    expect((await planProgram({ seed: SEED, api: api(hub), now: NOW })).stories.state).toBe("present");
  });

  it("righe rifiutate o non abbinate, o lavoro FAILED: errore dichiarato, non successo silenzioso", async () => {
    const hub = await ready({ importCounts: { accepted: 30, duplicate: 0, rejected: 5, unmatched: 3, invalid: 0 } });
    const planned = await planProgram({ seed: SEED, api: api(hub), now: NOW });
    const res = await applyStories({ seed: SEED, api: api(hub), stories: planned.stories, now: NOW, sleep });
    expect(res.problems.join(" ")).toMatch(/8 righe non accettate/);
    const failed = await ready({ importStatus: "FAILED" });
    const p2 = await planProgram({ seed: SEED, api: api(failed), now: NOW });
    const r2 = await applyStories({ seed: SEED, api: api(failed), stories: p2.stories, now: NOW, sleep });
    expect(r2.status).toBe("FAILED");
    expect(r2.problems).toHaveLength(1);
  });

  it("senza prerequisiti non carica", async () => {
    const hub = makeHub({ members: MEMBERS });
    const planned = await planProgram({ seed: SEED, api: api(hub), now: NOW });
    await expect(applyStories({ seed: SEED, api: api(hub), stories: planned.stories, now: NOW, sleep })).rejects.toThrow(/non sono caricabili/);
  });
});

describe("audit (regola 21)", () => {
  const item = (): PlanItem => ({ entity: "rewards", service: "reward", key: "RWD-X", action: "create", audit: { type: "REWARD", ids: ["RWD-X"], actions: ["CREATE"] } });
  it("voce dell'operatore: verificata; di un altro attore o mancante: problema esplicito", async () => {
    const run = async (audit: "ok" | "other" | "none") => {
      const hub = makeHub({ audit });
      hub.audit.length = 0;
      if (audit !== "none") hub.audit.push({ id: "A1", entityType: "REWARD", entityId: "RWD-X", action: "CREATE", actorName: audit === "ok" ? "marta.admin" : "altro.operatore", actorRole: "ADMIN" });
      return verifyAudit({ api: api(hub), items: [item()], since: new Date(NOW - 1000), expectedActor: "marta.admin", timeoutSec: 0, intervalSec: 1, sleep });
    };
    expect((await run("ok")).verified).toBe(1);
    expect((await run("other")).problems[0].reason).toMatch(/altro attore/);
    expect((await run("none")).problems[0].reason).toMatch(/mancante/);
  });
});

describe("ApiError", () => {
  it("una risposta non 2xx diventa ApiError con il codice RFC 9457, un redirect non si segue", async () => {
    const a = new Api({ transport: async () => ({ status: 422, body: { code: "CONDITION_INVALID", detail: "no" } }), scope: PROGRAM_SCOPE });
    await expect(a.request("campaign", "GET", "/v1/campaigns")).rejects.toMatchObject({ status: 422, code: "CONDITION_INVALID" });
    const r = new Api({ transport: async () => ({ status: 302, body: null }), scope: PROGRAM_SCOPE });
    await expect(r.request("campaign", "GET", "/v1/campaigns")).rejects.toBeInstanceOf(ApiError);
  });
});

// planStories su un hub che non risponde: stato «error», nessuna eccezione.
describe("lettura fallita", () => {
  it("stories error con il motivo", async () => {
    const hub = makeHub({ fail: ["/v1/campaigns"] });
    const s = await planStories({ seed: SEED, api: api(hub), plan: [] });
    expect(s.state).toBe("error");
  });
});

describe("revisione V10", () => {
  it("un effetto ISSUE_COUPON con un premio nominato dipende da quel premio", async () => {
    const seed = JSON.parse(JSON.stringify(SEED)) as ProgramSeed;
    const camp = (seed as unknown as { campaigns: { code: string; effects: unknown[] }[] }).campaigns.find((c) => c.code === "CMP-APP-DAILY")!;
    camp.effects = [{ type: "ISSUE_COUPON", rewardCode: "RWD-NON-ESISTE" }];
    const { plan } = await planProgram({ seed, api: api(makeHub()), now: NOW });
    expect(plan.find((i) => i.entity === "campaigns" && i.key === "CMP-APP-DAILY")?.reason).toMatch(/dipendenza non disponibile: rewards:RWD-NON-ESISTE/);
  });

  it("describeError: testo nostro per gli errori non di rete, codice per quelli di rete", () => {
    expect(describeError(new Error("accesso momentaneamente non verificabile: riprova tra poco"))).toBe("accesso momentaneamente non verificabile: riprova tra poco");
    expect(describeError(Object.assign(new Error("x"), { name: "TimeoutError" }))).toBe("timeout");
    expect(describeError(new TypeError("fetch failed", { cause: { code: "ECONNREFUSED" } }))).toBe("ECONNREFUSED");
  });

  it("applyPlan si ferma alla prima sessione scaduta, senza segnare voci come fallite", async () => {
    const hub = makeHub();
    const { plan, ctx } = await buildPlan({ seed: SEED, api: api(hub), now: NOW, options: { campaigns: true, vetrinaSource: true } });
    let calls = 0;
    const gone = new Api({
      transport: async () => {
        calls++;
        throw Object.assign(new Error("la sessione dell'operatore è scaduta"), { name: "SessionGoneError" });
      },
      scope: PROGRAM_SCOPE,
    });
    await expect(applyPlan({ plan, ctx, api: gone })).rejects.toMatchObject({ name: "SessionGoneError" });
    expect(calls).toBe(1);
  });

  describe("storie: quali import contano", () => {
    async function base() {
      const hub = makeHub({ members: MEMBERS });
      hub2Types(hub);
      hub.lists["/v1/sources"].push({ code: "vetrina-test" });
      for (const code of ["CMP-APP-DAILY", "CMP-PURCHASE-BASE", "CMP-SELF-READING"]) {
        hub.lists["/v1/campaigns"].push({ code });
        hub.state.campaignsLive.push(code);
      }
      return hub;
    }
    const job = (id: string, over: Record<string, unknown>) => ({ id, status: "DONE", rowsTotal: 38, rowsDone: 38, counts: { accepted: 38, duplicate: 0, rejected: 0, unmatched: 0, invalid: 0 }, defaultSource: "vetrina-test", fileName: STORY_FILE_NAME, createdAt: "2026-10-02T10:00:00Z", reads: 0, ...over });

    it("un import di un'altra riga della stessa fonte (V11) non è «le storie»", async () => {
      const hub = await base();
      hub.imports.set("01JAAAAAAAAAAAAAAAAAAAAAA1", job("01JAAAAAAAAAAAAAAAAAAAAAA1", { fileName: "una-riga.ndjson", rowsTotal: 38 }));
      const planned = await planProgram({ seed: SEED, api: api(hub), now: NOW });
      expect(planned.stories.state).toBe("ready");
    });

    it("presente solo se accepted + duplicate = righe; altrimenti ritentabile con chiave nuova", async () => {
      const hub = await base();
      hub.imports.set("01JAAAAAAAAAAAAAAAAAAAAAA1", job("01JAAAAAAAAAAAAAAAAAAAAAA1", { counts: { accepted: 30, duplicate: 0, rejected: 5, unmatched: 3, invalid: 0 } }));
      const planned = await planProgram({ seed: SEED, api: api(hub), now: NOW });
      expect(planned.stories.state).toBe("ready");
      expect(planned.stories.incompleteJobIds).toEqual(["01JAAAAAAAAAAAAAAAAAAAAAA1"]);
      await applyStories({ seed: SEED, api: api(hub), stories: planned.stories, now: NOW, sleep });
      expect(hub.writes()[0].headers?.["Idempotency-Key"]).toMatch(/^[0-9a-f]{64}-r1$/);
      const ok = await base();
      ok.imports.set("01JAAAAAAAAAAAAAAAAAAAAAA2", job("01JAAAAAAAAAAAAAAAAAAAAAA2", { counts: { accepted: 20, duplicate: 18, rejected: 0, unmatched: 0, invalid: 0 } }));
      expect((await planProgram({ seed: SEED, api: api(ok), now: NOW })).stories.state).toBe("present");
    });
  });
});
