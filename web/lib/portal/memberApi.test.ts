import { describe, expect, it } from "vitest";
import { portalApi } from "./memberApi";
import { accountNames } from "./account";
import { checkPortalBody, hasMemberIdInPath, hasMemberIdInQuery } from "@/lib/auth/memberScope";

// Seam del portale (F2-SEC-09, PT-08, PT-16, ADR-048, ADR-051; regole 6-bis e 18): in enterprise nessun memberId né in
// percorso, né in query, né nel corpo; in demo i percorsi restano quelli di sempre.

const ID = "MBR-000009";
const ent = portalApi(true, ID);
const demo = portalApi(false, ID);

function pathOf(p: string): { segments: string[]; params: URLSearchParams } {
  const url = new URL(p, "http://x");
  return { segments: url.pathname.split("/").filter(Boolean), params: url.searchParams };
}

describe("portalApi in enterprise: il membro viene solo dal token", () => {
  const paths = [ent.wallet, ent.walletActivity, ent.profile, ent.summary, ent.referral, ent.rewardCategories, ent.editions, ent.withMember("/v1/portal/redemptions/RDM-1/cancel")];

  it("nessun percorso contiene l'id del membro", () => {
    for (const p of paths) {
      expect(p, p).not.toContain(ID);
      expect(p, p).not.toMatch(/memberid/i);
      expect(hasMemberIdInPath(pathOf(p).segments), p).toBe(false);
      expect(hasMemberIdInQuery(pathOf(p).params), p).toBe(false);
    }
  });

  it("i percorsi sono quelli del membro dal token", () => {
    expect(ent.wallet).toBe("/v1/portal/me/wallet");
    expect(ent.walletActivity).toBe("/v1/portal/me/wallet/activity");
    expect(ent.profile).toBe("/v1/portal/me/profile");
    expect(ent.summary).toBe("/v1/portal/me/profile");
    expect(ent.referral).toBe("/v1/portal/me/referral");
    // Le variabili del portale al posto di quelle del backoffice (realm operatori): un membro non le potrebbe leggere.
    expect(ent.rewardCategories).toBe("/v1/portal/reward-categories");
    expect(ent.editions).toBe("/v1/portal/editions");
  });

  it("query e corpo non portano memberId, i parametri propri restano", () => {
    expect(ent.query()).toEqual({});
    expect(ent.query({ size: 3, placement: "WIN" })).toEqual({ size: 3, placement: "WIN" });
    expect(Object.keys(ent.query({ codes: "A,B" }))).not.toContain("memberId");
    expect(ent.body()).toEqual({});
    expect(ent.body({ rewardCode: "RWD-1", dismissed: true })).toEqual({ rewardCode: "RWD-1", dismissed: true });
    // Il BFF accetterebbe questi corpi: nessuna chiave memberId a nessun livello.
    const bytes = (o: unknown) => new TextEncoder().encode(JSON.stringify(o));
    expect(checkPortalBody("application/json", bytes(ent.body()))).toBeNull();
    expect(checkPortalBody("application/json", bytes(ent.body({ rewardCode: "RWD-1", shipping: { city: "Torino" } })))).toBeNull();
  });

  it("l'id resta disponibile solo per mostrare", () => {
    expect(ent.memberId).toBe(ID);
    expect(ent.enterprise).toBe(true);
  });
});

describe("portalApi in demo: invariato", () => {
  it("percorsi con l'id, come prima", () => {
    expect(demo.wallet).toBe(`/v1/portal/wallets/${ID}`);
    expect(demo.walletActivity).toBe(`/v1/portal/wallets/${ID}/activity`);
    expect(demo.profile).toBe(`/v1/portal/members/${ID}`);
    expect(demo.summary).toBe(`/v1/members/${ID}`);
    expect(demo.referral).toBe(`/v1/portal/members/${ID}/referral`);
    expect(demo.rewardCategories).toBe("/v1/reward-categories");
    expect(demo.editions).toBe("/v1/editions");
  });

  it("query, corpo e annullamento con memberId esplicito", () => {
    expect(demo.query()).toEqual({ memberId: ID });
    expect(demo.query({ size: 3 })).toEqual({ memberId: ID, size: 3 });
    expect(demo.body()).toEqual({ memberId: ID });
    expect(demo.body({ dismissed: false })).toEqual({ memberId: ID, dismissed: false });
    expect(demo.withMember("/v1/portal/redemptions/RDM-1/cancel")).toBe(`/v1/portal/redemptions/RDM-1/cancel?memberId=${ID}`);
    expect(demo.withMember("/x?a=1")).toBe(`/x?a=1&memberId=${ID}`);
  });
});

describe("accountNames", () => {
  it("usa i claim dedicati", () => {
    expect(accountNames({ givenName: "Laura", familyName: "Conti", name: "Altro Nome" })).toEqual({ firstName: "Laura", lastName: "Conti" });
  });
  it("altrimenti divide il nome completo al primo spazio", () => {
    expect(accountNames({ givenName: null, familyName: null, name: "Laura De Conti" })).toEqual({ firstName: "Laura", lastName: "De Conti" });
  });
  it("niente nome: vuoti, mai inventati", () => {
    expect(accountNames({ givenName: null, familyName: null, name: null })).toEqual({ firstName: "", lastName: "" });
  });
});
