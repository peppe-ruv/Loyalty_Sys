import Link from "next/link";
import { KeepAlive } from "@/components/shared/KeepAlive";

// Shell del portale (docs/07 §2, §5.3): mobile-first. Tema Aurora e tessera arrivano con M1 (docs/09).
export default function PortalLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="mx-auto min-h-dvh max-w-md bg-[var(--color-pt-bg)]">
      <header className="flex items-center justify-between px-4 py-3">
        <span className="font-semibold text-[var(--color-pt-night)]">Loyalty Hub</span>
        <Link href="/" className="text-sm text-[var(--color-pt-night)]/60">
          Demo Hub
        </Link>
      </header>
      <main className="px-4 pb-20">{children}</main>
      <KeepAlive />
    </div>
  );
}
