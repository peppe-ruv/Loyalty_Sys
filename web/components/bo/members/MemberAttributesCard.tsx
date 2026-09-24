"use client";

import { useState } from "react";
import { X } from "lucide-react";
import { useQueryClient } from "@tanstack/react-query";
import { lhFetch, LhError, useLhQuery } from "@/lib/api/client";
import type { MemberView } from "@/lib/api/types";
import { ANONYMIZED_LABEL, isAnonymized } from "@/lib/member/anonymized";
import { Card, CardBody } from "@/components/ui/card";
import { QueryState } from "@/components/bo/QueryState";
import { CodeText } from "@/components/bo/primitives";
import { INPUT } from "@/components/bo/FormBits";
import { useCan, Can } from "@/components/bo/Can";
import {
  addLabel,
  attributePatch,
  sameLabels,
  toFormValue,
  type AttributeDefinition,
  type AttributeValues,
} from "@/lib/member/attributes";

type MemberWithAttributes = MemberView & { labels: string[]; attributes?: AttributeValues | null };

/**
 * Attributi personalizzati ed etichette del membro (docs/08 §BO-03 scheda `segments`: "attributi custom
 * modificabili", F-MBR-03). Scrittura con `member.write` (ADMIN, CARE); gli altri ruoli vedono i valori.
 * Entrano nei segmenti al ricalcolo e nelle condizioni delle campagne con lo snapshot di `member.updated`.
 */
export function MemberAttributesCard({ id }: { id: string }) {
  const member = useLhQuery<MemberWithAttributes>("member", `/v1/members/${id}`);
  const defs = useLhQuery<AttributeDefinition[]>("member", "/v1/attribute-definitions");
  // L'esito resta visibile anche quando il form si ricrea sulla nuova versione del membro.
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null);
  return (
    <Card>
      <CardBody className="space-y-3 pt-4">
        <h2 className="text-sm font-semibold">Attributi ed etichette</h2>
        <QueryState query={member} service="member">
          {(m) =>
            isAnonymized(m.status) ? (
              // F-MBR-05 (M7.5): attributi cancellati con l'anonimizzazione, etichette conservate, niente modifiche.
              <div className="space-y-2 text-sm">
                <p className="italic text-slate-500">{ANONYMIZED_LABEL}: attributi personali cancellati, modifiche disabilitate.</p>
                {(m.labels ?? []).length > 0 ? (
                  <ul className="flex flex-wrap gap-1.5">
                    {(m.labels ?? []).map((l) => (
                      <li key={l} className="rounded-full border border-[var(--color-bo-border)] px-2 py-0.5 text-xs">
                        {l}
                      </li>
                    ))}
                  </ul>
                ) : null}
              </div>
            ) : (
            <QueryState query={defs} service="member">
              {(d) => <AttributesForm key={`${m.id}-${m.version}`} member={m} defs={d} message={message} setMessage={setMessage} />}
            </QueryState>
            )
          }
        </QueryState>
      </CardBody>
    </Card>
  );
}

type Message = { ok: boolean; text: string } | null;

