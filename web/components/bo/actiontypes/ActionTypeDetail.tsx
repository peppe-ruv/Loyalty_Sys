"use client";

import { useState } from "react";
import Link from "next/link";
import { useQueryClient } from "@tanstack/react-query";
import { lhFetch, LhError, useLhQuery } from "@/lib/api/client";
import type { CampaignSummary } from "@/lib/api/types";
import { QueryState } from "@/components/bo/QueryState";
import { CodeText } from "@/components/bo/primitives";
import { Can, useCan } from "@/components/bo/Can";
import { categoryLabel, ORIGIN_LABEL, type ActionField, type ActionType } from "@/lib/actiontypes/types";
import { campaignImpact, describeField } from "@/lib/actiontypes/schema";
import { integratorTypes } from "@/lib/actiontypes/code";
import { externalSourcesFor, reachOf, withAllowedType, withoutAllowedType, type SourceRow } from "@/lib/actiontypes/sources";
import { actionIcon } from "@/lib/icons/action-icons";
import { it } from "@/lib/i18n/it";
import { cn } from "@/lib/cn";

const t = it.actions.detail;

/**
 * Dettaglio di un'azione (docs/08 §BO-09): informazioni (percorso · tipo · obbligatorio · enum) come le vede il
 * costruttore di condizioni, esempio, **«Da dove può arrivare»** con una riga per fonte e, per ADMIN, *Abilita* sulle
 * fonti che non la accettano (Q-433), campagne che la usano con i link, *Crea una campagna con questa azione*, *Prova*.
 */
