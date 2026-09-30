import { afterEach, describe, expect, it, vi } from "vitest";
import { NAV } from "@/lib/nav";
import { KAFKA_RESTART_DOCS_URL, RECOMMENDED_PATH, enterpriseShowcaseUrl } from "./links";

describe("hub/links (HUB-01)", () => {
  it("il percorso consigliato ha 5 passi nell'ordine della spec", () => {
    expect(RECOMMENDED_PATH.map((s) => s.screen)).toEqual(["BO-29", "BO-24", "PT-01", "BO-06", "BO-30"]);
  });

  it("i passi del backoffice puntano alle rotte del menu", () => {
    const hrefById = new Map(NAV.flatMap((g) => g.items).map((i) => [i.id, i.href]));
    for (const step of RECOMMENDED_PATH.filter((s) => s.screen !== "BO-06" && s.screen.startsWith("BO-"))) {
      expect(step.href).toBe(hrefById.get(step.screen));
    }
    expect(RECOMMENDED_PATH.find((s) => s.screen === "BO-06")?.href).toBe("/backoffice/campaigns/new");
    expect(RECOMMENDED_PATH.find((s) => s.screen === "PT-01")?.href).toBe("/portal");
  });

  it("il riquadro Kafka rimanda a docs/11 §3", () => {
    expect(KAFKA_RESTART_DOCS_URL).toMatch(/docs\/11-DEPLOY-COSTO-ZERO\.md#3-kafka-su-aiven/);
  });
});

describe("hub/links — vetrina Enterprise (HUB-01, F2-DIST-09, ADR-049)", () => {
  const env = (v?: string) => ({ LH_HUB_ENTERPRISE_URL: v });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.resetModules();
  });

  it("assente, vuota o di soli spazi ⇒ null, senza avviso", () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    expect(enterpriseShowcaseUrl({})).toBeNull();
    expect(enterpriseShowcaseUrl(env(""))).toBeNull();
    expect(enterpriseShowcaseUrl(env("   "))).toBeNull();
    expect(warn).not.toHaveBeenCalled();
  });

  it.each([
    ["https://showcase.example.org", "https://showcase.example.org"],
    ["https://showcase.example.org/", "https://showcase.example.org"],
    ["  https://showcase.example.org  ", "https://showcase.example.org"],
    ["HTTPS://Showcase.Example.ORG", "https://showcase.example.org"],
    ["https://showcase.example.org:8443", "https://showcase.example.org:8443"],
    ["https://showcase.example.org:443", "https://showcase.example.org"],
  ])("origine valida %s ⇒ %s", (raw, expected) => {
    expect(enterpriseShowcaseUrl(env(raw))).toBe(expected);
  });

  it.each([
    ["http://showcase.example.org", "schema"],
    ["ftp://showcase.example.org", "schema"],
    ["https://showcase.example.org/enterprise", "percorso"],
    ["https://showcase.example.org/a/", "percorso"],
    ["https://showcase.example.org?x=1", "query_o_fragment"],
    ["https://showcase.example.org/?", "query_o_fragment"],
    ["https://showcase.example.org#top", "query_o_fragment"],
    ["https://user:pw@showcase.example.org", "credenziali"],
    ["https://user@showcase.example.org", "credenziali"],
    ["showcase.example.org", "non_url"],
    ["https://", "non_url"],
    ["https:showcase.example.org", "non_url"],
    ["https://@showcase.example.org", "credenziali"],
    ["https://:@showcase.example.org", "credenziali"],
    ["https://showcase.example.org\\", "non_url"],
    ["https://showcase.example.org/./", "percorso"],
    ["https://showcase.example.org/%2e", "percorso"],
    ["https://show\ncase.example.org", "non_url"],
    ["https://show case.example.org", "non_url"],
  ])("valore non valido %s ⇒ null (%s)", async (raw, problem) => {
    // L'avviso è uno per processo: ogni caso parte da un modulo nuovo.
    vi.resetModules();
    const fresh = await import("./links");
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    expect(fresh.enterpriseShowcaseUrl(env(raw))).toBeNull();
    expect(warn).toHaveBeenCalledTimes(1);
    expect(String(warn.mock.calls[0][0])).toContain(`(${problem})`);
  });

  it("l'avviso non contiene mai il valore (credenziali comprese) e si scrive una sola volta", async () => {
    vi.resetModules();
    const fresh = await import("./links");
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    expect(fresh.enterpriseShowcaseUrl(env("https://alice:s3cret-pw@showcase.example.org"))).toBeNull();
    expect(fresh.enterpriseShowcaseUrl(env("http://showcase.example.org"))).toBeNull();
    expect(warn).toHaveBeenCalledTimes(1);
    const msg = String(warn.mock.calls[0][0]);
    expect(msg).not.toContain("s3cret-pw");
    expect(msg).not.toContain("alice");
    expect(msg).not.toContain("showcase.example.org");
  });
});
