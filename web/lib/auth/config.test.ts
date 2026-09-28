// @vitest-environment node
import { describe, expect, it, vi } from "vitest";
import { getAuthConfig, InsecureConfigError, isEnterprise, parseAuthConfig, type Env } from "./config";
import { checkAuthConfigAtStartup } from "./startup";

// Profilo del BFF (ADR-027, regola 22): demo invariato senza variabili OIDC; enterprise solo con configurazione
// completa e sicura, altrimenti INSECURE_CONFIG con tutti i problemi elencati, mai un ripiego sul demo.

const KEY = Buffer.from(Array.from({ length: 32 }, (_, i) => i + 1)).toString("base64");

const ENTERPRISE: Env = {
  LH_PROFILE: "enterprise",
  LH_OIDC_ISSUER: "https://idp.lh.test/realms/loyaltyhub",
  LH_WEB_CLIENT_SECRET: "s3cr3t-generato-dall-idp-0123456789",
  LH_WEB_URL: "https://loyalty.lh.test",
  LH_WEB_SESSION_KEY: KEY,
};

function problemsOf(env: Env, readFile?: (p: string) => string): string[] {
  try {
    parseAuthConfig(env, readFile);
  } catch (err) {
    expect(err).toBeInstanceOf(InsecureConfigError);
    expect((err as InsecureConfigError).code).toBe("INSECURE_CONFIG");
    return [...(err as InsecureConfigError).problems];
  }
  throw new Error("configurazione accettata");
}

describe("profilo demo", () => {
  it("senza LH_PROFILE è demo, anche con variabili OIDC presenti", () => {
    expect(parseAuthConfig({})).toEqual({ mode: "demo" });
    expect(parseAuthConfig({ ...ENTERPRISE, LH_PROFILE: undefined })).toEqual({ mode: "demo" });
    expect(parseAuthConfig({ LH_PROFILE: "demo" })).toEqual({ mode: "demo" });
    expect(isEnterprise({})).toBe(false);
  });

  it("un LH_PROFILE sconosciuto è un errore e conta come enterprise (nessun percorso demo aperto per sbaglio)", () => {
    expect(problemsOf({ LH_PROFILE: "prod" })).toEqual(["LH_PROFILE deve essere 'demo' o 'enterprise' (trovato 'prod')"]);
    expect(isEnterprise({ LH_PROFILE: "prod" })).toBe(true);
  });
});

