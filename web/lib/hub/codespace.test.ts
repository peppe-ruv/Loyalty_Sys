// @vitest-environment node
import { beforeEach, describe, expect, it, vi } from "vitest";
import { NextRequest } from "next/server";
import {
  CACHE_MS,
  codespaceConfig,
  handleCodespaceGet,
  handleCodespacePost,
  mapState,
  resetStartWindow,
  START_WINDOW_MS,
} from "./codespace";

// HUB-01, Q-674: route del BFF che legge e avvia il codespace della vetrina. fetch finto, nessuna rete.

const NAME = "verbose-space-abc123xyz";
const TOKEN = "github_pat_SEGRETO_DI_PROVA_0123456789";
const ENV = { LH_VETRINA_CODESPACE: NAME, LH_VETRINA_GITHUB_TOKEN: TOKEN };
const ORIGIN = "https://loyalty-demo.example.org";

function gh(state: string, extra: Record<string, unknown> = {}) {
  return Response.json({ state, web_url: `https://${NAME}.github.dev`, owner: { login: "x" }, secret_field: "NON-DEVE-USCIRE", ...extra });
}

const get = () => new NextRequest(`${ORIGIN}/api/vetrina/codespace`);
const post = (origin: string | null = ORIGIN) =>
  new NextRequest(`${ORIGIN}/api/vetrina/codespace`, { method: "POST", headers: origin === null ? {} : { origin } });

beforeEach(() => {
  resetStartWindow();
  vi.restoreAllMocks();
  vi.spyOn(console, "error").mockImplementation(() => undefined);
  vi.spyOn(console, "warn").mockImplementation(() => undefined);
});

describe("mapState", () => {
  it.each([
    ["Available", "available"],
    ["Starting", "starting"],
    ["Provisioning", "starting"],
    ["Queued", "starting"],
    ["Rebuilding", "starting"],
    ["AwaitingAvailability", "starting"],
    ["Shutdown", "shutdown"],
    ["ShuttingDown", "shutdown"],
    ["Failed", "unknown"],
    ["Deleted", "unknown"],
    ["", "unknown"],
  ])("%s → %s", (raw, expected) => expect(mapState(raw)).toBe(expected));
  it("un valore non stringa è unknown", () => {
    expect(mapState(undefined)).toBe("unknown");
    expect(mapState(42)).toBe("unknown");
  });
});

describe("codespaceConfig", () => {
  it("richiede entrambe le variabili, solo in demo", () => {
    expect(codespaceConfig(ENV)).toEqual({ name: NAME, token: TOKEN });
    expect(codespaceConfig({ LH_VETRINA_CODESPACE: NAME })).toBeNull();
    expect(codespaceConfig({ LH_VETRINA_GITHUB_TOKEN: TOKEN })).toBeNull();
    expect(codespaceConfig({ ...ENV, LH_PROFILE: "enterprise" })).toBeNull();
    expect(codespaceConfig({ ...ENV, LH_PROFILE: "boh" })).toBeNull();
  });
  it.each(["../user", "a/b", "Nome Maiuscolo", "x", "-abc", "abc-", "a b c", "name?x=1", "name%2Fx"])(
    "nome non valido %j ⇒ come assente",
    (name) => expect(codespaceConfig({ ...ENV, LH_VETRINA_CODESPACE: name })).toBeNull(),
  );
});

