"use client";

import { useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { Braces, ListTree } from "lucide-react";
import { lhFetch, LhError, useLhQuery } from "@/lib/api/client";
import type { Campaign, EventType } from "@/lib/api/types";
import type { CampaignDraft, EffectSpec } from "@/lib/campaign/describe";
import { emptyGroup, fromJson, parseConditionsText, toJson, toJsonText, validateTree, type UiGroup } from "@/lib/campaign/conditions";
import { Card, CardBody } from "@/components/ui/card";
import { PageHeader } from "@/components/bo/primitives";
import { GeneratedSentence } from "@/components/bo/GeneratedSentence";
import { SimulationPanel } from "@/components/bo/SimulationPanel";
import { Can, useCan } from "@/components/bo/Can";
import { TriggerPicker } from "@/components/bo/campaigns/TriggerPicker";
import { SourcesPicker } from "@/components/bo/campaigns/SourcesPicker";
import { withAllowedSources } from "@/lib/campaign/sources";
import { ConditionBuilder, useConditionCatalog } from "@/components/bo/campaigns/ConditionBuilder";
import { cn } from "@/lib/cn";

// BO-06 Nuova campagna (docs/08 §BO-06): editor con frase generata dal vivo. Salva come DRAFT (POST).
// "2 Quando" sceglie i trigger fra i tipi azione di ingestion (anche custom, BO-09) e le fonti ammesse (Q-208: regola
// context.source alla radice delle condizioni, default tutte); "4 Se" è il ConditionBuilder con
// vista JSON alternativa. Gli altri blocchi restano in JSON.
const DEFAULT_CONDITIONS = { op: "all", rules: [{ field: "data.amount", cmp: "gte", value: 1 }] };
const DEFAULTS = {
  audience: '{ "all": true }',
  effects: '[ { "type": "GRANT_POINTS", "currency": "PTS", "mode": "PER_AMOUNT", "amountField": "data.amount", "value": 1, "unitStep": 1, "rounding": "FLOOR", "tierMultiplierApplies": true } ]',
  limits: '{ "perMember": [ { "max": 3, "period": "DAY" } ] }',
  schedule: '{ "startAt": "2026-01-01T00:00:00Z", "endAt": null }',
};

export default function NewCampaignPage() {
  const router = useRouter();
  const canEdit = useCan("object.edit");
  const [name, setName] = useState("");
  const [code, setCode] = useState("");
  const [memberDescription, setMemberDescription] = useState("");
  const [priority, setPriority] = useState(100);
  const [visibleInPortal, setVisibleInPortal] = useState(true);
  const [triggers, setTriggers] = useState<string[]>(["purchase.completed"]);
  const [sources, setSources] = useState<string[]>([]);
  const [json, setJson] = useState(DEFAULTS);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  // Condizioni: l'albero è la fonte; la vista JSON lo aggiorna solo quando il testo è valido.
  const [condTree, setCondTree] = useState<UiGroup>(() => fromJson(DEFAULT_CONDITIONS).tree ?? emptyGroup());
  const [condMode, setCondMode] = useState<"builder" | "json">("builder");
  const [condText, setCondText] = useState("");
  const [condJsonError, setCondJsonError] = useState<string | null>(null);
  const [condNotice, setCondNotice] = useState<string | null>(null);
  const catalog = useConditionCatalog(triggers);
  // Stessa query del TriggerPicker (cache condivisa): i nomi dei tipi custom per la frase generata.
  const eventTypes = useLhQuery<EventType[]>("ingestion", "/v1/event-types");
  const actionLabels = useMemo(
    () => Object.fromEntries((eventTypes.data ?? []).map((t) => [t.code, t.name])),
    [eventTypes.data],
  );
  const conditions = useMemo(() => toJson(condTree), [condTree]);
  const condProblems = useMemo(() => validateTree(condTree, catalog.catalog), [condTree, catalog.catalog]);

  const draft: CampaignDraft = useMemo(() => {
    return {
      triggerActionTypes: triggers,
      actionLabels,
      sources,
      audience: parse(json.audience) as CampaignDraft["audience"],
      conditions: conditions ?? undefined,
      effects: (parse(json.effects) as EffectSpec[]) ?? [],
      limits: parse(json.limits) as CampaignDraft["limits"],
    };
  }, [triggers, actionLabels, sources, json, conditions]);

  const autoCode = useMemo(
    () => "CMP-" + name.toUpperCase().replace(/[^A-Z0-9]+/g, "-").replace(/^-|-$/g, "").slice(0, 24),
    [name],
  );

  function switchCondMode(next: "builder" | "json") {
    setCondNotice(null);
    if (next === condMode) return;
    if (next === "json") {
      setCondText(toJsonText(condTree));
      setCondJsonError(null);
      setCondMode("json");
      return;
    }
    if (condJsonError) {
      setCondNotice("Correggi il JSON prima di tornare al costruttore: fino ad allora valgono le condizioni dell'ultima versione corretta.");
      return;
    }
    setCondMode("builder");
  }

  function editCondText(text: string) {
    setCondText(text);
    const parsed = parseConditionsText(text);
    if (parsed.tree) {
      setCondTree(parsed.tree);
      setCondJsonError(null);
    } else {
      // JSON non valido: errore in linea, l'albero resta quello dell'ultima versione corretta.
      setCondJsonError(parsed.error);
    }
  }

  async function save() {
    setError(null);
    if (condJsonError) {
      setError("Correggi il JSON delle condizioni (sezione 4)");
      return;
    }
    if (Object.keys(condProblems).length > 0) {
      setError("Completa le condizioni evidenziate nella sezione 4");
      return;
    }
    setSaving(true);
    try {
      const body = {
        code: code || autoCode,
        name,
        memberDescription,
        priority,
        visibleInPortal,
        triggerActionTypes: triggers,
        audience: parse(json.audience),
        // Nessuna condizione → null: il servizio salva {op: all, rules: []}, sempre vero. Le fonti ammesse (Q-208) entrano
        // come regola context.source in testa al gruppo TUTTE; nessuna fonte = tutte, nessuna regola.
        conditions: withAllowedSources(conditions, sources),
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
      <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_320px]">
        <div className="min-w-0 space-y-4">
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
              <p className="text-xs text-[var(--color-bo-ink-2)]">Tipi azione che fanno scattare la campagna (uno o più).</p>
              <TriggerPicker value={triggers} onChange={setTriggers} disabled={!canEdit} />
              <div>
                <p className="mb-1 text-xs font-medium">Fonti ammesse</p>
                <SourcesPicker value={sources} onChange={setSources} disabled={!canEdit} />
              </div>
            </CardBody>
          </Card>
          <Card>
            <CardBody className="space-y-3 pt-4">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <h3 className="text-sm font-semibold">4 · Se</h3>
                <div role="tablist" aria-label="Vista delle condizioni" className="inline-flex rounded border border-[var(--color-bo-border)] p-0.5 text-xs">
                  {(
                    [
                      ["builder", "Costruttore", ListTree],
                      ["json", "Vista JSON", Braces],
                    ] as const
                  ).map(([mode, label, Icon]) => (
                    <button
                      key={mode}
                      type="button"
                      role="tab"
                      aria-selected={condMode === mode}
                      onClick={() => switchCondMode(mode)}
                      className={cn(
                        "inline-flex items-center gap-1 rounded px-2 py-1",
                        condMode === mode ? "bg-slate-100 font-medium" : "text-[var(--color-bo-ink-2)]",
                      )}
                    >
                      <Icon className="size-3.5" /> {label}
                    </button>
                  ))}
                </div>
              </div>
              {condNotice ? <p className="rounded bg-amber-50 px-2 py-1 text-xs text-amber-900">{condNotice}</p> : null}
              {condMode === "builder" ? (
                <ConditionBuilder tree={condTree} onChange={setCondTree} catalog={catalog} triggers={triggers} disabled={!canEdit} />
              ) : (
                <div className="space-y-1">
                  <textarea
                    aria-label="Condizioni in JSON"
                    value={condText}
                    onChange={(e) => editCondText(e.target.value)}
                    readOnly={!canEdit}
                    rows={10}
                    spellCheck={false}
                    aria-invalid={condJsonError !== null}
                    className={cn(
                      "w-full rounded border px-2 py-1 font-mono text-xs",
                      condJsonError ? "border-red-400" : "border-[var(--color-bo-border)]",
                    )}
                  />
                  {condJsonError ? (
                    <p className="text-xs text-red-700" role="alert">
                      {condJsonError}
                    </p>
                  ) : (
                    <p className="text-xs text-[var(--color-bo-ink-2)]">
                      Gruppo {"{op, rules}"} con op all / any / not, oppure foglia {"{field, cmp, value}"}; al massimo 3 livelli di gruppi.
                    </p>
                  )}
                </div>
              )}
            </CardBody>
          </Card>
          {(["effects", "limits", "audience", "schedule"] as const).map((k) => (
            <Card key={k}>
              <CardBody className="space-y-2 pt-4">
                <h3 className="text-sm font-semibold capitalize">{sectionTitle(k)}</h3>
                <textarea
                  value={json[k]}
                  onChange={(e) => setJson({ ...json, [k]: e.target.value })}
                  rows={k === "effects" ? 5 : 3}
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
