"use client";

import { useMemo, useState } from "react";
import { useLhMutation, useLhQuery, type LhError, type Page } from "@/lib/api/client";
import type { Leaderboard, LeaderboardMetric, LeaderboardPeriod, LeaderboardRanking, MemberView } from "@/lib/api/types";
import { QueryState } from "@/components/bo/QueryState";
import { Can, useCan } from "@/components/bo/Can";
import { SideSheet } from "@/components/bo/SideSheet";
import { Card, CardBody } from "@/components/ui/card";
import { CodeText, PageHeader, StatusPill } from "@/components/bo/primitives";
import { Field, INPUT } from "@/components/bo/FormBits";
import { LB_METRIC_LABEL, LB_PERIOD_LABEL, periodKeyLabel } from "@/lib/gamification/leaderboards";
import { actionLabel } from "@/lib/campaign/describe";
import { formatPoints } from "@/lib/format/points";
import { formatDateTime } from "@/lib/format/dates";
import { cn } from "@/lib/cn";

// BO-16 Classifiche (docs/08 §BO-16): elenco (nome, metrica, periodo, top N, stato) e dettaglio con configurazione e
// anteprima del periodo scelto; nickname e nome reale affiancati (il portale mostra solo il nickname). Il nome reale
// arriva dal member-service: se non risponde, l'anteprima resta leggibile coi soli nickname.
export default function LeaderboardsPage() {
  const list = useLhQuery<Leaderboard[]>("gamification", "/v1/leaderboards");
  const [selected, setSelected] = useState<string | null>(null);
  const [editing, setEditing] = useState<Leaderboard | "new" | null>(null);
  const current = selected ?? list.data?.[0]?.code ?? null;
  return (
    <div>
      <PageHeader
        title="Classifiche"
        subtitle="Punteggi per periodo; parimerito a chi ci è arrivato prima. I membri non attivi restano fuori."
        actions={
          <Can capability="object.edit" mode="disable">
            <button onClick={() => setEditing("new")} className="rounded bg-[var(--color-bo-accent)] px-3 py-1.5 text-sm font-medium text-white hover:opacity-90">
              Nuova classifica
            </button>
          </Can>
        }
      />
      <QueryState query={list} service="gamification" isEmpty={(d) => d.length === 0} emptyTitle="Nessuna classifica" emptyHint="Crea la prima classifica.">
        {(d) => (
          <div className="grid gap-4 lg:grid-cols-[minmax(0,2fr)_minmax(0,3fr)]">
            <ul className="space-y-2">
              {d.map((l) => (
                <li key={l.code}>
                  <button
                    onClick={() => setSelected(l.code)}
                    className={cn(
                      "w-full rounded-lg border bg-white p-3 text-left",
                      l.code === current ? "border-[var(--color-bo-accent)] ring-1 ring-[var(--color-bo-accent)]" : "border-[var(--color-bo-border)] hover:bg-slate-50",
                    )}
                  >
                    <div className="flex items-start justify-between gap-2">
                      <span className="font-medium">{l.name}</span>
                      <StatusPill status={l.status} />
                    </div>
                    <div className="mt-1 flex flex-wrap items-center gap-2 text-xs text-[var(--color-bo-ink-2)]">
                      <CodeText>{l.code}</CodeText>
                      <span>{LB_METRIC_LABEL[l.metric]}</span>·<span>{LB_PERIOD_LABEL[l.period]}</span>·<span>top {l.topN}</span>
                    </div>
                    {l.actionTypes.length ? <p className="mt-1 text-xs text-[var(--color-bo-ink-2)]">{l.actionTypes.map(actionLabel).join(", ")}</p> : null}
                  </button>
                </li>
              ))}
            </ul>
            {current ? <Preview key={current} code={current} onEdit={() => setEditing(d.find((l) => l.code === current) ?? null)} /> : null}
          </div>
        )}
      </QueryState>
      <SideSheet open={editing != null} title={editing === "new" ? "Nuova classifica" : editing ? editing.name : ""} onClose={() => setEditing(null)}>
        {editing != null ? <LeaderboardEditor key={editing === "new" ? "new" : editing.code} leaderboard={editing === "new" ? null : editing} onDone={() => setEditing(null)} /> : null}
      </SideSheet>
    </div>
  );
}