export function ActionTypeDetail({
  type,
  sources,
  campaigns,
  justCreated = false,
  onEdit,
}: {
  type: ActionType;
  /** `null` = fonti non disponibili (ingestion dorme o risponde con errore). */
  sources: SourceRow[] | null;
  /** `null` = campagne non disponibili. */
  campaigns: CampaignSummary[] | null;
  /** Appena creata: avviso sulle fonti e scorciatoia verso il simulatore. */
  justCreated?: boolean;
  onEdit: () => void;
}) {
  const qc = useQueryClient();
  const canConfig = useCan("program.config");
  const fields = useLhQuery<ActionField[]>("ingestion", `/v1/event-types/${type.code}/fields`);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const Icon = actionIcon(type.icon);
  const props = (type.dataSchema?.properties ?? {}) as Record<string, { title?: unknown }>;
  const onlySimulator = sources != null && type.category !== "INTERNAL" && externalSourcesFor(type.code, sources).length === 0;
  const impact = campaigns ? campaignImpact(type.code, campaigns) : null;
  const integrator = integratorTypes(type.code);

  // Azioni ammesse di una fonte: sempre l'elenco completo, mai un elenco vuoto per sbaglio (Q-433).
  async function putAllowed(source: SourceRow, allowedTypes: string[]) {
    setBusy(source.code);
    setError(null);
    try {
      await lhFetch("ingestion", `/v1/sources/${source.code}`, { method: "PUT", body: JSON.stringify({ allowedTypes }) });
      await qc.invalidateQueries({ queryKey: ["ingestion"] });
    } catch (e) {
      const err = e as LhError;
      setError(err.detail || err.code || it.actions.sources.failed);
    } finally {
      setBusy(null);
    }
  }

  return (
    <div className="space-y-4 text-sm">
      <div className="flex flex-wrap items-center gap-2">
        <Icon className="size-5 text-[var(--color-bo-accent)]" aria-hidden />
        <CodeText>{type.code}</CodeText>
        <span className="rounded-full bg-slate-100 px-2 py-0.5 text-xs">{ORIGIN_LABEL[type.origin]}</span>
        <span className="text-xs text-[var(--color-bo-ink-2)]">{categoryLabel(type.category)}</span>
        {!type.enabled ? <span className="rounded-full bg-slate-100 px-2 py-0.5 text-xs text-slate-600">{t.disabled}</span> : null}
      </div>
      {type.description ? <p className="text-[var(--color-bo-ink-2)]">{type.description}</p> : null}

      {justCreated ? (
        <div role="status" className="space-y-1 rounded border border-emerald-200 bg-emerald-50 p-2 text-xs text-emerald-900">
          <p className="font-medium">{t.created(type.name)}</p>
          {onlySimulator ? <p>{t.onlySimulatorBanner}</p> : null}
        </div>
      ) : onlySimulator && type.origin === "CUSTOM" ? (
        <p role="status" className="rounded bg-amber-50 p-2 text-xs text-amber-900">
          {t.onlySimulatorBanner}
        </p>
      ) : null}

      <section aria-labelledby="atd-reach">
        <h4 id="atd-reach" className="mb-1 font-semibold">
          {t.reach}
        </h4>
        <p className="mb-2 text-xs text-[var(--color-bo-ink-2)]">{t.reachHint}</p>
        {sources == null ? (
          <p className="text-xs text-[var(--color-bo-ink-2)]">{t.sourcesUnavailable}</p>
        ) : (
          <table className="w-full text-xs">
            <tbody>
              {reachOf(type.code, sources).map(({ source, status }) => {
                const add = withAllowedType(source, type.code);
                const remove = withoutAllowedType(source, type.code);
                return (
                  <tr key={source.code} className="border-t border-[var(--color-bo-border)] align-top">
                    <td className="py-1 pr-2">{source.name}</td>
                    <td
                      className={cn(
                        "py-1 pr-2",
                        status === "NOT_ALLOWED" && "text-amber-800",
                        status === "OFF" && "text-slate-500",
                        (status === "ACCEPTS" || status === "ALL") && "text-emerald-800",
                      )}
                    >
                      {t.reachStatus[status]}
                    </td>
                    <td className="py-1 text-right">
                      {canConfig && source.kind === "HTTP" && add ? (
                        <button
                          type="button"
                          disabled={busy === source.code}
                          onClick={() => putAllowed(source, add)}
                          title={t.enableOnHint}
                          aria-label={`${t.enableOn}: ${source.name}`}
                          className="rounded border border-[var(--color-bo-border)] px-2 py-0.5 disabled:opacity-50"
                        >
                          {t.enableOn}
                        </button>
                      ) : null}
                      {canConfig && source.kind === "HTTP" && status === "ACCEPTS" ? (
                        "allowedTypes" in remove ? (
                          <button
                            type="button"
                            disabled={busy === source.code}
                            onClick={() => putAllowed(source, remove.allowedTypes)}
                            aria-label={`${t.remove}: ${source.name}`}
                            className="rounded border border-[var(--color-bo-border)] px-2 py-0.5 disabled:opacity-50"
                          >
                            {t.remove}
                          </button>
                        ) : remove.blocked === "LAST_TYPE" ? (
                          <span className="text-[var(--color-bo-ink-2)]">{t.removeLast}</span>
                        ) : null
                      ) : null}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
        {error ? (
          <p role="alert" className="mt-1 rounded bg-red-50 p-2 text-xs text-red-800">
            {error}
          </p>
        ) : null}
      </section>

      <section>
        <h4 className="mb-1 font-semibold">{t.fields}</h4>
        <QueryState query={fields} service="ingestion" isEmpty={(d) => d.length === 0} emptyTitle={t.fieldsEmpty} skeletonRows={3}>
          {(d) => (
            <table className="w-full text-xs">
              <tbody>
                {d.map((f) => {
                  const title = props[f.path.replace(/^data\./, "")]?.title;
                  return (
                    <tr key={f.path} className="border-t border-[var(--color-bo-border)]">
                      <td className="py-1 pr-2">
                        {typeof title === "string" ? <span className="block">{title}</span> : null}
                        <span className="font-mono">{f.path}</span>
                      </td>
                      <td className="py-1 text-[var(--color-bo-ink-2)]">{describeField(f)}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}
        </QueryState>
      </section>

      <section>
        <h4 className="mb-1 font-semibold">{t.sample}</h4>
        <pre className="max-h-48 overflow-auto rounded bg-[var(--color-bo-bg)] p-2 font-mono text-xs">
          {JSON.stringify(type.sampleData ?? {}, null, 2)}
        </pre>
      </section>

      <section>
        <h4 className="mb-1 font-semibold">{t.usedBy}</h4>
        {impact == null ? (
          <p className="text-xs text-[var(--color-bo-ink-2)]">{t.usedByUnavailable}</p>
        ) : impact.using.length === 0 ? (
          <p className="text-xs text-[var(--color-bo-ink-2)]">{t.usedByNone}</p>
        ) : (
          <ul className="flex flex-wrap gap-x-3 gap-y-1 text-xs">
            {impact.using.map((c) => (
              <li key={c.id}>
                <Link href={`/backoffice/campaigns/${c.id}`} className="underline">
                  {c.name}
                </Link>{" "}
                <span className="text-[var(--color-bo-ink-2)]">({c.status})</span>
              </li>
            ))}
          </ul>
        )}
      </section>

      <details className="text-xs">
        <summary className="cursor-pointer font-medium">{t.technical}</summary>
        <p className="mt-1 text-[var(--color-bo-ink-2)]">{t.integrator(integrator.short, integrator.full)}</p>
      </details>

      <div className="flex flex-wrap gap-2">
        <Link
          href={`/backoffice/demo/simulator?type=${encodeURIComponent(type.code)}`}
          className="rounded bg-[var(--color-bo-accent)] px-3 py-1.5 text-sm font-medium text-white"
        >
          {t.tryIt}
        </Link>
        {type.enabled ? (
          <Can capability="object.edit" mode="disable">
            <Link
              href={`/backoffice/campaigns/new?trigger=${encodeURIComponent(type.code)}`}
              className="rounded border border-[var(--color-bo-border)] px-3 py-1.5 text-sm"
            >
              {t.createCampaign}
            </Link>
          </Can>
        ) : null}
        <Can capability={type.origin === "CUSTOM" ? "actiontype.custom" : "program.config"} mode="disable">
          <button onClick={onEdit} className="rounded border border-[var(--color-bo-border)] px-3 py-1.5 text-sm">
            {t.edit}
          </button>
        </Can>
      </div>
    </div>
  );
}
