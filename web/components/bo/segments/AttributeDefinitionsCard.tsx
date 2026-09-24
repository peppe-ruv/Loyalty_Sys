"use client";

import { useState } from "react";
import { Plus, Trash2 } from "lucide-react";
import { useQueryClient } from "@tanstack/react-query";
import { lhFetch, LhError, useLhQuery } from "@/lib/api/client";
import { Card, CardBody } from "@/components/ui/card";
import { QueryState } from "@/components/bo/QueryState";
import { INPUT } from "@/components/bo/FormBits";
import { Can, useCan } from "@/components/bo/Can";
import type { AttributeDefinition, AttributeType } from "@/lib/member/attributes";

const TYPES: { value: AttributeType; label: string }[] = [
  { value: "STRING", label: "Testo" },
  { value: "NUMBER", label: "Numero" },
  { value: "BOOLEAN", label: "Sì / no" },
  { value: "DATE", label: "Data" },
];

interface Row {
  key: string;
  label: string;
  type: AttributeType;
  options: string;
  saved: boolean;
}

/**
 * Attributi personalizzati (F-MBR-03, `segment.write` = "segmenti, attributi custom", docs/08 §2): chiave, etichetta,
 * tipo e valori ammessi. Usati in BO-03, nei criteri dei segmenti e nelle condizioni delle campagne come
 * `member.attributes.<chiave>`. SPEC-GAP: Q-94 — docs/08 non assegna una schermata alle definizioni: stanno in BO-04.
 */
export function AttributeDefinitionsCard() {
  const query = useLhQuery<AttributeDefinition[]>("member", "/v1/attribute-definitions");
  return (
    <Card>
      <CardBody className="space-y-3 pt-4">
        <div>
          <h2 className="text-sm font-semibold">Attributi personalizzati</h2>
          <p className="text-xs text-[var(--color-bo-ink-2)]">
            Campi in più sul profilo dei membri, usabili nei criteri dei segmenti e nelle condizioni delle campagne
            (<span className="font-mono">member.attributes.chiave</span>).
          </p>
        </div>
        <QueryState query={query} service="member">
          {(d) => <DefinitionsForm key={JSON.stringify(d)} defs={d} />}
        </QueryState>
      </CardBody>
    </Card>
  );
}

function DefinitionsForm({ defs }: { defs: AttributeDefinition[] }) {
  const qc = useQueryClient();
  const canWrite = useCan("segment.write");
  const [rows, setRows] = useState<Row[]>(() => defs.map((d) => ({ ...d, options: d.options.join(", "), saved: true })));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<LhError | null>(null);
  const [ok, setOk] = useState(false);

  const update = (i: number, patch: Partial<Row>) => {
    setOk(false);
    setRows((rs) => rs.map((r, j) => (j === i ? { ...r, ...patch } : r)));
  };
  const fieldError = (i: number) =>
    error?.errors?.filter((e) => e.field.startsWith(`[${i}]`)).map((e) => e.message).join(" · ") || null;

  async function save() {
    setBusy(true);
    setError(null);
    try {
      const body = rows.map((r) => ({
        key: r.key.trim(),
        label: r.label.trim(),
        type: r.type,
        options: r.type === "STRING" ? r.options.split(",").map((o) => o.trim()).filter(Boolean) : [],
      }));
      await lhFetch("member", "/v1/attribute-definitions", { method: "PUT", body: JSON.stringify(body) });
      setOk(true);
      await qc.invalidateQueries({ queryKey: ["member"] });
    } catch (e) {
      setError(e instanceof LhError ? e : new LhError(0, "ERROR", "Salvataggio non riuscito", false));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="space-y-2">
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-xs text-[var(--color-bo-ink-2)]">
              <th className="py-1 pr-2 font-medium">Chiave</th>
              <th className="py-1 pr-2 font-medium">Etichetta</th>
              <th className="py-1 pr-2 font-medium">Tipo</th>
              <th className="py-1 pr-2 font-medium">Valori ammessi</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {rows.map((r, i) => (
              <tr key={i} className="align-top">
                <td className="py-1 pr-2">
                  <input
                    value={r.key}
                    onChange={(e) => update(i, { key: e.target.value.trim() })}
                    disabled={!canWrite || r.saved}
                    aria-label={`Chiave dell'attributo ${i + 1}`}
                    className={`${INPUT} font-mono`}
                  />
                  {fieldError(i) ? <span className="text-xs text-red-700">{fieldError(i)}</span> : null}
                </td>
                <td className="py-1 pr-2">
                  <input value={r.label} onChange={(e) => update(i, { label: e.target.value })} disabled={!canWrite} aria-label={`Etichetta dell'attributo ${i + 1}`} className={INPUT} />
                </td>
                <td className="py-1 pr-2">
                  <select value={r.type} onChange={(e) => update(i, { type: e.target.value as AttributeType })} disabled={!canWrite} aria-label={`Tipo dell'attributo ${i + 1}`} className={INPUT}>
                    {TYPES.map((t) => (
                      <option key={t.value} value={t.value}>
                        {t.label}
                      </option>
                    ))}
                  </select>
                </td>
                <td className="py-1 pr-2">
                  <input
                    value={r.options}
                    onChange={(e) => update(i, { options: e.target.value })}
                    disabled={!canWrite || r.type !== "STRING"}
                    placeholder={r.type === "STRING" ? "vuoto = testo libero" : "—"}
                    aria-label={`Valori dell'attributo ${i + 1}`}
                    className={INPUT}
                  />
                </td>
                <td className="py-1">
                  {canWrite ? (
                    <button
                      onClick={() => setRows((rs) => rs.filter((_, j) => j !== i))}
                      aria-label={`Togli l'attributo ${i + 1}`}
                      className="mt-1 rounded p-1 text-[var(--color-bo-ink-2)] hover:bg-[var(--color-bo-bg)]"
                    >
                      <Trash2 size={14} />
                    </button>
                  ) : null}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {error ? (
        <p role="alert" className="rounded bg-red-50 p-2 text-sm text-red-800">
          {error.code === "ATTRIBUTE_IN_USE"
            ? `${error.detail} Svuota prima il valore sui membri.`
            : error.detail || error.code}
        </p>
      ) : null}
      {ok ? <p role="status" className="rounded bg-emerald-50 p-2 text-sm text-emerald-800">Attributi salvati.</p> : null}
      <div className="flex gap-2">
        {canWrite ? (
          <button
            onClick={() => setRows((rs) => [...rs, { key: "", label: "", type: "STRING", options: "", saved: false }])}
            className="inline-flex items-center gap-1 rounded border border-[var(--color-bo-border)] px-2 py-1 text-xs"
          >
            <Plus size={12} /> Aggiungi attributo
          </button>
        ) : null}
        <Can capability="segment.write" mode="disable">
          <button
            onClick={save}
            disabled={busy}
            className="rounded bg-[var(--color-bo-accent)] px-3 py-1.5 text-sm font-medium text-white disabled:opacity-50"
          >
            {busy ? "Salvataggio…" : "Salva attributi"}
          </button>
        </Can>
      </div>
    </div>
  );
}
