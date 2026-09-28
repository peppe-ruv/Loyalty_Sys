import { cookies } from "next/headers";
import { getAuthConfig } from "./config";
import { bffFor, currentSession } from "./bff";
import type { SessionUser } from "./sessionStore";

// Chi sta guardando la pagina, per i layout server di backoffice e portale. SOLO LATO SERVER.
// Demo: identità simulata dal cookie persona (gestita dai layout come prima). Enterprise: utente della sessione del
// BFF, oppure `null` (la pagina porta al login). Profilo enterprise mal configurato ⇒ l'errore sale (pagina d'errore
// di Next), mai il demo. Al browser arrivano solo nome, username e ruolo: mai token (regola 20).

export type Viewer = { mode: "demo" } | { mode: "enterprise"; user: SessionUser | null };

export async function getViewer(): Promise<Viewer> {
  const cfg = getAuthConfig();
  if (cfg.mode === "demo") return { mode: "demo" };
  const current = await currentSession(await cookies(), bffFor(cfg));
  return { mode: "enterprise", user: current?.session.user ?? null };
}
