"use client";

import { categoryIcon } from "@/lib/reward/icons";
import { cn } from "@/lib/cn";

// Immagine del premio nel portale: foto se c'è, altrimenti l'icona della categoria su fondo tenue.
export function RewardArt({
  imageUrl,
  icon,
  className,
  muted = false,
}: {
  imageUrl: string | null;
  icon: string | null | undefined;
  className?: string;
  muted?: boolean;
}) {
  if (imageUrl) {
    // eslint-disable-next-line @next/next/no-img-element -- immagini da public/demo o URL del catalogo
    return <img src={imageUrl} alt="" className={cn("w-full object-cover", muted && "opacity-50 grayscale", className)} />;
  }
  const Icon = categoryIcon(icon);
  return (
    <div className={cn("flex w-full items-center justify-center bg-[var(--color-pt-primary)]/10", muted && "opacity-50", className)}>
      <Icon className="size-9 text-[var(--color-pt-primary)]" aria-hidden />
    </div>
  );
}
