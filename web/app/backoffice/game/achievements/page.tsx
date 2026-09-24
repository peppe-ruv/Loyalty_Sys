"use client";

import { useState } from "react";
import { useSearchParams } from "next/navigation";
import { useLhMutation, useLhQuery, type LhError } from "@/lib/api/client";
import type { Achievement, AchievementMetric, AchievementPeriod, BadgeView } from "@/lib/api/types";
import { QueryState } from "@/components/bo/QueryState";
import { DataTable, type Column } from "@/components/bo/DataTable";
import { Tabs } from "@/components/bo/Tabs";
import { Can, useCan } from "@/components/bo/Can";
import { SideSheet } from "@/components/bo/SideSheet";
import { CodeText, PageHeader, StatusPill } from "@/components/bo/primitives";
import { Field, INPUT } from "@/components/bo/FormBits";
import { GeneratedText } from "@/components/bo/GeneratedSentence";
import { ICON_CHOICES, METRIC_LABEL, PERIOD_LABEL, describeAchievement, gameIcon } from "@/lib/gamification/achievements";
import { actionLabel } from "@/lib/campaign/describe";
import { formatPoints } from "@/lib/format/points";

// BO-15 Obiettivi e badge (docs/08 §BO-15): `achievements` — metrica, tipi azione osservati, campo, traguardo,
// periodo, ripetibile, badge collegato, completamenti e membri in corso; editor con frase generata · `badges` — griglia
// con nome, descrizione, icona e quanti membri lo hanno.
const TABS = [
  { key: "achievements", label: "Obiettivi" },
  { key: "badges", label: "Badge" },
];

export default function AchievementsPage() {
  const tab = useSearchParams().get("tab") ?? "achievements";
  return (
    <div>
      <PageHeader title="Obiettivi e badge" subtitle="Traguardi che il membro raggiunge con le sue azioni; i badge li celebrano nel portale." />
      <Tabs tabs={TABS} current={tab} />
      {tab === "badges" ? <BadgesTab /> : <AchievementsTab />}
    </div>
  );
}

// ---------- obiettivi ----------

function AchievementsTab() {
  const list = useLhQuery<Achievement[]>("gamification", "/v1/achievements");
  const badges = useLhQuery<BadgeView[]>("gamification", "/v1/badges");
  const [editing, setEditing] = useState<Achievement | "new" | null>(null);
  const badgeName = (code: string | null) => (badges.data ?? []).find((b) => b.code === code)?.name ?? code;

  const columns: Column<Achievement>[] = [
    {
      key: "name",
      header: "Obiettivo",
      render: (a) => {
        const Icon = gameIcon(a.icon);
        return (
          <span className="flex items-center gap-2">
            <Icon className="size-4 shrink-0 text-[var(--color-bo-accent)]" aria-hidden />
            <span className="flex flex-col">
              <span className="font-medium">{a.name}</span>
              <CodeText>{a.code}</CodeText>
            </span>
          </span>
        );
      },
    },
    {
      key: "metric",
      header: "Metrica",
      render: (a) => (
        <span className="text-xs">
          {METRIC_LABEL[a.metric]}
          {a.sumField ? <span className="block font-mono text-[var(--color-bo-ink-2)]">{a.sumField}</span> : null}
        </span>
      ),
    },
    { key: "types", header: "Azioni osservate", render: (a) => <span className="text-xs">{a.actionTypes.map(actionLabel).join(", ")}</span> },
    {
      key: "target",
      header: "Traguardo",
      className: "text-right",
      render: (a) => (
        <span className="tabular-nums">
          {formatPoints(a.target)}
          {a.metric === "STREAK" ? (a.streakUnit === "WEEK" ? " sett." : " gg") : ""}
        </span>
      ),
    },
    {
      key: "period",
      header: "Periodo",
      render: (a) => (
        <span className="text-xs">
          {PERIOD_LABEL[a.period]}
          {a.repeatable ? " · ripetibile" : ""}
        </span>
      ),
    },
    { key: "badge", header: "Badge", render: (a) => <span className="text-xs">{a.badgeCode ? badgeName(a.badgeCode) : "—"}</span> },
    { key: "done", header: "Completati", className: "text-right", render: (a) => <span className="tabular-nums">{a.completions}</span> },
    { key: "running", header: "In corso", className: "text-right", render: (a) => <span className="tabular-nums">{a.inProgress}</span> },
    { key: "status", header: "Stato", render: (a) => <StatusPill status={a.status} /> },
  ];

  return (
    <>
      <div className="mb-3 flex justify-end">
        <Can capability="object.edit" mode="disable">
          <button
            onClick={() => setEditing("new")}
            className="rounded bg-[var(--color-bo-accent)] px-3 py-1.5 text-sm font-medium text-white hover:opacity-90"
          >
            Nuovo obiettivo
          </button>
        </Can>
      </div>
      <QueryState query={list} service="gamification" isEmpty={(d) => d.length === 0} emptyTitle="Nessun obiettivo" emptyHint="Crea il primo obiettivo.">
        {(d) => <DataTable columns={columns} rows={d} rowKey={(a) => a.id} onRowClick={setEditing} />}
      </QueryState>
      <SideSheet
        open={editing != null}
        title={editing === "new" ? "Nuovo obiettivo" : editing ? editing.name : ""}
        onClose={() => setEditing(null)}
      >
        {editing != null ? (
          <AchievementEditor
            key={editing === "new" ? "new" : editing.id}
            achievement={editing === "new" ? null : editing}
            badges={badges.data ?? []}
            onDone={() => setEditing(null)}
          />
        ) : null}
      </SideSheet>
    </>
  );
}

