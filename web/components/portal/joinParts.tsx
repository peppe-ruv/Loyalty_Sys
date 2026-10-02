"use client";

import { lhFetch } from "@/lib/api/client";
import { cn } from "@/lib/cn";

// Parti comuni alle due varianti di PT-08 (demo: app/portal/join/page.tsx; enterprise: EnterpriseJoin).

const WALLET_WAIT_MS = 20_000;

/** Attende che il wallet del nuovo membro esista (i punti di benvenuto arrivano via evento); allo scadere si procede. */
export async function waitForWallet(path: string, waitMs: number = WALLET_WAIT_MS, pollMs: number = 1000): Promise<void> {
  const deadline = Date.now() + waitMs;
  while (Date.now() < deadline) {
    try {
      await lhFetch("wallet", path);
      return;
    } catch {
      await new Promise((r) => setTimeout(r, pollMs));
    }
  }
}

export function Preparing() {
  return (
    <div className="space-y-3 py-10 text-center" role="status">
      <div className="mx-auto size-10 animate-spin rounded-full border-4 border-[var(--color-pt-primary)]/20 border-t-[var(--color-pt-primary)]" />
      <p className="font-medium text-[var(--color-pt-night)]">Stiamo preparando la tua tessera…</p>
      <p className="text-sm text-[var(--color-pt-night)]/60">I 100 punti di benvenuto sono in arrivo.</p>
    </div>
  );
}

export function JoinField({ label, error, hint, children }: { label: string; error?: string; hint?: string; children: React.ReactNode }) {
  return (
    <label className="block text-sm">
      <span className="mb-1 block font-medium text-[var(--color-pt-night)]">{label}</span>
      {children}
      {error ? <span className="mt-1 block text-xs text-red-700">{error}</span> : hint ? <span className="mt-1 block text-xs text-[var(--color-pt-night)]/60">{hint}</span> : null}
    </label>
  );
}

export function inputCls(error?: string): string {
  return cn(
    "w-full rounded-lg border px-3 py-2 text-sm text-[var(--color-pt-night)] outline-none focus:ring-2 focus:ring-[var(--color-pt-primary)]/30",
    error ? "border-red-400" : "border-[var(--color-bo-border)]",
  );
}