describe("GET /api/vetrina/codespace", () => {
  it("404 senza configurazione e nel profilo enterprise, senza chiamare GitHub", async () => {
    const f = vi.fn();
    for (const env of [{}, { LH_VETRINA_CODESPACE: NAME }, { ...ENV, LH_PROFILE: "enterprise" }]) {
      const res = await handleCodespaceGet(get(), { env, fetchImpl: f as unknown as typeof fetch });
      expect(res.status).toBe(404);
    }
    expect(f).not.toHaveBeenCalled();
  });

  it("chiama solo api.github.com con intestazioni, timeout e no-store; risponde {state, url}", async () => {
    const f = vi.fn(async () => gh("Available"));
    const res = await handleCodespaceGet(get(), { env: ENV, fetchImpl: f as unknown as typeof fetch });
    expect(res.status).toBe(200);
    expect(res.headers.get("cache-control")).toBe("no-store");
    expect(await res.json()).toEqual({ state: "available", url: `https://${NAME}.github.dev` });
    const [url, init] = f.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe(`https://api.github.com/user/codespaces/${NAME}`);
    expect(init.method).toBe("GET");
    expect(init.cache).toBe("no-store");
    expect(init.signal).toBeInstanceOf(AbortSignal);
    const h = init.headers as Record<string, string>;
    expect(h.Authorization).toBe(`Bearer ${TOKEN}`);
    expect(h.Accept).toBe("application/vnd.github+json");
    expect(h["X-GitHub-Api-Version"]).toBe("2022-11-28");
  });

  it.each([
    ["Shutdown", "shutdown"],
    ["Starting", "starting"],
    ["Exporting", "unknown"],
  ])("stato GitHub %s → %s", async (raw, state) => {
    const res = await handleCodespaceGet(get(), { env: ENV, fetchImpl: (async () => gh(raw)) as unknown as typeof fetch });
    expect((await res.json()).state).toBe(state);
  });

  it("url non https o fuori da github.dev ⇒ null", async () => {
    const res = await handleCodespaceGet(get(), {
      env: ENV,
      fetchImpl: (async () => gh("Available", { web_url: "https://evil.example.org" })) as unknown as typeof fetch,
    });
    expect((await res.json()).url).toBeNull();
  });

  it("mai il token né il corpo di GitHub nella risposta, in successo e in errore", async () => {
    const ok = await handleCodespaceGet(get(), { env: ENV, fetchImpl: (async () => gh("Available")) as unknown as typeof fetch });
    const okText = await ok.text();
    expect(okText).not.toContain(TOKEN);
    expect(okText).not.toContain("NON-DEVE-USCIRE");
    expect(okText).not.toContain("owner");
    resetStartWindow(); // la risposta buona sta in cache per 5 s: qui si vuole provare l'errore

    const bad = await handleCodespaceGet(get(), {
      env: ENV,
      fetchImpl: (async () => new Response(`Bad credentials ${TOKEN}`, { status: 401 })) as unknown as typeof fetch,
    });
    expect(bad.status).toBe(502);
    expect(bad.headers.get("content-type")).toBe("application/problem+json");
    const badBody = await bad.json();
    expect(badBody).toMatchObject({ code: "CODESPACE_UNAVAILABLE", status: 502 });
    expect(JSON.stringify(badBody)).not.toContain(TOKEN);
    expect(JSON.stringify(badBody)).not.toContain("Bad credentials");
    // Nemmeno nei log.
    const logged = JSON.stringify((console.error as unknown as { mock: { calls: unknown[] } }).mock.calls);
    expect(logged).not.toContain(TOKEN);
  });

  it("GitHub irraggiungibile o in timeout ⇒ 502", async () => {
    const res = await handleCodespaceGet(get(), {
      env: ENV,
      fetchImpl: (async () => {
        throw new TypeError(`fetch failed ${TOKEN}`);
      }) as unknown as typeof fetch,
    });
    expect(res.status).toBe(502);
    expect(await res.text()).not.toContain(TOKEN);
  });
});

describe("POST /api/vetrina/codespace", () => {
  it("404 senza configurazione e in enterprise", async () => {
    expect((await handleCodespacePost(post(), { env: {} })).status).toBe(404);
    expect((await handleCodespacePost(post(), { env: { ...ENV, LH_PROFILE: "enterprise" } })).status).toBe(404);
  });

  it("controllo dell'origine: assente o diversa ⇒ 403, nessuna chiamata a GitHub", async () => {
    const f = vi.fn();
    for (const origin of [null, "https://altro.example.org", "http://loyalty-demo.example.org", "null"]) {
      const res = await handleCodespacePost(post(origin), { env: ENV, fetchImpl: f as unknown as typeof fetch });
      expect(res.status).toBe(403);
    }
    expect(f).not.toHaveBeenCalled();
  });

  it("spento: POST /start e risposta «starting»", async () => {
    const f = vi.fn(async (url: string, init?: RequestInit) => (init?.method === "POST" ? new Response(null, { status: 200 }) : gh("Shutdown")));
    const res = await handleCodespacePost(post(), { env: ENV, fetchImpl: f as unknown as typeof fetch, now: () => 1_000_000 });
    expect(res.status).toBe(200);
    expect((await res.json()).state).toBe("starting");
    const calls = f.mock.calls.map((c) => [(c as unknown as [string, RequestInit])[1].method, (c as unknown as [string])[0]]);
    expect(calls).toEqual([
      ["GET", `https://api.github.com/user/codespaces/${NAME}`],
      ["POST", `https://api.github.com/user/codespaces/${NAME}/start`],
    ]);
  });

  it("già acceso: lo stato, senza avviare", async () => {
    const f = vi.fn(async () => gh("Available"));
    const res = await handleCodespacePost(post(), { env: ENV, fetchImpl: f as unknown as typeof fetch });
    expect((await res.json()).state).toBe("available");
    expect(f).toHaveBeenCalledTimes(1);
  });

  it("al più un avvio ogni 60 s: dentro la finestra solo lo stato corrente, poi di nuovo", async () => {
    let t = 5_000_000;
    const f = vi.fn(async (_url: string, init?: RequestInit) => (init?.method === "POST" ? new Response(null, { status: 200 }) : gh("Shutdown")));
    const deps = { env: ENV, fetchImpl: f as unknown as typeof fetch, now: () => t };
    const starts = () => f.mock.calls.filter((c) => (c as unknown as [string, RequestInit])[1].method === "POST").length;

    await handleCodespacePost(post(), deps);
    expect(starts()).toBe(1);
    t += START_WINDOW_MS - 1;
    const again = await handleCodespacePost(post(), deps);
    expect(starts()).toBe(1);
    // GitHub dice ancora «spento»: dentro la finestra il pulsante resta «in accensione».
    expect((await again.json()).state).toBe("starting");
    t += 1;
    await handleCodespacePost(post(), deps);
    expect(starts()).toBe(2);
  });

  it("un avvio fallito non apre la finestra: il tentativo successivo riparte subito", async () => {
    let failStart = true;
    const f = vi.fn(async (_url: string, init?: RequestInit) => {
      if (init?.method !== "POST") return gh("Shutdown");
      return failStart ? new Response("no", { status: 403 }) : new Response(null, { status: 200 });
    });
    const deps = { env: ENV, fetchImpl: f as unknown as typeof fetch, now: () => 9_000_000 };
    expect((await handleCodespacePost(post(), deps)).status).toBe(502);
    failStart = false;
    const retry = await handleCodespacePost(post(), deps);
    expect(retry.status).toBe(200);
    expect((await retry.json()).state).toBe("starting");
  });

  it("GET dentro la finestra con GitHub ancora «spento» ⇒ «starting»; dopo la finestra torna «shutdown»", async () => {
    let t = 7_000_000;
    const f = vi.fn(async (_url: string, init?: RequestInit) => (init?.method === "POST" ? new Response(null, { status: 200 }) : gh("Shutdown")));
    const deps = { env: ENV, fetchImpl: f as unknown as typeof fetch, now: () => t };
    await handleCodespacePost(post(), deps);
    t += 10_000;
    expect((await (await handleCodespaceGet(get(), deps)).json()).state).toBe("starting");
    t += START_WINDOW_MS;
    expect((await (await handleCodespaceGet(get(), deps)).json()).state).toBe("shutdown");
  });
});