function Preview({ code, onEdit }: { code: string; onEdit: () => void }) {
  const [periodKey, setPeriodKey] = useState<string>("");
  const ranking = useLhQuery<LeaderboardRanking>("gamification", `/v1/leaderboards/${code}/ranking`, { periodKey }, { refetchInterval: 15_000 });
  const members = useLhQuery<Page<MemberView>>("member", "/v1/members", { size: 100 });
  const realName = useMemo(() => {
    const m = new Map((members.data?.items ?? []).map((x) => [x.id, [x.firstName, x.lastName].filter(Boolean).join(" ")]));
    return (id: string) => m.get(id) || null;
  }, [members.data]);
  return (
    <Card>
      <CardBody className="space-y-3 pt-4">
        <QueryState query={ranking} service="gamification">
          {(r) => (
            <>
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div>
                  <h2 className="text-sm font-semibold">{r.name}</h2>
                  <p className="text-xs text-[var(--color-bo-ink-2)]">
                    {LB_METRIC_LABEL[r.metric]} · {periodKeyLabel(r.periodKey)}
                    {r.periodKey === r.currentPeriodKey ? " (in corso)" : ""}
                  </p>
                </div>
                <div className="flex items-center gap-2">
                  <select aria-label="Periodo" value={r.periodKey} onChange={(e) => setPeriodKey(e.target.value)} className="rounded border border-[var(--color-bo-border)] bg-white px-2 py-1 text-sm">
                    {r.periods.map((p) => (
                      <option key={p} value={p}>
                        {periodKeyLabel(p)}
                      </option>
                    ))}
                  </select>
                  <button onClick={onEdit} className="rounded border border-[var(--color-bo-border)] px-2.5 py-1 text-sm hover:bg-slate-50">
                    Configura
                  </button>
                </div>
              </div>
              {r.items.length === 0 ? (
                <p className="rounded-md bg-slate-50 p-3 text-sm text-[var(--color-bo-ink-2)]">Nessun punteggio in questo periodo.</p>
              ) : (
                <div className="overflow-x-auto rounded-md border border-[var(--color-bo-border)]">
                  <table className="w-full text-sm">
                    <thead className="border-b border-[var(--color-bo-border)] bg-slate-50 text-left text-xs uppercase tracking-wide text-[var(--color-bo-ink-2)]">
                      <tr>
                        <th className="px-3 py-2 font-medium">#</th>
                        <th className="px-3 py-2 font-medium">Nickname (portale)</th>
                        <th className="px-3 py-2 font-medium">Nome reale</th>
                        <th className="px-3 py-2 text-right font-medium">Punteggio</th>
                        <th className="px-3 py-2 font-medium">Raggiunto</th>
                      </tr>
                    </thead>
                    <tbody>
                      {r.items.map((i) => (
                        <tr key={i.memberId} className="border-b border-[var(--color-bo-border)] last:border-0">
                          <td className="px-3 py-2 tabular-nums">{i.rank}</td>
                          <td className="px-3 py-2 font-medium">{i.nickname ?? "—"}</td>
                          <td className="px-3 py-2 text-xs">
                            {realName(i.memberId) ?? <span className="text-[var(--color-bo-ink-2)]">{members.isError ? "member non raggiungibile" : "—"}</span>}{" "}
                            <CodeText>{i.memberId}</CodeText>
                          </td>
                          <td className="px-3 py-2 text-right tabular-nums">{formatPoints(i.score)}</td>
                          <td className="px-3 py-2 text-xs text-[var(--color-bo-ink-2)]">{formatDateTime(i.reachedAt)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </>
          )}
        </QueryState>
      </CardBody>
    </Card>
  );
}

function LeaderboardEditor({ leaderboard: l, onDone }: { leaderboard: Leaderboard | null; onDone: () => void }) {
  const canEdit = useCan("object.edit");
  const [f, setF] = useState({
    code: l?.code ?? "LDB-",
    name: l?.name ?? "",
    metric: (l?.metric ?? "PTS_EARNED") as LeaderboardMetric,
    actionTypes: (l?.actionTypes ?? []).join(", "),
    period: (l?.period ?? "MONTH") as LeaderboardPeriod,
    topN: String(l?.topN ?? 10),
    status: l?.status ?? "ACTIVE",
  });
  const [error, setError] = useState<LhError | null>(null);
  const save = useLhMutation<Leaderboard, Record<string, unknown>>("gamification", l ? "PUT" : "POST", () => (l ? `/v1/leaderboards/${l.code}` : "/v1/leaderboards"), {
    onSuccess: onDone,
  });
  return (
    <form
      className="space-y-3"
      onSubmit={(e) => {
        e.preventDefault();
        setError(null);
        save.mutate(
          {
            code: l ? undefined : f.code.trim().toUpperCase(),
            name: f.name,
            metric: f.metric,
            actionTypes: f.actionTypes.split(",").map((t) => t.trim()).filter(Boolean),
            period: f.period,
            topN: Number(f.topN),
            status: f.status,
          },
          { onError: setError },
        );
      }}
    >
      {l ? <p className="rounded-md bg-amber-50 p-2 text-xs text-amber-900">Metrica e periodo non si cambiano: i punteggi raccolti non sarebbero ricalcolabili.</p> : null}
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label="Nome">
          <input required value={f.name} disabled={!canEdit} onChange={(e) => setF({ ...f, name: e.target.value })} className={INPUT} />
        </Field>
        <Field label="Codice">
          <input required value={f.code} disabled={!canEdit || l != null} onChange={(e) => setF({ ...f, code: e.target.value.toUpperCase() })} className={`${INPUT} font-mono`} />
        </Field>
        <Field label="Metrica">
          <select value={f.metric} disabled={!canEdit || l != null} onChange={(e) => setF({ ...f, metric: e.target.value as LeaderboardMetric })} className={INPUT}>
            {Object.entries(LB_METRIC_LABEL).map(([k, v]) => (
              <option key={k} value={k}>
                {v}
              </option>
            ))}
          </select>
        </Field>
        <Field label="Periodo">
          <select value={f.period} disabled={!canEdit || l != null} onChange={(e) => setF({ ...f, period: e.target.value as LeaderboardPeriod })} className={INPUT}>
            {Object.entries(LB_PERIOD_LABEL).map(([k, v]) => (
              <option key={k} value={k}>
                {v}
              </option>
            ))}
          </select>
        </Field>
      </div>
      {f.metric === "ACTION_COUNT" ? (
        <Field label="Azioni conteggiate" hint="Codici separati da virgola, es. quiz.completed">
          <input value={f.actionTypes} disabled={!canEdit} onChange={(e) => setF({ ...f, actionTypes: e.target.value })} className={`${INPUT} font-mono`} />
        </Field>
      ) : null}
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label="Top N" hint="Tra 3 e 50.">
          <input type="number" min={3} max={50} value={f.topN} disabled={!canEdit} onChange={(e) => setF({ ...f, topN: e.target.value })} className={INPUT} />
        </Field>
        <Field label="Stato">
          <select value={f.status} disabled={!canEdit} onChange={(e) => setF({ ...f, status: e.target.value as "ACTIVE" | "INACTIVE" })} className={INPUT}>
            <option value="ACTIVE">Attiva</option>
            <option value="INACTIVE">Non attiva</option>
          </select>
        </Field>
      </div>
      {canEdit ? (
        <div className="flex items-center gap-3">
          <button type="submit" disabled={save.isPending} className="rounded bg-[var(--color-bo-accent)] px-3 py-1.5 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50">
            {l ? "Salva" : "Crea classifica"}
          </button>
          {error ? (
            <span role="alert" className="text-xs text-red-700">
              {error.detail || error.code}
            </span>
          ) : null}
        </div>
      ) : null}
    </form>
  );
}
