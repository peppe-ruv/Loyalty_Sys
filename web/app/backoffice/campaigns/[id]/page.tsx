"use client";

import { useParams } from "next/navigation";
import { useLhQuery } from "@/lib/api/client";
import type { Campaign } from "@/lib/api/types";
import type { CampaignDraft, ConditionNode, EffectSpec } from "@/lib/campaign/describe";
import { QueryState } from "@/components/bo/QueryState";
import { Card, CardBody } from "@/components/ui/card";
import { PageHeader, CodeText } from "@/components/bo/primitives";
import { LifecycleBar } from "@/components/bo/LifecycleBar";
import { GeneratedSentence } from "@/components/bo/GeneratedSentence";
import { SimulationPanel } from "@/components/bo/SimulationPanel";
import { CampaignStatsPanel } from "@/components/bo/campaigns/CampaignStatsPanel";

// BO-06 Editor campagna (docs/08 §BO-06) — vista di un oggetto esistente: regole in sola lettura (per cambiarle
// si duplica, docs/03 §3.6), barra ciclo di vita, frase generata e simulazione.
export default function CampaignEditorPage() {
  const id = String(useParams().id);
  const query = useLhQuery<Campaign>("campaign", `/v1/campaigns/${id}`);

  return (
    <QueryState query={query} service="campaign">
      {(c) => {
        const draft = toDraft(c);
        return (
          <div>
            <PageHeader
              title={c.name}
              subtitle={undefined}
              actions={<CodeText>{c.code}</CodeText>}
            />
            <div className="mb-4">
              <LifecycleBar campaignId={c.id} status={c.status} system={c.system} onChanged={() => query.refetch()} />
            </div>
            <div className="mb-4">
              <GeneratedSentence draft={draft} />
            </div>
            <div className="grid gap-4 lg:grid-cols-[1fr_320px]">
              <div className="space-y-4">
                <Section title="Quando" body={c.triggerActionTypes.join(", ") || "—"} />
                <Section title="Se (condizioni)" body={<Json value={c.conditions} />} />
                <Section title="Allora (effetti)" body={<Json value={c.effects} />} />
                <Section title="Limiti" body={<Json value={c.limits} />} />
                <Section title="Calendario" body={<Json value={c.schedule} />} />
                <p className="text-xs text-slate-400">
                  Le regole di un oggetto salvato sono in sola lettura: per cambiarle, duplica la campagna
                  (docs/03 §3.6). L&apos;editor completo di creazione è in «Nuova campagna».
                </p>
              </div>
              <Card>
                <CardBody className="pt-4">
                  <SimulationPanel campaignId={c.id} />
                </CardBody>
              </Card>
            </div>

            <div className="mt-6">
              <h2 className="mb-3 text-sm font-semibold text-[var(--color-bo-ink)]">Statistiche</h2>
              <CampaignStatsPanel campaignId={c.id} />
            </div>
          </div>
        );
      }}
    </QueryState>
  );
}

function Section({ title, body }: { title: string; body: React.ReactNode }) {
  return (
    <Card>
      <CardBody className="pt-4">
        <h3 className="mb-2 text-sm font-semibold">{title}</h3>
        <div className="text-sm">{body}</div>
      </CardBody>
    </Card>
  );
}

function Json({ value }: { value: unknown }) {
  return <pre className="overflow-x-auto rounded bg-slate-50 p-2 font-mono text-xs">{JSON.stringify(value, null, 2)}</pre>;
}

function toDraft(c: Campaign): CampaignDraft {
  return {
    triggerActionTypes: c.triggerActionTypes,
    audience: c.audience as CampaignDraft["audience"],
    conditions: c.conditions as ConditionNode,
    effects: c.effects as EffectSpec[],
    limits: c.limits as CampaignDraft["limits"],
  };
}
