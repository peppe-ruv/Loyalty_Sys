"use client";

import type { SegmentType } from "@/lib/segments/types";
import { cn } from "@/lib/cn";

// Tipo di segmento (BO-04, BO-03): dinamico = criteri ricalcolati, statico = elenco manuale.
export function TypePill({ type }: { type: SegmentType }) {
  return (
    <span
      className={cn(
        "inline-flex rounded-full px-2 py-0.5 text-xs font-medium",
        type === "DYNAMIC" ? "bg-sky-100 text-sky-800" : "bg-violet-100 text-violet-800",
      )}
    >
      {type === "DYNAMIC" ? "Dinamico" : "Statico"}
    </span>
  );
}
