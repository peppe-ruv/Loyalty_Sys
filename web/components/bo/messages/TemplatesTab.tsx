"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { lhFetch, useLhMutation, useLhQuery, type LhError, type Page } from "@/lib/api/client";
import type { MemberView } from "@/lib/api/types";
import { FACTS, factLabel, placeholdersFor, sampleEvent, type PreviewSource } from "@/lib/messages/facts";
import { CATEGORIES, CATEGORY_LABEL, CHANNELS, CHANNEL_LABEL } from "@/lib/messages/inbox";
import { ICON_CHOICES, messageIcon } from "@/lib/messages/icons";
import {
  emptyTemplateForm,
  errorsByField,
  insertAt,
  templateToForm,
  templateUsage,
  validateTemplateForm,
  type CampaignLike,
  type TemplateForm,
} from "@/lib/messages/templates";
import type { MessageTemplate, NotificationRule, RenderResult, TemplateRequest } from "@/lib/messages/types";
import { QueryState } from "@/components/bo/QueryState";
import { DataTable, type Column } from "@/components/bo/DataTable";
import { Can, useCan } from "@/components/bo/Can";
import { SideSheet } from "@/components/bo/SideSheet";
import { Field, INPUT } from "@/components/bo/FormBits";
import { CodeText } from "@/components/bo/primitives";
import { MessagePreview } from "./MessagePreview";
import { useTemplateUsageSources } from "./useTemplateUsage";
import { cn } from "@/lib/cn";

// Campi affiancati: lo stile dei campi senza la larghezza piena.
const INLINE = INPUT.replace("w-full ", "");

// BO-19 `templates` (docs/08 §BO-19, F-MSG-02): elenco con codice, titolo, canale, categoria e chi li usa; editor in
// foglio laterale con segnaposto {{…}} suggeriti dal tipo di fatto delle regole (o dalla campagna SEND_MESSAGE) che
// usano il template, anteprima renderizzata dal servizio su un evento campione (POST …/render) e "Dove si vede".
export function TemplatesTab() {
  const list = useLhQuery<MessageTemplate[]>("engagement", "/v1/message-templates");
  const usage = useTemplateUsageSources();
  const [editing, setEditing] = useState<MessageTemplate | "new" | null>(null);

  const columns: Column<MessageTemplate>[] = [
    {
      key: "name",
      header: "Template",
      render: (t) => {
        const Icon = messageIcon(t.icon, t.category);
        return (
          <span className="flex items-center gap-2">
            <Icon className="size-4 shrink-0 text-[var(--color-bo-accent)]" aria-hidden />
            <span className="flex flex-col">
              <span className="font-medium">{t.name}</span>
              <CodeText>{t.code}</CodeText>
            </span>
          </span>
        );
      },
    },
    { key: "title", header: "Titolo", render: (t) => <span className="font-mono text-xs">{t.titleTpl}</span> },
    { key: "channel", header: "Canale", render: (t) => <ChannelPill channel={t.channel} /> },
    { key: "category", header: "Categoria", render: (t) => CATEGORY_LABEL[t.category] ?? t.category },
    {
      key: "usage",
      header: "Usato da",
      render: (t) => {
        const u = templateUsage(t.code, usage.rules, usage.campaigns);
        const parts = [
          u.usedByRules.length ? `${u.usedByRules.length} regol${u.usedByRules.length === 1 ? "a" : "e"}` : null,
          u.usedByCampaigns.length ? u.usedByCampaigns.map((c) => c.code).join(", ") : null,
        ].filter(Boolean);
        return parts.length ? <span className="text-xs">{parts.join(" · ")}</span> : <span className="text-xs text-amber-700">nessuno</span>;
      },
    },
  ];

  return (
    <>
      <div className="mb-3 flex justify-end">
        <Can capability="content.write" mode="disable">
          <button onClick={() => setEditing("new")} className="rounded bg-[var(--color-bo-accent)] px-3 py-1.5 text-sm font-medium text-white hover:opacity-90">
            Nuovo template
          </button>
        </Can>
      </div>
      <QueryState query={list} service="engagement" isEmpty={(d) => d.length === 0} emptyTitle="Nessun template" emptyHint="Crea il primo template di messaggio.">
        {(d) => <DataTable columns={columns} rows={d} rowKey={(t) => t.code} onRowClick={setEditing} />}
      </QueryState>
      <SideSheet
        open={editing != null}
        title={
          editing === "new" ? (
            "Nuovo template"
          ) : editing ? (
            <span className="flex flex-col">
              <span className="font-semibold">{editing.name}</span>
              <CodeText>{editing.code}</CodeText>
            </span>
          ) : (
            ""
          )
        }
        onClose={() => setEditing(null)}
      >
        {editing != null ? (
          <TemplateEditor
            key={editing === "new" ? "new" : editing.code}
            template={editing === "new" ? null : editing}
            rules={usage.rules}
            campaigns={usage.campaigns}
            campaignsUnavailable={usage.campaignsUnavailable}
            onSaved={setEditing}
          />
        ) : null}
      </SideSheet>
    </>
  );
}

