"use client";

import { useState } from "react";
import { useLhQuery } from "@/lib/api/client";
import { useActiveMember } from "@/components/portal/MemberContext";
import { usePending } from "@/components/portal/PendingContext";
import { QueryState } from "@/components/bo/QueryState";
import { PendingBanner, ActivityRow, type ActivityItem } from "@/components/portal/parts";
import { formatDateTime } from "@/lib/format/dates";

// PT-07 Attività (docs/09 §PT-07): movimenti per giorno, filtro per valuta, riga "in arrivo…".
const FILTERS = [
  { key: "", label: "Tutti" },
  { key: "PTS", label: "Punti" },
  { key: "STS", label: "Punti status" },
];

export default function ActivityPage() {
  const memberId = useActiveMember();
  const { pending } = usePending();
  const [currency, setCurrency] = useState("");
  const query = useLhQuery<ActivityItem[]>("wallet", `/v1/portal/wallets/${memberId}/activity`, {
    currency,
    size: 100,
  }, { refetchInterval: pending ? 5000 : undefined });

  return (
    <div className="space-y-4">
      <h1 className="text-lg font-semibold text-[var(--color-pt-night)]">La mia attività</h1>
      <PendingBanner />
      <div className="flex gap-1.5">
        {FILTERS.map((f) => (
          <button
            key={f.key}
            onClick={() => setCurrency(f.key)}
            className={
              "rounded-full px-3 py-1 text-xs " +
              (currency === f.key ? "bg-[var(--color-pt-primary)] text-white" : "bg-white text-[var(--color-pt-night)]/70 border border-[var(--color-bo-border)]")
            }
          >
            {f.label}
          </button>
        ))}
      </div>
      <QueryState
        query={query}
        service="wallet"
        isEmpty={(d) => d.length === 0}
        emptyTitle="Nessun movimento ancora"
        emptyHint="Scopri come guadagnare punti dalla sezione «Guadagna»."
      >
        {(d) => <ActivityGroups items={d} />}
      </QueryState>
    </div>
  );
}

function ActivityGroups({ items }: { items: ActivityItem[] }) {
  const groups = new Map<string, ActivityItem[]>();
  for (const item of items) {
    const day = formatDateTime(item.occurredAt).split(",")[0];
    const list = groups.get(day) ?? [];
    list.push(item);
    groups.set(day, list);
  }
  return (
    <div className="space-y-4">
      {[...groups.entries()].map(([day, list]) => (
        <section key={day}>
          <h2 className="mb-1 text-xs font-medium uppercase tracking-wide text-[var(--color-pt-night)]/50">{day}</h2>
          <ul className="rounded-xl border border-[var(--color-bo-border)] bg-white px-3">
            {list.map((item) => (
              <ActivityRow key={item.id} item={item} />
            ))}
          </ul>
        </section>
      ))}
    </div>
  );
}
