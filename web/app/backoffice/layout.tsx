import { cookies } from "next/headers";
import { KeepAlive } from "@/components/shared/KeepAlive";
import { Sidebar } from "@/components/bo/Sidebar";
import { MobileNav } from "@/components/bo/MobileNav";
import { PersonaProvider, type BoPersona } from "@/components/bo/PersonaContext";
import { AccessDenied } from "@/components/shared/auth/AccessDenied";
import { LoginRedirect } from "@/components/shared/auth/LoginRedirect";
import { LogoutButton } from "@/components/shared/auth/LogoutButton";
import { memberConsoleUrl } from "@/lib/auth/idpConsole";
import { getViewer } from "@/lib/auth/viewer";
import { testMode } from "@/lib/hub/testMode";
import { canSeeMemberUsers, canSeeSampleProgram, canSendAction } from "@/lib/nav";
import { PERSONA_COOKIE, parsePersona } from "@/lib/persona/cookie";
import {
  DEFAULT_BACKOFFICE_USERNAME,
  findBackofficePersona,
} from "@/lib/persona/personas";

// Shell del backoffice (docs/08 §1): sidebar a gruppi + barra alta con la persona corrente.
// Profilo demo: persona simulata dal cookie lh_persona. Profilo enterprise (docs/07 §4-bis): operatore della sessione
// OIDC del BFF, con «Esci»; senza sessione si va al login, un account solo membro non entra.
export default async function BackofficeLayout({ children }: { children: React.ReactNode }) {
  const viewer = await getViewer();
  let persona: BoPersona;
  if (viewer.mode === "enterprise") {
    if (!viewer.user) return <LoginRedirect area="backoffice" />;
    if (viewer.user.kind === "member") {
      return (
        <AccessDenied
          title="Accesso al backoffice non consentito"
          detail="Questo account è di un membro del programma: esci e accedi con un account operatore."
        />
      );
    }
    persona = { username: viewer.user.username, displayName: viewer.user.name ?? viewer.user.username, role: viewer.user.role };
  } else {
    persona = await demoPersona();
  }
  // «Utenti membri» (ADR-051 dec. 7): console del realm dei membri, solo ADMIN e CARE in enterprise con quel realm.
  const memberUsersUrl = viewer.mode === "enterprise" && canSeeMemberUsers(persona.role) ? memberConsoleUrl() : null;
  // «Carica il programma di esempio» (V10): solo ADMIN, solo enterprise, solo nell'ambiente di test dichiarato (Q-676).
  const sampleProgram = canSeeSampleProgram(persona.role, viewer.mode, testMode() !== null);
  // «Invia un'azione» (V11): ADMIN e CARE, solo enterprise, solo nell'ambiente di test dichiarato.
  const sendAction = canSendAction(persona.role, viewer.mode, testMode() !== null);
  const initials = persona.displayName
    .split(" ")
    .map((w) => w[0])
    .join("");

  return (
    <PersonaProvider value={{ username: persona.username, displayName: persona.displayName, role: persona.role, mode: viewer.mode, memberUsersUrl, sampleProgram, sendAction }}>
      <div className="flex min-h-dvh">
        <Sidebar />
        <div className="flex min-w-0 flex-1 flex-col">
          <header className="flex items-center justify-between border-b border-[var(--color-bo-border)] bg-[var(--color-bo-surface)] px-4 py-3">
            <div className="flex items-center gap-2">
              <MobileNav />
              <span className="font-semibold md:hidden">Loyalty Hub</span>
              <span className="hidden text-sm text-[var(--color-bo-ink-2)] md:inline">Backoffice</span>
            </div>
            <div className="flex items-center gap-2">
              <div className="flex h-8 w-8 items-center justify-center rounded-full bg-[var(--color-bo-accent)] text-xs font-bold text-white">
                {initials}
              </div>
              <div className="text-right leading-tight">
                <div className="text-sm font-medium">{persona.displayName}</div>
                <div className="text-xs text-[var(--color-bo-ink-2)]">{persona.role}</div>
              </div>
              {viewer.mode === "enterprise" ? <LogoutButton /> : null}
            </div>
          </header>
          <main className="mx-auto w-full max-w-[1440px] flex-1 p-6">{children}</main>
        </div>
        <KeepAlive />
      </div>
    </PersonaProvider>
  );
}

/** Persona simulata del profilo demo (docs/07 §4), invariata dalla Fase 1. */
async function demoPersona(): Promise<BoPersona> {
  const raw = (await cookies()).get(PERSONA_COOKIE)?.value;
  const parsed = parsePersona(raw);
  const username = parsed?.kind === "BO" ? parsed.username : DEFAULT_BACKOFFICE_USERNAME;
  const found = findBackofficePersona(username) ?? findBackofficePersona(DEFAULT_BACKOFFICE_USERNAME)!;
  // Q-186 DECISA: il ruolo mostrato e usato da can() è quello che il proxy manda in X-LH-Actor (dal cookie, già
  // ricondotto ad ANALYST se fuori elenco), non quello ricavato dallo username.
  return parsed?.kind === "BO" ? { ...found, role: parsed.role } : found;
}
