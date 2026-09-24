"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { Bell } from "lucide-react";
import { useLhQuery } from "@/lib/api/client";
import { UNREAD_POLL_MS, unreadBadge } from "@/lib/messages/inbox";
import type { UnreadCount } from "@/lib/messages/types";
import { cn } from "@/lib/cn";
import { useActiveMember } from "./MemberContext";

// Campanella della shell (docs/09 §1): contatore dei non letti da engagement, ogni 30 s e a ogni fatto ricevuto
// (PendingContext invalida la query all'arrivo dei fatti del giro). Tocco → PT-12. Engagement che dorme o risponde
// male → la campanella resta, senza contatore (nessun errore mostrato al membro).
// SPEC-GAP: Q-76 — il portale ascolta l'SSE solo durante l'attesa di un'azione (PendingContext): i messaggi nati fuori
// da un'azione del membro (es. scadenze, azioni da BO-28) arrivano col polling dei 30 s.
export const UNREAD_PATH = "/v1/portal/inbox/unread-count";

export function useUnreadCount() {
  const memberId = useActiveMember();
  return useLhQuery<UnreadCount>("engagement", UNREAD_PATH, { memberId }, { refetchInterval: UNREAD_POLL_MS });
}

export function InboxBell() {
  const pathname = usePathname();
  const unread = useUnreadCount();
  const badge = unread.isError ? null : unreadBadge(unread.data?.unread);
  const active = pathname === "/portal/inbox";
  return (
    <Link
      href="/portal/inbox"
      aria-label={badge ? `Notifiche, ${unread.data?.unread} non lette` : "Notifiche"}
      className={cn(
        "relative inline-flex size-11 items-center justify-center rounded-full text-[var(--color-pt-night)] hover:bg-black/5",
        active && "bg-black/5",
      )}
    >
      <Bell className="size-5" aria-hidden />
      {badge ? (
        <span
          aria-hidden
          className="absolute right-1 top-1 inline-flex min-w-[18px] items-center justify-center rounded-full bg-[var(--color-pt-coin)] px-1 text-[10px] font-bold leading-[18px] text-[var(--color-pt-night)]"
        >
          {badge}
        </span>
      ) : null}
    </Link>
  );
}
