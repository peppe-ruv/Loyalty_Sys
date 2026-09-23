"use client";

import { useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { useLhMutation, useLhQuery, type LhError, type Page } from "@/lib/api/client";
import type { Coupon, CouponGenerateResult, CouponImportResult, CouponPool, CouponStatus } from "@/lib/api/types";
import { QueryState } from "@/components/bo/QueryState";
import { DataTable, type Column } from "@/components/bo/DataTable";
import { Can } from "@/components/bo/Can";
import { PageHeader, StatusPill, CodeText } from "@/components/bo/primitives";
import { Card, CardBody } from "@/components/ui/card";
import { CouponStatusBar } from "@/components/bo/rewards/CouponStatusBar";
import { CouponTill } from "@/components/bo/rewards/CouponTill";
import { COUPON_STATUSES, COUPON_STATUS_LABEL, parseCodes } from "@/lib/reward/coupons";
import { formatDate } from "@/lib/format/dates";
import { formatPoints } from "@/lib/format/points";
import { cn } from "@/lib/cn";

// BO-12 Coupon (docs/08 §BO-12; F-CPN-01..03): elenco pool (premio collegato, prefisso, codici per stato); dettaglio
// con tabella dei codici filtrabile, *Genera codici* (≤ 5000) e *Importa*; cassa simulata per verifica/uso/annullo.
const PAGE_SIZE = 25;

export default function CouponsPage() {
  const router = useRouter();
  const search = useSearchParams();
  const selected = search.get("pool");
  const pools = useLhQuery<CouponPool[]>("reward", "/v1/coupon-pools");
  const [creating, setCreating] = useState(false);

  const select = (id: string) => router.replace(`/backoffice/rewards/coupons?pool=${id}`, { scroll: false });

  return (
    <div>
      <PageHeader
        title="Coupon"
        subtitle="Pool di codici per i premi a evasione automatica e le vincite dei concorsi."
        actions={
          <Can capability="object.edit" mode="disable">
            <button
              onClick={() => setCreating(true)}
              className="rounded bg-[var(--color-bo-accent)] px-3 py-1.5 text-sm font-medium text-white hover:opacity-90"
            >
              Nuovo pool
            </button>
          </Can>
        }
      />
      <div className="grid gap-4 lg:grid-cols-[1fr_340px]">
        <div className="min-w-0 space-y-4">
          {creating ? <NewPoolForm onDone={(id) => { setCreating(false); if (id) select(id); }} /> : null}
          <QueryState query={pools} service="reward" isEmpty={(d) => d.length === 0} emptyTitle="Nessun pool coupon">
            {(ps) => {
              const current = ps.find((p) => p.id === selected) ?? ps[0];
              return (
                <>
                  <ul className="grid gap-2 sm:grid-cols-2">
                    {ps.map((p) => (
                      <li key={p.id}>
                        <button
                          onClick={() => select(p.id)}
                          aria-pressed={p.id === current.id}
                          className={cn(
                            "w-full rounded-md border bg-[var(--color-bo-surface)] p-3 text-left",
                            p.id === current.id ? "border-[var(--color-bo-accent)]" : "border-[var(--color-bo-border)] hover:border-slate-400",
                          )}
                        >
                          <div className="mb-1 flex items-baseline justify-between gap-2">
                            <span className="font-medium text-[var(--color-bo-ink)]">{p.name}</span>
                            <span className="text-xs tabular-nums text-[var(--color-bo-ink-2)]">{formatPoints(p.total)} codici</span>
                          </div>
                          <div className="mb-2 flex flex-wrap items-center gap-2 text-xs text-[var(--color-bo-ink-2)]">
                            <CodeText>{p.prefix}-…</CodeText>
                            <span>{p.rewards.length ? p.rewards.map((r) => r.name).join(", ") : "Nessun premio collegato"}</span>
                          </div>
                          <CouponStatusBar counts={p.counts} compact />
                        </button>
                      </li>
                    ))}
                  </ul>
                  <PoolDetail key={current.id} pool={current} />
                </>
              );
            }}
          </QueryState>
        </div>
        <div>
          <CouponTill />
        </div>
      </div>
    </div>
  );
}

function PoolDetail({ pool }: { pool: CouponPool }) {
  const [status, setStatus] = useState<CouponStatus | "">("");
  const [page, setPage] = useState(0);
  const coupons = useLhQuery<Page<Coupon>>("reward", `/v1/coupon-pools/${pool.id}/coupons`, { status, page, size: PAGE_SIZE });

  const columns: Column<Coupon>[] = [
    { key: "code", header: "Codice", render: (c) => <span className="font-mono text-xs">{c.code}</span> },
    { key: "status", header: "Stato", render: (c) => <StatusPill status={c.status} /> },
    { key: "member", header: "Membro", render: (c) => <span className="font-mono text-xs">{c.memberId ?? "—"}</span> },
    { key: "origin", header: "Origine", render: (c) => <span className="text-xs">{c.origin === "CAMPAIGN" ? "Campagna" : c.origin === "REDEMPTION" ? "Richiesta premio" : "—"}</span> },
    { key: "issued", header: "Emesso", render: (c) => <span className="text-xs">{formatDate(c.issuedAt)}</span> },
    { key: "expires", header: "Scade", render: (c) => <span className="text-xs">{formatDate(c.expiresAt)}</span> },
  ];

  return (
    <Card>
      <CardBody className="space-y-4 pt-4">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <h2 className="text-sm font-semibold">{pool.name}</h2>
            <p className="text-xs text-[var(--color-bo-ink-2)]">
              <CodeText>{pool.code}</CodeText> · validità {pool.validityDays} giorni dall&apos;emissione
            </p>
          </div>
        </div>
        <CouponStatusBar counts={pool.counts} />

        <Can capability="object.edit" mode="hide">
          <div className="grid gap-3 md:grid-cols-2">
            <GenerateForm pool={pool} />
            <ImportForm pool={pool} />
          </div>
        </Can>

        <div>
          <div className="mb-2 flex flex-wrap items-center gap-2">
            <select
              aria-label="Stato"
              value={status}
              onChange={(e) => {
                setStatus(e.target.value as CouponStatus | "");
                setPage(0);
              }}
              className="rounded border border-[var(--color-bo-border)] px-2 py-1.5 text-sm"
            >
              <option value="">Tutti gli stati</option>
              {COUPON_STATUSES.map((s) => (
                <option key={s} value={s}>
                  {COUPON_STATUS_LABEL[s]}
                </option>
              ))}
            </select>
          </div>
          <QueryState query={coupons} service="reward" isEmpty={(d) => d.items.length === 0} emptyTitle="Nessun codice in questo stato">
            {(d) => (
              <>
                <DataTable columns={columns} rows={d.items} rowKey={(c) => c.code} />
                <div className="mt-2 flex items-center justify-between text-xs text-[var(--color-bo-ink-2)]">
                  <span className="tabular-nums">
                    {formatPoints(d.page.totalItems)} codici · pagina {d.page.number + 1} di {Math.max(1, d.page.totalPages)}
                  </span>
                  <span className="flex gap-2">
                    <button disabled={page === 0} onClick={() => setPage((p) => p - 1)} className="rounded border border-[var(--color-bo-border)] px-2 py-1 disabled:opacity-40">
                      ← Precedente
                    </button>
                    <button
                      disabled={page + 1 >= d.page.totalPages}
                      onClick={() => setPage((p) => p + 1)}
                      className="rounded border border-[var(--color-bo-border)] px-2 py-1 disabled:opacity-40"
                    >
                      Successiva →
                    </button>
                  </span>
                </div>
              </>
            )}
          </QueryState>
        </div>
      </CardBody>
    </Card>
  );
}

function GenerateForm({ pool }: { pool: CouponPool }) {
  const [count, setCount] = useState("100");
  const [result, setResult] = useState<CouponGenerateResult | null>(null);
  const [error, setError] = useState<LhError | null>(null);
  const generate = useLhMutation<CouponGenerateResult, { count: number }>("reward", "POST", () => `/v1/coupon-pools/${pool.id}/generate`);
  const n = Number(count);
  const valid = Number.isInteger(n) && n >= 1 && n <= 5000;

  return (
    <form
      className="space-y-2 rounded border border-[var(--color-bo-border)] p-3"
      onSubmit={(e) => {
        e.preventDefault();
        setError(null);
        setResult(null);
        generate.mutate({ count: n }, { onSuccess: setResult, onError: setError });
      }}
    >
      <h3 className="text-xs font-semibold">Genera codici</h3>
      <div className="flex gap-2">
        <input
          aria-label="Quanti codici"
          type="number"
          min={1}
          max={5000}
          value={count}
          onChange={(e) => setCount(e.target.value)}
          className="w-28 rounded border border-[var(--color-bo-border)] bg-white px-2 py-1 text-sm tabular-nums"
        />
        <button type="submit" disabled={!valid || generate.isPending} className="rounded bg-[var(--color-bo-accent)] px-3 py-1 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50">
          Genera
        </button>
      </div>
      <p className="text-xs text-[var(--color-bo-ink-2)]">
        Da 1 a 5 000 per volta, formato {pool.prefix}-XXXX-XXXX. Con lo stesso seme escono gli stessi codici.
      </p>
      {result ? (
        <p role="status" className="text-xs text-emerald-700">
          Generati {formatPoints(result.generated)} codici · ora disponibili {formatPoints(result.available)}.
        </p>
      ) : null}
      {error ? <p role="alert" className="text-xs text-red-700">{error.detail || error.code}</p> : null}
    </form>
  );
}

function ImportForm({ pool }: { pool: CouponPool }) {
  const [text, setText] = useState("");
  const [result, setResult] = useState<CouponImportResult | null>(null);
  const [error, setError] = useState<LhError | null>(null);
  const importCodes = useLhMutation<CouponImportResult, { codes: string[] }>("reward", "POST", () => `/v1/coupon-pools/${pool.id}/import`);
  const codes = parseCodes(text);

  return (
    <form
      className="space-y-2 rounded border border-[var(--color-bo-border)] p-3"
      onSubmit={(e) => {
        e.preventDefault();
        setError(null);
        setResult(null);
        importCodes.mutate({ codes }, { onSuccess: (r) => { setResult(r); setText(""); }, onError: setError });
      }}
    >
      <h3 className="text-xs font-semibold">Importa</h3>
      <textarea
        aria-label="Codici da importare"
        rows={3}
        placeholder={"Un codice per riga\n" + pool.prefix + "-AAAA-BBBB"}
        value={text}
        onChange={(e) => setText(e.target.value)}
        className="w-full rounded border border-[var(--color-bo-border)] bg-white px-2 py-1 font-mono text-xs"
      />
      <button type="submit" disabled={codes.length === 0 || importCodes.isPending} className="rounded border border-[var(--color-bo-border)] px-3 py-1 text-sm hover:bg-slate-50 disabled:opacity-50">
        Importa {codes.length ? formatPoints(codes.length) : ""} codici
      </button>
      {result ? (
        <div role="status" className="text-xs">
          <p className="text-emerald-700">
            Importati {formatPoints(result.imported)} · scartati {formatPoints(result.skipped.length)}
          </p>
          {result.skipped.length ? (
            <p className="mt-1 text-[var(--color-bo-ink-2)]">
              Scartati (non validi, doppi o già esistenti): <span className="font-mono">{result.skipped.slice(0, 10).join(", ")}</span>
              {result.skipped.length > 10 ? ` e altri ${result.skipped.length - 10}` : ""}
            </p>
          ) : null}
        </div>
      ) : null}
      {error ? <p role="alert" className="text-xs text-red-700">{error.detail || error.code}</p> : null}
    </form>
  );
}

function NewPoolForm({ onDone }: { onDone: (id?: string) => void }) {
  const [code, setCode] = useState("POOL-");
  const [name, setName] = useState("");
  const [prefix, setPrefix] = useState("");
  const [validity, setValidity] = useState("90");
  const [error, setError] = useState<LhError | null>(null);
  const create = useLhMutation<CouponPool, { code: string; name: string; prefix: string; validityDays: number }>(
    "reward",
    "POST",
    () => "/v1/coupon-pools",
  );
  const input = "rounded border border-[var(--color-bo-border)] bg-white px-2 py-1 text-sm";

  return (
    <Card>
      <CardBody className="pt-4">
        <form
          className="flex flex-wrap items-end gap-3"
          onSubmit={(e) => {
            e.preventDefault();
            setError(null);
            create.mutate(
              { code: code.trim(), name: name.trim(), prefix: prefix.trim(), validityDays: Number(validity) },
              { onSuccess: (p) => onDone(p.id), onError: setError },
            );
          }}
        >
          <label className="text-xs text-[var(--color-bo-ink-2)]">
            Codice
            <input value={code} onChange={(e) => setCode(e.target.value.toUpperCase())} className={`${input} block w-36 font-mono`} />
          </label>
          <label className="text-xs text-[var(--color-bo-ink-2)]">
            Nome
            <input value={name} onChange={(e) => setName(e.target.value)} className={`${input} block w-48`} />
          </label>
          <label className="text-xs text-[var(--color-bo-ink-2)]">
            Prefisso
            <input value={prefix} onChange={(e) => setPrefix(e.target.value.toUpperCase())} className={`${input} block w-24 font-mono`} />
          </label>
          <label className="text-xs text-[var(--color-bo-ink-2)]">
            Validità (giorni)
            <input type="number" min={1} value={validity} onChange={(e) => setValidity(e.target.value)} className={`${input} block w-24 tabular-nums`} />
          </label>
          <button
            type="submit"
            disabled={create.isPending || !name.trim() || !prefix.trim() || code.trim().length < 6}
            className="rounded bg-[var(--color-bo-accent)] px-3 py-1 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50"
          >
            Crea pool
          </button>
          <button type="button" onClick={() => onDone()} className="rounded border border-[var(--color-bo-border)] px-3 py-1 text-sm hover:bg-slate-50">
            Annulla
          </button>
          {error ? <p role="alert" className="w-full text-xs text-red-700">{error.detail || error.code}</p> : null}
        </form>
      </CardBody>
    </Card>
  );
}
