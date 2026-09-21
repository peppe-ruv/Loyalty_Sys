"use client";

import { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { lhFetch, useLhQuery, LhError } from "@/lib/api/client";
import type { Tier, TierCount } from "@/lib/api/types";
import { Card, CardBody } from "@/components/ui/card";
import { PageHeader } from "@/components/bo/primitives";
import { Can } from "@/components/bo/Can";

// BO-07 Livelli (docs/08 §BO-07): scala dei livelli + modifica (program.config) + discesa morbida spiegata.
export default function TiersPage() {
  const tiers = useLhQuery<Tier[]>("wallet", "/v1/tiers");
  const dist = useLhQuery<TierCount[]>("wallet", "/v1/tiers/distribution");
  const [selected, setSelected] = useState<string | null>(null);

  const counts = new Map((dist.data ?? []).map((d) => [d.code, d.members]));
  const scale = tiers.data ?? [];
  const current = scale.find((t) => t.code === selected) ?? null;

  return (
    <div>
      <PageHeader title="Livelli" subtitle="Scala dei livelli, soglie di punti status e vantaggi" />

      <div className="mb-4 grid grid-cols-2 gap-3 lg:grid-cols-4">
        {scale.map((t) => (
          <button key={t.code} onClick={() => setSelected(t.code)} className="text-left">
            <Card className={selected === t.code ? "ring-2 ring-[var(--color-bo-accent)]" : ""}>
              <CardBody className="pt-3">
                <div className="flex items-center gap-2">
                  <span className="inline-block h-3 w-3 rounded-full" style={{ background: t.color ?? "#94a3b8" }} />
                  <p className="text-sm font-semibold">{t.name}</p>
                </div>
                <dl className="mt-2 space-y-0.5 text-xs text-[var(--color-bo-ink-2)]">
                  <div className="flex justify-between"><dt>Soglia STS</dt><dd className="font-mono">{t.thresholdSts.toLocaleString("it-IT")}</dd></div>
                  <div className="flex justify-between"><dt>Moltiplicatore</dt><dd className="font-mono">×{t.multiplier.toLocaleString("it-IT")}</dd></div>
                  <div className="flex justify-between"><dt>Membri</dt><dd className="font-mono">{(counts.get(t.code) ?? 0).toLocaleString("it-IT")}</dd></div>
                </dl>
                <ul className="mt-2 space-y-0.5 text-[11px] text-[var(--color-bo-ink-2)]">
                  {t.benefits.slice(0, 3).map((b, i) => <li key={i}>· {b}</li>)}
                </ul>
              </CardBody>
            </Card>
          </button>
        ))}
      </div>

      {current ? (
        <Can capability="program.config" mode="disable">
          <TierEditor key={current.code} tier={current} />
        </Can>
      ) : (
        <p className="text-sm text-[var(--color-bo-ink-2)]">Seleziona un livello per modificarlo.</p>
      )}

      <Card className="mt-4">
        <CardBody className="pt-4 text-sm">
          <h3 className="mb-1 font-semibold">Discesa morbida a fine edizione</h3>
          <p className="text-[var(--color-bo-ink-2)]">
            A chiusura di un&apos;edizione un membro può scendere <strong>al massimo di un livello</strong>. Esempio:
            Stefano Galli chiude l&apos;anno <strong>GOLD</strong> con 650 punti status: la soglia guadagnata è
            <strong> BASE</strong>, ma il pavimento della discesa morbida è <strong>SILVER</strong> → resta
            <strong> SILVER</strong>. La chiusura si gestisce in <em>Valute, scadenze, edizioni</em> (BO-08).
          </p>
        </CardBody>
      </Card>
    </div>
  );
}

function TierEditor({ tier }: { tier: Tier }) {
  const qc = useQueryClient();
  const [name, setName] = useState(tier.name);
  const [threshold, setThreshold] = useState(String(tier.thresholdSts));
  const [multiplier, setMultiplier] = useState(String(tier.multiplier));
  const [benefits, setBenefits] = useState(tier.benefits.join("\n"));
  const [color, setColor] = useState(tier.color ?? "#94a3b8");
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null);

  async function save() {
    setBusy(true);
    setMsg(null);
    try {
      await lhFetch<Tier>("wallet", `/v1/tiers/${tier.code}`, {
        method: "PUT",
        body: JSON.stringify({
          name,
          thresholdSts: Number(threshold),
          multiplier: Number(multiplier),
          benefits: benefits.split("\n").map((b) => b.trim()).filter(Boolean),
          color,
        }),
      });
      setMsg({ ok: true, text: "Livello salvato." });
      qc.invalidateQueries({ queryKey: ["wallet"] });
    } catch (e) {
      const err = e as LhError;
      const text =
        err.code === "TIER_THRESHOLDS_NOT_MONOTONIC"
          ? "Soglia non valida: deve crescere col livello (BASE resta 0)."
          : err.detail || "Salvataggio non riuscito.";
      setMsg({ ok: false, text });
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card>
      <CardBody className="space-y-3 pt-4">
        <div className="flex items-center justify-between">
          <h3 className="text-sm font-semibold">Modifica livello {tier.code}</h3>
          <button
            onClick={save}
            disabled={busy}
            className="rounded bg-[var(--color-bo-accent)] px-3 py-1.5 text-sm font-medium text-white disabled:opacity-50"
          >
            {busy ? "Salvataggio…" : "Salva"}
          </button>
        </div>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <Field label="Nome"><input value={name} onChange={(e) => setName(e.target.value)} className={inputCls} /></Field>
          <Field label="Colore"><input type="color" value={color} onChange={(e) => setColor(e.target.value)} className="h-9 w-full rounded border border-[var(--color-bo-border)]" /></Field>
          <Field label={`Soglia STS${tier.code === "BASE" ? " (fissa a 0)" : ""}`}>
            <input type="number" value={threshold} onChange={(e) => setThreshold(e.target.value)} disabled={tier.code === "BASE"} className={inputCls} />
          </Field>
          <Field label="Moltiplicatore PTS"><input type="number" step="0.05" value={multiplier} onChange={(e) => setMultiplier(e.target.value)} className={inputCls} /></Field>
        </div>
        <Field label="Vantaggi (uno per riga)">
          <textarea value={benefits} onChange={(e) => setBenefits(e.target.value)} rows={4} className={inputCls} />
        </Field>
        {msg ? (
          <p className={msg.ok ? "text-xs text-[var(--color-earn)]" : "text-xs text-[var(--color-spend)]"}>{msg.text}</p>
        ) : null}
      </CardBody>
    </Card>
  );
}

const inputCls = "w-full rounded border border-[var(--color-bo-border)] px-2 py-1.5 text-sm";

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="block">
      <span className="mb-1 block text-xs text-[var(--color-bo-ink-2)]">{label}</span>
      {children}
    </label>
  );
}
