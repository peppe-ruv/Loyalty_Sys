"use client";

import { Gift } from "lucide-react";
import { cn } from "@/lib/cn";

// Tre pacchi (docs/09 §PT-06, meccanica BOX, Q-56): qualunque si scelga contiene l'esito deciso dal server.
export function GiftBoxes({
  chosen,
  opened,
  disabled,
  onChoose,
}: {
  chosen: number | null;
  opened: boolean;
  disabled: boolean;
  onChoose: (i: number) => void;
}) {
  const colors = ["var(--color-pt-primary)", "var(--color-pt-secondary)", "var(--color-pt-coin)"];
  return (
    <div className="grid grid-cols-3 gap-3" role="group" aria-label="Scegli un pacco">
      {colors.map((c, i) => (
        <button
          key={i}
          type="button"
          disabled={disabled}
          onClick={() => onChoose(i)}
          aria-label={`Apri il pacco ${i + 1}`}
          className={cn(
            "flex aspect-square items-center justify-center rounded-2xl shadow-md transition disabled:cursor-default",
            chosen != null && chosen !== i && "opacity-40",
          )}
          style={{ background: c, animation: chosen === i && opened ? "lh-box-pop 0.9s ease-out" : undefined }}
        >
          <Gift className="size-10 text-white" aria-hidden />
        </button>
      ))}
    </div>
  );
}