function ChannelPill({ channel }: { channel: MessageTemplate["channel"] }) {
  return (
    <span className={cn("inline-flex whitespace-nowrap rounded-full px-2 py-0.5 text-xs font-medium", channel === "EMAIL_FAKE" ? "bg-violet-100 text-violet-800" : "bg-sky-100 text-sky-800")}>
      {CHANNEL_LABEL[channel] ?? channel}
    </span>
  );
}

type TplField = "titleTpl" | "bodyTpl";

const sourceKey = (s: PreviewSource) => (s.kind === "fact" ? `fact:${s.factType}` : `campaign:${s.campaignCode}`);

function TemplateEditor({
  template: t,
  rules,
  campaigns,
  campaignsUnavailable,
  onSaved,
}: {
  template: MessageTemplate | null;
  rules: NotificationRule[];
  campaigns: CampaignLike[];
  campaignsUnavailable: boolean;
  onSaved: (t: MessageTemplate) => void;
}) {
  const canEdit = useCan("content.write");
  const isNew = t == null;
  const [f, setF] = useState<TemplateForm>(t ? templateToForm(t) : emptyTemplateForm());
  const [clientErrors, setClientErrors] = useState<Record<string, string[]>>({});
  const [serverError, setServerError] = useState<LhError | null>(null);
  const [saved, setSaved] = useState(false);
  const set = <K extends keyof TemplateForm>(k: K, v: TemplateForm[K]) => {
    setSaved(false);
    setF((p) => ({ ...p, [k]: v }));
  };

  // Sorgenti che usano il template → segnaposto suggeriti e scelta dell'evento campione.
  const usage = useMemo(() => templateUsage(t?.code ?? "", rules, campaigns), [t?.code, rules, campaigns]);
  const options: PreviewSource[] = usage.sources.length ? usage.sources : FACTS.map((x) => ({ kind: "fact" as const, factType: x.type }));
  const [sourceSel, setSourceSel] = useState<string>(() => sourceKey(options[0]));
  const source = options.find((o) => sourceKey(o) === sourceSel) ?? options[0];
  const placeholders = placeholdersFor(usage.sources.length ? usage.sources : [source], t?.code ?? f.code);

  // Inserimento del segnaposto nel campo col cursore (titolo o testo).
  const titleRef = useRef<HTMLInputElement>(null);
  const bodyRef = useRef<HTMLTextAreaElement>(null);
  const [lastField, setLastField] = useState<TplField>("titleTpl");
  function insert(token: string) {
    const el = lastField === "titleTpl" ? titleRef.current : bodyRef.current;
    const r = insertAt(f[lastField], token, el?.selectionStart ?? null, el?.selectionEnd ?? null);
    set(lastField, r.text);
    requestAnimationFrame(() => {
      el?.focus();
      el?.setSelectionRange(r.caret, r.caret);
    });
  }

  const save = useLhMutation<MessageTemplate, TemplateRequest>(
    "engagement",
    isNew ? "POST" : "PUT",
    () => (isNew ? "/v1/message-templates" : `/v1/message-templates/${encodeURIComponent(t.code)}`),
    {
      onSuccess: (res) => {
        setSaved(true);
        onSaved(res);
      },
    },
  );

  function submit(e: React.FormEvent) {
    e.preventDefault();
    setServerError(null);
    const errors = validateTemplateForm(f, isNew);
    setClientErrors(errors);
    if (Object.keys(errors).length > 0) return;
    save.mutate(
      {
        code: isNew ? f.code.trim().toUpperCase() : undefined,
        name: f.name.trim(),
        channel: f.channel,
        category: f.category,
        icon: f.icon,
        linkTarget: f.linkTarget.trim(),
        titleTpl: f.titleTpl,
        bodyTpl: f.bodyTpl,
        version: t?.version,
      },
      { onError: setServerError },
    );
  }

  const fieldErrors = { ...errorsByField(serverError?.errors), ...clientErrors };
  const err = (k: string) => (fieldErrors[k]?.length ? <FieldErrors messages={fieldErrors[k]} /> : null);
  const conflict = serverError?.status === 409 && serverError.code === "VERSION_CONFLICT";

  return (
    <div className="space-y-5">
      <form className="space-y-3" onSubmit={submit} noValidate>
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Nome">
            <input value={f.name} disabled={!canEdit} onChange={(e) => set("name", e.target.value)} className={INPUT} aria-invalid={!!fieldErrors.name} />
            {err("name")}
          </Field>
          <Field label="Codice" hint={isNew ? "Es. MSG-POINTS-EARNED. Non si cambia dopo." : undefined}>
            <input value={f.code} disabled={!canEdit || !isNew} onChange={(e) => set("code", e.target.value.toUpperCase())} className={`${INPUT} font-mono`} aria-invalid={!!fieldErrors.code} />
            {err("code")}
          </Field>
        </div>
        <div className="grid gap-3 sm:grid-cols-3">
          <Field label="Canale" hint={f.channel === "EMAIL_FAKE" ? "Nessun invio reale: l'anteprima si consulta nel registro." : undefined}>
            <select value={f.channel} disabled={!canEdit} onChange={(e) => set("channel", e.target.value as TemplateForm["channel"])} className={INPUT}>
              {CHANNELS.map((c) => <option key={c} value={c}>{CHANNEL_LABEL[c]}</option>)}
            </select>
            {err("channel")}
          </Field>
          <Field label="Categoria">
            <select value={f.category} disabled={!canEdit} onChange={(e) => set("category", e.target.value as TemplateForm["category"])} className={INPUT}>
              {CATEGORIES.map((c) => <option key={c} value={c}>{CATEGORY_LABEL[c]}</option>)}
            </select>
            {err("category")}
          </Field>
          <Field label="Icona">
            <select value={f.icon} disabled={!canEdit} onChange={(e) => set("icon", e.target.value)} className={INPUT}>
              {!ICON_CHOICES.includes(f.icon) && f.icon ? <option value={f.icon}>{f.icon}</option> : null}
              {ICON_CHOICES.map((i) => <option key={i} value={i}>{i}</option>)}
            </select>
          </Field>
        </div>
        <Field label="Link nel portale" hint="Dove porta il tocco sulla notifica, es. /portal/activity. Vuoto = nessun link.">
          <input value={f.linkTarget} disabled={!canEdit} onChange={(e) => set("linkTarget", e.target.value)} className={`${INPUT} font-mono`} aria-invalid={!!fieldErrors.linkTarget} />
          {err("linkTarget")}
        </Field>
        <Field label="Titolo">
          <input
            ref={titleRef}
            value={f.titleTpl}
            disabled={!canEdit}
            onFocus={() => setLastField("titleTpl")}
            onChange={(e) => set("titleTpl", e.target.value)}
            className={`${INPUT} font-mono`}
            aria-invalid={!!fieldErrors.titleTpl}
          />
          {err("titleTpl")}
        </Field>
        <Field label="Testo">
          <textarea
            ref={bodyRef}
            rows={3}
            value={f.bodyTpl}
            disabled={!canEdit}
            onFocus={() => setLastField("bodyTpl")}
            onChange={(e) => set("bodyTpl", e.target.value)}
            className={`${INPUT} font-mono`}
            aria-invalid={!!fieldErrors.bodyTpl}
          />
          {err("bodyTpl")}
        </Field>
        {canEdit ? (
          <div role="group" aria-label="Segnaposto suggeriti">
            <p className="mb-1 text-xs font-medium text-[var(--color-bo-ink-2)]">
              Segnaposto suggeriti{usage.sources.length ? "" : " (dal fatto scelto per l'anteprima)"} · si inseriscono nel {lastField === "titleTpl" ? "titolo" : "testo"}
            </p>
            <div className="flex flex-wrap gap-1">
              {placeholders.map((p) => (
                <button
                  key={p.token}
                  type="button"
                  onClick={() => insert(p.token)}
                  className={cn(
                    "rounded border px-1.5 py-0.5 font-mono text-[11px] hover:bg-slate-50",
                    p.group === "data" ? "border-[var(--color-bo-accent)]/40 text-[var(--color-bo-accent)]" : "border-[var(--color-bo-border)] text-[var(--color-bo-ink-2)]",
                  )}
                >
                  {p.token}
                </button>
              ))}
            </div>
          </div>
        ) : null}
        {canEdit ? (
          <div className="flex flex-wrap items-center gap-3">
            <button type="submit" disabled={save.isPending} className="rounded bg-[var(--color-bo-accent)] px-3 py-1.5 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50">
              {isNew ? "Crea template" : "Salva"}
            </button>
            {saved ? <span className="text-xs text-emerald-700">Salvato.</span> : null}
            {conflict ? (
              <span role="alert" className="text-xs text-red-700">Qualcun altro ha modificato il template: chiudi e riapri per ricaricarlo.</span>
            ) : serverError && Object.keys(errorsByField(serverError.errors)).length === 0 ? (
              <span role="alert" className="text-xs text-red-700">{serverError.detail || serverError.code}</span>
            ) : null}
          </div>
        ) : (
          <p className="text-xs text-[var(--color-bo-ink-2)]">Sola lettura: i template si modificano con il ruolo ADMIN o MARKETING.</p>
        )}
      </form>

      <section className="space-y-2 border-t border-[var(--color-bo-border)] pt-4">
        <h3 className="text-sm font-semibold">Anteprima</h3>
        <TemplatePreview
          template={t}
          form={f}
          options={options}
          sourceSel={sourceKey(source)}
          onSource={setSourceSel}
          sourceOfOptions={(o) => (o.kind === "fact" ? `Fatto · ${factLabel(o.factType)}` : `Campagna · ${o.campaignCode}`)}
        />
      </section>

      <section className="space-y-2 border-t border-[var(--color-bo-border)] pt-4">
        <h3 className="text-sm font-semibold">Dove si vede</h3>
        <ul className="space-y-1 text-sm">
          {usage.usedByRules.map((r) => (
            <li key={r.id}>
              Regola <Link href="/backoffice/content/messages?tab=rules" className="text-[var(--color-bo-accent)] hover:underline"><CodeText>{r.code}</CodeText></Link> · quando arriva «{factLabel(r.factType)}»{r.enabled ? "" : " (disattivata)"}
            </li>
          ))}
          {usage.usedByCampaigns.map((c) => (
            <li key={c.code}>
              Campagna <CodeText>{c.code}</CodeText> «{c.name}» · effetto SEND_MESSAGE ({c.status})
            </li>
          ))}
          {usage.usedByRules.length === 0 && usage.usedByCampaigns.length === 0 && !isNew ? (
            <li className="text-amber-700">Nessuna regola né campagna lo usa: il template non raggiunge nessun membro.</li>
          ) : null}
          {campaignsUnavailable ? <li className="text-xs text-[var(--color-bo-ink-2)]">Campagne non disponibili adesso (servizio «campaign» addormentato).</li> : null}
          {!isNew ? (
            <li>
              <Link href={`/backoffice/content/messages?tab=log&template=${encodeURIComponent(t.code)}`} className="text-[var(--color-bo-accent)] hover:underline">
                Messaggi inviati con questo template →
              </Link>
            </li>
          ) : null}
          <li>
            <Link href="/portal/inbox" className="text-[var(--color-bo-accent)] hover:underline">Inbox di un membro nel portale (PT-12) →</Link>
          </li>
        </ul>
      </section>
    </div>
  );
}

