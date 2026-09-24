"use client";

import Link from "next/link";
import { useLhQuery } from "@/lib/api/client";
import { QueryState } from "@/components/bo/QueryState";
import { CodeText, StatusPill } from "@/components/bo/primitives";
import { Can } from "@/components/bo/Can";
import { CATEGORY_LABEL, type ActionField, type ActionType } from "@/lib/actiontypes/types";
import { describeField } from "@/lib/actiontypes/schema";

/**
 * Dettaglio di un tipo azione (docs/08 §BO-09): schema dei campi (percorso · tipo · obbligatorio · enum) come lo vede
 * il costruttore di condizioni, `sample_data`, campagne che lo usano; *Prova* apre BO-28 col tipo scelto.
 */
export function ActionTypeDetail({
  type,
  sources,
  usedBy,
  onEdit,
}: {
  type: ActionType;
  sources: string[] | null;
  usedBy: string[] | null;
  onEdit: () => void;
}) {
  const fields = useLhQuery<ActionField[]>("ingestion", `/v1/event-types/${type.code}/fields`);
  return (
    <div className="space-y-4 text-sm">
      <div className="flex flex-wrap items-center gap-2">
        <CodeText>{type.code}</CodeText>
        <StatusPill status={type.origin} />
        <span className="text-xs text-[var(--color-bo-ink-2)]">{CATEGORY_LABEL[type.category ?? ""] ?? type.category}</span>
        {!type.enabled ? <span className="rounded-full bg-slate-100 px-2 py-0.5 text-xs text-slate-600">disabilitato</span> : null}
      </div>
      {type.description ? <p className="text-[var(--color-bo-ink-2)]">{type.description}</p> : null}

      <section>
        <h4 className="mb-1 font-semibold">Campi (per le condizioni)</h4>
        <QueryState query={fields} service="ingestion" isEmpty={(d) => d.length === 0} emptyTitle="Nessun campo in data">
          {(d) => (
            <table className="w-full text-xs">
              <tbody>
                {d.map((f) => (
                  <tr key={f.path} className="border-t border-[var(--color-bo-border)]">
                    <td className="py-1 pr-2 font-mono">{f.path}</td>
                    <td className="py-1 text-[var(--color-bo-ink-2)]">{describeField(f)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </QueryState>
      </section>

      <section>
        <h4 className="mb-1 font-semibold">Dati d&apos;esempio</h4>
        <pre className="max-h-48 overflow-auto rounded bg-[var(--color-bo-bg)] p-2 font-mono text-xs">
          {JSON.stringify(type.sampleData ?? {}, null, 2)}
        </pre>
      </section>

      <section className="grid gap-2 sm:grid-cols-2">
        <div>
          <h4 className="mb-1 font-semibold">Fonti che lo inviano</h4>
          <p className="text-xs text-[var(--color-bo-ink-2)]">{sources == null ? "—" : sources.length ? sources.join(", ") : "nessuna"}</p>
        </div>
        <div>
          <h4 className="mb-1 font-semibold">Usato da</h4>
          <p className="text-xs text-[var(--color-bo-ink-2)]">
            {usedBy == null ? "campagne non disponibili" : usedBy.length ? usedBy.join(", ") : "nessuna campagna"}
          </p>
        </div>
      </section>

      <div className="flex flex-wrap gap-2">
        <Link
          href={`/backoffice/demo/simulator?type=${encodeURIComponent(type.code)}`}
          className="rounded bg-[var(--color-bo-accent)] px-3 py-1.5 text-sm font-medium text-white"
        >
          Prova nel simulatore
        </Link>
        <Can capability={type.origin === "CUSTOM" ? "actiontype.custom" : "program.config"} mode="disable">
          <button onClick={onEdit} className="rounded border border-[var(--color-bo-border)] px-3 py-1.5 text-sm">
            Modifica
          </button>
        </Can>
      </div>
    </div>
  );
}
