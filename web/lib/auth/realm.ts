// Realm di Keycloak del BFF (ADR-051 decisione 6): `operators` per il backoffice, `members` per il portale. Con un solo
// realm configurato (`LH_OIDC_MEMBER_ISSUER` vuota) i due nomi indicano la stessa sessione, come prima di ADR-051.
// Modulo senza dipendenze dal server: lo usano anche il browser (token CSRF) e le rotte.

export type Realm = "operators" | "members";

export interface RealmCookies {
  /** Id opaco della sessione (`HttpOnly`). */
  session: string;
  /** Token CSRF legato alla sessione, leggibile dal JavaScript della stessa origine. */
  csrf: string;
  /** Stato cifrato del login in corso. */
  flow: string;
}

export const REALM_COOKIES: Readonly<Record<Realm, RealmCookies>> = {
  operators: { session: "__Host-lh_session", csrf: "__Host-lh_csrf", flow: "__Host-lh_auth" },
  members: { session: "__Host-lh_msession", csrf: "__Host-lh_mcsrf", flow: "__Host-lh_mauth" },
};

/** Percorso di ritorno dall'IdP (redirect URI del client del realm). */
export const CALLBACK_PATHS: Readonly<Record<Realm, string>> = {
  operators: "/api/auth/callback",
  members: "/api/auth/callback/members",
};

/** Percorso del back-channel logout chiamato dall'IdP. */
export const BACKCHANNEL_PATHS: Readonly<Record<Realm, string>> = {
  operators: "/api/auth/backchannel-logout",
  members: "/api/auth/backchannel-logout/members",
};

/** Valore di `?realm=` o del campo `realm`: solo i due nomi ammessi, altrimenti `null`. */
export function parseRealm(value: string | null | undefined): Realm | null {
  return value === "operators" || value === "members" ? value : null;
}

/** Il portale (`/portal…`) appartiene al realm dei membri; ogni altra pagina a quello degli operatori. */
export function realmForPage(pathname: string): Realm {
  return pathname === "/portal" || pathname.startsWith("/portal/") || pathname.startsWith("/portal?") ? "members" : "operators";
}

/** Le API del portale (`/v1/portal/**`) usano la sessione del membro; tutte le altre quella dell'operatore. */
export function realmForApi(path: readonly string[]): Realm {
  return path[0] === "v1" && path[1] === "portal" ? "members" : "operators";
}
