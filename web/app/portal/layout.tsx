import { cookies } from "next/headers";
import { KeepAlive } from "@/components/shared/KeepAlive";
import { MemberProvider } from "@/components/portal/MemberContext";
import { PortalShell } from "@/components/portal/PortalShell";
import { InboxBell } from "@/components/portal/InboxBell";
import { PERSONA_COOKIE, parsePersona } from "@/lib/persona/cookie";
import { DEFAULT_MEMBER_ID } from "@/lib/persona/personas";
import { ThemeProvider } from "@/components/shared/ThemeContext";
import { getPortalTheme } from "@/lib/theme/server";
import { themeStyle } from "@/lib/theme/theme";

// Shell del portale (docs/09 §1): mobile-first. Il tema (colori, nome, logo) viene da engagement a runtime (M6.5,
// F-THM-01) e si applica come variabili CSS; se il servizio dorme, Aurora. Il membro attivo viene dal cookie lh_persona.
export default async function PortalLayout({ children }: { children: React.ReactNode }) {
  const parsed = parsePersona((await cookies()).get(PERSONA_COOKIE)?.value);
  const memberId = parsed?.kind === "MEMBER" ? parsed.memberId : DEFAULT_MEMBER_ID;
  const theme = await getPortalTheme();

  return (
    <MemberProvider memberId={memberId}>
      <ThemeProvider theme={theme}>
      <div className="mx-auto min-h-dvh max-w-md bg-[var(--color-pt-bg)]" style={themeStyle(theme)}>
        <header className="flex items-center justify-between px-4 py-1.5">
          <span className="flex items-center gap-2 font-semibold text-[var(--color-pt-night)]">
            {theme.logoUrl ? (
              // eslint-disable-next-line @next/next/no-img-element -- logo del tema (percorso o URL https)
              <img src={theme.logoUrl} alt="" className="h-6 w-auto" />
            ) : null}
            {theme.programName}
          </span>
          <InboxBell />
        </header>
        <main className="px-4">
          <PortalShell>{children}</PortalShell>
        </main>
        <KeepAlive />
      </div>
      </ThemeProvider>
    </MemberProvider>
  );
}
