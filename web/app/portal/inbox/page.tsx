"use client";

import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useQueryClient } from "@tanstack/react-query";
import { lhFetch, useLhQuery, type Page } from "@/lib/api/client";
import { useActiveMember } from "@/components/portal/MemberContext";
import { UNREAD_PATH, useUnreadCount } from "@/components/portal/InboxBell";
import { UNREAD_POLL_MS, inboxHref, markAllReadLocally, markReadLocally, newArrivals, relativeWhen } from "@/lib/messages/inbox";
import { messageIcon } from "@/lib/messages/icons";
import type { PortalMessage, ReadAllOutcome } from "@/lib/messages/types";
import { cn } from "@/lib/cn";

// PT-12 Notifiche (docs/09 §PT-12, F-MSG-01): elenco cronologico con icona per categoria, titolo, testo e quando; i non
// letti hanno il pallino e il fondo leggero. Tocco → segna letto e segue il link (solo percorsi del portale).
// "Segna tutte come lette". Arrivo in tempo reale: la campanella (30 s e a ogni fatto) fa ricaricare l'elenco; le righe
// nuove compaiono in cima evidenziate. Engagement che dorme → riquadro gentile, niente gergo tecnico.
// Route `/portal/inbox` come da tabella di docs/09 §1.
const INBOX_PATH = "/v1/portal/inbox";
const PAGE = 20;
const MAX_SIZE = 100; // tetto del servizio (InboxService.MAX_SIZE)

export default function InboxPage() {
  const memberId = useActiveMember();
  const router = useRouter();
  const qc = useQueryClient();
  const [size, setSize] = useState(PAGE);
  const query = { memberId, size };
  const inbox = useLhQuery<Page<PortalMessage>>("engagement", INBOX_PATH, query, { refetchInterval: UNREAD_POLL_MS });
  const unread = useUnreadCount();
  const [busy, setBusy] = useState(false);

  // Un aumento dei non letti (campanella) = nuovi messaggi: ricarica l'elenco senza aspettare il suo intervallo.
  const lastUnread = useRef<number | undefined>(undefined);
  const refetchInbox = inbox.refetch;
  useEffect(() => {
    const n = unread.data?.unread;
    if (n !== undefined && lastUnread.current !== undefined && n > lastUnread.current) void refetchInbox();
    lastUnread.current = n;
  }, [unread.data?.unread, refetchInbox]);

  // Righe comparse dopo il primo caricamento: evidenziate per qualche secondo.
  const shown = useRef<PortalMessage[] | undefined>(undefined);
  const [fresh, setFresh] = useState<Set<string>>(new Set());
  const items = inbox.data?.items;
  useEffect(() => {
    if (!items) return;
    const arrivals = newArrivals(shown.current, items);
    shown.current = items;
    if (arrivals.size === 0) return;
    setFresh(arrivals);
    const t = setTimeout(() => setFresh(new Set()), 4_000);
    return () => clearTimeout(t);
  }, [items]);

  const setLocal = (update: (items: PortalMessage[]) => PortalMessage[]) =>
    qc.setQueryData<Page<PortalMessage>>(["engagement", INBOX_PATH, query], (old) => (old ? { ...old, items: update(old.items) } : old));
  const refreshUnread = () => qc.invalidateQueries({ queryKey: ["engagement", UNREAD_PATH] });

  async function open(m: PortalMessage) {
    const href = inboxHref(m.linkTarget);
    if (!m.read) {
      setLocal((list) => markReadLocally(list, m.id, new Date().toISOString()));
      try {
        await lhFetch<PortalMessage>("engagement", `${INBOX_PATH}/${encodeURIComponent(m.id)}/read`, {
          method: "POST",
          body: JSON.stringify({ memberId }),
        });
      } catch {
        // Lettura non registrata (servizio che dorme): al prossimo aggiornamento la voce torna non letta.
      }
      refreshUnread();
    }
    if (href) router.push(href);
  }

  async function readAll() {
    setBusy(true);
    setLocal((list) => markAllReadLocally(list, new Date().toISOString()));
    try {
      await lhFetch<ReadAllOutcome>("engagement", `${INBOX_PATH}/read-all`, { method: "POST", body: JSON.stringify({ memberId }) });
    } catch {
      void inbox.refetch();
    } finally {
      setBusy(false);
      refreshUnread();
    }
  }

  const hasUnread = (items ?? []).some((m) => !m.read);
  const total = inbox.data?.page.totalItems ?? 0;

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between gap-2">
        <h1 className="text-lg font-semibold text-[var(--color-pt-night)]">Notifiche</h1>
        {hasUnread ? (
          <button
            onClick={readAll}
            disabled={busy}
            className="min-h-11 rounded-full px-3 text-xs font-medium text-[var(--color-pt-secondary)] hover:bg-black/5 disabled:opacity-50"
          >
            Segna tutte come lette
          </button>
        ) : null}
      </div>

      {inbox.isLoading ? (
        <ul className="space-y-2" aria-label="Caricamento notifiche">
          {Array.from({ length: 4 }).map((_, i) => (
            <li key={i} className="h-16 animate-pulse rounded-xl bg-white" />
          ))}
        </ul>
      ) : inbox.isError ? (
        <div role="status" className="rounded-xl border border-[var(--color-bo-border)] bg-white p-4 text-sm text-[var(--color-pt-night)]">
          <p className="font-medium">{inbox.error.asleep ? "Stiamo recuperando le tue notifiche…" : "Non riusciamo a mostrare le notifiche adesso."}</p>
          <p className="mt-1 text-xs text-[var(--color-pt-night)]/60">Riprova tra qualche istante: i tuoi messaggi non vanno persi.</p>
          <button onClick={() => inbox.refetch()} className="mt-3 min-h-11 rounded-full border border-[var(--color-bo-border)] px-4 text-xs font-medium">
            Riprova
          </button>
        </div>
      ) : (items ?? []).length === 0 ? (
        <div className="rounded-xl border border-dashed border-[var(--color-bo-border)] bg-white p-6 text-center text-sm text-[var(--color-pt-night)]">
          <p className="font-medium">Nessuna notifica per ora</p>
          <p className="mt-1 text-xs text-[var(--color-pt-night)]/60">Qui trovi punti, livelli, premi e vincite appena succedono.</p>
          <Link href="/portal/earn" className="mt-3 inline-block text-xs font-medium text-[var(--color-pt-secondary)]">
            Scopri come guadagnare punti →
          </Link>
        </div>
      ) : (
        <>
          <ul className="divide-y divide-[var(--color-bo-border)] overflow-hidden rounded-xl border border-[var(--color-bo-border)] bg-white">
            {(items ?? []).map((m) => (
              <InboxRow key={m.id} message={m} fresh={fresh.has(m.id)} onOpen={() => open(m)} />
            ))}
          </ul>
          {total > (items ?? []).length && size < MAX_SIZE ? (
            <button
              onClick={() => setSize((s) => Math.min(MAX_SIZE, s + PAGE))}
              className="min-h-11 w-full rounded-full border border-[var(--color-bo-border)] bg-white text-xs font-medium text-[var(--color-pt-night)]"
            >
              Mostra le precedenti
            </button>
          ) : null}
        </>
      )}
    </div>
  );
}

