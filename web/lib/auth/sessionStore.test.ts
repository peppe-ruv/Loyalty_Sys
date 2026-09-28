// @vitest-environment node
import { inspect } from "node:util";
import { describe, expect, it } from "vitest";
import { InMemorySessionStore, type SessionTokens, type SessionUser } from "./sessionStore";

// Store delle sessioni del BFF: token cifrati in memoria, id opachi, inattività e durata massima (Q-354),
// tetto di sessioni, chiusura per sid/sub (back-channel logout).

const MASTER = Buffer.from(Array.from({ length: 32 }, (_, i) => i * 3 + 1));

function user(overrides: Partial<SessionUser> = {}): SessionUser {
  return { sub: "sub-1", sid: "sid-1", username: "marta.admin", name: "Marta Villa", roles: ["ADMIN"], role: "ADMIN", kind: "operator", ...overrides };
}

const TOKENS: SessionTokens = {
  accessToken: "ACCESS-TOKEN-SEGRETO",
  accessExpiresAt: 1_000_300,
  refreshToken: "REFRESH-TOKEN-SEGRETO",
  idToken: "ID-TOKEN-SEGRETO",
};

function setup(options: { idle?: number; max?: number; count?: number } = {}) {
  let now = 1_000_000;
  const store = new InMemorySessionStore({
    masterKey: MASTER,
    idleSeconds: options.idle ?? 1800,
    maxSeconds: options.max ?? 36000,
    maxSessions: options.count ?? 100,
    now: () => now,
  });
  return { store, advance: (s: number) => (now += s) };
}

describe("InMemorySessionStore", () => {
  it("crea e rilegge una sessione; id sconosciuto ⇒ null", async () => {
    const { store } = setup();
    const id = await store.create(user(), TOKENS);
    const session = await store.get(id);
    expect(session?.user.username).toBe("marta.admin");
    expect(session?.tokens).toEqual(TOKENS);
    expect(await store.get("sconosciuto")).toBeNull();
  });

  it("in memoria né token né id del cookie in chiaro", async () => {
    const { store } = setup();
    const id = await store.create(user(), TOKENS);
    const dump = inspect(store, { depth: 10, showHidden: true });
    expect(dump).not.toContain("ACCESS-TOKEN-SEGRETO");
    expect(dump).not.toContain("REFRESH-TOKEN-SEGRETO");
    expect(dump).not.toContain("ID-TOKEN-SEGRETO");
    expect(dump).not.toContain(id);
  });

  it("blob alterato in memoria ⇒ sessione scartata", async () => {
    const { store } = setup();
    const id = await store.create(user(), TOKENS);
    const entries = (store as unknown as { entries: Map<string, { blob: string }> }).entries;
    const entry = [...entries.values()][0];
    const raw = Buffer.from(entry.blob, "base64url");
    raw[raw.length - 1] ^= 0xff;
    entry.blob = raw.toString("base64url");
    expect(await store.get(id)).toBeNull();
    expect(store.size).toBe(0);
  });

  it("blob spostato su un'altra sessione (AAD = impronta dell'id): non si apre", async () => {
    const { store } = setup();
    const a = await store.create(user({ sub: "a", username: "a" }), { ...TOKENS, accessToken: "AT-A" });
    const b = await store.create(user({ sub: "b", username: "b" }), { ...TOKENS, accessToken: "AT-B" });
    const entries = [...(store as unknown as { entries: Map<string, { blob: string }> }).entries.values()];
    // Se l'AAD fosse ignorato, la sessione b restituirebbe l'identità e i token di a.
    entries[1].blob = entries[0].blob;
    expect(await store.get(b)).toBeNull();
    expect((await store.get(a))?.tokens.accessToken).toBe("AT-A");
  });

  it("tetto per account: esce la sessione più vecchia dello stesso sub, le altre persone restano", async () => {
    let now = 1_000_000;
    const store = new InMemorySessionStore({ masterKey: MASTER, idleSeconds: 1800, maxSeconds: 36000, maxSessions: 5, maxSessionsPerSubject: 2, now: () => now });
    const others = [await store.create(user({ sub: "x" }), TOKENS), await store.create(user({ sub: "y" }), TOKENS)];
    const first = await store.create(user({ sub: "abuso" }), TOKENS);
    const second = await store.create(user({ sub: "abuso" }), TOKENS);
    now += 1;
    for (let i = 0; i < 20; i++) await store.create(user({ sub: "abuso" }), TOKENS);
    expect(await store.get(first)).toBeNull();
    expect(await store.get(second)).toBeNull();
    for (const id of others) expect(await store.get(id)).not.toBeNull();
    expect(store.size).toBe(4);
  });

  it("inattività: l'uso rinnova, oltre il limite la sessione scade", async () => {
    const { store, advance } = setup({ idle: 1800 });
    const id = await store.create(user(), TOKENS);
    advance(1700);
    expect(await store.get(id)).not.toBeNull();
    advance(1700);
    expect(await store.get(id)).not.toBeNull();
    advance(1801);
    expect(await store.get(id)).toBeNull();
  });

  it("durata massima: scade anche se usata di continuo", async () => {
    const { store, advance } = setup({ idle: 1800, max: 3600 });
    const id = await store.create(user(), TOKENS);
    for (let i = 0; i < 3; i++) {
      advance(1200);
      expect(await store.get(id)).not.toBeNull();
    }
    advance(1);
    expect(await store.get(id)).toBeNull();
  });

  it("oltre il tetto esce la sessione usata meno di recente", async () => {
    const { store, advance } = setup({ count: 2 });
    const a = await store.create(user({ sub: "a" }), TOKENS);
    const b = await store.create(user({ sub: "b" }), TOKENS);
    advance(1);
    await store.get(a); // a diventa la più recente
    const c = await store.create(user({ sub: "c" }), TOKENS);
    expect(await store.get(b)).toBeNull();
    expect(await store.get(a)).not.toBeNull();
    expect(await store.get(c)).not.toBeNull();
  });

  it("updateTokens sostituisce i token; su una sessione chiusa restituisce false", async () => {
    const { store } = setup();
    const id = await store.create(user(), TOKENS);
    const renewed = { ...TOKENS, accessToken: "NUOVO", refreshToken: "RUOTATO" };
    expect(await store.updateTokens(id, renewed)).toBe(true);
    expect((await store.get(id))?.tokens).toEqual(renewed);
    await store.delete(id);
    expect(await store.updateTokens(id, renewed)).toBe(false);
  });

  it("deleteMatching: per sid, per sub, per entrambi; nessun criterio ⇒ nulla", async () => {
    const { store } = setup();
    const s1 = await store.create(user({ sub: "u1", sid: "x" }), TOKENS);
    const s2 = await store.create(user({ sub: "u1", sid: "y" }), TOKENS);
    const s3 = await store.create(user({ sub: "u2", sid: "z" }), TOKENS);
    expect(await store.deleteMatching({})).toBe(0);
    expect(await store.deleteMatching({ sid: "x", sub: "u2" })).toBe(0);
    expect(await store.deleteMatching({ sid: "x" })).toBe(1);
    expect(await store.get(s1)).toBeNull();
    expect(await store.deleteMatching({ sub: "u1" })).toBe(1);
    expect(await store.get(s2)).toBeNull();
    expect(await store.get(s3)).not.toBeNull();
  });
});
