// @vitest-environment node
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

// HUB-01, Q-728: `/api/demo/status` aggiunge `vetrina.testMembers` SOLO nell'ambiente di test della vetrina.

const ENTERPRISE = {
  LH_PROFILE: "enterprise",
  LH_OIDC_ISSUER: "https://idp.lh.test/realms/loyaltyhub",
  LH_WEB_CLIENT_SECRET: "s3cr3t-generato-dall-idp-0123456789",
  LH_WEB_URL: "https://loyalty.lh.test",
  LH_WEB_SESSION_KEY: Buffer.from(Array.from({ length: 32 }, (_, i) => i + 11)).toString("base64"),
  LH_OIDC_MEMBER_ISSUER: "https://idp2.lh.test/realms/loyaltyhub-members",
  LH_WEB_MEMBER_CLIENT_SECRET: "s3cr3t-del-portale-0123456789",
};
const KEYS = [...Object.keys(ENTERPRISE), "LH_TEST_USERS_ALLOWED", "LH_ENVIRONMENT", "LH_VETRINA_STATE_DIR"];

let dir: string;
beforeEach(() => {
  dir = mkdtempSync(join(tmpdir(), "lh-status-"));
  for (const k of KEYS) vi.stubEnv(k, "");
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string) =>
      String(url).endsWith("/actuator/health")
        ? Response.json({ status: "UP", components: { db: { status: "UP" }, kafka: { status: "UP" } } })
        : new Response("{}", { status: 200 }),
    ),
  );
  vi.resetModules();
});
afterEach(() => {
  rmSync(dir, { recursive: true, force: true });
  vi.unstubAllEnvs();
  vi.unstubAllGlobals();
});

async function status() {
  const { GET } = await import("./route");
  return (await GET()).json();
}

describe("GET /api/demo/status", () => {
  it("demo: nessun campo `vetrina`", async () => {
    expect(await status()).not.toHaveProperty("vetrina");
  });

  it("enterprise senza ambiente di test: nessun campo `vetrina` anche con il marcatore", async () => {
    writeFileSync(join(dir, "membri-di-test"), "ready\n");
    for (const [k, v] of Object.entries(ENTERPRISE)) vi.stubEnv(k, v);
    vi.stubEnv("LH_VETRINA_STATE_DIR", dir);
    expect(await status()).not.toHaveProperty("vetrina");
  });

  it("ambiente di test: `pending` senza marcatore, `ready` con il marcatore (anche dentro la cache di 2 s)", async () => {
    for (const [k, v] of Object.entries(ENTERPRISE)) vi.stubEnv(k, v);
    vi.stubEnv("LH_TEST_USERS_ALLOWED", "true");
    vi.stubEnv("LH_ENVIRONMENT", "test");
    vi.stubEnv("LH_VETRINA_STATE_DIR", dir);
    expect((await status()).vetrina).toEqual({ testMembers: "pending" });
    const { GET } = await import("./route");
    writeFileSync(join(dir, "membri-di-test"), "ready\n");
    const body = await (await GET()).json();
    expect(body.vetrina).toEqual({ testMembers: "ready" });
    expect(typeof body.checkedAt).toBe("string");
    expect(JSON.stringify(body)).not.toMatch(/@|password|Anna|Marco|Giulia/);
  });
});
