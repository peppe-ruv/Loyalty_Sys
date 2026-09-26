"use client";

import { use, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { lhFetch, useLhMutation, useLhQuery, type LhError, type Page } from "@/lib/api/client";
import type { Reward } from "@/lib/api/types";
import type { ContentItem } from "@/lib/content/types";
import type { CriteriaNode, MemberSample, PreviewResult, RefreshResult, Segment, SegmentRequest, SegmentType } from "@/lib/segments/types";
import { describeCriteria, fromCriteria, parseCriteriaJson, toCriteria, validateRows, type BuilderState } from "@/lib/segments/criteria";
import { refreshMessage, segmentUsage, type CampaignLike } from "@/lib/segments/usage";
import { QueryState } from "@/components/bo/QueryState";
import { Can, useCan } from "@/components/bo/Can";
import { Field, INPUT, Section } from "@/components/bo/FormBits";
import { CodeText, DegradedBox, PageHeader, StatusPill, TierBadge } from "@/components/bo/primitives";
import { CriteriaBuilder } from "@/components/bo/segments/CriteriaBuilder";
import { StaticMembersPicker } from "@/components/bo/segments/StaticMembersPicker";
import { TypePill } from "@/components/bo/segments/TypePill";
import { Card, CardBody } from "@/components/ui/card";
import { formatRelative } from "@/lib/format/dates";
import { cn } from "@/lib/cn";

// BO-04 editor (docs/08 §BO-04, F-SEG-01/02/03): nome, descrizione, tipo. DYNAMIC → costruttore di condizioni sui campi
// del membro (o JSON) con anteprima dal vivo (debounce 600 ms: conteggio + 10 membri campione); STATIC → selettore
// membri. Per un segmento esistente: "Ricalcola ora" ({entered, left, total}), membri attuali, "usato da", archiviazione.
const EMPTY: BuilderState = { op: "all", rows: [{ field: "member.tier", param: "", cmp: "in", value: "GOLD, PLATINUM" }] };

export default function SegmentEditorPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const isNew = id === "new";
  const segment = useLhQuery<Segment>("member", `/v1/segments/${id}`, undefined, { enabled: !isNew });
  if (isNew) return <Editor initial={null} />;
  return (
    <QueryState query={segment} service="member">
      {(s) => <Editor key={`${s.id}-${s.version}-${s.status}`} initial={s} />}
    </QueryState>
  );
}

