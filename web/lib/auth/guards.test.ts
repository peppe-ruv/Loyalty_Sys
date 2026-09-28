// @vitest-environment node
import { describe, expect, it } from "vitest";
import { checkCsrf, checkOrigin, csrfTokenFor } from "./csrf";
import { deriveKey } from "./crypto";
import { hasMemberIdInPath, isPortalPath, stripMemberBody, stripMemberQuery } from "./memberScope";
import { safeReturnTo } from "./returnTo";
import { effectiveRole, rolesFromClaim, sessionKind } from "./roles";
import { csrfHeaders, loginHref, readCsrfToken } from "./browser";

// Controlli puri del BFF: ritorno dopo il login (open redirect), CSRF, membro solo dal token, ruoli dal claim.

describe("safeReturnTo (niente open redirect)", () => {
  it.each([
    ["/backoffice", "/backoffice"],
    ["/portal/rewards?tab=mine#top", "/portal/rewards?tab=mine#top"],
    ["/backoffice/members/MBR-000002", "/backoffice/members/MBR-000002"],
    ["/portal/%2e%2e/backoffice", "/backoffice"],
  ])("ammesso %s", (raw, expected) => {
    expect(safeReturnTo(raw)).toBe(expected);
  });

  it.each([
    ["URL assoluto", "https://attaccante.example/"],
    ["schema javascript", "javascript:alert(1)"],
    ["protocol-relative", "//attaccante.example/x"],
    ["barra rovesciata", "/\\attaccante.example"],
    ["barra rovesciata codificata dopo la normalizzazione", "\\/attaccante.example"],
    ["tabulazione che i browser scartano", "/\t/attaccante.example"],
    ["a capo", "/\n/attaccante.example"],
    ["spazio iniziale", " /backoffice"],
    ["relativo senza barra", "backoffice"],
    ["endpoint API", "/api/auth/logout"],
    ["endpoint API dopo la normalizzazione", "/portal/../api/lh/member/v1/members"],
    ["vuoto", ""],
    ["troppo lungo", `/${"a".repeat(2048)}`],
  ])("rifiutato: %s", (_, raw) => {
    expect(safeReturnTo(raw, "/fallback")).toBe("/fallback");
  });

  it("assente ⇒ pagina iniziale", () => {
    expect(safeReturnTo(null)).toBe("/");
    expect(safeReturnTo(undefined)).toBe("/");
  });
});

describe("CSRF", () => {
  const ORIGIN = "https://loyalty.lh.test";
  const csrfKey = deriveKey(Buffer.alloc(32, 9).fill(3, 0, 1), "csrf");
  const session = { id: "sessione-opaca", csrfKey };
  const token = csrfTokenFor(session.id, csrfKey);
  const req = (method: string, headers: Record<string, string>) => ({ method, headers: new Headers(headers) });
  const good = { origin: ORIGIN, "sec-fetch-site": "same-origin", "x-lh-csrf": token };

  it.each(["GET", "HEAD", "OPTIONS"])("%s non cambia stato: nessun controllo", (method) => {
    expect(checkCsrf(req(method, { origin: "https://attaccante.example", "sec-fetch-site": "cross-site" }), ORIGIN, session)).toBeNull();
  });

  it.each(["POST", "PUT", "PATCH", "DELETE"])("%s con origine, Sec-Fetch-Site e token giusti passa", (method) => {
    expect(checkCsrf(req(method, good), ORIGIN, session)).toBeNull();
  });

  it.each([
    ["Sec-Fetch-Site cross-site", { ...good, "sec-fetch-site": "cross-site" }, "SEC_FETCH_SITE"],
    ["Sec-Fetch-Site same-site (sottodominio)", { ...good, "sec-fetch-site": "same-site" }, "SEC_FETCH_SITE"],
    ["Sec-Fetch-Site none", { ...good, "sec-fetch-site": "none" }, "SEC_FETCH_SITE"],
    ["Origin di un altro sito", { ...good, origin: "https://attaccante.example" }, "ORIGIN"],
    ["Origin null (iframe sandbox, redirect)", { ...good, origin: "null" }, "ORIGIN"],
    ["Origin con schema diverso", { ...good, origin: "http://loyalty.lh.test" }, "ORIGIN"],
    ["Origin assente", { "sec-fetch-site": "same-origin", "x-lh-csrf": token }, "ORIGIN"],
    ["token assente", { origin: ORIGIN, "sec-fetch-site": "same-origin" }, "TOKEN"],
    ["token di un'altra sessione", { ...good, "x-lh-csrf": csrfTokenFor("altra-sessione", csrfKey) }, "TOKEN"],
    ["token alterato", { ...good, "x-lh-csrf": `${token.slice(0, -1)}A` }, "TOKEN"],
  ])("POST rifiutato: %s", (_, headers, failure) => {
    expect(checkCsrf(req("POST", headers as Record<string, string>), ORIGIN, session)).toBe(failure);
  });

  it("browser senza Sec-Fetch-Site: bastano Origin e token", () => {
    expect(checkCsrf(req("POST", { origin: ORIGIN, "x-lh-csrf": token }), ORIGIN, session)).toBeNull();
  });

  it("senza sessione si verificano solo le intestazioni di provenienza", () => {
    expect(checkCsrf(req("POST", { origin: ORIGIN }), ORIGIN, null)).toBeNull();
    expect(checkOrigin(new Headers({ origin: "https://attaccante.example" }), ORIGIN)).toBe("ORIGIN");
  });
});