describe("cache dello stato (5 s)", () => {
  it("GET ripetuti dentro 5 s chiamano GitHub una volta sola; scaduta la cache si rilegge", async () => {
    let t = 3_000_000;
    const f = vi.fn(async () => gh("Available"));
    const deps = { env: ENV, fetchImpl: f as unknown as typeof fetch, now: () => t };
    await handleCodespaceGet(get(), deps);
    t += CACHE_MS - 1;
    const second = await handleCodespaceGet(get(), deps);
    expect(f).toHaveBeenCalledTimes(1);
    expect(await second.json()).toEqual({ state: "available", url: `https://${NAME}.github.dev` });
    t += 1;
    await handleCodespaceGet(get(), deps);
    expect(f).toHaveBeenCalledTimes(2);
  });

  it("la lettura iniziale del POST usa la cache; un avvio riuscito la svuota", async () => {
    let t = 4_000_000;
    let state = "Shutdown";
    const f = vi.fn(async (_url: string, init?: RequestInit) => (init?.method === "POST" ? new Response(null, { status: 200 }) : gh(state)));
    const deps = { env: ENV, fetchImpl: f as unknown as typeof fetch, now: () => t };
    const gets = () => f.mock.calls.filter((c) => (c as unknown as [string, RequestInit])[1].method === "GET").length;
    await handleCodespaceGet(get(), deps);
    await handleCodespacePost(post(), deps);
    expect(gets()).toBe(1);
    // Dopo l'avvio la cache è vuota: la lettura successiva va a GitHub.
    state = "Available";
    t += 1000;
    expect((await (await handleCodespaceGet(get(), deps)).json()).state).toBe("available");
    expect(gets()).toBe(2);
  });

  it("gli errori non finiscono in cache", async () => {
    let fail = true;
    const f = vi.fn(async () => (fail ? new Response("x", { status: 500 }) : gh("Available")));
    const deps = { env: ENV, fetchImpl: f as unknown as typeof fetch, now: () => 1_000 };
    expect((await handleCodespaceGet(get(), deps)).status).toBe(502);
    fail = false;
    expect((await handleCodespaceGet(get(), deps)).status).toBe(200);
  });
});

describe("POST /api/vetrina/codespace, errori", () => {
  it("errore di GitHub nell'avvio ⇒ 502 senza token né corpo", async () => {
    const f = vi.fn(async (_url: string, init?: RequestInit) => (init?.method === "POST" ? new Response(`forbidden ${TOKEN}`, { status: 403 }) : gh("Shutdown")));
    const res = await handleCodespacePost(post(), { env: ENV, fetchImpl: f as unknown as typeof fetch });
    expect(res.status).toBe(502);
    const text = await res.text();
    expect(text).not.toContain(TOKEN);
    expect(text).not.toContain("forbidden");
  });
});