function AttributesForm({
  member,
  defs,
  message,
  setMessage,
}: {
  member: MemberWithAttributes;
  defs: AttributeDefinition[];
  message: Message;
  setMessage: (m: Message) => void;
}) {
  const qc = useQueryClient();
  const canWrite = useCan("member.write");
  const current = member.attributes ?? {};
  const [form, setForm] = useState<Record<string, string>>(() =>
    Object.fromEntries(defs.map((d) => [d.key, toFormValue(d, current[d.key])])),
  );
  const [labels, setLabels] = useState<string[]>(member.labels);
  const [draftLabel, setDraftLabel] = useState("");
  const [busy, setBusy] = useState(false);

  const { attributes, errors } = attributePatch(defs, current, form);
  const dirty = Object.keys(attributes).length > 0 || !sameLabels(labels, member.labels);
  const blocked = busy || !dirty || Object.keys(errors).length > 0;

  async function save() {
    setBusy(true);
    setMessage(null);
    try {
      await lhFetch("member", `/v1/members/${member.id}`, {
        method: "PATCH",
        body: JSON.stringify({ version: member.version, attributes, labels }),
      });
      setMessage({ ok: true, text: "Salvato: i segmenti si aggiornano al prossimo ricalcolo." });
      await qc.invalidateQueries({ queryKey: ["member"] });
    } catch (e) {
      const err = e as LhError;
      setMessage({
        ok: false,
        text: err.code === "VERSION_CONFLICT" ? "Il membro è cambiato nel frattempo: ricarica la pagina." : err.detail || err.code,
      });
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="space-y-4">
      {defs.length === 0 ? (
        <p className="text-sm text-[var(--color-bo-ink-2)]">Nessun attributo personalizzato definito (Segmenti › Attributi personalizzati).</p>
      ) : (
        <div className="grid gap-3 sm:grid-cols-2">
          {defs.map((d) => (
            <label key={d.key} className="block text-xs text-[var(--color-bo-ink-2)]">
              <span className="flex items-baseline justify-between gap-2">
                {d.label}
                <span className="font-mono text-[10px]">{d.key}</span>
              </span>
              <AttributeInput def={d} value={form[d.key] ?? ""} disabled={!canWrite} onChange={(v) => setForm({ ...form, [d.key]: v })} />
              {errors[d.key] ? <span className="text-xs text-red-700">{errors[d.key]}</span> : null}
            </label>
          ))}
        </div>
      )}

      <div>
        <p className="mb-1 text-xs text-[var(--color-bo-ink-2)]">Etichette</p>
        <ul className="flex flex-wrap gap-1.5">
          {labels.length === 0 ? <li className="text-sm text-[var(--color-bo-ink-2)]">Nessuna etichetta</li> : null}
          {labels.map((l) => (
            <li key={l} className="inline-flex items-center gap-1">
              <CodeText>{l}</CodeText>
              {canWrite ? (
                <button
                  onClick={() => setLabels(labels.filter((x) => x !== l))}
                  aria-label={`Togli l'etichetta ${l}`}
                  className="rounded p-0.5 text-[var(--color-bo-ink-2)] hover:bg-[var(--color-bo-bg)]"
                >
                  <X size={12} />
                </button>
              ) : null}
            </li>
          ))}
        </ul>
        {canWrite ? (
          <form
            className="mt-2 flex gap-2"
            onSubmit={(e) => {
              e.preventDefault();
              setLabels(addLabel(labels, draftLabel));
              setDraftLabel("");
            }}
          >
            <input
              value={draftLabel}
              onChange={(e) => setDraftLabel(e.target.value)}
              placeholder="nuova etichetta, es. vip"
              aria-label="Nuova etichetta"
              className={INPUT}
            />
            <button type="submit" className="rounded border border-[var(--color-bo-border)] px-2 text-sm">
              Aggiungi
            </button>
          </form>
        ) : null}
      </div>

      {message ? (
        <p role="status" className={`rounded p-2 text-sm ${message.ok ? "bg-emerald-50 text-emerald-800" : "bg-red-50 text-red-800"}`}>
          {message.text}
        </p>
      ) : null}
      <Can capability="member.write" mode="disable">
        <button
          onClick={save}
          disabled={blocked}
          className="rounded bg-[var(--color-bo-accent)] px-3 py-1.5 text-sm font-medium text-white disabled:opacity-50"
        >
          {busy ? "Salvataggio…" : "Salva attributi"}
        </button>
      </Can>
    </div>
  );
}

function AttributeInput({
  def,
  value,
  disabled,
  onChange,
}: {
  def: AttributeDefinition;
  value: string;
  disabled: boolean;
  onChange: (v: string) => void;
}) {
  if (def.type === "BOOLEAN" || def.options.length > 0) {
    const options = def.type === "BOOLEAN" ? [["true", "Sì"], ["false", "No"]] : def.options.map((o) => [o, o]);
    return (
      <select value={value} disabled={disabled} onChange={(e) => onChange(e.target.value)} className={`${INPUT} mt-1`}>
        <option value="">—</option>
        {options.map(([v, l]) => (
          <option key={v} value={v}>
            {l}
          </option>
        ))}
      </select>
    );
  }
  return (
    <input
      type={def.type === "DATE" ? "date" : "text"}
      inputMode={def.type === "NUMBER" ? "decimal" : undefined}
      value={value}
      disabled={disabled}
      onChange={(e) => onChange(e.target.value)}
      className={`${INPUT} mt-1`}
    />
  );
}
