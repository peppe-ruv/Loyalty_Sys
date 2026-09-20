"use client";

import { useMemo, useState } from "react";
import { useLhQuery } from "@/lib/api/client";
import { QueryState } from "@/components/bo/QueryState";
import { PageHeader } from "@/components/bo/primitives";
import { KpiTile } from "@/components/bo/dashboard/KpiTile";
import { PeriodSelector } from "@/components/bo/dashboard/PeriodSelector";
import { PointsAreaChart, type AreaRow } from "@/components/bo/dashboard/PointsAreaChart";
import { SourceBars } from "@/components/bo/dashboard/SourceBars";
import type { KpiBreakdown, KpiOverview, KpiTimeSeries, Period } from "@/lib/api/insight";

// BO-01 — Dashboard (docs/08 §BO-01, F-INS-03/04). Salute del programma in un colpo d'occhio: KpiTile con
// delta e sparkline, area impilata punti + barre azioni per fonte. In M2 sono vive le metriche di M1/M2;
// distribuzione tier, passività e top campagne/premi arrivano con M3/M4 (voci non ancora renderizzate).

export default function DashboardPage() {
  const [period, setPeriod] = useState<Period>(30);
  const q = { days: period } as const;

  const overview = useLhQuery<KpiOverview>("insight", "/v1/kpi/overview", q, { refetchInterval: 30000 });
  const earned = useLhQuery<KpiTimeSeries>("insight", "/v1/kpi/timeseries", { metric: "points_earned", ...q });
  const spent = useLhQuery<KpiTimeSeries>("insight", "/v1/kpi/timeseries", { metric: "points_spent", ...q });
  const expired = useLhQuery<KpiTimeSeries>("insight", "/v1/kpi/timeseries", { metric: "points_expired", ...q });
  const actionsTs = useLhQuery<KpiTimeSeries>("insight", "/v1/kpi/timeseries", { metric: "actions", ...q });
  const activeTs = useLhQuery<KpiTimeSeries>("insight", "/v1/kpi/timeseries", { metric: "members_active", ...q });
  const bySource = useLhQuery<KpiBreakdown>("insight", "/v1/kpi/breakdown", { metric: "actions", dimension: "source", ...q, limit: 5 });

  const areaRows = useMemo(
    () => mergeArea(earned.data, spent.data, expired.data),
    [earned.data, spent.data, expired.data],
  );

  return (
    <div>
      <PageHeader
        title="Dashboard"
        subtitle="Salute del programma in un colpo d'occhio. I giorni dello storico sono dati dimostrativi."
        actions={<PeriodSelector value={period} onChange={setPeriod} />}
      />

      {/* Riga 1 — KpiTile con delta e sparkline. */}
      <QueryState query={overview} service="insight">
        {(o) => (
          <div className="grid grid-cols-2 gap-3 md:grid-cols-3 xl:grid-cols-6">
            <KpiTile label="Membri attivi" value={o.membersActive} delta={o.deltas.membersActive} spark={spark(activeTs.data)} />
            <KpiTile label="Azioni ricevute" value={o.actions} delta={o.deltas.actions} spark={spark(actionsTs.data)} />
            <KpiTile label="PTS emessi" value={o.pointsEarned} unit="PTS" delta={o.deltas.pointsEarned} spark={spark(earned.data)} />
            <KpiTile label="PTS spesi" value={o.pointsSpent} unit="PTS" delta={o.deltas.pointsSpent} spark={spark(spent.data)} />
            <KpiTile label="Richieste premio" value={o.redemptions} delta={o.deltas.redemptions} hint="da M4" />
            <KpiTile label="Giocate / vincite" value={o.plays} hint="da M5" />
          </div>
        )}
      </QueryState>

      {/* Riga 2 — area impilata punti + barre azioni per fonte. */}
      <div className="mt-4 grid gap-4 lg:grid-cols-[3fr_2fr]">
        <Section title="Punti per giorno" hint="Emessi, spesi e scaduti">
          <QueryState query={earned} service="insight">
            {() => <PointsAreaChart rows={areaRows} />}
          </QueryState>
        </Section>
        <Section title="Azioni per fonte" hint="Ripartizione nel periodo">
          <QueryState query={bySource} service="insight" isEmpty={(d) => d.slices.length === 0}
            emptyTitle="Nessuna azione" emptyHint="Invia un'azione dal simulatore o dal portale.">
            {(d) => <SourceBars slices={d.slices} total={d.total} />}
          </QueryState>
        </Section>
      </div>

      {/* Riga 3 — "Da guardare" (le fonti mancanti arrivano con M3/M4/M7). */}
      <div className="mt-4">
        <Section title="Da guardare" hint="Segnali operativi">
          <QueryState query={overview} service="insight">
            {(o) => (
              <div className="grid grid-cols-2 gap-3 text-sm sm:grid-cols-4">
                <Watch label="Nuovi membri nel periodo" value={o.membersNew} />
                <Watch label="Cambi livello" value={o.tierChanges} />
                <Watch label="Punti emessi netti" value={o.pointsEarned - o.pointsSpent} unit="PTS" />
                <Watch label="Membri totali" value={o.membersTotal} />
              </div>
            )}
          </QueryState>
          <p className="mt-3 text-xs text-[var(--color-bo-ink-2)]">
            Richieste da evadere, oggetti in revisione, stock e DLQ aperte compaiono con le milestone M4 e M7.
          </p>
        </Section>
      </div>
    </div>
  );
}

function Section({ title, hint, children }: { title: string; hint?: string; children: React.ReactNode }) {
  return (
    <section className="rounded-lg border border-[var(--color-bo-border)] bg-white p-4">
      <div className="mb-3 flex items-baseline justify-between gap-2">
        <h2 className="text-sm font-semibold text-[var(--color-bo-ink)]">{title}</h2>
        {hint ? <span className="text-xs text-[var(--color-bo-ink-2)]">{hint}</span> : null}
      </div>
      {children}
    </section>
  );
}

function Watch({ label, value, unit }: { label: string; value: number; unit?: string }) {
  return (
    <div className="rounded-md border border-[var(--color-bo-border)] bg-[var(--color-bo-bg)] px-3 py-2">
      <p className="text-xs text-[var(--color-bo-ink-2)]">{label}</p>
      <p className="mt-0.5 text-lg font-semibold tabular-nums text-[var(--color-bo-ink)]">
        {new Intl.NumberFormat("it-IT").format(value)}
        {unit ? <span className="ml-1 text-xs font-normal text-[var(--color-bo-ink-2)]">{unit}</span> : null}
      </p>
    </div>
  );
}

function spark(ts?: KpiTimeSeries): number[] | undefined {
  if (!ts || ts.points.length < 2) return undefined;
  return ts.points.map((p) => p.value);
}

/** Fonde le tre serie punti su un asse-giorno comune per l'area impilata. */
function mergeArea(earned?: KpiTimeSeries, spent?: KpiTimeSeries, expired?: KpiTimeSeries): AreaRow[] {
  if (!earned) return [];
  const rows = new Map<string, AreaRow>();
  const put = (ts: KpiTimeSeries | undefined, key: "emitted" | "spent" | "expired") => {
    for (const p of ts?.points ?? []) {
      const row = rows.get(p.day) ?? { day: p.day, emitted: 0, spent: 0, expired: 0, synthetic: false };
      row[key] = p.value;
      row.synthetic = row.synthetic || p.synthetic;
      rows.set(p.day, row);
    }
  };
  put(earned, "emitted");
  put(spent, "spent");
  put(expired, "expired");
  return [...rows.values()].sort((a, b) => a.day.localeCompare(b.day));
}
