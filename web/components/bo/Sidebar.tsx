"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { visibleNav } from "@/lib/nav";
import { cn } from "@/lib/cn";

// Sidebar del backoffice (docs/08 §1): gruppi, voce attiva con barra teal. Solo voci già realizzate.
export function Sidebar() {
  const pathname = usePathname();
  const groups = visibleNav();

  return (
    <aside className="hidden w-60 shrink-0 flex-col bg-[var(--color-bo-ink)] px-3 py-5 text-[#c9d3e0] md:flex">
      <Link href="/" className="mb-6 px-2 text-lg font-semibold text-white">
        Loyalty Hub
      </Link>
      <nav className="flex-1 space-y-5 text-sm">
        {groups.map((g) => (
          <div key={g.label}>
            <p className="px-2 pb-1 text-xs uppercase tracking-wide text-[#6b7890]">{g.label}</p>
            <ul>
              {g.items.map((item) => {
                const active = pathname === item.href || pathname.startsWith(item.href + "/");
                return (
                  <li key={item.id}>
                    <Link
                      href={item.href}
                      className={cn(
                        "flex items-center gap-2 rounded border-l-2 px-2 py-1.5",
                        active
                          ? "border-[var(--color-bo-accent)] bg-white/5 text-white"
                          : "border-transparent text-[#8b98ad] hover:text-white",
                      )}
                    >
                      {item.label}
                    </Link>
                  </li>
                );
              })}
            </ul>
          </div>
        ))}
      </nav>
      <Link href="/" className="mt-4 px-2 text-xs text-[#8b98ad] hover:text-white">
        ← Demo Hub
      </Link>
    </aside>
  );
}
