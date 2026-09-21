"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { visibleNav } from "@/lib/nav";
import { cn } from "@/lib/cn";

// Elenco dei gruppi/voci della sidebar (docs/08 §1), condiviso tra la sidebar fissa (desktop) e il
// cassetto mobile. {@code onNavigate} chiude il cassetto quando si apre una voce.
export function NavLinks({ onNavigate }: { onNavigate?: () => void }) {
  const pathname = usePathname();
  const groups = visibleNav();

  return (
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
                    onClick={onNavigate}
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
  );
}
