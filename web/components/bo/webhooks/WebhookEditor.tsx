"use client";

import { useState } from "react";
import { useLhMutation, type LhError } from "@/lib/api/client";
import { errorsByField } from "@/lib/messages/templates";
import {
  emptyForm,
  formFrom,
  formProblems,
  groupedFacts,
  toRequest,
  toggleFactType,
  type WebhookForm,
} from "@/lib/webhooks/webhooks";
import type { Webhook, WebhookRequest } from "@/lib/webhooks/types";
import { useCan } from "@/components/bo/Can";
import { Field, INPUT } from "@/components/bo/FormBits";
import { CodeText } from "@/components/bo/primitives";

// Editor di BO-23 (docs/08 §BO-23): URL, tipi di fatto (dal catalogo, selezione multipla per area), attivo. Alla
// creazione il servizio restituisce il segreto una sola volta: l'editor lo mostra con "Copia" e l'avviso che non sarà
// più visibile. Scritture solo con `webhook.write` (ADMIN); gli errori del servizio tornano sui campi.
export function WebhookEditor({
  webhook,
  onSaved,
  onCreated,
  onDeleted,
}: {
  webhook: Webhook | null;
  onSaved: () => void;
  onCreated: (created: Webhook) => void;
  onDeleted: () => void;
}) {
  const canEdit = useCan("webhook.write");
  const isNew = webhook == null;
  const [form, setForm] = useState<WebhookForm>(webhook ? formFrom(webhook) : emptyForm());
  const [touched, setTouched] = useState(false);
  const [serverError, setServerError] = useState<LhError | null>(null);
  const save = useLhMutation<Webhook, WebhookRequest>(
    "engagement",
    isNew ? "POST" : "PUT",
    () => (isNew ? "/v1/webhooks" : `/v1/webhooks/${webhook.id}`),
  );
  const remove = useLhMutation<void, undefined>("engagement", "DELETE", () => `/v1/webhooks/${webhook?.id}`, {
    onSuccess: onDeleted,
  });

  const problems = formProblems(form);
  const server = errorsByField(serverError?.errors);
  const errorsFor = (field: string) => [...(touched ? problems[field] ?? [] : []), ...(server[field] ?? [])];
  const set = (patch: Partial<WebhookForm>) => setForm((f) => ({ ...f, ...patch }));

  return (
    <form
      className="space-y-4"
      noValidate
      onSubmit={(e) => {
        e.preventDefault();
        setTouched(true);
        setServerError(null);
        if (Object.keys(problems).length > 0) return;
        save.mutate(toRequest(form, webhook), {
          onError: setServerError,
          onSuccess: (saved) => (isNew ? onCreated(saved) : onSaved()),
        });
      }}
    >
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label="Nome">
          <input value={form.name} disabled={!canEdit} onChange={(e) => set({ name: e.target.value })} placeholder="CRM del programma" className={INPUT} aria-invalid={errorsFor("name").length > 0} />
          <Errors messages={errorsFor("name")} />
        </Field>
        <Field label="Codice" hint={isNew ? "Facoltativo: se vuoto viene generato (WH-…)." : "Non si modifica."}>
          <input value={form.code} disabled={!canEdit || !isNew} onChange={(e) => set({ code: e.target.value.toUpperCase() })} placeholder="WH-…" className={`${INPUT} font-mono`} aria-invalid={errorsFor("code").length > 0} />
          <Errors messages={errorsFor("code")} />
        </Field>
      </div>
      <Field label="URL di destinazione" hint="Solo https:// verso indirizzi pubblici (in locale anche http://localhost). Timeout 5 s, redirect non seguiti.">
        <input value={form.url} disabled={!canEdit} onChange={(e) => set({ url: e.target.value })} placeholder="https://example.org/hook" className={`${INPUT} font-mono text-xs`} aria-invalid={errorsFor("url").length > 0} />
        <Errors messages={errorsFor("url")} />
      </Field>

      <Field group label={`Tipi di fatto (${form.factTypes.length} scelti)`}>
        <div className="space-y-3 rounded border border-[var(--color-bo-border)] p-3">
          {groupedFacts().map((g) => (
            <fieldset key={g.label}>
              <legend className="mb-1 text-xs font-semibold text-[var(--color-bo-ink-2)]">{g.label}</legend>
              <div className="grid gap-x-3 gap-y-1 sm:grid-cols-2">
                {g.facts.map((f) => (
                  <label key={f.type} className="flex items-start gap-2 text-sm">
                    <input
                      type="checkbox"
                      className="mt-1"
                      checked={form.factTypes.includes(f.type)}
                      disabled={!canEdit}
                      onChange={() => set({ factTypes: toggleFactType(form.factTypes, f.type) })}
                    />
                    <span className="flex flex-col">
                      <span>{f.label}</span>
                      <span className="font-mono text-[11px] text-[var(--color-bo-ink-2)]">{f.type}</span>
                    </span>
                  </label>
                ))}
              </div>
            </fieldset>
          ))}
        </div>
        <Errors messages={errorsFor("factTypes")} />
      </Field>

      <Field group label="Stato">
        <label className="inline-flex items-center gap-2 text-sm">
          <input type="checkbox" checked={form.enabled} disabled={!canEdit} onChange={(e) => set({ enabled: e.target.checked })} />
          Attivo: i nuovi fatti dei tipi scelti generano consegne
        </label>
      </Field>

      {canEdit ? (
        <div className="flex flex-wrap items-center gap-3">
          <button type="submit" disabled={save.isPending} className="rounded bg-[var(--color-bo-accent)] px-3 py-1.5 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50">
            {save.isPending ? "Salvataggio…" : isNew ? "Crea webhook" : "Salva"}
          </button>
          {!isNew ? (
            <button
              type="button"
              disabled={remove.isPending}
              onClick={() => {
                if (window.confirm(`Eliminare il webhook ${webhook.code} e il suo registro consegne?`)) remove.mutate(undefined);
              }}
              className="rounded border border-red-300 px-3 py-1.5 text-sm text-red-700 hover:bg-red-50 disabled:opacity-50"
            >
              Elimina
            </button>
          ) : null}
          {serverError?.status === 409 && serverError.code === "VERSION_CONFLICT" ? (
            <span role="alert" className="text-xs text-red-700">Qualcun altro ha modificato il webhook: chiudi e riapri per ricaricarlo.</span>
          ) : serverError && Object.keys(server).length === 0 ? (
            <span role="alert" className="text-xs text-red-700">{serverError.detail || serverError.code}</span>
          ) : null}
          {remove.isError ? <span role="alert" className="text-xs text-red-700">{remove.error.detail || remove.error.code}</span> : null}
        </div>
      ) : (
        <p className="text-xs text-[var(--color-bo-ink-2)]">Sola lettura: i webhook si gestiscono con il ruolo ADMIN.</p>
      )}
    </form>
  );
}

