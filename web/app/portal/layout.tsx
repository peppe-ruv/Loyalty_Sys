import { cookies } from "next/headers";
import { KeepAlive } from "@/components/shared/KeepAlive";
import { MemberProvider } from "@/components/portal/MemberContext";
import { PortalShell } from "@/components/portal/PortalShell";
import { InboxBell } from "@/components/portal/InboxBell";
import { PERSONA_COOKIE, parsePersona } from "@/lib/persona/cookie";
import { DEFAULT_MEMBER_ID } from "@/lib/persona/personas";

// Shell del portale (docs/09 §1): mobile-first, tema "Aurora". Il membro attivo viene dal cookie lh_persona.
export default async function PortalLayout({ children }: { children: React.ReactNode }) {
  const parsed = parsePersona((await cookies()).get(PERSONA_COOKIE)?.value);
  const memberId = parsed?.kind === "MEMBER" ? parsed.memberId : DEFAULT_MEMBER_ID;

  return (
    <MemberProvider memberId={memberId}>
      <div className="mx-auto min-h-dvh max-w-md bg-[var(--color-pt-bg)]">
        <header className="flex items-center justify-between px-4 py-1.5">
          <span className="font-semibold text-[var(--color-pt-night)]">Club Aurora</span>
          <InboxBell />
        </header>
        <main className="px-4">
          <PortalShell>{children}</PortalShell>
        </main>
        <KeepAlive />
      </div>
    </MemberProvider>
  );
}
