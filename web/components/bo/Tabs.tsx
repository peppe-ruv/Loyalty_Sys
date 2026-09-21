"use client";

import Link from "next/link";
import { usePathname, useSearchParams } from "next/navigation";
import { cn } from "@/lib/cn";

// Schede guidate dall'URL (?tab=), così lo stato è condivisibile e navigabile (docs/08 §3.1).
export interface TabDef {
  key: string;
  label: string;
}

export function Tabs({ tabs, param = "tab", current }: { tabs: TabDef[]; param?: string; current: string }) {
  const pathname = usePathname();
  const search = useSearchParams();

  function href(key: string): string {
    const next = new URLSearchParams(search.toString());
    next.set(param, key);
    return `${pathname}?${next.toString()}`;
  }

  return (
    <div className="mb-4 flex gap-1 border-b border-[var(--color-bo-border)]">
      {tabs.map((t) => (
        <Link
          key={t.key}
          href={href(t.key)}
          className={cn(
            "border-b-2 px-3 py-2 text-sm",
            t.key === current
              ? "border-[var(--color-bo-accent)] font-medium text-[var(--color-bo-ink)]"
              : "border-transparent text-[var(--color-bo-ink-2)] hover:text-[var(--color-bo-ink)]",
          )}
        >
          {t.label}
        </Link>
      ))}
    </div>
  );
}
