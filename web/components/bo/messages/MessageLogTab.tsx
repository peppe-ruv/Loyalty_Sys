"use client";

import { useState } from "react";
import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useLhQuery, type Page } from "@/lib/api/client";
import type { MemberView } from "@/lib/api/types";
import { CATEGORIES, CATEGORY_LABEL, CHANNELS, CHANNEL_LABEL } from "@/lib/messages/inbox";
import { messageIcon } from "@/lib/messages/icons";
import type { MessageLogEntry } from "@/lib/messages/types";
import { formatDateTime } from "@/lib/format/dates";
import { QueryState } from "@/components/bo/QueryState";
import { DataTable, type Column } from "@/components/bo/DataTable";
import { SideSheet } from "@/components/bo/SideSheet";
import { INPUT } from "@/components/bo/FormBits";
import { CodeText } from "@/components/bo/primitives";
import { MessagePreview } from "./MessagePreview";
import { cn } from "@/lib/cn";

const FILTER = INPUT.replace("w-full ", "");
const SIZE = 20;

// BO-19 `log` (docs/08 §BO-19): registro dei messaggi consegnati (GET /v1/messages), tutti i canali, dal più recente.
// Filtri membro / categoria / canale (+ template, dal link dell'editor) nell'URL; paginazione `page/size`. Tocco sulla
// riga → anteprima: la riga dell'inbox per INAPP, la finta e-mail per EMAIL_FAKE (nessun invio reale).
// SPEC-GAP: Q-84 — i seed (docs/10 §7) hanno solo template INAPP: le anteprime e-mail compaiono dopo aver creato un
// template EMAIL_FAKE e una regola che lo usa.
export function MessageLogTab() {
  const search = useSearchParams();
  const router = useRouter();
  const pathname = usePathname();
  const memberId = search.get("member") ?? "";
  const category = search.get("category") ?? "";
  const channel = search.get("channel") ?? "";
  const templateCode = search.get("template") ?? "";
  const page = Math.max(0, Number(search.get("page") ?? "0") || 0);
  const members = useLhQuery<Page<MemberView>>("member", "/v1/members", { size: 100 });
  const log = useLhQuery<Page<MessageLogEntry>>("engagement", "/v1/messages", { memberId, category, channel, templateCode, page, size: SIZE });
  const [open, setOpen] = useState<MessageLogEntry | null>(null);

  function setParam(key: string, value: string) {
    const next = new URLSearchParams(search.toString());
    if (value) next.set(key, value);
    else next.delete(key);
    if (key !== "page") next.delete("page");
    router.replace(`${pathname}?${next.toString()}`);
  }

  const memberName = (id: string) => {
    const m = members.data?.items.find((x) => x.id === id);
    return m?.firstName ? `${m.firstName} ${m.lastName ?? ""}`.trim() : id;
  };

  const columns: Column<MessageLogEntry>[] = [
    { key: "when", header: "Quando", className: "whitespace-nowrap", render: (m) => <span className="text-xs tabular-nums">{formatDateTime(m.createdAt)}</span> },
    {
      key: "member",
      header: "Membro",
      render: (m) => (
        <Link href={`/backoffice/members/${m.memberId}`} onClick={(e) => e.stopPropagation()} className="hover:underline">
          {memberName(m.memberId)} <span className="block"><CodeText>{m.memberId}</CodeText></span>
        </Link>
      ),
    },
    {
      key: "message",
      header: "Messaggio",
      render: (m) => {
        const Icon = messageIcon(m.icon, m.category);
        return (
          <span className="flex items-start gap-2">
            <Icon className="mt-0.5 size-4 shrink-0 text-[var(--color-bo-accent)]" aria-hidden />
            <span className="flex flex-col">
              <span className="font-medium">{m.title}</span>
              <span className="line-clamp-1 text-xs text-[var(--color-bo-ink-2)]">{m.body}</span>
            </span>
          </span>
        );
      },
    },
    { key: "template", header: "Template", className: "whitespace-nowrap", render: (m) => <CodeText>{m.templateCode}</CodeText> },
    {
      key: "channel",
      header: "Canale",
      render: (m) =>
        m.channel === "EMAIL_FAKE" ? (
          <span className="inline-flex whitespace-nowrap rounded-full bg-violet-100 px-2 py-0.5 text-xs font-medium text-violet-800">Anteprima e-mail</span>
        ) : (
          <span className="inline-flex whitespace-nowrap rounded-full bg-sky-100 px-2 py-0.5 text-xs font-medium text-sky-800">{CHANNEL_LABEL.INAPP}</span>
        ),
    },
    { key: "category", header: "Categoria", render: (m) => <span className="text-xs">{CATEGORY_LABEL[m.category] ?? m.category}</span> },
    { key: "read", header: "Letto", render: (m) => <span className="text-xs">{m.channel === "EMAIL_FAKE" ? "—" : m.readAt ? "sì" : "no"}</span> },
    { key: "source", header: "Origine", render: (m) => <span className="font-mono text-[11px] text-[var(--color-bo-ink-2)]">{m.sourceType ?? "—"}</span> },
  ];

  const info = log.data?.page;
  return (
    <>
      <div className="mb-3 flex flex-wrap gap-2">
        <select aria-label="Membro" value={memberId} onChange={(e) => setParam("member", e.target.value)} className={cn(FILTER, "w-56")}>
          <option value="">Tutti i membri</option>
          {(members.data?.items ?? []).map((m) => (
            <option key={m.id} value={m.id}>{m.firstName ? `${m.firstName} ${m.lastName ?? ""} · ${m.id}` : m.id}</option>
          ))}
          {memberId && !(members.data?.items ?? []).some((m) => m.id === memberId) ? <option value={memberId}>{memberId}</option> : null}
        </select>
        <select aria-label="Categoria" value={category} onChange={(e) => setParam("category", e.target.value)} className={cn(FILTER, "w-40")}>
          <option value="">Tutte le categorie</option>
          {CATEGORIES.map((c) => <option key={c} value={c}>{CATEGORY_LABEL[c]}</option>)}
        </select>
        <select aria-label="Canale" value={channel} onChange={(e) => setParam("channel", e.target.value)} className={cn(FILTER, "w-44")}>
          <option value="">Tutti i canali</option>
          {CHANNELS.map((c) => <option key={c} value={c}>{CHANNEL_LABEL[c]}</option>)}
        </select>
        {templateCode ? (
          <button onClick={() => setParam("template", "")} className="inline-flex items-center gap-1 rounded-full border border-[var(--color-bo-border)] px-2 text-xs" aria-label="Rimuovi il filtro sul template">
            template <CodeText>{templateCode}</CodeText> ✕
          </button>
        ) : null}
      </div>
      <QueryState
        query={log}
        service="engagement"
        isEmpty={(d) => d.items.length === 0}
        emptyTitle="Nessun messaggio"
        emptyHint="Nessun messaggio con questi filtri. I messaggi nascono dalle regole di notifica e dalle campagne con SEND_MESSAGE."
      >
        {(d) => (
          <>
            <DataTable columns={columns} rows={d.items} rowKey={(m) => m.id} onRowClick={setOpen} />
            <div className="mt-2 flex items-center justify-between text-xs text-[var(--color-bo-ink-2)]">
              <span>
                {info ? `${info.totalItems} messaggi · pagina ${info.number + 1} di ${Math.max(1, info.totalPages)}` : null}
              </span>
              <span className="flex gap-2">
                <button disabled={page === 0} onClick={() => setParam("page", String(page - 1))} className="rounded border border-[var(--color-bo-border)] px-2 py-1 disabled:opacity-40">
                  ← Precedente
                </button>
                <button
                  disabled={!info || page + 1 >= info.totalPages}
                  onClick={() => setParam("page", String(page + 1))}
                  className="rounded border border-[var(--color-bo-border)] px-2 py-1 disabled:opacity-40"
                >
                  Successiva →
                </button>
              </span>
            </div>
          </>
        )}
      </QueryState>
      <SideSheet
        open={open != null}
        title={open ? <span className="flex flex-col"><span className="font-semibold">{open.channel === "EMAIL_FAKE" ? "Anteprima e-mail" : "Messaggio in app"}</span><CodeText>{open.id}</CodeText></span> : ""}
        onClose={() => setOpen(null)}
      >
        {open ? (
          <div className="space-y-4">
            <MessagePreview message={open} recipient={`${memberName(open.memberId)} (${open.memberId})`} />
            <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
              <dt className="text-[var(--color-bo-ink-2)]">Inviato</dt>
              <dd>{formatDateTime(open.createdAt)}</dd>
              <dt className="text-[var(--color-bo-ink-2)]">Letto</dt>
              <dd>{open.channel === "EMAIL_FAKE" ? "— (non entra nell'inbox)" : open.readAt ? formatDateTime(open.readAt) : "non ancora"}</dd>
              <dt className="text-[var(--color-bo-ink-2)]">Template</dt>
              <dd><CodeText>{open.templateCode}</CodeText></dd>
              <dt className="text-[var(--color-bo-ink-2)]">Origine</dt>
              <dd className="font-mono text-xs">{open.sourceType ?? "—"} · {open.sourceEventId}</dd>
              {open.correlationId ? (
                <>
                  <dt className="text-[var(--color-bo-ink-2)]">Tracciato</dt>
                  <dd>
                    <Link href={`/backoffice/observe/traces?c=${open.correlationId}`} className="text-[var(--color-bo-accent)] hover:underline">
                      Apri il tracciato →
                    </Link>
                  </dd>
                </>
              ) : null}
            </dl>
          </div>
        ) : null}
      </SideSheet>
    </>
  );
}
