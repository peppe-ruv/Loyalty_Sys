// @vitest-environment node
import { beforeEach, describe, expect, it, vi } from "vitest";
import { CHECK_KEYS, parseChecks } from "./readiness";
import { MAX_BODY_BYTES, PROBE_TIMEOUT_MS, forwardDomain, forwardedHosts, probeVetrina } from "./vetrinaProbe";

// HUB-01, Q-728: sonda di prontezza della vetrina. fetch finto, nessuna rete.

const NAME = "verbose-space-abc123xyz";
const WEB = `${NAME}-8000.app.github.dev`;
const IDP = `${NAME}-8001.app.github.dev`;
const HOSTS = { web: WEB, idp: IDP };
const PATHS = {
  status: `https://${WEB}/api/demo/status`,
  ops: `https://${IDP}/realms/loyaltyhub/.well-known/openid-configuration`,
  members: `https://${IDP}/realms/loyaltyhub-members/.well-known/openid-configuration`,
};

const statusBody = (over: Record<string, unknown> = {}) => ({
  services: [
    { code: "ingestion", state: "UP" },
    { code: "member", state: "UP" },
  ],
  kafka: { state: "UP" },
  db: { state: "UP" },
  checkedAt: "2026-10-04T10:00:00Z",
  vetrina: { testMembers: "ready" },
  ...over,
});

type Answers = Partial<Record<keyof typeof PATHS, Response | "throw">>;

function fake(answers: Answers = {}) {
  const ready: Record<keyof typeof PATHS, () => Response> = {
    status: () => Response.json(statusBody()),
    ops: () => Response.json({ issuer: `https://${IDP}/realms/loyaltyhub` }),
    members: () => Response.json({ issuer: `https://${IDP}/realms/loyaltyhub-members` }),
  };
  return vi.fn(async (url: string) => {
    const key = (Object.keys(PATHS) as (keyof typeof PATHS)[]).find((k) => PATHS[k] === url);
    if (key === undefined) throw new Error(`destinazione non prevista: ${url}`);
    const a = answers[key];
    if (a === "throw") throw new TypeError("rete");
    return a ?? ready[key]();
  });
}

const run = (f: ReturnType<typeof fake>) => probeVetrina(f as unknown as typeof fetch, HOSTS);
const pendingOf = (c: Record<string, string>) => CHECK_KEYS.filter((k) => c[k] !== "ok");

beforeEach(() => {
  vi.restoreAllMocks();
  vi.spyOn(console, "warn").mockImplementation(() => undefined);
});

describe("forwardedHosts e forwardDomain", () => {
  it("deriva i due host dal nome e dal dominio di inoltro (default app.github.dev)", () => {
    expect(forwardDomain({})).toBe("app.github.dev");
    expect(forwardedHosts(NAME, "app.github.dev")).toEqual(HOSTS);
  });

  it("LH_VETRINA_FORWARD_DOMAIN: ammesso solo un sottodominio di github.dev", () => {
    expect(forwardDomain({ LH_VETRINA_FORWARD_DOMAIN: "eu.app.github.dev" })).toBe("eu.app.github.dev");
    for (const bad of ["evil.example.org", "github.dev.evil.org", "app.github.dev/x", "app.github.dev:443", "APP.github.dev", "a b.github.dev", "https://app.github.dev", "x@app.github.dev"]) {
      expect(forwardDomain({ LH_VETRINA_FORWARD_DOMAIN: bad })).toBeNull();
    }
  });

  it("nome o dominio malformati ⇒ nessun host", () => {
    expect(forwardedHosts(NAME, null)).toBeNull();
    for (const name of ["../x", "a/b", "Nome", "x", "-abc", "abc-", "a b", "name?x=1", "name.evil.org", "name@evil"]) {
      expect(forwardedHosts(name, "app.github.dev")).toBeNull();
    }
    expect(forwardedHosts(NAME, "evil.org/x")).toBeNull();
    // Etichetta DNS oltre 63 caratteri.
    expect(forwardedHosts("a".repeat(60), "app.github.dev")).toBeNull();
  });
});