describe("token CSRF lato browser", () => {
  it("letto dal cookie __Host-lh_csrf e aggiunto solo ai metodi che cambiano stato", () => {
    const cookie = "lh_persona=x; __Host-lh_csrf=abc-123_x; altro=1";
    expect(readCsrfToken(cookie)).toBe("abc-123_x");
    expect(csrfHeaders("POST", cookie)).toEqual({ "X-LH-CSRF": "abc-123_x" });
    expect(csrfHeaders("delete", cookie)).toEqual({ "X-LH-CSRF": "abc-123_x" });
    expect(csrfHeaders("GET", cookie)).toEqual({});
    expect(csrfHeaders(undefined, cookie)).toEqual({});
  });

  it("profilo demo (nessun cookie CSRF): nessun header in più", () => {
    expect(readCsrfToken("lh_persona=%7B%7D")).toBeNull();
    expect(csrfHeaders("POST", "lh_persona=%7B%7D")).toEqual({});
    expect(csrfHeaders("POST", "")).toEqual({});
  });

  it("il login riporta alla pagina corrente", () => {
    expect(loginHref({ pathname: "/portal/rewards", search: "?tab=mine" })).toBe("/api/auth/login?returnTo=%2Fportal%2Frewards%3Ftab%3Dmine");
  });
});

describe("membro solo dal token (API del portale)", () => {
  it("percorsi del portale e id del membro nel percorso", () => {
    expect(isPortalPath(["v1", "portal", "catalog"])).toBe(true);
    expect(isPortalPath(["v1", "members", "MBR-1"])).toBe(false);
    expect(hasMemberIdInPath(["v1", "portal", "wallets", "MBR-000002"])).toBe(true);
    expect(hasMemberIdInPath(["v1", "portal", "wallets", "MBR-000002", "activity"])).toBe(true);
    expect(hasMemberIdInPath(["v1", "portal", "members", "MBR-000002", "referral"])).toBe(true);
    expect(hasMemberIdInPath(["v1", "portal", "tiers"])).toBe(false);
    expect(hasMemberIdInPath(["v1", "portal", "rewards", "RWD-1"])).toBe(false);
    expect(hasMemberIdInPath(["v1", "members", "MBR-000002"])).toBe(false);
  });

  it("memberId tolto dalla query in ogni grafia, gli altri parametri restano", () => {
    const params = new URLSearchParams("memberId=MBR-9&size=3&MEMBERID=MBR-8&memberid=MBR-7&codes=A,B");
    expect(stripMemberQuery(params).toString()).toBe("size=3&codes=A%2CB");
  });

  it("memberId tolto dal primo livello del corpo JSON, anche scritto con escape", () => {
    expect(JSON.parse(stripMemberBody('{"memberId":"MBR-9","rewardCode":"RWD-1"}'))).toEqual({ rewardCode: "RWD-1" });
    expect(JSON.parse(stripMemberBody('{"member\\u0049d":"MBR-9","x":1}'))).toEqual({ x: 1 });
    expect(stripMemberBody('{"rewardCode":"RWD-1"}')).toBe('{"rewardCode":"RWD-1"}');
    expect(stripMemberBody("non json")).toBe("non json");
    expect(stripMemberBody('[{"memberId":"MBR-9"}]')).toBe('[{"memberId":"MBR-9"}]');
  });
});

describe("ruoli dal claim lh_roles (stessa regola dei servizi, Q-365)", () => {
  it.each([
    [["ADMIN", "MARKETING"], "ADMIN"],
    [["LEGAL"], "LEGAL"],
    [["LEGAL", "MEMBER"], "LEGAL"],
    [["MARKETING", "LEGAL"], "ANALYST"],
    [["MEMBER"], "ANALYST"],
    [[], "ANALYST"],
    [["offline_access", "uma_authorization"], "ANALYST"],
  ])("%j ⇒ %s", (roles, expected) => {
    expect(effectiveRole(roles)).toBe(expected);
  });

  it("membro solo con MEMBER e nessun ruolo operatore", () => {
    expect(sessionKind(["MEMBER"])).toBe("member");
    expect(sessionKind(["MEMBER", "CARE"])).toBe("operator");
    expect(sessionKind([])).toBe("operator");
  });

  it("claim malformato ⇒ nessun ruolo", () => {
    expect(rolesFromClaim("ADMIN")).toEqual([]);
    expect(rolesFromClaim(["ADMIN", 1, null, "ADMIN"])).toEqual(["ADMIN"]);
  });
});
