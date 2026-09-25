import { cookies } from "next/headers";
import { KeepAlive } from "@/components/shared/KeepAlive";
import { Sidebar } from "@/components/bo/Sidebar";
import { MobileNav } from "@/components/bo/MobileNav";
import { PersonaProvider } from "@/components/bo/PersonaContext";
import { PERSONA_COOKIE, parsePersona } from "@/lib/persona/cookie";
import {
  DEFAULT_BACKOFFICE_USERNAME,
  findBackofficePersona,
} from "@/lib/persona/personas";

// Shell del backoffice (docs/08 §1): sidebar a gruppi + barra alta con la persona corrente.
export default async function BackofficeLayout({ children }: { children: React.ReactNode }) {
  const raw = (await cookies()).get(PERSONA_COOKIE)?.value;
  const parsed = parsePersona(raw);
  const username = parsed?.kind === "BO" ? parsed.username : DEFAULT_BACKOFFICE_USERNAME;
  const found = findBackofficePersona(username) ?? findBackofficePersona(DEFAULT_BACKOFFICE_USERNAME)!;
  // Q-186 DECISA: il ruolo mostrato e usato da can() è quello che il proxy manda in X-LH-Actor (dal cookie, già
  // ricondotto ad ANALYST se fuori elenco), non quello ricavato dallo username.
  const persona = parsed?.kind === "BO" ? { ...found, role: parsed.role } : found;
  const initials = persona.displayName
    .split(" ")
    .map((w) => w[0])
    .join("");

  return (
    <PersonaProvider value={{ username: persona.username, displayName: persona.displayName, role: persona.role }}>
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
            </div>
          </header>
          <main className="mx-auto w-full max-w-[1440px] flex-1 p-6">{children}</main>
        </div>
        <KeepAlive />
      </div>
    </PersonaProvider>
  );
}
