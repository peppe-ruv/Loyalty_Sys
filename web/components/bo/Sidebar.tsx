import Link from "next/link";
import { NavLinks } from "./NavLinks";

// Sidebar del backoffice (docs/08 §1): gruppi, voce attiva con barra teal. Solo voci già realizzate.
// Fissa da md in su; sotto md la navigazione è nel cassetto mobile (MobileNav).
export function Sidebar() {
  return (
    <aside className="hidden w-60 shrink-0 flex-col bg-[var(--color-bo-ink)] px-3 py-5 text-[#c9d3e0] md:flex">
      <Link href="/" className="mb-6 px-2 text-lg font-semibold text-white">
        Loyalty Hub
      </Link>
      <NavLinks />
      <Link href="/" className="mt-4 px-2 text-xs text-[#8b98ad] hover:text-white">
        ← Demo Hub
      </Link>
    </aside>
  );
}
