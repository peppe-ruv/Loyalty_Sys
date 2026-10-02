import type { Env } from "./config";
import { getAuthConfig } from "./config";

// Console di amministrazione di Keycloak dei due realm (ADR-051 decisioni 7 e 8, Q-670): `<origine dell'IdP>/admin/<realm>/console/`.
// Origine e NOME del realm si ricavano dagli emittenti già configurati (`LH_OIDC_ISSUER`, `LH_OIDC_MEMBER_ISSUER`, forma
// `<origine>/realms/<nome>`), mai da una costante né da una variabile nuova: se il percorso non ha quella forma non c'è
// console (`null`). SOLO LATO SERVER: al browser l'indirizzo arriva come prop di un server component, mai NEXT_PUBLIC.

/** Nome del realm dal percorso dell'emittente (`/realms/<nome>`, nome con soli caratteri sicuri); altrimenti `null`. */
export function realmName(issuer: URL): string | null {
  const m = /^\/realms\/([A-Za-z0-9_-]+)\/?$/.exec(issuer.pathname);
  return m ? m[1] : null;
}

export interface ConsoleTarget {
  realm: string;
  url: string;
}

export function consoleUrl(idpOrigin: string, realm: string): string {
  return `${idpOrigin}/admin/${realm}/console/`;
}

/** Console del realm di un emittente, o `null` se il percorso dell'emittente non è `/realms/<nome>`. */
export function consoleTarget(issuer: URL): ConsoleTarget | null {
  const realm = realmName(issuer);
  return realm === null ? null : { realm, url: consoleUrl(issuer.origin, realm) };
}

/**
 * Console del realm dei membri, o `null` se non siamo in enterprise, il realm dei membri non è configurato, il suo
 * emittente non ha la forma `/realms/<nome>` o la configurazione è rifiutata (mai un indirizzo in demo).
 */
export function memberConsoleUrl(env: Env = process.env): string | null {
  try {
    const cfg = getAuthConfig(env);
    return cfg.mode === "enterprise" && cfg.members ? (consoleTarget(cfg.members.issuer)?.url ?? null) : null;
  } catch {
    return null;
  }
}
