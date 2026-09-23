"use client";

import { useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { useLhQuery } from "@/lib/api/client";
import type { Reward, RewardBand, RewardCategory, RewardStats } from "@/lib/api/types";
import { QueryState } from "@/components/bo/QueryState";
import { DataTable, type Column } from "@/components/bo/DataTable";
import { Can } from "@/components/bo/Can";
import { PageHeader, StatusPill, CodeText } from "@/components/bo/primitives";
import { BandChip, CostCell, RewardImage, StockBar, TierLock, TYPE_LABEL } from "@/components/bo/rewards/RewardBits";
import { cn } from "@/lib/cn";

// BO-10 Catalogo premi (docs/08 §BO-10): griglia (immagine, nome, fascia/costo, stato, stock, lucchetto tier) o
// tabella; filtri stato, fascia, categoria, tipo. Il costo di un premio è la soglia della sua fascia (docs/03 §5).
const STATUSES = ["", "LIVE", "DRAFT", "IN_REVIEW", "PAUSED", "ENDED", "ARCHIVED"];
const TYPES = ["", "PHYSICAL", "COUPON", "DIGITAL", "DONATION", "EXPERIENCE"];

type View = "grid" | "table";

export default function RewardsCatalogPage() {
  const router = useRouter();
  const [status, setStatus] = useState("");
  const [band, setBand] = useState("");
  const [category, setCategory] = useState("");
  const [type, setType] = useState("");
  const [view, setView] = useState<View>("grid");

  const query = useLhQuery<Reward[]>("reward", "/v1/rewards", { status, band, category, type });
  const bands = useLhQuery<RewardBand[]>("reward", "/v1/reward-bands");
  const categories = useLhQuery<RewardCategory[]>("reward", "/v1/reward-categories");
  const stats = useLhQuery<RewardStats>("reward", "/v1/rewards/stats");

  const bandByCode = useMemo(() => new Map((bands.data ?? []).map((b) => [b.code, b])), [bands.data]);
  const categoryByCode = useMemo(() => new Map((categories.data ?? []).map((c) => [c.code, c])), [categories.data]);
  const cost = (r: Reward) => bandByCode.get(r.bandCode)?.pointsThreshold;
  const open = (r: Reward) => router.push(`/backoffice/rewards/${r.id}`);

  const columns: Column<Reward>[] = [
    {
      key: "name",
      header: "Premio",
      render: (r) => (
        <span className="flex items-center gap-1.5">
          <span className="font-medium">{r.name}</span>
          <CodeText>{r.code}</CodeText>
        </span>
      ),
    },
    { key: "status", header: "Stato", render: (r) => <StatusPill status={r.status} /> },
    { key: "band", header: "Fascia", render: (r) => <BandChip band={bandByCode.get(r.bandCode)} code={r.bandCode} /> },
    { key: "cost", header: "Costo", className: "text-right", render: (r) => <CostCell value={cost(r)} /> },
    { key: "category", header: "Categoria", render: (r) => <span className="text-xs">{categoryByCode.get(r.categoryCode ?? "")?.name ?? "—"}</span> },
    { key: "type", header: "Tipo", render: (r) => <span className="text-xs">{TYPE_LABEL[r.type] ?? r.type}</span> },
    { key: "stock", header: "Stock", render: (r) => <StockBar reward={r} /> },
    { key: "tiers", header: "Visibilità", render: (r) => (r.eligibleTiers.length ? <TierLock tiers={r.eligibleTiers} /> : <span className="text-xs">Tutti</span>) },
  ];

  const live = stats.data?.rewardsByStatus.LIVE ?? 0;
  const low = stats.data?.lowStock.length ?? 0;

  return (
    <div>
      <PageHeader
        title="Catalogo premi"
        subtitle={stats.data ? `${live} premi LIVE · ${low} con stock sotto il 10 %` : "Premi riscattabili con i punti"}
        actions={
          <Can capability="object.edit" mode="disable">
            <button
              onClick={() => router.push("/backoffice/rewards/new")}
              className="rounded bg-[var(--color-bo-accent)] px-3 py-1.5 text-sm font-medium text-white hover:opacity-90"
            >
              Nuovo premio
            </button>
          </Can>
        }
      />

      <div className="mb-3 flex flex-wrap items-center gap-2">
        <Filter label="Stato" value={status} onChange={setStatus} options={STATUSES.map((s) => [s, s || "Tutti gli stati"])} />
        <Filter
          label="Fascia"
          value={band}
          onChange={setBand}
          options={[["", "Tutte le fasce"], ...(bands.data ?? []).map((b) => [b.code, `${b.code} · ${b.pointsThreshold} PTS`] as [string, string])]}
        />
        <Filter
          label="Categoria"
          value={category}
          onChange={setCategory}
          options={[["", "Tutte le categorie"], ...(categories.data ?? []).map((c) => [c.code, c.name] as [string, string])]}
        />
        <Filter label="Tipo" value={type} onChange={setType} options={TYPES.map((t) => [t, t ? TYPE_LABEL[t] : "Tutti i tipi"])} />
        <div className="ml-auto inline-flex overflow-hidden rounded border border-[var(--color-bo-border)] text-sm" role="group" aria-label="Vista">
          {(["grid", "table"] as View[]).map((v) => (
            <button
              key={v}
              onClick={() => setView(v)}
              aria-pressed={view === v}
              className={cn("px-3 py-1.5", view === v ? "bg-[var(--color-bo-accent)] text-white" : "hover:bg-slate-50")}
            >
              {v === "grid" ? "Griglia" : "Tabella"}
            </button>
          ))}
        </div>
      </div>

      <QueryState
        query={query}
        service="reward"
        isEmpty={(d) => d.length === 0}
        emptyTitle="Nessun premio"
        emptyHint="Nessun premio con questi filtri. Cambia i filtri o crea un premio."
      >
        {(d) =>
          view === "table" ? (
            <DataTable columns={columns} rows={d} rowKey={(r) => r.id} onRowClick={open} />
          ) : (
            <ul className="grid grid-cols-[repeat(auto-fill,minmax(220px,1fr))] gap-3">
              {d.map((r) => (
                <li key={r.id}>
                  <button
                    onClick={() => open(r)}
                    className="flex h-full w-full flex-col rounded-md border border-[var(--color-bo-border)] bg-[var(--color-bo-surface)] text-left hover:border-[var(--color-bo-accent)]"
                  >
                    <RewardImage reward={r} icon={categoryByCode.get(r.categoryCode ?? "")?.icon} />
                    <div className="flex flex-1 flex-col gap-2 p-3">
                      <div className="flex items-start justify-between gap-2">
                        <span className="font-medium leading-tight text-[var(--color-bo-ink)]">{r.name}</span>
                        <StatusPill status={r.status} />
                      </div>
                      <div className="flex items-center gap-2 text-xs">
                        <BandChip band={bandByCode.get(r.bandCode)} code={r.bandCode} />
                        <CostCell value={cost(r)} />
                      </div>
                      <TierLock tiers={r.eligibleTiers} />
                      <div className="mt-auto">
                        <StockBar reward={r} />
                      </div>
                    </div>
                  </button>
                </li>
              ))}
            </ul>
          )
        }
      </QueryState>
    </div>
  );
}

function Filter({
  label,
  value,
  onChange,
  options,
}: {
  label: string;
  value: string;
  onChange: (v: string) => void;
  options: [string, string][];
}) {
  return (
    <select
      aria-label={label}
      value={value}
      onChange={(e) => onChange(e.target.value)}
      className="rounded border border-[var(--color-bo-border)] px-2 py-1.5 text-sm"
    >
      {options.map(([v, l]) => (
        <option key={v} value={v}>
          {l}
        </option>
      ))}
    </select>
  );
}
