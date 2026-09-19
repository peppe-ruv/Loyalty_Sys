import Link from "next/link";
import { KeepAlive } from "@/components/shared/KeepAlive";
import { DEFAULT_BACKOFFICE_USERNAME, findBackofficePersona } from "@/lib/persona/personas";
import { it } from "@/lib/i18n/it";

// Shell del backoffice (docs/07 §2, §5.2). Le voci di menu compaiono con le rispettive milestone (M1+).
export default function BackofficeLayout({ children }: { children: React.ReactNode }) {
  const persona = findBackofficePersona(DEFAULT_BACKOFFICE_USERNAME);
  const initials = persona?.displayName.split(" ").map((w) => w[0]).join("") ?? "?";

  return (
    <div className="flex min-h-dvh">
      <aside className="hidden w-60 shrink-0 flex-col bg-[var(--color-bo-ink)] px-4 py-5 text-[#c9d3e0] md:flex">
        <Link href="/" className="mb-6 text-lg font-semibold text-white">
          Loyalty Hub
        </Link>
        <nav className="text-sm text-[#8b98ad]">{it.shell.comingSoon}…</nav>
        <Link href="/" className="mt-auto text-sm text-[#8b98ad] hover:text-white">
          {it.shell.backToHub}
        </Link>
      </aside>

      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex items-center justify-between border-b border-[var(--color-bo-border)] bg-[var(--color-bo-surface)] px-4 py-3">
          <span className="font-semibold md:hidden">Loyalty Hub</span>
          <span className="hidden text-sm text-[var(--color-bo-ink-2)] md:inline">Backoffice</span>
          <div className="flex items-center gap-2">
            <div className="flex h-8 w-8 items-center justify-center rounded-full bg-[var(--color-bo-accent)] text-xs font-bold text-white">
              {initials}
            </div>
            <div className="text-right leading-tight">
              <div className="text-sm font-medium">{persona?.displayName}</div>
              <div className="text-xs text-[var(--color-bo-ink-2)]">{persona?.role}</div>
            </div>
          </div>
        </header>

        <main className="flex-1 p-6">{children}</main>
      </div>

      <KeepAlive />
    </div>
  );
}
