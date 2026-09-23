"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { activeHref, visibleNav, type NavItem } from "@/lib/nav";
import { useLhQuery, type Page } from "@/lib/api/client";
import { cn } from "@/lib/cn";

// Elenco dei gruppi/voci della sidebar (docs/08 §1), condiviso tra la sidebar fissa (desktop) e il
// cassetto mobile. {@code onNavigate} chiude il cassetto quando si apre una voce.
export function NavLinks({ onNavigate }: { onNavigate?: () => void }) {
  const pathname = usePathname();
  const groups = visibleNav();
  const current = activeHref(pathname, groups);

  return (
    <nav className="flex-1 space-y-5 text-sm">
      {groups.map((g) => (
        <div key={g.label}>
          <p className="px-2 pb-1 text-xs uppercase tracking-wide text-[#6b7890]">{g.label}</p>
          <ul>
            {g.items.map((item) => {
              const active = item.href === current;
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
                    <span className="flex-1">{item.label}</span>
                    {item.counter ? <NavCounter kind={item.counter} /> : null}
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

/** Richieste premio da gestire: confermate a evasione manuale + da verificare (insiemi disgiunti). */
function NavCounter({ kind }: { kind: NonNullable<NavItem["counter"]> }) {
  const opts = { refetchInterval: 30_000 };
  const todo = useLhQuery<Page<unknown>>("reward", "/v1/redemptions", { status: "CONFIRMED", fulfilment: "MANUAL", size: 1 }, opts);
  const attention = useLhQuery<Page<unknown>>("reward", "/v1/redemptions", { needsAttention: "true", size: 1 }, opts);
  if (kind !== "redemptions" || todo.data == null || attention.data == null) return null;
  const n = todo.data.page.totalItems + attention.data.page.totalItems;
  if (n === 0) return null;
  return (
    <span className="rounded-full bg-[var(--color-bo-accent)] px-1.5 text-xs font-medium tabular-nums text-white" aria-label={`${n} da gestire`}>
      {n}
    </span>
  );
}
