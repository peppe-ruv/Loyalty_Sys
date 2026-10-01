import { describe, expect, it, vi } from "vitest";
import { cachedEnterpriseStatus, notUp, readEnterpriseStatus, statusView, tileState } from "./enterpriseStatus";

// HUB-02 (ADR-049, F2-DIST-09): tessere hub/web/idp/cms e Postgres/Kafka dalle sonde lato server.

const HUB = "http://hub.internal:8080";
const ISSUER = "https://idp.example.org/realms/loyaltyhub";

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}

const healthy = { status: "UP", components: { db: { status: "UP" }, kafka: { status: "UP" } } };

function fakeFetch(routes: Record<string, () => Response | Promise<Response>>) {
  return vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    const route = routes[url];
    if (!route) throw new TypeError("fetch failed");
    return route();
  }) as unknown as typeof fetch;
}

const okRoutes = {
  [`${HUB}/actuator/health`]: () => json(healthy),
  [`${ISSUER}/.well-known/openid-configuration`]: () => json({ issuer: ISSUER }),
};

describe("readEnterpriseStatus", () => {
  it("tutto attivo: 4 tessere di ruolo e 2 d'infrastruttura, cms non installato, vista ok", async () => {
    const status = await readEnterpriseStatus({ hubUrl: HUB, issuer: ISSUER, fetchImpl: fakeFetch(okRoutes) });
    expect(status.tiles.map((t) => [t.key, t.group, t.state])).toEqual([
      ["hub", "role", "UP"],
      ["web", "role", "UP"],
      ["idp", "role", "UP"],
      ["cms", "role", "NOT_INSTALLED"],
      ["db", "infra", "UP"],
      ["kafka", "infra", "UP"],
    ]);
    expect(statusView(status)).toBe("ok");
    expect(notUp(status)).toEqual([]);
  });

  it("hub in avvio (503) con Kafka giù: hub e Kafka non attivi, Postgres attivo, vista degraded", async () => {
    const fetchImpl = fakeFetch({
      ...okRoutes,
      [`${HUB}/actuator/health`]: () =>
        json({ status: "DOWN", components: { db: { status: "UP" }, kafka: { status: "DOWN" } } }, 503),
    });
    const status = await readEnterpriseStatus({ hubUrl: `${HUB}/`, issuer: ISSUER, fetchImpl });
    expect(tileState(status, "hub")).toBe("DOWN");
    expect(tileState(status, "db")).toBe("UP");
    expect(tileState(status, "kafka")).toBe("DOWN");
    expect(statusView(status)).toBe("degraded");
    expect(notUp(status)).toEqual(["hub", "kafka"]);
  });

  it("hub irraggiungibile: Postgres e Kafka non verificabili, vista error", async () => {
    const fetchImpl = fakeFetch({ [`${ISSUER}/.well-known/openid-configuration`]: () => json({ issuer: ISSUER }) });
    const status = await readEnterpriseStatus({ hubUrl: HUB, issuer: ISSUER, fetchImpl });
    expect(tileState(status, "hub")).toBe("DOWN");
    expect(tileState(status, "db")).toBe("UNKNOWN");
    expect(tileState(status, "kafka")).toBe("UNKNOWN");
    expect(tileState(status, "idp")).toBe("UP");
    expect(statusView(status)).toBe("error");
  });

  it.each([
    ["discovery in errore", () => json({}, 500)],
    ["discovery senza issuer", () => json({ foo: 1 })],
    ["discovery non JSON", () => new Response("<html>", { status: 200 })],
  ])("IdP non attivo con %s ⇒ degraded", async (_name, discovery) => {
    const fetchImpl = fakeFetch({ ...okRoutes, [`${ISSUER}/.well-known/openid-configuration`]: discovery });
    const status = await readEnterpriseStatus({ hubUrl: HUB, issuer: ISSUER, fetchImpl });
    expect(tileState(status, "idp")).toBe("DOWN");
    expect(statusView(status)).toBe("degraded");
  });

  it("senza emittente configurato l'IdP non si sonda ed è giù", async () => {
    const fetchImpl = fakeFetch(okRoutes);
    const status = await readEnterpriseStatus({ hubUrl: HUB, issuer: null, fetchImpl });
    expect(tileState(status, "idp")).toBe("DOWN");
    expect(fetchImpl).toHaveBeenCalledTimes(1);
  });

  it("sonda scaduta (timeout) ⇒ giù, senza eccezioni", async () => {
    const hang = vi.fn(
      (_input: RequestInfo | URL, init?: RequestInit) =>
        new Promise<Response>((_resolve, reject) => {
          init?.signal?.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError")));
        }),
    ) as unknown as typeof fetch;
    const status = await readEnterpriseStatus({ hubUrl: HUB, issuer: ISSUER, fetchImpl: hang, timeoutMs: 5 });
    expect(statusView(status)).toBe("error");
    expect(tileState(status, "idp")).toBe("DOWN");
  });

  it("lo stato non contiene URL dell'hub né dell'emittente (nulla di configurazione arriva alla pagina)", async () => {
    const status = await readEnterpriseStatus({ hubUrl: HUB, issuer: ISSUER, fetchImpl: fakeFetch(okRoutes) });
    const text = JSON.stringify(status);
    expect(text).not.toContain("hub.internal");
    expect(text).not.toContain("idp.example.org");
  });
});

describe("cachedEnterpriseStatus", () => {
  it("riusa lo stato per 3 s, poi sonda di nuovo", async () => {
    let now = 1_000_000;
    const fetchImpl = fakeFetch(okRoutes);
    const opts = { hubUrl: `${HUB}/cache`, issuer: ISSUER, fetchImpl, now: () => now };
    await cachedEnterpriseStatus(opts);
    await cachedEnterpriseStatus(opts);
    expect(fetchImpl).toHaveBeenCalledTimes(2); // una sonda per hub e una per l'IdP
    now += 3_001;
    await cachedEnterpriseStatus(opts);
    expect(fetchImpl).toHaveBeenCalledTimes(4);
  });
});
