"use client";

import { useState } from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { lhFetch, useLhQuery } from "@/lib/api/client";
import { ulid } from "@/lib/ids";
import type { PortalContest } from "@/lib/api/types";
import { cn } from "@/lib/cn";
import { PendingProvider, usePending } from "./PendingContext";
import { useActiveMember, switchMember } from "./MemberContext";

// Shell del portale (docs/09 §1): tab bar Home · Guadagna · Premi · Gioca · Io (pallino su Gioca se c'è una giocata
// disponibile) + tray demo PT-14. L'attività resta raggiungibile dalla Home.
const TABS = [
  { href: "/portal", label: "Home" },
  { href: "/portal/earn", label: "Guadagna" },
  { href: "/portal/rewards", label: "Premi", also: ["/portal/my-rewards"] },
  { href: "/portal/play", label: "Gioca", dot: true },
  { href: "/portal/profile", label: "Io", also: ["/portal/activity"] },
];

export function PortalShell({ children }: { children: React.ReactNode }) {
  return (
    <PendingProvider>
      <div className="pb-24">{children}</div>
      <TabBar />
      <DemoTray />
    </PendingProvider>
  );
}

function TabBar() {
  const pathname = usePathname();
  const memberId = useActiveMember();
  const contests = useLhQuery<PortalContest[]>("gamification", "/v1/portal/contests", { memberId }, { refetchInterval: 60_000 });
  const canPlay = (contests.data ?? []).some((c) => c.playsAvailable > 0);
  return (
    <nav className="fixed inset-x-0 bottom-0 z-20 mx-auto flex max-w-md justify-around border-t border-[var(--color-bo-border)] bg-white/95 py-2 backdrop-blur">
      {TABS.map((t) => {
        const under = (h: string) => pathname === h || pathname.startsWith(h + "/");
        const active = t.href === "/portal" ? pathname === t.href : under(t.href) || ("also" in t && (t.also ?? []).some(under));
        return (
          <Link
            key={t.href}
            href={t.href}
            className={cn("relative min-w-16 text-center text-xs", active ? "font-semibold text-[var(--color-pt-primary)]" : "text-[var(--color-pt-night)]/60")}
          >
            {t.label}
            {"dot" in t && t.dot && canPlay ? (
              <span className="absolute -top-0.5 right-3 size-2 rounded-full bg-[var(--color-pt-coin)]" aria-label="giocata disponibile" />
            ) : null}
          </Link>
        );
      })}
    </nav>
  );
}

interface PersonaView {
  memberId: string;
  name: string;
  tier: string;
  story: string | null;
}

// Ogni azione entra dalla sua fonte reale (docs seed/sources.json): l'evento passa da /v1/events come una
// qualsiasi azione esterna, non dal simulatore admin (che il portale — attore membro — non può chiamare).
const ACTIONS: { label: string; type: string; source: string; data: (amount: number) => Record<string, unknown> }[] = [
  { label: "Acquisto", type: "purchase.completed", source: "ecommerce", data: (a) => ({ orderId: "ORD-" + Date.now(), amount: a, currency: "EUR", channel: "ONLINE" }) },
  { label: "Accesso all'app", type: "app.login.daily", source: "app", data: () => ({ platform: "IOS" }) },
  { label: "Attiva bolletta digitale", type: "ebill.activated", source: "billing", data: () => ({ contractId: "CTR-" + Date.now() }) },
  { label: "Attiva domiciliazione", type: "directdebit.activated", source: "billing", data: () => ({ contractId: "CTR-" + Date.now() }) },
  { label: "Invia autolettura", type: "selfreading.submitted", source: "app", data: () => ({ meterId: "MTR-1", reading: 14820 }) },
  { label: "Completa sondaggio", type: "survey.completed", source: "partner", data: () => ({ surveyId: "SRV-1", score: 80 }) },
];

function DemoTray() {
  const [open, setOpen] = useState(false);
  const [amount, setAmount] = useState(130);
  const [busy, setBusy] = useState(false);
  const memberId = useActiveMember();
  const { markPending } = usePending();
  const personas = useLhQuery<PersonaView[]>("member", "/v1/demo/personas", undefined, { enabled: open });

  async function fire(type: string, source: string, data: Record<string, unknown>) {
    setBusy(true);
    try {
      // Azione reale del membro dalla fonte esterna: /v1/events (pubblico), non il simulatore admin.
      const res = await lhFetch<{ correlationId?: string }>("ingestion", "/v1/events", {
        method: "POST",
        body: JSON.stringify({
          specversion: "1.0",
          id: ulid(),
          source,
          type,
          subject: "member:" + memberId,
          time: new Date().toISOString(),
          data,
        }),
      });
      // Il correlationId dell'azione: l'attesa si chiude via SSE al fatto del wallet (usePendingTrace, M2.3).
      markPending(["Punti in arrivo…"], res?.correlationId);
      setOpen(false);
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <button
        onClick={() => setOpen(true)}
        className="fixed bottom-16 right-4 z-30 rounded-full border border-dashed border-[var(--color-pt-night)]/40 bg-white px-3 py-1.5 font-mono text-xs shadow"
      >
        Demo
      </button>
      {open ? (
        <div className="fixed inset-0 z-40 flex items-end justify-center bg-black/30" onClick={() => setOpen(false)}>
          <div
            onClick={(e) => e.stopPropagation()}
            className="w-full max-w-md rounded-t-2xl border border-dashed border-[var(--color-pt-night)]/40 bg-white p-4 font-mono"
          >
            <div className="mb-2 flex items-center justify-between">
              <span className="text-sm font-semibold">Pannello demo</span>
              <button onClick={() => setOpen(false)} className="text-sm text-slate-500">chiudi</button>
            </div>

            <p className="mb-1 text-xs text-slate-500">Chi sei</p>
            <div className="mb-3 max-h-32 space-y-1 overflow-y-auto">
              {(personas.data ?? []).map((p) => (
                <button
                  key={p.memberId}
                  onClick={() => switchMember(p.memberId)}
                  className={cn(
                    "flex w-full items-center justify-between rounded border px-2 py-1 text-left text-xs",
                    p.memberId === memberId ? "border-[var(--color-pt-primary)] bg-[var(--color-pt-primary)]/5" : "border-slate-200",
                  )}
                >
                  <span>{p.name} · {p.tier}</span>
                  <span className="text-slate-400">{p.memberId}</span>
                </button>
              ))}
            </div>

            <p className="mb-1 text-xs text-slate-500">Fai accadere qualcosa (membro {memberId})</p>
            <div className="mb-2 flex items-center gap-2 text-xs">
              importo €
              <input type="number" value={amount} onChange={(e) => setAmount(Number(e.target.value))} className="w-20 rounded border border-slate-200 px-1 py-0.5" />
            </div>
            <div className="grid grid-cols-2 gap-1.5">
              {ACTIONS.map((a) => (
                <button
                  key={a.label}
                  disabled={busy}
                  onClick={() => fire(a.type, a.source, a.data(amount))}
                  className="rounded border border-slate-200 px-2 py-1 text-xs hover:bg-slate-50 disabled:opacity-50"
                >
                  {a.label}
                </button>
              ))}
            </div>

            <div className="mt-3 border-t border-slate-100 pt-2 text-xs">
              <Link href="/backoffice" className="text-[var(--color-pt-secondary)]">Apri il backoffice →</Link>
            </div>
          </div>
        </div>
      ) : null}
    </>
  );
}