function Editor({ initial }: { initial: Segment | null }) {
  const router = useRouter();
  const canWrite = useCan("segment.write");
  const [code, setCode] = useState(initial?.code ?? "");
  const [name, setName] = useState(initial?.name ?? "");
  const [description, setDescription] = useState(initial?.description ?? "");
  const [type, setType] = useState<SegmentType>(initial?.type ?? "DYNAMIC");
  const initialBuilder = initial ? fromCriteria(initial.criteria) : EMPTY;
  const [mode, setMode] = useState<"builder" | "json">(initialBuilder ? "builder" : "json");
  const [builder, setBuilder] = useState<BuilderState>(initialBuilder ?? { op: "all", rows: [] });
  const [jsonText, setJsonText] = useState(JSON.stringify(initial?.criteria ?? toCriteria(EMPTY), null, 2));
  const [memberIds, setMemberIds] = useState<string[]>([]);
  const [message, setMessage] = useState<string | null>(null);

  const readOnly = !canWrite || initial?.status === "ARCHIVED";
  const dynamic = type === "DYNAMIC";

  // Criteri correnti (dal costruttore o dal JSON) e loro problemi.
  const parsed = mode === "json" ? parseCriteriaJson(jsonText) : null;
  const criteria: CriteriaNode | null = mode === "builder" ? toCriteria(builder) : (parsed?.criteria ?? null);
  const problems = mode === "builder" ? validateRows(builder) : {};
  const criteriaOk = mode === "builder" ? Object.keys(problems).length === 0 : parsed?.error == null;

  // Membri attuali (prima pagina, al più 100: docs/06 §2, Q-332).
  const current = useLhQuery<Page<MemberSample>>("member", `/v1/segments/${initial?.id}/members`, { size: 100 }, { enabled: !!initial });
  // Elenco completo di uno statico, pagina per pagina: diventa lo stato del selettore e il salvataggio lo sostituisce
  // per intero, quindi non deve fermarsi alla prima pagina.
  const staticMembers = useQuery({
    queryKey: ["member", "segment-members-all", initial?.id],
    enabled: initial?.type === "STATIC",
    queryFn: async () => {
      const ids: string[] = [];
      for (let page = 0; ; page++) {
        const p = await lhFetch<Page<MemberSample>>("member", `/v1/segments/${initial?.id}/members`, { query: { page, size: 100 } });
        ids.push(...p.items.map((m) => m.memberId));
        if (p.items.length === 0 || page + 1 >= p.page.totalPages) return ids;
      }
    },
  });
  const [staticLoaded, setStaticLoaded] = useState(false);
  useEffect(() => {
    if (initial?.type === "STATIC" && staticMembers.data && !staticLoaded) {
      setMemberIds(staticMembers.data);
      setStaticLoaded(true);
    }
  }, [initial?.type, staticMembers.data, staticLoaded]);

  const create = useLhMutation<Segment, SegmentRequest>("member", "POST", () => "/v1/segments", {
    onSuccess: (s) => router.replace(`/backoffice/segments/${s.code}`),
  });
  const update = useLhMutation<Segment, SegmentRequest>("member", "PUT", () => `/v1/segments/${initial?.id}`, {
    onSuccess: (s) => setMessage(`Salvato · ${s.memberCount} membri`),
  });
  const saveMembers = useLhMutation<RefreshResult, { memberIds: string[] }>("member", "PUT", () => `/v1/segments/${initial?.id}/members`, {
    onSuccess: (r) => setMessage(refreshMessage(r)),
  });
  const refresh = useLhMutation<RefreshResult, undefined>("member", "POST", () => `/v1/segments/${initial?.id}/refresh`, {
    onSuccess: (r) => setMessage(refreshMessage(r)),
  });
  const error = (create.error ?? update.error ?? saveMembers.error ?? refresh.error) as LhError | null;
  const busy = create.isPending || update.isPending || saveMembers.isPending || refresh.isPending;

  function save() {
    setMessage(null);
    if (!initial) {
      create.mutate({
        code: code.trim().toUpperCase(),
        name: name.trim(),
        description: description.trim() || null,
        type,
        ...(dynamic ? { criteria } : { memberIds }),
      });
      return;
    }
    update.mutate({ version: initial.version, name: name.trim(), description: description.trim() || null, ...(dynamic ? { criteria } : {}) });
    if (!dynamic) saveMembers.mutate({ memberIds });
  }

  function switchMode(next: "builder" | "json") {
    if (next === mode) return;
    if (next === "json") {
      setJsonText(JSON.stringify(toCriteria(builder), null, 2));
      setMode("json");
      return;
    }
    const back = parsed?.criteria ? fromCriteria(parsed.criteria) : null;
    if (!back) {
      setMessage("Questi criteri usano gruppi annidati o campi fuori dal costruttore: restano modificabili solo in JSON.");
      return;
    }
    setBuilder(back);
    setMode("builder");
  }

  return (
    <div>
      <PageHeader
        title={initial ? initial.name : "Nuovo segmento"}
        subtitle={initial ? undefined : "Dinamico: criteri ricalcolati. Statico: elenco manuale di membri."}
        actions={<Link href="/backoffice/segments" className="text-sm text-[var(--color-bo-ink-2)] hover:underline">← Segmenti</Link>}
      />
      {initial ? (
        <div className="mb-4 flex flex-wrap items-center gap-2 text-sm">
          <CodeText>{initial.code}</CodeText>
          <TypePill type={initial.type} />
          <StatusPill status={initial.status} />
          <span className="text-[var(--color-bo-ink-2)]">
            {initial.memberCount} {initial.memberCount === 1 ? "membro" : "membri"} · {initial.refreshedAt ? `ricalcolato ${formatRelative(initial.refreshedAt)}` : "mai ricalcolato"}
          </span>
          <div className="ml-auto flex gap-2">
            {initial.type === "DYNAMIC" && initial.status === "ACTIVE" ? (
              <Can capability="segment.write" mode="disable">
                <button
                  type="button"
                  disabled={busy}
                  onClick={() => {
                    setMessage(null);
                    refresh.mutate(undefined);
                  }}
                  className="rounded bg-[var(--color-bo-accent)] px-3 py-1.5 text-xs font-medium text-white disabled:opacity-50"
                >
                  {refresh.isPending ? "Ricalcolo…" : "Ricalcola ora"}
                </button>
              </Can>
            ) : null}
            <Can capability="segment.write" mode="disable">
              <button
                type="button"
                disabled={busy}
                onClick={() => {
                  const archiving = initial.status === "ACTIVE";
                  if (archiving && !window.confirm(`Archiviare ${initial.code}? I ${initial.memberCount} membri ne escono subito.`)) return;
                  update.mutate({ version: initial.version, status: archiving ? "ARCHIVED" : "ACTIVE" });
                }}
                className="rounded border border-[var(--color-bo-border)] px-3 py-1.5 text-xs hover:bg-slate-50 disabled:opacity-50"
              >
                {initial.status === "ACTIVE" ? "Archivia" : "Riattiva"}
              </button>
            </Can>
          </div>
        </div>
      ) : null}

      <div role="status" aria-live="polite">
        {message ? <p className="mb-3 rounded bg-emerald-50 px-3 py-2 text-sm text-emerald-800">{message}</p> : null}
      </div>
      {error ? <ErrorBox error={error} /> : null}

      <div className="grid gap-6 lg:grid-cols-[minmax(0,3fr)_minmax(0,2fr)]">
        <form
          className="space-y-4"
          onSubmit={(e) => {
            e.preventDefault();
            save();
          }}
        >
          <fieldset disabled={readOnly} className="space-y-4">
            <Section title="Generale">
              <div className="grid gap-3 sm:grid-cols-2">
                <Field label="Codice" hint="Maiuscole, cifre e trattini, es. SEG-GRANDI-SPESE. Non si cambia dopo la creazione.">
                  <input value={code} onChange={(e) => setCode(e.target.value.toUpperCase())} disabled={!!initial} className={INPUT} required />
                </Field>
                <Field label="Tipo">
                  <select value={type} onChange={(e) => setType(e.target.value as SegmentType)} disabled={!!initial} className={INPUT}>
                    <option value="DYNAMIC">Dinamico (criteri)</option>
                    <option value="STATIC">Statico (elenco manuale)</option>
                  </select>
                </Field>
              </div>
              <Field label="Nome"><input value={name} onChange={(e) => setName(e.target.value)} className={INPUT} required maxLength={80} /></Field>
              <Field label="Descrizione"><textarea value={description} onChange={(e) => setDescription(e.target.value)} className={INPUT} rows={2} maxLength={280} /></Field>
            </Section>

            {dynamic ? (
              <Section title="Criteri">
                <div className="flex gap-1 border-b border-[var(--color-bo-border)]" role="tablist">
                  {(
                    [
                      ["builder", "Costruttore"],
                      ["json", "JSON"],
                    ] as const
                  ).map(([m, label]) => (
                    <button
                      key={m}
                      type="button"
                      role="tab"
                      aria-selected={mode === m}
                      onClick={() => switchMode(m)}
                      className={cn("-mb-px border-b-2 px-3 py-1.5 text-xs", mode === m ? "border-[var(--color-bo-accent)] font-medium" : "border-transparent text-[var(--color-bo-ink-2)]")}
                    >
                      {label}
                    </button>
                  ))}
                </div>
                {mode === "builder" ? (
                  <>
                    <CriteriaBuilder state={builder} onChange={setBuilder} problems={problems} disabled={readOnly} />
                    {problems[-1] ? <p className="text-xs text-red-700">{problems[-1]}</p> : null}
                  </>
                ) : (
                  <Field label="Criteri (JSON, formato delle condizioni)" hint='Esempio: {"op":"all","rules":[{"field":"member.tier","cmp":"in","value":["GOLD","PLATINUM"]}]}'>
                    <textarea value={jsonText} onChange={(e) => setJsonText(e.target.value)} className={cn(INPUT, "font-mono text-xs")} rows={10} spellCheck={false} />
                  </Field>
                )}
                {parsed?.error ? <p className="text-xs text-red-700">{parsed.error}</p> : null}
                {criteriaOk ? <p className="text-xs text-[var(--color-bo-ink-2)]">In breve: {describeCriteria(criteria)}</p> : null}
              </Section>
            ) : (
              <Section title="Membri">
                {initial && staticMembers.isLoading ? <p className="text-xs text-[var(--color-bo-ink-2)]">Caricamento dell&apos;elenco…</p> : null}
                {initial && staticMembers.isError ? <DegradedBox service="member" onRetry={() => staticMembers.refetch()} /> : null}
                <StaticMembersPicker value={memberIds} onChange={setMemberIds} disabled={readOnly} />
              </Section>
            )}
          </fieldset>

          <Can capability="segment.write" mode="disable">
            <button
              type="submit"
              disabled={busy || readOnly || (dynamic && !criteriaOk) || !name.trim() || (!initial && !code.trim())}
              className="rounded bg-[var(--color-bo-accent)] px-4 py-2 text-sm font-medium text-white disabled:opacity-50"
            >
              {busy ? "Salvataggio…" : initial ? "Salva" : "Crea segmento"}
            </button>
          </Can>
          {dynamic && initial ? (
            <p className="text-xs text-[var(--color-bo-ink-2)]">
              Salvando criteri nuovi il segmento si ricalcola subito: chi entra o esce produce i fatti member.segment.* nel rail.
            </p>
          ) : null}
        </form>

        <div className="space-y-4">
          {dynamic ? <LivePreview criteria={criteriaOk ? criteria : null} /> : null}
          {initial ? <CurrentMembers query={current} /> : null}
          {initial ? <UsedBy code={initial.code} /> : null}
        </div>
      </div>
    </div>
  );
}

