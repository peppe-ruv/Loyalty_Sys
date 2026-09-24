import { describe, expect, it } from "vitest";
import { inboxHref, markAllReadLocally, markReadLocally, newArrivals, relativeWhen, unreadBadge } from "./inbox";
import type { PortalMessage } from "./types";

const msg = (id: string, read = false): PortalMessage => ({
  id,
  category: "POINTS",
  title: "Hai guadagnato 162 punti",
  body: "Grazie Marco!",
  icon: "coins",
  linkTarget: "/portal/activity",
  createdAt: "2026-09-24T08:00:00Z",
  read,
  readAt: read ? "2026-09-24T09:00:00Z" : null,
});

describe("unreadBadge", () => {
  it("nasconde lo zero e i valori assenti, tronca oltre 9", () => {
    expect(unreadBadge(0)).toBeNull();
    expect(unreadBadge(undefined)).toBeNull();
    expect(unreadBadge(null)).toBeNull();
    expect(unreadBadge(3)).toBe("3");
    expect(unreadBadge(9)).toBe("9");
    expect(unreadBadge(12)).toBe("9+");
  });
});

describe("inboxHref", () => {
  it("segue solo percorsi interni del portale", () => {
    expect(inboxHref("/portal/activity")).toBe("/portal/activity");
    expect(inboxHref(" /portal/my-rewards ")).toBe("/portal/my-rewards");
    expect(inboxHref("/portal")).toBe("/portal");
    expect(inboxHref("/portal/earn#CMP-BIRTHDAY")).toBe("/portal/earn#CMP-BIRTHDAY");
  });

  it("scarta URL esterni, schemi, risalite e percorsi fuori dal portale", () => {
    expect(inboxHref(null)).toBeNull();
    expect(inboxHref("")).toBeNull();
    expect(inboxHref("https://example.org")).toBeNull();
    expect(inboxHref("javascript:alert(1)")).toBeNull();
    expect(inboxHref("//evil.example/portal")).toBeNull();
    expect(inboxHref("/portal//evil.example")).toBeNull();
    expect(inboxHref("/portal/../backoffice")).toBeNull();
    expect(inboxHref("/portalx")).toBeNull();
    expect(inboxHref("/backoffice")).toBeNull();
    expect(inboxHref("/portal/a b")).toBeNull();
  });
});

describe("relativeWhen", () => {
  const now = new Date("2026-09-24T10:00:00Z"); // 12:00 a Roma
  it("usa adesso, minuti, oggi e ieri", () => {
    expect(relativeWhen("2026-09-24T09:59:40Z", now)).toBe("adesso");
    expect(relativeWhen("2026-09-24T09:48:00Z", now)).toBe("12 min fa");
    expect(relativeWhen("2026-09-24T06:42:00Z", now)).toBe("oggi alle 08:42");
    expect(relativeWhen("2026-09-23T16:05:00Z", now)).toBe("ieri alle 18:05");
  });

  it("conta i giorni di calendario a Roma, poi passa alla data estesa", () => {
    // 23:30 del 22 a Roma = 21:30Z: due giorni di calendario fa
    expect(relativeWhen("2026-09-22T21:30:00Z", now)).toBe("2 giorni fa");
    expect(relativeWhen("2026-09-18T10:00:00Z", now)).toBe("6 giorni fa");
    expect(relativeWhen("2026-09-10T10:00:00Z", now)).toBe("10 set 2026");
  });

  it("il confine del giorno è la mezzanotte di Roma, non di UTC", () => {
    // 00:30 del 24 a Roma = 22:30Z del 23 → oggi; 23:30 del 23 a Roma → ieri
    expect(relativeWhen("2026-09-23T22:30:00Z", now)).toBe("oggi alle 00:30");
    expect(relativeWhen("2026-09-23T21:30:00Z", now)).toBe("ieri alle 23:30");
  });

  it("un orario nel futuro (orologi sfasati) è adesso", () => {
    expect(relativeWhen("2026-09-24T10:05:00Z", now)).toBe("adesso");
  });
});

describe("stato locale dell'inbox", () => {
  it("segna una voce come letta senza toccare le altre", () => {
    const next = markReadLocally([msg("a"), msg("b")], "a", "2026-09-24T10:00:00Z");
    expect(next[0].read).toBe(true);
    expect(next[0].readAt).toBe("2026-09-24T10:00:00Z");
    expect(next[1].read).toBe(false);
  });

  it("segna tutte come lette conservando la data di lettura già presente", () => {
    const next = markAllReadLocally([msg("a", true), msg("b")], "2026-09-24T10:00:00Z");
    expect(next.every((m) => m.read)).toBe(true);
    expect(next[0].readAt).toBe("2026-09-24T09:00:00Z");
  });

  it("riconosce i nuovi arrivi rispetto all'elenco mostrato", () => {
    expect(newArrivals(undefined, [msg("a")]).size).toBe(0);
    expect([...newArrivals([msg("a")], [msg("b"), msg("a")])]).toEqual(["b"]);
  });
});
