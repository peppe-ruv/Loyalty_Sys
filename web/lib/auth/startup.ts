import { getAuthConfig, InsecureConfigError, type Env } from "./config";

// Verifica all'avvio del server web (instrumentation.ts, runtime Node). Profilo enterprise con configurazione assente o
// insicura ⇒ il processo esce con un errore esplicito `INSECURE_CONFIG` (CLAUDE.md regola 22, docs/18 §3.15 punto 2):
// meglio un container che non parte di un portale che parte senza login. Nessuna chiamata di rete qui: l'IdP può
// avviarsi dopo il web, la discovery avviene al primo login.

export function checkAuthConfigAtStartup(env: Env = process.env, exit: (code: number) => never = process.exit): void {
  try {
    const cfg = getAuthConfig(env);
    if (cfg.mode === "enterprise") {
      console.info(`Profilo enterprise: login OIDC verso ${cfg.issuer.origin}, client ${cfg.clientId}, origine ${cfg.publicUrl.origin}.`);
    }
  } catch (err) {
    if (!(err instanceof InsecureConfigError)) throw err;
    console.error(err.message);
    exit(1);
  }
}
