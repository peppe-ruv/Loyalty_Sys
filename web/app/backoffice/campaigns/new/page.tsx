"use client";

import { useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { lhFetch, LhError } from "@/lib/api/client";
import type { Campaign } from "@/lib/api/types";
import type { CampaignDraft, ConditionNode, EffectSpec } from "@/lib/campaign/describe";
import { Card, CardBody } from "@/components/ui/card";
import { PageHeader } from "@/components/bo/primitives";
import { GeneratedSentence } from "@/components/bo/GeneratedSentence";
import { SimulationPanel } from "@/components/bo/SimulationPanel";
import { Can } from "@/components/bo/Can";

// BO-06 Nuova campagna (docs/08 §BO-06): editor con frase generata dal vivo. Salva come DRAFT (POST).
const DEFAULTS = {
  audience: '{ "all": true }',
  conditions: '{ "op": "all", "rules": [ { "field": "data.amount", "cmp": "gte", "value": 1 } ] }',
  effects: '[ { "type": "GRANT_POINTS", "currency": "PTS", "mode": "PER_AMOUNT", "amountField": "data.amount", "value": 1, "unitStep": 1, "rounding": "FLOOR", "tierMultiplierApplies": true } ]',
  limits: '{ "perMember": [ { "max": 3, "period": "DAY" } ] }',
  schedule: '{ "startAt": "2026-01-01T00:00:00Z", "endAt": null }',
};

export default function NewCampaignPage() {
  const router = useRouter();
  const [name, setName] = useState("");
  const [code, setCode] = useState("");
  const [memberDescription, setMemberDescription] = useState("");
  const [priority, setPriority] = useState(100);
  const [visibleInPortal, setVisibleInPortal] = useState(true);
  const [triggers, setTriggers] = useState("purchase.completed");
  const [json, setJson] = useState(DEFAULTS);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const draft: CampaignDraft = useMemo(() => {
    return {
      triggerActionTypes: triggers.split(",").map((t) => t.trim()).filter(Boolean),
      audience: parse(json.audience) as CampaignDraft["audience"],
      conditions: parse(json.conditions) as ConditionNode | undefined,
      effects: (parse(json.effects) as EffectSpec[]) ?? [],
      limits: parse(json.limits) as CampaignDraft["limits"],
    };
  }, [triggers, json]);

  const autoCode = useMemo(
    () => "CMP-" + name.toUpperCase().replace(/[^A-Z0-9]+/g, "-").replace(/^-|-$/g, "").slice(0, 24),
    [name],
  );

  async function save() {
    setError(null);
    setSaving(true);
    try {
      const body = {
        code: code || autoCode,
        name,
        memberDescription,
        priority,
        visibleInPortal,
        triggerActionTypes: draft.triggerActionTypes,
        audience: parse(json.audience),
        conditions: parse(json.conditions),
        effects: parse(json.effects),
        limits: parse(json.limits),
        schedule: parse(json.schedule),
      };
      const created = await lhFetch<Campaign>("campaign", "/v1/campaigns", { method: "POST", body: JSON.stringify(body) });
      router.push(`/backoffice/campaigns/${created.id}`);
    } catch (e) {
      setError(e instanceof LhError ? e.detail || e.code : "Controlla i campi JSON");
    } finally {
      setSaving(false);
    }
  }

  return (
    <div>
      <PageHeader title="Nuova campagna" subtitle="Crea una bozza (DRAFT)" />
      <div className="mb-4">
        <GeneratedSentence draft={draft} />
      </div>
      <div className="grid gap-4 lg:grid-cols-[1fr_320px]">
        <div className="space-y-4">
          <Card>
            <CardBody className="space-y-3 pt-4">
              <h3 className="text-sm font-semibold">1 · Generale</h3>
              <Field label="Nome">
                <input value={name} onChange={(e) => setName(e.target.value)} className={input} />
              </Field>
              <Field label={`Codice (auto: ${autoCode || "—"})`}>
                <input value={code} onChange={(e) => setCode(e.target.value)} placeholder={autoCode} className={input} />
              </Field>
              <Field label="Descrizione per il membro">
                <input value={memberDescription} onChange={(e) => setMemberDescription(e.target.value)} className={input} />
              </Field>
              <div className="flex gap-4">
                <Field label="Priorità">
                  <input type="number" value={priority} onChange={(e) => setPriority(Number(e.target.value))} className={input} />
                </Field>
                <label className="flex items-end gap-2 pb-1 text-sm">
                  <input type="checkbox" checked={visibleInPortal} onChange={(e) => setVisibleInPortal(e.target.checked)} />
                  Visibile nel portale
                </label>
              </div>
            </CardBody>
          </Card>
          <Card>
            <CardBody className="space-y-3 pt-4">
              <h3 className="text-sm font-semibold">2 · Quando</h3>
              <Field label="Tipi azione trigger (separati da virgola)">
                <input value={triggers} onChange={(e) => setTriggers(e.target.value)} className={input} />
              </Field>
            </CardBody>
          </Card>
          {(["conditions", "effects", "limits", "audience", "schedule"] as const).map((k) => (
            <Card key={k}>
              <CardBody className="space-y-2 pt-4">
                <h3 className="text-sm font-semibold capitalize">{sectionTitle(k)}</h3>
                <textarea
                  value={json[k]}
                  onChange={(e) => setJson({ ...json, [k]: e.target.value })}
                  rows={k === "effects" || k === "conditions" ? 5 : 3}
                  className="w-full rounded border border-[var(--color-bo-border)] px-2 py-1 font-mono text-xs"
                />
              </CardBody>
            </Card>
          ))}
          {error ? <p className="text-sm text-red-700">{error}</p> : null}
          <div className="flex gap-2">
            <Can capability="object.edit" mode="disable">
              <button onClick={save} disabled={saving || !name} className="rounded bg-[var(--color-bo-accent)] px-3 py-1.5 text-sm font-medium text-white disabled:opacity-50">
                {saving ? "Salvataggio…" : "Salva bozza"}
              </button>
            </Can>
            <button onClick={() => router.back()} className="rounded border border-[var(--color-bo-border)] px-3 py-1.5 text-sm">
              Annulla
            </button>
          </div>
        </div>
        <Card>
          <CardBody className="pt-4">
            <p className="mb-2 text-xs text-slate-400">La simulazione usa le campagne LIVE salvate.</p>
            <SimulationPanel />
          </CardBody>
        </Card>
      </div>
    </div>
  );
}

const input = "mt-1 w-full rounded border border-[var(--color-bo-border)] px-2 py-1 text-sm";

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="block text-xs text-[var(--color-bo-ink-2)]">
      {label}
      {children}
    </label>
  );
}

function sectionTitle(k: string): string {
  const map: Record<string, string> = {
    conditions: "4 · Se (condizioni, JSON)",
    effects: "5 · Allora (effetti, JSON)",
    limits: "6 · Limiti (JSON)",
    audience: "3 · A chi (JSON)",
    schedule: "7 · Calendario (JSON)",
  };
  return map[k] ?? k;
}

function parse(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}
