// @vitest-environment node
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { DEFAULT_STATE_DIR, MASTER_CONSOLE_MARKER, MEMBERS_MARKER, readMasterConsole, readMasterConsoleState, readVetrinaFlags, stateDir } from "./vetrinaMarkers";

// HUB-01, Q-728: marcatore dei membri di test registrati, letto da /api/demo/status solo nell'ambiente di test.

const BASE = {
  LH_PROFILE: "enterprise",
  LH_OIDC_ISSUER: "https://idp.lh.test/realms/loyaltyhub",
  LH_WEB_CLIENT_SECRET: "s3cr3t-generato-dall-idp-0123456789",
  LH_WEB_URL: "https://loyalty.lh.test",
  LH_WEB_SESSION_KEY: Buffer.from(Array.from({ length: 32 }, (_, i) => i + 11)).toString("base64"),
  LH_OIDC_MEMBER_ISSUER: "https://idp2.lh.test/realms/loyaltyhub-members",
  LH_WEB_MEMBER_CLIENT_SECRET: "s3cr3t-del-portale-0123456789",
};
const TEST = { ...BASE, LH_TEST_USERS_ALLOWED: "true", LH_ENVIRONMENT: "test" };

let dir: string;
beforeEach(() => {
  dir = mkdtempSync(join(tmpdir(), "lh-marker-"));
  vi.spyOn(console, "warn").mockImplementation(() => undefined);
});
afterEach(() => {
  rmSync(dir, { recursive: true, force: true });
  vi.restoreAllMocks();
});

describe("stateDir", () => {
  it("default /run/lh-vetrina; solo percorsi assoluti", () => {
    expect(stateDir({})).toBe(DEFAULT_STATE_DIR);
    expect(stateDir({ LH_VETRINA_STATE_DIR: "relativo/x" })).toBe(DEFAULT_STATE_DIR);
    expect(stateDir({ LH_VETRINA_STATE_DIR: "/tmp/x" })).toBe("/tmp/x");
  });
});

describe("readVetrinaFlags", () => {
  it("fuori dall'ambiente di test il campo non esiste (demo, enterprise senza le due variabili, solo una delle due)", async () => {
    writeFileSync(join(dir, MEMBERS_MARKER), "ready\n");
    const at = { LH_VETRINA_STATE_DIR: dir };
    expect(await readVetrinaFlags({ ...at })).toBeNull();
    expect(await readVetrinaFlags({ ...at, ...BASE })).toBeNull();
    expect(await readVetrinaFlags({ ...at, ...BASE, LH_TEST_USERS_ALLOWED: "true" })).toBeNull();
    expect(await readVetrinaFlags({ ...at, ...BASE, LH_ENVIRONMENT: "test" })).toBeNull();
    expect(await readVetrinaFlags({ ...at, LH_TEST_USERS_ALLOWED: "true", LH_ENVIRONMENT: "test" })).toBeNull();
  });

  it("marcatore con `ready` ⇒ ready", async () => {
    writeFileSync(join(dir, MEMBERS_MARKER), "ready\n");
    expect(await readVetrinaFlags({ ...TEST, LH_VETRINA_STATE_DIR: dir })).toEqual({ testMembers: "ready" });
  });

  it("marcatore assente, cartella assente o contenuto diverso ⇒ pending", async () => {
    const env = { ...TEST, LH_VETRINA_STATE_DIR: dir };
    expect(await readVetrinaFlags(env)).toEqual({ testMembers: "pending" });
    expect(await readVetrinaFlags({ ...env, LH_VETRINA_STATE_DIR: join(dir, "non-esiste") })).toEqual({ testMembers: "pending" });
    for (const content of ["", "pending", "READY", "ready ma anche altro testo oltre il limite"]) {
      writeFileSync(join(dir, MEMBERS_MARKER), content);
      expect(await readVetrinaFlags(env)).toEqual({ testMembers: "pending" });
    }
  });

  it("un marcatore che è una cartella o non leggibile ⇒ pending, senza eccezioni", async () => {
    mkdirSync(join(dir, MEMBERS_MARKER));
    expect(await readVetrinaFlags({ ...TEST, LH_VETRINA_STATE_DIR: dir })).toEqual({ testMembers: "pending" });
  });

  it("il campo porta solo `ready` o `pending`: nessun altro dato", async () => {
    writeFileSync(join(dir, MEMBERS_MARKER), "ready\n");
    const flags = await readVetrinaFlags({ ...TEST, LH_VETRINA_STATE_DIR: dir });
    expect(Object.keys(flags ?? {})).toEqual(["testMembers"]);
  });
});

describe("readMasterConsoleState (ADR-055, Q-727)", () => {
  it("file assente o cartella assente ⇒ null", async () => {
    expect(await readMasterConsoleState({ LH_VETRINA_STATE_DIR: dir })).toBeNull();
    expect(await readMasterConsoleState({ LH_VETRINA_STATE_DIR: join(dir, "non-esiste") })).toBeNull();
  });

  it("`aperta` ⇒ open, `chiusa` ⇒ closed", async () => {
    writeFileSync(join(dir, MASTER_CONSOLE_MARKER), "aperta\n");
    expect(await readMasterConsoleState({ LH_VETRINA_STATE_DIR: dir })).toBe("open");
    writeFileSync(join(dir, MASTER_CONSOLE_MARKER), "chiusa\n");
    expect(await readMasterConsoleState({ LH_VETRINA_STATE_DIR: dir })).toBe("closed");
  });

  it("contenuto diverso, vuoto o troppo lungo ⇒ closed (prudente)", async () => {
    for (const content of ["", "open", "APERTA", "aperta ma anche altro testo oltre il limite"]) {
      writeFileSync(join(dir, MASTER_CONSOLE_MARKER), content);
      expect(await readMasterConsoleState({ LH_VETRINA_STATE_DIR: dir }), content).toBe("closed");
    }
  });

  it("un marcatore che è una cartella ⇒ null, senza eccezioni", async () => {
    mkdirSync(join(dir, MASTER_CONSOLE_MARKER));
    expect(await readMasterConsoleState({ LH_VETRINA_STATE_DIR: dir })).toBeNull();
  });
});

describe("readMasterConsole", () => {
  it("fuori dall'ambiente di test ⇒ null anche con il marcatore", async () => {
    writeFileSync(join(dir, MASTER_CONSOLE_MARKER), "aperta\n");
    expect(await readMasterConsole({ LH_VETRINA_STATE_DIR: dir, ...BASE })).toBeNull();
  });

  it("nell'ambiente di test: stato + indirizzo della console master sull'origine dell'IdP degli operatori", async () => {
    writeFileSync(join(dir, MASTER_CONSOLE_MARKER), "chiusa\n");
    expect(await readMasterConsole({ ...TEST, LH_VETRINA_STATE_DIR: dir })).toEqual({
      state: "closed",
      url: "https://idp.lh.test/admin/master/console/",
    });
  });

  it("senza marcatore ⇒ null (nessuna scheda)", async () => {
    expect(await readMasterConsole({ ...TEST, LH_VETRINA_STATE_DIR: dir })).toBeNull();
  });
});
