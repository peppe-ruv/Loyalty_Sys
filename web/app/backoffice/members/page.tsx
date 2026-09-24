"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { useLhQuery, type Page } from "@/lib/api/client";
import type { MemberView } from "@/lib/api/types";
import type { Segment } from "@/lib/segments/types";
import { QueryState } from "@/components/bo/QueryState";
import { DataTable, type Column } from "@/components/bo/DataTable";
import { PageHeader, StatusPill, TierBadge, CodeText, PointsAmount } from "@/components/bo/primitives";
import { isAnonymized, memberDisplayName, personalValue } from "@/lib/member/anonymized";

// BO-02 Membri (docs/08 §BO-02): elenco con filtri testo/stato/tier/segmento (M6.6). Riga → scheda 360°.
const STATUSES = ["", "ACTIVE", "BLOCKED", "INACTIVE", "ANONYMIZED"];
const TIERS = ["", "BASE", "SILVER", "GOLD", "PLATINUM"];

export default function MembersPage() {
  const router = useRouter();
  const [q, setQ] = useState("");
  const [status, setStatus] = useState("");
  const [tier, setTier] = useState("");
  const [segment, setSegment] = useState("");

  const query = useLhQuery<Page<MemberView>>("member", "/v1/members", { q, status, tier, segment, size: 50 });
  // Filtro per segmento (docs/08 §BO-02, M6.6): i segmenti attivi di member.
  const segments = useLhQuery<Page<Segment>>("member", "/v1/segments", { status: "ACTIVE", size: 100 });

  const columns: Column<MemberView>[] = [
    {
      key: "member",
      header: "Membro",
      render: (m) => (
        <span className={isAnonymized(m.status) ? "italic text-slate-400" : ""}>{memberDisplayName(m)}</span>
      ),
    },
    { key: "id", header: "ID", render: (m) => <CodeText>{m.id}</CodeText> },
    {
      key: "email",
      header: "E-mail",
      render: (m) => <span className={isAnonymized(m.status) ? "italic text-slate-400" : ""}>{personalValue(m.status, m.email)}</span>,
    },
    { key: "status", header: "Stato", render: (m) => <StatusPill status={m.status} /> },
    { key: "tier", header: "Tier", render: (m) => <TierBadge tier={m.tier} /> },
    { key: "pts", header: "Saldo PTS", className: "text-right", render: (m) => <PointsAmount value={m.balancePts} /> },
    { key: "sts", header: "STS", className: "text-right", render: (m) => <span className="tabular-nums">{m.periodSts}</span> },
  ];

  return (
    <div>
      <PageHeader
        title="Membri"
        subtitle={query.data ? `${query.data.page.totalItems} membri` : "Anagrafica dei membri"}
      />
      <div className="mb-3 flex flex-wrap gap-2">
        <input
          value={q}
          onChange={(e) => setQ(e.target.value)}
          placeholder="Cerca per nome, e-mail, ID…"
          className="w-64 rounded border border-[var(--color-bo-border)] px-2 py-1.5 text-sm"
        />
        <select value={status} onChange={(e) => setStatus(e.target.value)} className="rounded border border-[var(--color-bo-border)] px-2 py-1.5 text-sm">
          {STATUSES.map((s) => (
            <option key={s} value={s}>{s === "" ? "Tutti gli stati" : s}</option>
          ))}
        </select>
        <select value={tier} onChange={(e) => setTier(e.target.value)} className="rounded border border-[var(--color-bo-border)] px-2 py-1.5 text-sm">
          {TIERS.map((t) => (
            <option key={t} value={t}>{t === "" ? "Tutti i tier" : t}</option>
          ))}
        </select>
        {segments.data && segments.data.items.length > 0 ? (
          <select aria-label="Segmento" value={segment} onChange={(e) => setSegment(e.target.value)} className="rounded border border-[var(--color-bo-border)] px-2 py-1.5 text-sm">
            <option value="">Tutti i segmenti</option>
            {segments.data.items.map((s) => (
              <option key={s.code} value={s.code}>{s.name}</option>
            ))}
          </select>
        ) : null}
      </div>
      <QueryState query={query} service="member" isEmpty={(d) => d.items.length === 0} emptyTitle="Nessun membro">
        {(d) => (
          <DataTable
            columns={columns}
            rows={d.items}
            rowKey={(m) => m.id}
            onRowClick={(m) => router.push(`/backoffice/members/${m.id}`)}
          />
        )}
      </QueryState>
    </div>
  );
}
