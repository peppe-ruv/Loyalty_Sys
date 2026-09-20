"use client";

import { useLhQuery } from "@/lib/api/client";
import { QueryState } from "@/components/bo/QueryState";
import { formatPoints } from "@/lib/format/points";
import type { CampaignStats } from "@/lib/api/types";
import { DailyBars } from "./DailyBars";

// Statistiche campagna (F-CMP-10, docs/08 §BO-05/06): attivazioni, membri unici, punti erogati, budget
// residuo + serie giornaliera 30 giorni. Dati: campaign GET /v1/campaigns/{id}/stats.

export function CampaignStatsPanel({ campaignId }: { campaignId: string }) {
  const query = useLhQuery<CampaignStats>("campaign", `/v1/campaigns/${campaignId}/stats`, undefined, {
    refetchInterval: 10000,
  });

  return (
    <QueryState query={query} service="campaign">
      {(s) => (
        <div className="space-y-4">
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
            <Stat label="Attivazioni" value={s.matches} />
            <Stat label="Membri unici" value={s.uniqueMembers} />
            <Stat label="Punti decisi" value={s.pointsDecided} unit="PTS" />
            <Stat label="Punti erogati" value={s.pointsGranted} unit="PTS" />
          </div>

          {s.budget && (s.budget.maxPoints != null || s.budget.maxMatches != null) ? (
            <div className="rounded-md border border-[var(--color-bo-border)] p-3">
              <p className="mb-2 text-xs font-medium text-[var(--color-bo-ink-2)]">Budget</p>
              {s.budget.maxPoints != null ? (
                <BudgetBar
                  label="Punti"
                  used={s.pointsDecided}
                  max={s.budget.maxPoints}
                  remaining={s.budget.remainingPoints ?? 0}
                  unit="PTS"
                />
              ) : null}
              {s.budget.maxMatches != null ? (
                <BudgetBar
                  label="Attivazioni"
                  used={s.matches}
                  max={s.budget.maxMatches}
                  remaining={s.budget.remainingMatches ?? 0}
                />
              ) : null}
            </div>
          ) : (
            <p className="text-xs text-[var(--color-bo-ink-2)]">Nessun budget impostato per questa campagna.</p>
          )}

          <div className="grid gap-4 lg:grid-cols-2">
            <ChartCard title="Attivazioni al giorno" subtitle="Ultimi 30 giorni">
              <DailyBars data={s.daily.map((d) => ({ day: d.day, value: d.matches }))} colorVar="var(--color-topic-actions)" />
            </ChartCard>
            <ChartCard title="Punti decisi al giorno" subtitle="Ultimi 30 giorni">
              <DailyBars data={s.daily.map((d) => ({ day: d.day, value: d.points }))} colorVar="var(--color-earn)" unit="PTS" />
            </ChartCard>
          </div>
        </div>
      )}
    </QueryState>
  );
}

function Stat({ label, value, unit }: { label: string; value: number; unit?: string }) {
  return (
    <div className="rounded-md border border-[var(--color-bo-border)] bg-white p-3">
      <p className="text-xs text-[var(--color-bo-ink-2)]">{label}</p>
      <p className="mt-0.5 text-xl font-semibold tabular-nums text-[var(--color-bo-ink)]">
        {formatPoints(value)}
        {unit ? <span className="ml-1 text-xs font-normal text-[var(--color-bo-ink-2)]">{unit}</span> : null}
      </p>
    </div>
  );
}

function BudgetBar({ label, used, max, remaining, unit }: { label: string; used: number; max: number; remaining: number; unit?: string }) {
  const pct = max > 0 ? Math.min(100, (used / max) * 100) : 0;
  const tone = pct >= 90 ? "var(--color-expire)" : pct >= 70 ? "var(--color-spend)" : "var(--color-bo-accent)";
  return (
    <div className="mb-2 last:mb-0">
      <div className="mb-1 flex items-baseline justify-between text-xs">
        <span className="text-[var(--color-bo-ink)]">{label}</span>
        <span className="tabular-nums text-[var(--color-bo-ink-2)]">
          {formatPoints(used)} / {formatPoints(max)}
          {unit ? ` ${unit}` : ""} · residuo {formatPoints(remaining)}
        </span>
      </div>
      <div className="h-2.5 w-full overflow-hidden rounded-sm bg-[var(--color-bo-bg)]">
        <div className="h-full rounded-sm" style={{ width: `${Math.max(2, pct)}%`, background: tone }} />
      </div>
    </div>
  );
}

function ChartCard({ title, subtitle, children }: { title: string; subtitle?: string; children: React.ReactNode }) {
  return (
    <section className="rounded-md border border-[var(--color-bo-border)] bg-white p-3">
      <div className="mb-2 flex items-baseline justify-between gap-2">
        <h3 className="text-xs font-semibold text-[var(--color-bo-ink)]">{title}</h3>
        {subtitle ? <span className="text-[11px] text-[var(--color-bo-ink-2)]">{subtitle}</span> : null}
      </div>
      {children}
    </section>
  );
}
