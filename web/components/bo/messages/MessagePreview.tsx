"use client";

import { Mail } from "lucide-react";
import { messageIcon } from "@/lib/messages/icons";
import type { MessageCategory, MessageChannel } from "@/lib/messages/types";

// Anteprime di BO-19: la riga dell'inbox come la vede il membro in PT-12 (canale INAPP) oppure la finta e-mail
// (canale EMAIL_FAKE: nessun invio reale, docs/servizi/engagement-service.md §1 — si consulta solo qui).
export interface PreviewMessage {
  title: string;
  body: string;
  icon: string | null;
  category: MessageCategory;
  linkTarget: string | null;
  channel: MessageChannel;
}

export function MessagePreview({ message, recipient }: { message: PreviewMessage; recipient?: string }) {
  return message.channel === "EMAIL_FAKE" ? <EmailPreview message={message} recipient={recipient} /> : <InboxPreview message={message} />;
}

function InboxPreview({ message: m }: { message: PreviewMessage }) {
  const Icon = messageIcon(m.icon, m.category);
  return (
    <div className="rounded-2xl border border-[var(--color-bo-border)] bg-[var(--color-pt-bg)] p-3">
      <p className="mb-2 text-[11px] font-medium uppercase tracking-wide text-[var(--color-pt-night)]/50">Notifiche · come in PT-12</p>
      <div className="flex items-start gap-3 rounded-xl bg-white p-3">
        <span className="mt-0.5 inline-flex size-9 shrink-0 items-center justify-center rounded-full bg-[var(--color-pt-primary)]/10 text-[var(--color-pt-primary)]">
          <Icon className="size-4" aria-hidden />
        </span>
        <span className="min-w-0 flex-1">
          <span className="block text-sm font-semibold text-[var(--color-pt-night)]">{m.title || <em className="font-normal opacity-50">titolo vuoto</em>}</span>
          <span className="mt-0.5 block text-xs text-[var(--color-pt-night)]/70">{m.body || <em className="opacity-50">testo vuoto</em>}</span>
          {m.linkTarget ? <span className="mt-1 block font-mono text-[11px] text-[var(--color-pt-secondary)]">→ {m.linkTarget}</span> : null}
        </span>
        <span className="mt-2 size-2 shrink-0 rounded-full bg-[var(--color-pt-coin)]" aria-label="non letta" />
      </div>
    </div>
  );
}

function EmailPreview({ message: m, recipient }: { message: PreviewMessage; recipient?: string }) {
  return (
    <div className="overflow-hidden rounded-md border border-[var(--color-bo-border)] bg-white text-sm">
      <div className="flex items-center gap-2 border-b border-[var(--color-bo-border)] bg-slate-50 px-3 py-2 text-xs text-[var(--color-bo-ink-2)]">
        <Mail className="size-3.5" aria-hidden /> Anteprima e-mail · nessun invio reale
      </div>
      <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 border-b border-[var(--color-bo-border)] px-3 py-2 text-xs">
        <dt className="text-[var(--color-bo-ink-2)]">Da</dt>
        <dd>Club Aurora</dd>
        <dt className="text-[var(--color-bo-ink-2)]">A</dt>
        <dd>{recipient ?? "membro del programma"}</dd>
        <dt className="text-[var(--color-bo-ink-2)]">Oggetto</dt>
        <dd className="font-medium">{m.title || "—"}</dd>
      </dl>
      <div className="whitespace-pre-line px-3 py-3 text-[var(--color-bo-ink)]">{m.body || "—"}</div>
      {m.linkTarget ? <div className="px-3 pb-3 font-mono text-xs text-[var(--color-bo-accent)]">{m.linkTarget}</div> : null}
    </div>
  );
}
