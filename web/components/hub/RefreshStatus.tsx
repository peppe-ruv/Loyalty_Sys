"use client";

import { useEffect, useState, useTransition } from "react";
import { useRouter } from "next/navigation";
import { RefreshCw } from "lucide-react";
import { Button } from "@/components/ui/button";
import { WAKE_RETRY_MAX_MS, WAKE_RETRY_MS } from "@/components/shared/QueryState";
import { it } from "@/lib/i18n/it";

// HUB-02: lo stato si calcola sul server durante il rendering, quindi «Aggiorna» e «Riprova» rifanno il rendering della
// pagina (`router.refresh()`), senza endpoint dedicati. Nel degraded riprova da solo ogni 5 s fino a 90 s (docs/07 §6).

export function RefreshStatusButton({ label = it.hubEnterprise.refresh }: { label?: string }) {
  const router = useRouter();
  const [pending, startTransition] = useTransition();
  return (
    <Button variant="ghost" disabled={pending} onClick={() => startTransition(() => router.refresh())}>
      <RefreshCw className={pending ? "h-4 w-4 animate-spin" : "h-4 w-4"} aria-hidden /> {label}
    </Button>
  );
}

export function AutoRetryNote() {
  const router = useRouter();
  const [gaveUp, setGaveUp] = useState(false);

  // Il router di Next è stabile tra i rendering: l'intervallo parte una volta sola per riquadro montato.
  useEffect(() => {
    let elapsed = 0;
    const timer = setInterval(() => {
      elapsed += WAKE_RETRY_MS;
      router.refresh();
      if (elapsed >= WAKE_RETRY_MAX_MS) {
        clearInterval(timer);
        setGaveUp(true);
      }
    }, WAKE_RETRY_MS);
    return () => clearInterval(timer);
  }, [router]);

  return (
    <p className="mt-1 text-xs" data-testid="auto-retry">
      {gaveUp ? it.hubEnterprise.degradedGaveUp : it.hubEnterprise.degradedHint}
    </p>
  );
}