function InboxRow({ message: m, fresh, onOpen }: { message: PortalMessage; fresh: boolean; onOpen: () => void }) {
  const Icon = messageIcon(m.icon, m.category);
  const followable = inboxHref(m.linkTarget) != null;
  return (
    <li>
      <button
        onClick={onOpen}
        aria-label={`${m.read ? "" : "Non letta: "}${m.title}`}
        className={cn(
          "flex w-full items-start gap-3 px-3 py-3 text-left transition-colors",
          m.read ? "bg-white" : "bg-[var(--color-pt-primary)]/5",
          fresh && "motion-safe:animate-pulse",
        )}
      >
        <span className="mt-0.5 inline-flex size-9 shrink-0 items-center justify-center rounded-full bg-[var(--color-pt-primary)]/10 text-[var(--color-pt-primary)]">
          <Icon className="size-4" aria-hidden />
        </span>
        <span className="min-w-0 flex-1">
          <span className="flex items-start justify-between gap-2">
            <span className={cn("text-sm text-[var(--color-pt-night)]", m.read ? "font-medium" : "font-semibold")}>{m.title}</span>
            <span className="shrink-0 text-[11px] text-[var(--color-pt-night)]/50">{relativeWhen(m.createdAt)}</span>
          </span>
          <span className="mt-0.5 block text-xs text-[var(--color-pt-night)]/70">{m.body}</span>
          {followable ? <span className="mt-1 block text-[11px] font-medium text-[var(--color-pt-secondary)]">Apri →</span> : null}
        </span>
        {m.read ? null : <span className="mt-2 size-2 shrink-0 rounded-full bg-[var(--color-pt-coin)]" aria-hidden />}
      </button>
    </li>
  );
}