interface FormState {
  code: string;
  name: string;
  description: string;
  icon: string;
  actionTypes: string;
  metric: AchievementMetric;
  sumField: string;
  streakUnit: "DAY" | "WEEK";
  target: string;
  period: AchievementPeriod;
  repeatable: boolean;
  badgeCode: string;
  status: "ACTIVE" | "INACTIVE";
}

function AchievementEditor({ achievement: a, badges, onDone }: { achievement: Achievement | null; badges: BadgeView[]; onDone: () => void }) {
  const canEdit = useCan("object.edit");
  const [f, setF] = useState<FormState>({
    code: a?.code ?? "ACH-",
    name: a?.name ?? "",
    description: a?.description ?? "",
    icon: a?.icon ?? "award",
    actionTypes: (a?.actionTypes ?? []).join(", "),
    metric: a?.metric ?? "COUNT",
    sumField: a?.sumField ?? "data.amount",
    streakUnit: a?.streakUnit ?? "DAY",
    target: String(a?.target ?? 1),
    period: a?.period ?? "EVER",
    repeatable: a?.repeatable ?? false,
    badgeCode: a?.badgeCode ?? "",
    status: a?.status ?? "ACTIVE",
  });
  const [error, setError] = useState<LhError | null>(null);
  const save = useLhMutation<Achievement, Record<string, unknown>>(
    "gamification",
    a ? "PUT" : "POST",
    () => (a ? `/v1/achievements/${a.id}` : "/v1/achievements"),
    { onSuccess: onDone },
  );
  const types = f.actionTypes.split(",").map((t) => t.trim()).filter(Boolean);
  const set = <K extends keyof FormState>(k: K, v: FormState[K]) => setF((p) => ({ ...p, [k]: v }));
  const sentence = describeAchievement({
    metric: f.metric,
    actionTypes: types,
    target: Number(f.target) || 0,
    period: f.period,
    repeatable: f.repeatable,
    sumField: f.sumField,
    streakUnit: f.streakUnit,
  });

  return (
    <form
      className="space-y-3"
      onSubmit={(e) => {
        e.preventDefault();
        setError(null);
        save.mutate(
          {
            code: a ? undefined : f.code.trim().toUpperCase(),
            name: f.name,
            description: f.description || null,
            icon: f.icon,
            actionTypes: types,
            metric: f.metric,
            sumField: f.metric === "SUM" ? f.sumField : "",
            streakUnit: f.metric === "STREAK" ? f.streakUnit : null,
            target: Number(f.target),
            period: f.period,
            repeatable: f.repeatable,
            badgeCode: f.badgeCode,
            status: f.status,
          },
          { onError: setError },
        );
      }}
    >
      <GeneratedText text={sentence} />
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label="Nome">
          <input required value={f.name} disabled={!canEdit} onChange={(e) => set("name", e.target.value)} className={INPUT} />
        </Field>
        <Field label="Codice" hint={a ? undefined : "Es. ACH-PRIMO-QUIZ. Non si cambia dopo."}>
          <input required value={f.code} disabled={!canEdit || a != null} onChange={(e) => set("code", e.target.value.toUpperCase())} className={`${INPUT} font-mono`} />
        </Field>
      </div>
      <Field label="Descrizione per il membro">
        <input value={f.description} disabled={!canEdit} onChange={(e) => set("description", e.target.value)} className={INPUT} />
      </Field>
      <Field label="Azioni osservate" hint="Codici separati da virgola, es. purchase.completed. Le azioni interne contano solo se elencate.">
        <input value={f.actionTypes} disabled={!canEdit} onChange={(e) => set("actionTypes", e.target.value)} className={`${INPUT} font-mono`} />
      </Field>
      <div className="grid gap-3 sm:grid-cols-3">
        <Field label="Metrica">
          <select value={f.metric} disabled={!canEdit} onChange={(e) => set("metric", e.target.value as AchievementMetric)} className={INPUT}>
            {Object.entries(METRIC_LABEL).map(([k, l]) => (
              <option key={k} value={k}>
                {l}
              </option>
            ))}
          </select>
        </Field>
        {f.metric === "SUM" ? (
          <Field label="Campo da sommare">
            <input value={f.sumField} disabled={!canEdit} onChange={(e) => set("sumField", e.target.value)} className={`${INPUT} font-mono`} />
          </Field>
        ) : f.metric === "STREAK" ? (
          <Field label="Unità della serie">
            <select value={f.streakUnit} disabled={!canEdit} onChange={(e) => set("streakUnit", e.target.value as "DAY" | "WEEK")} className={INPUT}>
              <option value="DAY">Giorni</option>
              <option value="WEEK">Settimane</option>
            </select>
          </Field>
        ) : (
          <div />
        )}
        <Field label="Traguardo">
          <input type="number" min={1} value={f.target} disabled={!canEdit} onChange={(e) => set("target", e.target.value)} className={INPUT} />
        </Field>
      </div>
      <div className="grid gap-3 sm:grid-cols-3">
        <Field label="Periodo">
          <select value={f.period} disabled={!canEdit} onChange={(e) => set("period", e.target.value as AchievementPeriod)} className={INPUT}>
            {Object.entries(PERIOD_LABEL).map(([k, l]) => (
              <option key={k} value={k}>
                {l}
              </option>
            ))}
          </select>
        </Field>
        <Field group label="Ripetibile">
          <label className="inline-flex items-center gap-2 text-sm">
            <input type="checkbox" checked={f.repeatable} disabled={!canEdit} onChange={(e) => set("repeatable", e.target.checked)} />
            Una volta per periodo
          </label>
        </Field>
        <Field label="Badge collegato">
          <select value={f.badgeCode} disabled={!canEdit} onChange={(e) => set("badgeCode", e.target.value)} className={INPUT}>
            <option value="">Nessuno</option>
            {badges.map((b) => (
              <option key={b.code} value={b.code}>
                {b.name}
              </option>
            ))}
          </select>
        </Field>
      </div>
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label="Icona">
          <select value={f.icon} disabled={!canEdit} onChange={(e) => set("icon", e.target.value)} className={INPUT}>
            {ICON_CHOICES.map((i) => (
              <option key={i} value={i}>
                {i}
              </option>
            ))}
          </select>
        </Field>
        <Field label="Stato">
          <select value={f.status} disabled={!canEdit} onChange={(e) => set("status", e.target.value as "ACTIVE" | "INACTIVE")} className={INPUT}>
            <option value="ACTIVE">Attivo</option>
            <option value="INACTIVE">Non attivo</option>
          </select>
        </Field>
      </div>
      {canEdit ? (
        <div className="flex items-center gap-3">
          <button type="submit" disabled={save.isPending} className="rounded bg-[var(--color-bo-accent)] px-3 py-1.5 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50">
            {a ? "Salva" : "Crea obiettivo"}
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

// ---------- badge ----------

function BadgesTab() {
  const list = useLhQuery<BadgeView[]>("gamification", "/v1/badges");
  const achievements = useLhQuery<Achievement[]>("gamification", "/v1/achievements");
  const [editing, setEditing] = useState<BadgeView | "new" | null>(null);
  const achName = (code: string) => (achievements.data ?? []).find((a) => a.code === code)?.name ?? code;
  return (
    <>
      <div className="mb-3 flex justify-end">
        <Can capability="object.edit" mode="disable">
          <button onClick={() => setEditing("new")} className="rounded bg-[var(--color-bo-accent)] px-3 py-1.5 text-sm font-medium text-white hover:opacity-90">
            Nuovo badge
          </button>
        </Can>
      </div>
      <QueryState query={list} service="gamification" isEmpty={(d) => d.length === 0} emptyTitle="Nessun badge" emptyHint="Crea il primo badge.">
        {(d) => (
          <ul className="grid grid-cols-[repeat(auto-fill,minmax(220px,1fr))] gap-3">
            {d.map((b) => {
              const Icon = gameIcon(b.icon);
              return (
                <li key={b.code}>
                  <button
                    onClick={() => setEditing(b)}
                    className="flex h-full w-full flex-col items-start gap-2 rounded-lg border border-[var(--color-bo-border)] bg-white p-4 text-left hover:border-[var(--color-bo-accent)]"
                  >
                    <span className="flex size-12 items-center justify-center rounded-full" style={{ background: b.color ?? "#94a3b8" }}>
                      <Icon className="size-6 text-white" aria-hidden />
                    </span>
                    <span className="font-medium">{b.name}</span>
                    <span className="text-xs text-[var(--color-bo-ink-2)]">{b.description}</span>
                    <span className="mt-auto flex w-full items-center justify-between text-xs">
                      <CodeText>{b.code}</CodeText>
                      <span className="tabular-nums">
                        {b.holders} {b.holders === 1 ? "membro" : "membri"}
                      </span>
                    </span>
                    {b.unlockedBy.length ? (
                      <span className="text-[11px] text-[var(--color-bo-ink-2)]">Da: {b.unlockedBy.map(achName).join(", ")}</span>
                    ) : (
                      <span className="text-[11px] text-[var(--color-bo-ink-2)]">Solo da campagne (AWARD_BADGE)</span>
                    )}
                  </button>
                </li>
              );
            })}
          </ul>
        )}
      </QueryState>
      <SideSheet open={editing != null} title={editing === "new" ? "Nuovo badge" : editing ? editing.name : ""} onClose={() => setEditing(null)}>
        {editing != null ? <BadgeEditor key={editing === "new" ? "new" : editing.code} badge={editing === "new" ? null : editing} onDone={() => setEditing(null)} /> : null}
      </SideSheet>
    </>
  );
}

function BadgeEditor({ badge: b, onDone }: { badge: BadgeView | null; onDone: () => void }) {
  const canEdit = useCan("object.edit");
  const [f, setF] = useState({ code: b?.code ?? "BDG-", name: b?.name ?? "", description: b?.description ?? "", icon: b?.icon ?? "award", color: b?.color ?? "#2a78d6" });
  const [error, setError] = useState<LhError | null>(null);
  const save = useLhMutation<unknown, typeof f>("gamification", b ? "PUT" : "POST", () => (b ? `/v1/badges/${b.code}` : "/v1/badges"), {
    onSuccess: onDone,
  });
  const Icon = gameIcon(f.icon);
  return (
    <form
      className="space-y-3"
      onSubmit={(e) => {
        e.preventDefault();
        setError(null);
        save.mutate({ ...f, code: f.code.trim().toUpperCase() }, { onError: setError });
      }}
    >
      <div className="flex items-center gap-3">
        <span className="flex size-14 items-center justify-center rounded-full" style={{ background: f.color }}>
          <Icon className="size-7 text-white" aria-hidden />
        </span>
        <span className="text-sm text-[var(--color-bo-ink-2)]">Anteprima del badge nel portale</span>
      </div>
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label="Nome">
          <input required value={f.name} disabled={!canEdit} onChange={(e) => setF({ ...f, name: e.target.value })} className={INPUT} />
        </Field>
        <Field label="Codice">
          <input required value={f.code} disabled={!canEdit || b != null} onChange={(e) => setF({ ...f, code: e.target.value.toUpperCase() })} className={`${INPUT} font-mono`} />
        </Field>
      </div>
      <Field label="Descrizione">
        <input value={f.description} disabled={!canEdit} onChange={(e) => setF({ ...f, description: e.target.value })} className={INPUT} />
      </Field>
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label="Icona">
          <select value={f.icon} disabled={!canEdit} onChange={(e) => setF({ ...f, icon: e.target.value })} className={INPUT}>
            {ICON_CHOICES.map((i) => (
              <option key={i} value={i}>
                {i}
              </option>
            ))}
          </select>
        </Field>
        <Field label="Colore">
          <input type="color" value={f.color} disabled={!canEdit} onChange={(e) => setF({ ...f, color: e.target.value })} className="h-9 w-16 rounded border border-[var(--color-bo-border)]" />
        </Field>
      </div>
      {canEdit ? (
        <div className="flex items-center gap-3">
          <button type="submit" disabled={save.isPending} className="rounded bg-[var(--color-bo-accent)] px-3 py-1.5 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50">
            {b ? "Salva" : "Crea badge"}
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
