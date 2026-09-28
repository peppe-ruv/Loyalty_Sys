// Avvio del server Next (una volta per processo, prima di servire richieste). Solo runtime Node: nel profilo
// enterprise una configurazione di accesso assente o insicura ferma il processo (lib/auth/startup.ts, regola 22).
export async function register() {
  if (process.env.NEXT_RUNTIME === "nodejs") {
    const { checkAuthConfigAtStartup } = await import("./lib/auth/startup");
    checkAuthConfigAtStartup();
  }
}