describe("profilo enterprise", () => {
  it("configurazione completa: valori letti, client `web` e durate di Q-354 per default", () => {
    const cfg = parseAuthConfig(ENTERPRISE);
    expect(cfg.mode).toBe("enterprise");
    if (cfg.mode !== "enterprise") return;
    expect(cfg.issuer.href).toBe("https://idp.lh.test/realms/loyaltyhub");
    expect(cfg.publicUrl.origin).toBe("https://loyalty.lh.test");
    expect(cfg.clientId).toBe("web");
    expect(cfg.sessionKey).toHaveLength(32);
    expect(cfg).toMatchObject({ idleSeconds: 1800, maxSeconds: 36000, maxSessions: 10000, allowInsecureIssuer: false });
  });

  it("nulla configurato: tutti i problemi insieme", () => {
    expect(problemsOf({ LH_PROFILE: "enterprise" })).toEqual([
      "LH_OIDC_ISSUER mancante",
      "LH_WEB_URL mancante",
      "LH_WEB_CLIENT_SECRET mancante (oppure LH_WEB_CLIENT_SECRET_FILE)",
      "LH_WEB_SESSION_KEY mancante (oppure LH_WEB_SESSION_KEY_FILE)",
    ]);
  });

  it.each([
    ["http verso un host di rete", { LH_OIDC_ISSUER: "http://idp:8080/realms/loyaltyhub" }, "LH_OIDC_ISSUER deve usare https (http solo verso localhost)"],
    ["origine web in http", { LH_WEB_URL: "http://loyalty.lh.test" }, "LH_WEB_URL deve usare https (http solo per localhost)"],
    ["origine web con percorso", { LH_WEB_URL: "https://loyalty.lh.test/app" }, "LH_WEB_URL deve essere un'origine senza percorso (es. https://loyalty.example.org)"],
    ["credenziali nell'URL", { LH_OIDC_ISSUER: "https://u:p@idp.lh.test/realms/x" }, "LH_OIDC_ISSUER non deve contenere credenziali"],
    ["segreto corto", { LH_WEB_CLIENT_SECRET: "corto" }, "LH_WEB_CLIENT_SECRET troppo corto (almeno 16 caratteri)"],
    ["segnaposto non sostituito", { LH_WEB_CLIENT_SECRET: "${LH_WEB_CLIENT_SECRET}" }, "LH_WEB_CLIENT_SECRET è un valore d'esempio o un segnaposto"],
    ["chiave di 16 byte", { LH_WEB_SESSION_KEY: Buffer.alloc(16, 7).toString("base64") }, "LH_WEB_SESSION_KEY deve valere 32 byte (trovati 16)"],
    ["chiave non casuale", { LH_WEB_SESSION_KEY: Buffer.alloc(32, 0).toString("base64") }, "LH_WEB_SESSION_KEY non è casuale"],
    ["chiave non base64", { LH_WEB_SESSION_KEY: "non è base64!" }, "LH_WEB_SESSION_KEY deve essere base64 di 32 byte (openssl rand -base64 32)"],
    ["inattività oltre il massimo", { LH_WEB_SESSION_IDLE_SECONDS: "7200", LH_WEB_SESSION_MAX_SECONDS: "3600" }, "LH_WEB_SESSION_IDLE_SECONDS non può superare LH_WEB_SESSION_MAX_SECONDS"],
    ["durata non numerica", { LH_WEB_SESSION_MAX_SECONDS: "10h" }, "LH_WEB_SESSION_MAX_SECONDS deve essere un intero tra 300 e 604800"],
  ])("rifiuta %s", (_, override, expected) => {
    expect(problemsOf({ ...ENTERPRISE, ...override })).toContain(expected);
  });

  it("http ammesso solo su loopback (sviluppo locale), e lo segnala al client OIDC", () => {
    const cfg = parseAuthConfig({ ...ENTERPRISE, LH_OIDC_ISSUER: "http://localhost:8089/realms/loyaltyhub", LH_WEB_URL: "http://localhost:3000" });
    expect(cfg).toMatchObject({ mode: "enterprise", allowInsecureIssuer: true });
  });

  it("segreti da *_FILE; la variabile diretta vince sul file", () => {
    const files: Record<string, string> = { "/run/secrets/web": "segreto-dal-file-0123456789\n", "/run/secrets/key": `${KEY}\n` };
    const readFile = (p: string) => files[p] ?? (() => { throw new Error("ENOENT"); })();
    const fromFiles = parseAuthConfig(
      { ...ENTERPRISE, LH_WEB_CLIENT_SECRET: undefined, LH_WEB_CLIENT_SECRET_FILE: "/run/secrets/web", LH_WEB_SESSION_KEY: undefined, LH_WEB_SESSION_KEY_FILE: "/run/secrets/key" },
      readFile,
    );
    expect(fromFiles).toMatchObject({ clientSecret: "segreto-dal-file-0123456789" });
    const direct = parseAuthConfig({ ...ENTERPRISE, LH_WEB_CLIENT_SECRET_FILE: "/run/secrets/web" }, readFile);
    expect(direct).toMatchObject({ clientSecret: ENTERPRISE.LH_WEB_CLIENT_SECRET });
    expect(problemsOf({ ...ENTERPRISE, LH_WEB_CLIENT_SECRET: undefined, LH_WEB_CLIENT_SECRET_FILE: "/manca" }, readFile)).toContain(
      "LH_WEB_CLIENT_SECRET_FILE non leggibile",
    );
  });

  it("getAuthConfig ricalcola solo quando cambiano le variabili e ripete lo stesso errore", () => {
    const a = getAuthConfig(ENTERPRISE);
    expect(getAuthConfig({ ...ENTERPRISE })).toBe(a);
    expect(getAuthConfig({ ...ENTERPRISE, LH_WEB_CLIENT_ID: "web2" })).not.toBe(a);
    expect(() => getAuthConfig({ LH_PROFILE: "enterprise" })).toThrow(InsecureConfigError);
    expect(() => getAuthConfig({ LH_PROFILE: "enterprise" })).toThrow(/INSECURE_CONFIG/);
  });
});

describe("avvio del server (instrumentation.ts)", () => {
  it("enterprise mal configurato: il processo esce con 1 e il motivo nel log", () => {
    const error = vi.spyOn(console, "error").mockImplementation(() => undefined);
    const exit = vi.fn() as unknown as (code: number) => never;
    checkAuthConfigAtStartup({ LH_PROFILE: "enterprise", LH_OIDC_ISSUER: "https://idp.lh.test/r" }, exit);
    expect(exit).toHaveBeenCalledWith(1);
    expect(error.mock.calls[0][0]).toMatch(/INSECURE_CONFIG.*LH_WEB_URL mancante/);
    error.mockRestore();
  });

  it("demo e enterprise valido: nessuna uscita, e nessun segreto nel log", () => {
    const info = vi.spyOn(console, "info").mockImplementation(() => undefined);
    const exit = vi.fn() as unknown as (code: number) => never;
    checkAuthConfigAtStartup({}, exit);
    checkAuthConfigAtStartup(ENTERPRISE, exit);
    expect(exit).not.toHaveBeenCalled();
    const logged = info.mock.calls.flat().join(" ");
    expect(logged).not.toContain(ENTERPRISE.LH_WEB_CLIENT_SECRET);
    expect(logged).not.toContain(KEY);
    info.mockRestore();
  });
});
