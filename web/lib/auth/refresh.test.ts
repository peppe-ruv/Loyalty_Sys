// @vitest-environment node
import { describe, expect, it, vi } from "vitest";
import { RefreshRejectedError, type OidcClient, type TokenSet } from "./oidc";
import { freshSession, IdpUnavailableError, REFRESH_SKEW_SECONDS } from "./refresh";
import { InMemorySessionStore, type Session, type SessionUser } from "./sessionStore";

// Rinnovo trasparente con rotazione del refresh token e single-flight per sessione.

const NOW = 2_000_000;
const USER: SessionUser = { sub: "u1", sid: "s1", username: "paolo.care", name: null, roles: ["CARE"], role: "CARE", kind: "operator" };

function setup(refresh: OidcClient["refresh"], accessExpiresAt = NOW + 10) {
  const store = new InMemorySessionStore({
    masterKey: Buffer.from(Array.from({ length: 32 }, (_, i) => i + 7)),
    idleSeconds: 1800,
    maxSeconds: 36000,
    maxSessions: 10,
    now: () => NOW,
  });
  const oidc = { refresh: vi.fn(refresh) } as unknown as OidcClient & { refresh: ReturnType<typeof vi.fn> };
  const inflight = new Map<string, Promise<Session | null>>();
  const deps = { store, oidc, inflight, now: () => NOW };
  const create = () => store.create(USER, { accessToken: "AT-0", accessExpiresAt, refreshToken: "RT-0", idToken: "ID-0" });
  return { store, oidc, inflight, deps, create };
}

const renewed = (n: number, extra: Partial<TokenSet> = {}): TokenSet => ({
  accessToken: `AT-${n}`,
  accessExpiresAt: NOW + 300,
  refreshToken: `RT-${n}`,
  idToken: null,
  claims: null,
  ...extra,
});

describe("freshSession", () => {
  it("token ancora valido oltre il margine: nessun rinnovo", async () => {
    const { oidc, deps, create } = setup(async () => renewed(1), NOW + REFRESH_SKEW_SECONDS + 1);
    const id = await create();
    expect((await freshSession(id, deps))?.tokens.accessToken).toBe("AT-0");
    expect(oidc.refresh).not.toHaveBeenCalled();
  });

  it("token in scadenza: rinnovo, refresh token ruotato salvato, id_token precedente conservato", async () => {
    const { store, oidc, deps, create } = setup(async () => renewed(1));
    const id = await create();
    const session = await freshSession(id, deps);
    expect(oidc.refresh).toHaveBeenCalledWith("RT-0");
    expect(session?.tokens).toEqual({ accessToken: "AT-1", accessExpiresAt: NOW + 300, refreshToken: "RT-1", idToken: "ID-0" });
    expect((await store.get(id))?.tokens.refreshToken).toBe("RT-1");
  });

  it("single-flight: dieci richieste parallele, un solo rinnovo, stesso risultato per tutte", async () => {
    let release!: () => void;
    const gate = new Promise<void>((resolve) => (release = resolve));
    const { oidc, inflight, deps, create } = setup(async () => {
      await gate;
      return renewed(1);
    });
    const id = await create();
    const pending = Array.from({ length: 10 }, () => freshSession(id, deps));
    await vi.waitFor(() => expect(inflight.size).toBe(1));
    release();
    const results = await Promise.all(pending);
    expect(oidc.refresh).toHaveBeenCalledTimes(1);
    expect(new Set(results.map((s) => s?.tokens.accessToken))).toEqual(new Set(["AT-1"]));
    expect(inflight.size).toBe(0);
  });

  it("sessioni diverse si rinnovano in modo indipendente", async () => {
    let n = 0;
    const { oidc, deps, create } = setup(async () => renewed(++n));
    const [a, b] = [await create(), await create()];
    await Promise.all([freshSession(a, deps), freshSession(b, deps)]);
    expect(oidc.refresh).toHaveBeenCalledTimes(2);
  });

  it("IdP senza nuovo refresh token: resta quello di prima", async () => {
    const { deps, create } = setup(async () => renewed(1, { refreshToken: null }));
    const id = await create();
    expect((await freshSession(id, deps))?.tokens.refreshToken).toBe("RT-0");
  });

  it("refresh token rifiutato (revocato, già usato): sessione chiusa, null", async () => {
    const { store, deps, create } = setup(async () => {
      throw new RefreshRejectedError();
    });
    const id = await create();
    expect(await freshSession(id, deps)).toBeNull();
    expect(await store.get(id)).toBeNull();
  });

  it("IdP irraggiungibile: IdpUnavailableError e la sessione resta per il tentativo dopo", async () => {
    const { store, inflight, deps, create } = setup(async () => {
      throw new TypeError("fetch failed");
    });
    const id = await create();
    await expect(freshSession(id, deps)).rejects.toBeInstanceOf(IdpUnavailableError);
    expect(await store.get(id)).not.toBeNull();
    expect(inflight.size).toBe(0);
  });

  it("sessione chiusa durante il rinnovo (logout, back-channel): null, niente resurrezione", async () => {
    let release!: () => void;
    const gate = new Promise<void>((resolve) => (release = resolve));
    const { store, deps, create } = setup(async () => {
      await gate;
      return renewed(1);
    });
    const id = await create();
    const pending = freshSession(id, deps);
    await store.delete(id);
    release();
    expect(await pending).toBeNull();
    expect(await store.get(id)).toBeNull();
  });

  it("senza refresh token: sessione chiusa", async () => {
    const { store, deps } = setup(async () => renewed(1));
    const id = await store.create(USER, { accessToken: "AT", accessExpiresAt: NOW, refreshToken: null, idToken: "ID" });
    expect(await freshSession(id, deps)).toBeNull();
    expect(await store.get(id)).toBeNull();
  });

  it("sessione sconosciuta: null senza chiamare l'IdP", async () => {
    const { oidc, deps } = setup(async () => renewed(1));
    expect(await freshSession("inventato", deps)).toBeNull();
    expect(oidc.refresh).not.toHaveBeenCalled();
  });
});