/** Anteprima dal vivo (debounce 600 ms): conteggio + 10 membri campione, senza salvare. */
function LivePreview({ criteria }: { criteria: CriteriaNode | null }) {
  const json = criteria ? JSON.stringify(criteria) : "";
  const [debounced, setDebounced] = useState(json);
  useEffect(() => {
    const t = setTimeout(() => setDebounced(json), 600);
    return () => clearTimeout(t);
  }, [json]);
  const preview = useQuery<PreviewResult, LhError>({
    queryKey: ["member", "segment-preview", debounced],
    queryFn: () => lhFetch<PreviewResult>("member", "/v1/segments/preview", { method: "POST", body: JSON.stringify({ criteria: JSON.parse(debounced) }) }),
    enabled: debounced !== "",
  });

  return (
    <Card>
      <CardBody className="space-y-2 pt-4">
        <h3 className="text-sm font-semibold">Anteprima dal vivo</h3>
        {!criteria ? (
          <p className="text-xs text-[var(--color-bo-ink-2)]">Completa i criteri per vedere chi ne fa parte.</p>
        ) : (
          <QueryState query={preview} service="member">
            {(p) => (
              <div>
                <p className="text-2xl font-semibold tabular-nums">
                  {p.count} <span className="text-sm font-normal text-[var(--color-bo-ink-2)]">{p.count === 1 ? "membro" : "membri"}</span>
                </p>
                {p.sample.length === 0 ? (
                  <p className="text-xs text-[var(--color-bo-ink-2)]">Nessun membro soddisfa i criteri.</p>
                ) : (
                  <SampleList items={p.sample} title={p.count > p.sample.length ? `Primi ${p.sample.length}` : undefined} />
                )}
              </div>
            )}
          </QueryState>
        )}
      </CardBody>
    </Card>
  );
}

