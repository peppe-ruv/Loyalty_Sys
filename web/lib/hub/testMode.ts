import { getAuthConfig, type Env } from "@/lib/auth/config";
import { consoleTarget, type ConsoleTarget } from "@/lib/auth/idpConsole";

// HUB-02 — l'ambiente di test dichiarato (Q-676): enterprise con LH_TEST_USERS_ALLOWED=true e LH_ENVIRONMENT=test.
// SOLO LATO SERVER. Una configurazione rifiutata (INSECURE_CONFIG) vale come «non ammesso», mai come test.

export interface TestMode {
  /** Realm e console di Keycloak degli operatori e, se configurato, dei membri: dagli emittenti (`/realms/<nome>`), `null` se non hanno quella forma. */
  operators: ConsoleTarget | null;
  members: ConsoleTarget | null;
}

/** `null` se gli utenti di test non sono ammessi (demo, enterprise senza le due variabili, configurazione rifiutata). */
export function testMode(env: Env = process.env): TestMode | null {
  try {
    const cfg = getAuthConfig(env);
    if (cfg.mode !== "enterprise" || !cfg.testUsersAllowed) return null;
    return { operators: consoleTarget(cfg.issuer), members: cfg.members ? consoleTarget(cfg.members.issuer) : null };
  } catch {
    return null;
  }
}