describe("probeVetrina", () => {
  it("vetrina pronta: tutte e nove le risorse ok; fetch solo verso i tre indirizzi attesi", async () => {
    const f = fake();
    const c = await run(f);
    expect(c).toEqual(Object.fromEntries(CHECK_KEYS.map((k) => [k, "ok"])));
    expect(parseChecks(c)).toEqual(c);
    expect(f.mock.calls.map((x) => x[0]).sort()).toEqual([PATHS.status, PATHS.ops, PATHS.members].sort());
  });

  it("richieste: GET, redirect manual, no-store, timeout, nessuna credenziale né intestazione sensibile", async () => {
    const f = fake();
    await run(f);
    for (const call of f.mock.calls as unknown as [string, RequestInit][]) {
      const init = call[1];
      expect(init.method).toBe("GET");
      expect(init.redirect).toBe("manual");
      expect(init.cache).toBe("no-store");
      expect(init.signal).toBeInstanceOf(AbortSignal);
      expect(Object.keys(init.headers as Record<string, string>)).toEqual(["Accept"]);
      expect(init.body).toBeUndefined();
    }
    expect(PROBE_TIMEOUT_MS).toBeLessThanOrEqual(10_000);
  });

  it("host non validi ⇒ nessuna richiesta, solo `codespace` ok", async () => {
    const f = fake();
    const c = await probeVetrina(f as unknown as typeof fetch, null);
    expect(f).not.toHaveBeenCalled();
    expect(pendingOf(c)).toEqual(CHECK_KEYS.filter((k) => k !== "codespace"));
  });

  it("porta privata: 3xx verso il login di GitHub ⇒ porte, web e accesso in attesa, nessun reindirizzamento seguito", async () => {
    const login = () => new Response(null, { status: 302, headers: { location: "https://github.com/login?x=1" } });
    const f = fake({ status: login(), ops: login(), members: login() });
    const c = await run(f);
    expect(c.codespace).toBe("ok");
    expect(pendingOf(c)).toEqual(CHECK_KEYS.filter((k) => k !== "codespace"));
    expect(f).toHaveBeenCalledTimes(3); // nessuna richiesta verso il `location`
    expect(f.mock.calls.every((x) => x[0].startsWith("https://" + NAME))).toBe(true);
  });

  it("basta una porta privata (8001) per lasciare le porte in attesa, ma il web resta ok solo con le porte pubbliche", async () => {
    const f = fake({ ops: new Response(null, { status: 302 }), members: new Response(null, { status: 302 }) });
    const c = await run(f);
    expect(c.ports).toBe("pending");
    expect(c.web).toBe("pending");
  });

  it.each([401, 403, 502, 503])("stato %i dell'inoltro non vale «porta pubblica»", async (status) => {
    const c = await run(fake({ status: new Response("x", { status }) }));
    expect(c.ports).toBe("pending");
  });

  it("Keycloak non risponde ancora (porta 8001 con 502): web, servizi e infrastruttura non ok finché le porte non lo sono", async () => {
    const c = await run(fake({ ops: new Response("bad gateway", { status: 502 }), members: new Response("bad gateway", { status: 502 }) }));
    expect(c.idpOperators).toBe("pending");
    expect(c.idpMembers).toBe("pending");
    expect(c.testMembers).toBe("pending");
  });

  it("realm operatori e membri sono indipendenti", async () => {
    const onlyOps = await run(fake({ members: new Response("x", { status: 404 }) }));
    expect(onlyOps.idpOperators).toBe("ok");
    expect(onlyOps.idpMembers).toBe("pending");
    const onlyMembers = await run(fake({ ops: Response.json({ issuer: "https://x/realms/altro" }) }));
    expect(onlyMembers.idpOperators).toBe("pending");
    expect(onlyMembers.idpMembers).toBe("ok");
  });

  it("discovery senza issuer o non JSON ⇒ in attesa", async () => {
    expect((await run(fake({ ops: Response.json({}) }))).idpOperators).toBe("pending");
    expect((await run(fake({ ops: new Response("<html>", { status: 200 }) }))).idpOperators).toBe("pending");
  });

  it("servizi, Postgres e Kafka letti dallo stato: ciascuno per conto suo", async () => {
    const down = (over: Record<string, unknown>) => fake({ status: Response.json(statusBody(over)) });
    expect((await run(down({ services: [{ code: "a", state: "UP" }, { code: "b", state: "SLEEPING" }] }))).hub).toBe("pending");
    expect((await run(down({ services: [] }))).hub).toBe("pending");
    expect((await run(down({ db: { state: "DOWN" } }))).db).toBe("pending");
    expect((await run(down({ db: { state: "DOWN" } }))).kafka).toBe("ok");
    expect((await run(down({ kafka: { state: "WAKING" } }))).kafka).toBe("pending");
    expect((await run(down({ kafka: { state: "WAKING" } }))).db).toBe("ok");
  });

  it("membri di test: solo `ready` esatto; campo assente (immagine più vecchia) o `pending` ⇒ in attesa", async () => {
    const withFlag = (v: unknown) => fake({ status: Response.json(statusBody({ vetrina: v })) });
    expect((await run(withFlag({ testMembers: "ready" }))).testMembers).toBe("ok");
    for (const v of [{ testMembers: "pending" }, { testMembers: true }, { testMembers: "READY" }, {}, null, "ready"]) {
      expect((await run(withFlag(v))).testMembers).toBe("pending");
    }
    const noField = fake({ status: Response.json({ ...statusBody(), vetrina: undefined }) });
    const c = await run(noField);
    expect(c.testMembers).toBe("pending");
    expect(pendingOf(c)).toEqual(["testMembers"]);
  });

  it("web senza `checkedAt` valido o non JSON ⇒ web in attesa", async () => {
    expect((await run(fake({ status: Response.json({ services: [] }) }))).web).toBe("pending");
    expect((await run(fake({ status: new Response("ok", { status: 200 }) }))).web).toBe("pending");
  });

  it("errori di rete: tutto in attesa, nessuna eccezione e nessun messaggio di errore nel risultato", async () => {
    const c = await run(fake({ status: "throw", ops: "throw", members: "throw" }));
    expect(pendingOf(c)).toEqual(CHECK_KEYS.filter((k) => k !== "codespace"));
    expect(JSON.stringify(c)).not.toContain("rete");
  });

  it("corpo troppo grande ⇒ ignorato", async () => {
    const big = JSON.stringify({ ...statusBody(), pad: "x".repeat(MAX_BODY_BYTES) });
    const c = await run(fake({ status: new Response(big, { status: 200 }) }));
    expect(c.web).toBe("pending");
  });

  it("il risultato porta solo le nove chiavi ok/pending: mai indirizzi, corpi o intestazioni", async () => {
    const c = await run(fake());
    expect(Object.keys(c)).toEqual([...CHECK_KEYS]);
    expect(JSON.stringify(c)).not.toMatch(/github|http|issuer|checkedAt/);
  });
});