function FieldErrors({ messages }: { messages: string[] }) {
  return (
    <span role="alert" className="mt-1 block text-xs text-red-700">
      {messages.join(" · ")}
    </span>
  );
}

function useDebounced<T>(value: T, ms: number): T {
  const [v, setV] = useState(value);
  const key = JSON.stringify(value);
  useEffect(() => {
    const id = setTimeout(() => setV(JSON.parse(key) as T), ms);
    return () => clearTimeout(id);
  }, [key, ms]);
  return v;
}

function TemplatePreview({
  template: t,
  form: f,
  options,
  sourceSel,
  onSource,
  sourceOfOptions,
}: {
  template: MessageTemplate | null;
  form: TemplateForm;
  options: PreviewSource[];
  sourceSel: string;
  onSource: (k: string) => void;
  sourceOfOptions: (o: PreviewSource) => string;
}) {
  const members = useLhQuery<Page<MemberView>>("member", "/v1/members", { size: 100 });
  const [memberId, setMemberId] = useState("MBR-000002");
  const source = options.find((o) => sourceKey(o) === sourceSel) ?? options[0];
  const draft = useDebounced({ titleTpl: f.titleTpl, bodyTpl: f.bodyTpl }, 400);
  const render = useQuery<RenderResult, LhError>({
    queryKey: ["engagement", "render", t?.code ?? "", draft, sourceSel, memberId],
    queryFn: () =>
      lhFetch<RenderResult>("engagement", `/v1/message-templates/${encodeURIComponent(t?.code ?? "")}/render`, {
        method: "POST",
        body: JSON.stringify({ sampleEvent: sampleEvent(source, t?.code ?? "", memberId), memberId, ...draft }),
      }),
    enabled: t != null,
    retry: false,
  });
  const recipient = members.data?.items.find((m) => m.id === memberId);

  return (
    <div className="space-y-2">
      <div className="flex flex-wrap gap-2">
        <select aria-label="Evento campione" value={sourceSel} onChange={(e) => onSource(e.target.value)} className={cn(INLINE, "w-auto max-w-full")}>
          {options.map((o) => <option key={sourceKey(o)} value={sourceKey(o)}>{sourceOfOptions(o)}</option>)}
        </select>
        <select aria-label="Membro" value={memberId} onChange={(e) => setMemberId(e.target.value)} className={cn(INLINE, "w-auto")}>
          {(members.data?.items ?? [{ id: memberId, firstName: null, lastName: null, tier: "" } as unknown as MemberView]).map((m) => (
            <option key={m.id} value={m.id}>{m.firstName ? `${m.firstName} ${m.lastName ?? ""}` : m.id}</option>
          ))}
        </select>
      </div>
      {/* SPEC-GAP: Q-75 — POST …/{code}/render richiede un template esistente: per una bozza mai salvata si mostra il testo grezzo. */}
      {t == null ? (
        <>
          <p className="text-xs text-[var(--color-bo-ink-2)]">L&apos;anteprima con i segnaposto risolti è disponibile dopo il primo salvataggio; qui sotto il testo così com&apos;è.</p>
          <MessagePreview message={{ title: f.titleTpl, body: f.bodyTpl, icon: f.icon, category: f.category, linkTarget: f.linkTarget || null, channel: f.channel }} />
        </>
      ) : render.isLoading ? (
        <div className="h-24 animate-pulse rounded-2xl bg-slate-100" />
      ) : render.isError ? (
        render.error.asleep ? (
          <p className="rounded-md border border-amber-200 bg-amber-50 p-3 text-xs text-amber-800">Servizio «engagement» non raggiungibile: anteprima non disponibile.</p>
        ) : (
          <p role="alert" className="rounded-md border border-red-200 bg-red-50 p-3 text-xs text-red-800">
            Anteprima non riuscita: {render.error.detail || render.error.code}
          </p>
        )
      ) : render.data ? (
        <>
          <MessagePreview
            message={{ title: render.data.title, body: render.data.body, icon: f.icon, category: f.category, linkTarget: f.linkTarget || null, channel: f.channel }}
            recipient={recipient ? `${recipient.firstName ?? ""} ${recipient.lastName ?? ""} (${recipient.id})` : memberId}
          />
          {render.data.missing.length ? (
            <p className="text-xs text-amber-700">
              Segnaposto non risolti da questo evento (resi vuoti): {render.data.missing.map((m) => `{{${m}}}`).join(", ")}
            </p>
          ) : (
            <p className="text-xs text-[var(--color-bo-ink-2)]">Tutti i segnaposto sono risolti.</p>
          )}
        </>
      ) : null}
    </div>
  );
}