function CurrentMembers({ query }: { query: ReturnType<typeof useLhQuery<Page<MemberSample>>> }) {
  return (
    <Card>
      <CardBody className="space-y-2 pt-4">
        <h3 className="text-sm font-semibold">Membri attuali</h3>
        <QueryState query={query} service="member" isEmpty={(d) => d.items.length === 0} emptyTitle="Nessun membro nel segmento">
          {(d) => <SampleList items={d.items} withEntered title={d.page.totalItems > d.items.length ? `Primi ${d.items.length} di ${d.page.totalItems}` : undefined} />}
        </QueryState>
      </CardBody>
    </Card>
  );
}

function SampleList({ items, withEntered, title }: { items: MemberSample[]; withEntered?: boolean; title?: string }) {
  return (
    <div>
      {title ? <p className="mb-1 text-xs text-[var(--color-bo-ink-2)]">{title}</p> : null}
      <ul className="divide-y divide-[var(--color-bo-border)] rounded border border-[var(--color-bo-border)]">
        {items.map((m) => (
          <li key={m.memberId} className="flex items-center justify-between gap-2 px-2 py-1.5 text-sm">
            <Link href={`/backoffice/members/${m.memberId}?tab=segments`} className="hover:underline">
              {m.name}
            </Link>
            <span className="flex items-center gap-2 text-xs text-[var(--color-bo-ink-2)]">
              {m.status !== "ACTIVE" ? <StatusPill status={m.status} /> : null}
              <TierBadge tier={m.tier} />
              {withEntered && m.enteredAt ? <span title={m.enteredAt}>dentro da {formatRelative(m.enteredAt)}</span> : null}
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}

/** ReaderPanel (docs/08 §BO-04): oggetti che usano il segmento, ognuno dal servizio che lo possiede. */
function UsedBy({ code }: { code: string }) {
  const campaigns = useLhQuery<CampaignLike[]>("campaign", "/v1/campaigns");
  const rewards = useLhQuery<Reward[]>("reward", "/v1/rewards");
  const contents = useLhQuery<ContentItem[]>("engagement", "/v1/contents");
  const usage = useMemo(
    () => segmentUsage(code, { campaigns: campaigns.data, rewards: rewards.data, contents: contents.data }),
    [code, campaigns.data, rewards.data, contents.data],
  );
  const groups: { label: string; service: string; items: string[]; href: (c: string) => string; q: { isLoading: boolean; isError: boolean } }[] = [
    { label: "Campagne", service: "campaign", items: usage.campaigns, href: (c) => `/backoffice/campaigns/${campaignId(campaigns.data, c)}`, q: campaigns },
    { label: "Premi", service: "reward", items: usage.rewards, href: (c) => `/backoffice/rewards/${rewardId(rewards.data, c)}`, q: rewards },
    { label: "Contenuti", service: "engagement", items: usage.contents, href: (c) => `/backoffice/content/${contentId(contents.data, c)}`, q: contents },
  ];
  return (
    <Card>
      <CardBody className="space-y-2 pt-4">
        <h3 className="text-sm font-semibold">Usato da</h3>
        {groups.map((g) => (
          <div key={g.label}>
            <p className="text-xs font-medium text-[var(--color-bo-ink-2)]">{g.label}</p>
            {g.q.isLoading ? (
              <p className="text-xs text-slate-400">…</p>
            ) : g.q.isError ? (
              <p className="text-xs text-amber-700">Servizio «{g.service}» non raggiungibile</p>
            ) : g.items.length === 0 ? (
              <p className="text-xs text-[var(--color-bo-ink-2)]">nessuno</p>
            ) : (
              <ul className="flex flex-wrap gap-1.5 pt-0.5">
                {g.items.map((c) => (
                  <li key={c}>
                    <Link href={g.href(c)} className="font-mono text-xs text-[var(--color-bo-accent)] hover:underline">{c}</Link>
                  </li>
                ))}
              </ul>
            )}
          </div>
        ))}
      </CardBody>
    </Card>
  );
}

function campaignId(list: (CampaignLike & { id?: string })[] | undefined, code: string): string {
  return list?.find((c) => c.code === code)?.id ?? code;
}
function rewardId(list: Reward[] | undefined, code: string): string {
  return list?.find((r) => r.code === code)?.id ?? code;
}
function contentId(list: ContentItem[] | undefined, code: string): string {
  return list?.find((c) => c.code === code)?.id ?? code;
}

function ErrorBox({ error }: { error: LhError }) {
  if (error.asleep) return <div className="mb-3"><DegradedBox service="member" /></div>;
  return (
    <div className="mb-3 rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
      <p className="font-medium">{error.detail || error.code}</p>
      {error.errors.length > 0 ? (
        <ul className="mt-1 list-disc pl-5 text-xs">
          {error.errors.map((e, i) => (
            <li key={i}>
              <span className="font-mono">{e.field}</span>: {e.message}
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}
