import { cookies } from "next/headers";
import { KeepAlive } from "@/components/shared/KeepAlive";
import { MemberProvider } from "@/components/portal/MemberContext";
import { EnterpriseMemberGate, type AccountIdentity } from "@/components/portal/EnterpriseMemberGate";
import { PortalShell } from "@/components/portal/PortalShell";
import { InboxBell } from "@/components/portal/InboxBell";
import { AccessDenied } from "@/components/shared/auth/AccessDenied";
import { LoginRedirect } from "@/components/shared/auth/LoginRedirect";
import { LogoutButton } from "@/components/shared/auth/LogoutButton";
import { getViewer } from "@/lib/auth/viewer";
import { PERSONA_COOKIE, parsePersona } from "@/lib/persona/cookie";
import { demoPortalMember } from "@/lib/persona/demoMember";
import { ThemeProvider } from "@/components/shared/ThemeContext";
import { getPortalTheme } from "@/lib/theme/server";
import { themeStyle } from "@/lib/theme/theme";

// Shell del portale (docs/09 §1): mobile-first. Il tema (colori, nome, logo) viene da engagement a runtime (M6.5,
// F-THM-01) e si applica come variabili CSS; se il servizio dorme, Aurora.
// Profilo demo: il membro attivo viene dal cookie lh_persona, con il tray demo. Profilo enterprise (docs/07 §4-bis,
// F2-SEC-09, ADR-051): serve la sessione OIDC di un membro (altrimenti il login del realm dei membri); il BFF non
// inoltra mai un memberId scelto dal browser e il membro viene solo dal token: `EnterpriseMemberGate` chiede
// `GET /v1/portal/me/profile` (id per mostrare e chiavare le cache; 404 MEMBER_NOT_REGISTERED → registrazione PT-16).
// Nome ed e-mail dell'account passano come prop alla sola pagina di registrazione; i token non lasciano mai il server
// (regola 20). SPEC-GAP: Q-410.
export default async function PortalLayout({ children }: { children: React.ReactNode }) {
  const viewer = await getViewer("members");
  let memberId = "";
  let account: AccountIdentity | null = null;
  if (viewer.mode === "enterprise") {
    if (!viewer.user) return <LoginRedirect area="portale" />;
    if (viewer.user.kind !== "member") {
      return (
        <AccessDenied
          realm="members"
          title="Il portale è riservato ai membri"
          detail="Questo account è di un operatore: esci e accedi con l'account di un membro del programma."
        />
      );
    }
    account = {
      givenName: viewer.user.givenName ?? null,
      familyName: viewer.user.familyName ?? null,
      name: viewer.user.name,
      email: viewer.user.email ?? null,
    };
  } else {
    // Stesso membro che il proxy manda ai servizi in X-LH-Member (lib/persona/demoMember.ts, Q-555).
    memberId = demoPortalMember(parsePersona((await cookies()).get(PERSONA_COOKIE)?.value));
  }
  const theme = await getPortalTheme();
  const demo = viewer.mode === "demo";

  const content = (
    <>
      <header className="flex items-center justify-between px-4 py-1.5">
        <span className="flex items-center gap-2 font-semibold text-[var(--color-pt-night)]">
          {theme.logoUrl ? (
            // eslint-disable-next-line @next/next/no-img-element -- logo del tema (percorso o URL https)
            <img src={theme.logoUrl} alt="" className="h-6 w-auto" />
          ) : null}
          {theme.programName}
        </span>
        {demo ? (
          <InboxBell />
        ) : (
          <span className="flex items-center gap-2">
            <InboxBell />
            <LogoutButton realm="members" />
          </span>
        )}
      </header>
      <main className="px-4">
        <PortalShell demo={demo}>{children}</PortalShell>
      </main>
      <KeepAlive />
    </>
  );

  return (
    <ThemeProvider theme={theme}>
      <div className="mx-auto min-h-dvh max-w-md bg-[var(--color-pt-bg)]" style={themeStyle(theme)}>
        {account ? (
          <EnterpriseMemberGate account={account}>{content}</EnterpriseMemberGate>
        ) : (
          <MemberProvider memberId={memberId}>{content}</MemberProvider>
        )}
      </div>
    </ThemeProvider>
  );
}
