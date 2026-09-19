"use client";

import { useState } from "react";
import { useLhQuery } from "@/lib/api/client";
import { QueryState } from "@/components/bo/QueryState";
import { DataTable, type Column } from "@/components/bo/DataTable";
import { PageHeader, StatusPill, CodeText } from "@/components/bo/primitives";
import { formatDateTime } from "@/lib/format/dates";

// BO-26 Monitor ingressi (docs/08 §BO-26): eventi in ingresso per esito. Retry/match in M7.
interface InboundRow {
  id: string;
  eventId: string;
  sourceCode: string;
  typeCode: string;
  subject: string;
  memberId: string | null;
  receivedAt: string | null;
  status: string;
  rejectCode: string | null;
  rejectDetail: string | null;
  correlationId: string;
}

const OUTCOMES = ["", "ACCEPTED", "DUPLICATE", "REJECTED", "UNMATCHED"];

export default function InboundPage() {
  const [status, setStatus] = useState("");
  const query = useLhQuery<InboundRow[]>("ingestion", "/v1/inbound-events", { status, limit: 200 });

  const columns: Column<InboundRow>[] = [
    { key: "when", header: "Ricevuto", render: (e) => (e.receivedAt ? formatDateTime(e.receivedAt) : "—") },
    { key: "source", header: "Fonte", render: (e) => <CodeText>{e.sourceCode}</CodeText> },
    { key: "type", header: "Tipo", render: (e) => <span className="text-xs">{e.typeCode}</span> },
    { key: "member", header: "Membro", render: (e) => (e.memberId ? <CodeText>{e.memberId}</CodeText> : "—") },
    { key: "status", header: "Esito", render: (e) => <StatusPill status={e.status} /> },
    {
      key: "reject",
      header: "Motivo",
      render: (e) => (e.rejectCode ? <span className="text-xs text-red-700" title={e.rejectDetail ?? ""}>{e.rejectCode}</span> : "—"),
    },
  ];

  return (
    <div>
      <PageHeader title="Monitor ingressi" subtitle="Eventi in ingresso e loro esito" />
      <div className="mb-3 flex gap-1">
        {OUTCOMES.map((o) => (
          <button
            key={o}
            onClick={() => setStatus(o)}
            className={
              "rounded-full px-3 py-1 text-xs " +
              (status === o ? "bg-[var(--color-bo-accent)] text-white" : "bg-slate-100 text-slate-700")
            }
          >
            {o === "" ? "Tutti" : o}
          </button>
        ))}
      </div>
      <QueryState query={query} service="ingestion" isEmpty={(d) => d.length === 0} emptyTitle="Nessun evento in ingresso" emptyHint="Invia un'azione dal Simulatore eventi (BO-28).">
        {(d) => <DataTable columns={columns} rows={d} rowKey={(e) => e.id} />}
      </QueryState>
    </div>
  );
}