/** Segreto di firma, mostrato una sola volta dopo la creazione. */
export function SecretReveal({ webhook, onDone }: { webhook: Webhook; onDone: () => void }) {
  const [copied, setCopied] = useState(false);
  const secret = webhook.secret ?? "";
  return (
    <div className="space-y-4">
      <p className="text-sm">
        Webhook <CodeText>{webhook.code}</CodeText> creato. Questo è il <strong>segreto di firma</strong>: serve al sistema
        ricevente per verificare l&apos;header <code className="font-mono text-xs">X-LH-Signature</code>.
      </p>
      <div role="alert" className="rounded-md border border-amber-200 bg-amber-50 p-3 text-sm text-amber-900">
        Copialo adesso: <strong>non sarà più visibile</strong>. Se lo perdi, elimina il webhook e creane uno nuovo.
      </div>
      <div className="flex items-center gap-2">
        <code className="min-w-0 flex-1 break-all rounded bg-slate-100 px-2 py-1.5 font-mono text-xs" data-testid="webhook-secret">
          {secret}
        </code>
        <button
          type="button"
          onClick={() => {
            void navigator.clipboard?.writeText(secret).then(() => setCopied(true), () => setCopied(false));
          }}
          className="shrink-0 rounded border border-[var(--color-bo-border)] px-2 py-1 text-xs hover:bg-slate-50"
        >
          {copied ? "Copiato ✓" : "Copia"}
        </button>
      </div>
      <p className="text-xs text-[var(--color-bo-ink-2)]">
        Verifica d&apos;esempio: <code className="font-mono">deploy/webhook-receiver/verify.mjs</code> (HMAC-SHA256 sul
        corpo grezzo, confronto a tempo costante).
      </p>
      <button type="button" onClick={onDone} className="rounded bg-[var(--color-bo-accent)] px-3 py-1.5 text-sm font-medium text-white hover:opacity-90">
        Ho copiato il segreto
      </button>
    </div>
  );
}

function Errors({ messages }: { messages: string[] }) {
  if (messages.length === 0) return null;
  return (
    <span role="alert" className="mt-1 block text-xs text-red-700">
      {messages.join(" · ")}
    </span>
  );
}
