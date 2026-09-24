"use client";

import { use, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useLhMutation, useLhQuery, type LhError } from "@/lib/api/client";
import type { CampaignSummary, Contest, Reward } from "@/lib/api/types";
import type { ContentFrequency, ContentItem, ContentKind, ContentLinkType, ContentPlacement, ContentTone } from "@/lib/content/types";
import { PLACEMENT_LABEL, contentHref } from "@/lib/content/links";
import { CONTENT_ACTIONS, KIND_LABEL, PLACEMENTS, fromLocalInput, toLocalInput } from "@/lib/content/manage";
import { QueryState } from "@/components/bo/QueryState";
import { Can, useCan } from "@/components/bo/Can";
import { PhoneFrame } from "@/components/bo/PhoneFrame";
import { ContentCard, type ContentVariant } from "@/components/shared/content/ContentCard";
import { PopupModal } from "@/components/shared/content/PopupModal";
import { WinCard } from "@/components/shared/content/WinCard";
import { Field, INPUT, Section } from "@/components/bo/FormBits";
import { CodeText, PageHeader, StatusPill } from "@/components/bo/primitives";
import { cn } from "@/lib/cn";

// BO-18 editor (docs/08 §BO-18): form a sinistra, anteprima fedele a destra in PhoneFrame con i componenti del portale
// (components/shared/content), aggiornata mentre si scrive. Destinazione della CTA scelta da elenco: concorso, premio,
// campagna, pagina del portale, URL. Pop-up: frequenza e chiudibile, anteprima con lo stesso PopupModal del portale.
type Draft = {
  code: string;
  kind: ContentKind;
  placement: ContentPlacement | "";
  title: string;
  body: string;
  imageUrl: string;
  ctaLabel: string;
  destination: "NONE" | "PAGE" | "URL" | Exclude<ContentLinkType, "NONE">;
  ctaTarget: string;
  linkCode: string;
  tiers: string[];
  segments: string;
  startAt: string;
  endAt: string;
  priority: number;
  frequency: ContentFrequency;
  dismissible: boolean;
  tone: ContentTone;
};

const TIERS = ["BASE", "SILVER", "GOLD", "PLATINUM"];
const TONES: { value: ContentTone; label: string }[] = [
  { value: "PRIMARY", label: "Primario" },
  { value: "SECONDARY", label: "Secondario" },
  { value: "COIN", label: "Moneta" },
  { value: "NIGHT", label: "Notte" },
];
const PORTAL_PAGES = [
  { path: "/portal", label: "Home" },
  { path: "/portal/earn", label: "Guadagna" },
  { path: "/portal/rewards", label: "Premi" },
  { path: "/portal/play", label: "Gioca" },
  { path: "/portal/leaderboard", label: "Classifica" },
  { path: "/portal/achievements", label: "Obiettivi e badge" },
  { path: "/portal/invite", label: "Porta un amico" },
  { path: "/portal/activity", label: "La mia attività" },
  { path: "/portal/my-rewards", label: "I miei premi e coupon" },
  { path: "/portal/profile", label: "Profilo" },
];

export default function ContentEditorPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const isNew = id === "new";
  const content = useLhQuery<ContentItem>("engagement", `/v1/contents/${id}`, undefined, { enabled: !isNew });
  if (isNew) return <Editor initial={null} />;
  return (
    <QueryState query={content} service="engagement">
      {(c) => <Editor key={`${c.id}-${c.version}-${c.status}`} initial={c} />}
    </QueryState>
  );
}

function toDraft(c: ContentItem | null): Draft {
  const destination: Draft["destination"] = !c
    ? "PAGE"
    : c.linkType !== "NONE"
      ? c.linkType
      : c.ctaTarget?.startsWith("https://")
        ? "URL"
        : c.ctaTarget
          ? "PAGE"
          : "NONE";
  return {
    code: c?.code ?? "",
    kind: c?.kind ?? "CARD",
    placement: c?.placement ?? "HOME_GRID",
    title: c?.title ?? "",
    body: c?.body ?? "",
    imageUrl: c?.imageUrl ?? "",
    ctaLabel: c?.ctaLabel ?? "",
    destination,
    ctaTarget: c?.ctaTarget ?? "/portal",
    linkCode: c?.linkCode ?? "",
    tiers: c?.audience.tiers ?? [],
    segments: (c?.audience.segments ?? []).join(", "),
    startAt: toLocalInput(c?.startAt ?? null),
    endAt: toLocalInput(c?.endAt ?? null),
    priority: c?.priority ?? 50,
    frequency: c?.frequency ?? "ONCE",
    dismissible: c?.dismissible ?? true,
    tone: (c?.style?.tone as ContentTone) ?? "PRIMARY",
  };
}

function toBody(d: Draft, version: number | null) {
  const typed = d.destination === "CONTEST" || d.destination === "CAMPAIGN" || d.destination === "REWARD" || d.destination === "PRIZE";
  return {
    ...(version == null ? { code: d.code.trim().toUpperCase() } : { version }),
    kind: d.kind,
    placement: d.kind === "POPUP" ? null : d.placement || null,
    title: d.title.trim(),
    body: d.body.trim() || null,
    imageUrl: d.imageUrl.trim() || null,
    ctaLabel: d.ctaLabel.trim() || null,
    ctaTarget: d.destination === "PAGE" || d.destination === "URL" ? d.ctaTarget.trim() || null : null,
    linkType: typed ? d.destination : "NONE",
    linkCode: typed ? d.linkCode || null : null,
    audience: { tiers: d.tiers, segments: d.segments.split(",").map((s) => s.trim()).filter(Boolean), statuses: [] },
    startAt: fromLocalInput(d.startAt),
    endAt: fromLocalInput(d.endAt),
    priority: d.priority,
    frequency: d.kind === "POPUP" ? d.frequency : null,
    dismissible: d.kind === "POPUP" ? d.dismissible : true,
    style: { tone: d.tone },
  };
}

function Editor({ initial }: { initial: ContentItem | null }) {
  const router = useRouter();
  const canWrite = useCan("content.write");
  const [d, setD] = useState<Draft>(() => toDraft(initial));
  const [saved, setSaved] = useState<string | null>(null);
  const set = <K extends keyof Draft>(k: K, v: Draft[K]) => { setD((x) => ({ ...x, [k]: v })); setSaved(null); };

  const create = useLhMutation<ContentItem, ReturnType<typeof toBody>>("engagement", "POST", () => "/v1/contents", {
    onSuccess: (c) => router.replace(`/backoffice/content/${c.id}`),
  });
  const update = useLhMutation<ContentItem, ReturnType<typeof toBody>>("engagement", "PUT", () => `/v1/contents/${initial?.id}`, {
    onSuccess: () => setSaved("Salvato"),
  });
  const error = (create.error ?? update.error) as LhError | null;
  const busy = create.isPending || update.isPending;

  const preview = {
    code: d.code || "NUOVO",
    kind: d.kind,
    placement: d.kind === "POPUP" ? null : (d.placement || null),
    title: d.title || "Titolo del contenuto",
    body: d.body || null,
    imageUrl: d.imageUrl || null,
    ctaLabel: d.ctaLabel || null,
    ...(() => {
      const b = toBody(d, 0);
      return { ctaTarget: b.ctaTarget, linkType: b.linkType as ContentLinkType, linkCode: b.linkCode };
    })(),
    style: { tone: d.tone },
  };
  const variant: ContentVariant =
    d.kind === "BANNER" ? "banner" : d.placement === "HOME_HERO" ? "hero" : d.placement === "HOME_GRID" ? "grid" : "inline";
  const href = contentHref(preview);

  return (
    <div>
      <PageHeader
        title={initial ? initial.title : "Nuovo contenuto"}
        subtitle={initial ? undefined : "Nasce in bozza: pubblicalo quando l'anteprima ti convince."}
        actions={<Link href="/backoffice/content" className="text-sm text-[var(--color-bo-ink-2)] hover:underline">← Contenuti</Link>}
      />
      {initial ? <StatusBar content={initial} /> : null}

      <div className="grid gap-6 lg:grid-cols-[minmax(0,3fr)_minmax(0,2fr)]">
        <form
          className="space-y-4"
          onSubmit={(e) => {
            e.preventDefault();
            if (initial) update.mutate(toBody(d, initial.version));
            else create.mutate(toBody(d, null));
          }}
        >
          <fieldset disabled={!canWrite || initial?.status === "ARCHIVED"} className="space-y-4">
            <Section title="Generale">
              <div className="grid gap-3 sm:grid-cols-2">
                <Field label="Codice" hint="Maiuscole, cifre e trattini, es. CNT-AUTUNNO">
                  <input value={d.code} onChange={(e) => set("code", e.target.value.toUpperCase())} disabled={!!initial} className={INPUT} required />
                </Field>
                <Field label="Tipo">
                  <select value={d.kind} onChange={(e) => set("kind", e.target.value as ContentKind)} disabled={!!initial} className={INPUT}>
                    {Object.entries(KIND_LABEL).map(([k, l]) => <option key={k} value={k}>{l}</option>)}
                  </select>
                </Field>
                {d.kind !== "POPUP" ? (
                  <Field label="Posizionamento">
                    <select value={d.placement} onChange={(e) => set("placement", e.target.value as ContentPlacement)} className={INPUT}>
                      {PLACEMENTS.map((p) => <option key={p} value={p}>{PLACEMENT_LABEL[p]}</option>)}
                    </select>
                  </Field>
                ) : null}
                <Field label="Tono">
                  <select value={d.tone} onChange={(e) => set("tone", e.target.value as ContentTone)} className={INPUT}>
                    {TONES.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
                  </select>
                </Field>
              </div>
              <Field label="Titolo"><input value={d.title} onChange={(e) => set("title", e.target.value)} className={INPUT} required maxLength={80} /></Field>
              <Field label="Testo"><textarea value={d.body} onChange={(e) => set("body", e.target.value)} className={INPUT} rows={3} maxLength={280} /></Field>
              <Field label="Immagine (percorso o URL, facoltativa)" hint="Senza immagine il portale mostra un'illustrazione col colore del tono.">
                <input value={d.imageUrl} onChange={(e) => set("imageUrl", e.target.value)} className={INPUT} placeholder="/demo/contents/…" />
              </Field>
            </Section>

            <Section title="Pulsante">
              <div className="grid gap-3 sm:grid-cols-2">
                <Field label="Etichetta"><input value={d.ctaLabel} onChange={(e) => set("ctaLabel", e.target.value)} className={INPUT} maxLength={24} /></Field>
                <Field label="Destinazione">
                  <select value={d.destination} onChange={(e) => { set("destination", e.target.value as Draft["destination"]); set("linkCode", ""); }} className={INPUT}>
                    <option value="NONE">Nessuna</option>
                    <option value="PAGE">Pagina del portale</option>
                    <option value="CONTEST">Concorso</option>
                    <option value="REWARD">Premio del catalogo</option>
                    <option value="CAMPAIGN">Campagna</option>
                    {d.placement === "WIN" ? <option value="PRIZE">Premio in palio (card vincita)</option> : null}
                    <option value="URL">Indirizzo web (https)</option>
                  </select>
                </Field>
              </div>
              <DestinationPicker d={d} set={set} />
              {d.ctaLabel && !href && d.destination !== "PRIZE" ? <p className="text-xs text-amber-700">Destinazione non valida: il pulsante non comparirà.</p> : null}
            </Section>

            <Section title="Pubblico e calendario">
              <Field label="Livelli" hint="Nessuno selezionato = tutti i livelli" group>
                <div className="flex flex-wrap gap-3">
                  {TIERS.map((t) => (
                    <label key={t} className="flex items-center gap-1.5 text-sm">
                      <input type="checkbox" checked={d.tiers.includes(t)} onChange={(e) => set("tiers", e.target.checked ? [...d.tiers, t] : d.tiers.filter((x) => x !== t))} />
                      {t}
                    </label>
                  ))}
                </div>
              </Field>
              <Field label="Segmenti (codici separati da virgola)" hint="Codici di BO-04 (es. SEG-DIGITAL); un codice inesistente non include nessuno.">
                <input value={d.segments} onChange={(e) => set("segments", e.target.value)} className={INPUT} placeholder="SEG-DIGITAL" />
              </Field>
              <div className="grid gap-3 sm:grid-cols-3">
                <Field label="Dal"><input type="datetime-local" value={d.startAt} onChange={(e) => set("startAt", e.target.value)} className={INPUT} /></Field>
                <Field label="Al"><input type="datetime-local" value={d.endAt} onChange={(e) => set("endAt", e.target.value)} className={INPUT} /></Field>
                <Field label="Priorità" hint="Più alta = prima">
                  <input type="number" min={0} max={1000} value={d.priority} onChange={(e) => set("priority", Number(e.target.value))} className={INPUT} />
                </Field>
              </div>
              {d.kind === "POPUP" ? (
                <div className="grid gap-3 sm:grid-cols-2">
                  <Field label="Frequenza">
                    <select value={d.frequency} onChange={(e) => set("frequency", e.target.value as ContentFrequency)} className={INPUT}>
                      <option value="ONCE">Una volta sola</option>
                      <option value="ONCE_PER_DAY">Una volta al giorno</option>
                      <option value="ALWAYS">A ogni visita</option>
                    </select>
                  </Field>
                  <label className="flex items-center gap-2 pt-5 text-sm">
                    <input type="checkbox" checked={d.dismissible} onChange={(e) => set("dismissible", e.target.checked)} /> Chiudibile
                  </label>
                </div>
              ) : null}
            </Section>
          </fieldset>

          {error ? (
            <p className="rounded border border-red-200 bg-red-50 p-2 text-sm text-red-800">{error.code}: {error.detail}</p>
          ) : null}
          <div className="flex items-center gap-3">
            <Can capability="content.write" mode="disable">
              <button type="submit" disabled={busy || initial?.status === "ARCHIVED"} className="rounded bg-[var(--color-bo-accent)] px-4 py-1.5 text-sm font-medium text-white disabled:opacity-50">
                {busy ? "Salvataggio…" : initial ? "Salva" : "Crea bozza"}
              </button>
            </Can>
            {saved ? <span className="text-sm text-emerald-700">{saved}</span> : null}
          </div>
        </form>

        <div className="lg:sticky lg:top-4 lg:self-start">
          <PhoneFrame label={d.kind === "POPUP" ? "Anteprima del pop-up all'ingresso della Home" : `Anteprima in «${d.placement ? PLACEMENT_LABEL[d.placement] : "—"}»`}>
            {d.kind === "POPUP" ? (
              <div className="relative min-h-[380px]">
                <PopupModal content={preview} dismissible={d.dismissible} preview />
              </div>
            ) : d.placement === "WIN" ? (
              <WinCard content={preview} prizeLabel={d.linkCode || "Premio in palio"} followUp="" preview />
            ) : variant === "grid" ? (
              <div className="grid grid-cols-2 gap-2">
                <ContentCard content={preview} variant="grid" preview />
                <div className="rounded-2xl border border-dashed border-[var(--color-pt-night)]/15" aria-hidden />
              </div>
            ) : (
              <ContentCard content={preview} variant={variant} preview />
            )}
          </PhoneFrame>
        </div>
      </div>
    </div>
  );
}

function DestinationPicker({ d, set }: { d: Draft; set: <K extends keyof Draft>(k: K, v: Draft[K]) => void }) {
  const contests = useLhQuery<Contest[]>("gamification", "/v1/contests", undefined, { enabled: d.destination === "CONTEST" || d.destination === "PRIZE" });
  const rewards = useLhQuery<Reward[]>("reward", "/v1/rewards", undefined, { enabled: d.destination === "REWARD" });
  const campaigns = useLhQuery<CampaignSummary[]>("campaign", "/v1/campaigns", undefined, { enabled: d.destination === "CAMPAIGN" });
  const choose = (options: { value: string; label: string }[], loading: boolean) => (
    <select value={d.linkCode} onChange={(e) => set("linkCode", e.target.value)} className={INPUT} disabled={loading}>
      <option value="">{loading ? "Caricamento…" : "Scegli…"}</option>
      {options.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
    </select>
  );
  switch (d.destination) {
    case "PAGE":
      return (
        <Field label="Pagina">
          <select value={d.ctaTarget} onChange={(e) => set("ctaTarget", e.target.value)} className={INPUT}>
            {PORTAL_PAGES.map((p) => <option key={p.path} value={p.path}>{p.label}</option>)}
          </select>
        </Field>
      );
    case "URL":
      return <Field label="Indirizzo" hint="Solo https://"><input value={d.ctaTarget} onChange={(e) => set("ctaTarget", e.target.value)} className={INPUT} placeholder="https://example.org/…" /></Field>;
    case "CONTEST":
      return <Field label="Concorso">{choose((contests.data ?? []).map((c) => ({ value: c.code, label: `${c.name} (${c.status})` })), contests.isLoading)}</Field>;
    case "REWARD":
      return <Field label="Premio">{choose((rewards.data ?? []).map((r) => ({ value: r.code, label: r.name })), rewards.isLoading)}</Field>;
    case "CAMPAIGN":
      return <Field label="Campagna">{choose((campaigns.data ?? []).map((c) => ({ value: c.code, label: `${c.name} (${c.status})` })), campaigns.isLoading)}</Field>;
    case "PRIZE": {
      const prizes = (contests.data ?? []).flatMap((c) => (c.prizes ?? []).map((p) => ({ value: p.code, label: `${p.name} — ${c.name}` })));
      return <Field label="Premio in palio">{choose(Array.from(new Map(prizes.map((p) => [p.value, p])).values()), contests.isLoading)}</Field>;
    }
    default:
      return null;
  }
}

function StatusBar({ content }: { content: ContentItem }) {
  const [error, setError] = useState<string | null>(null);
  const transition = useLhMutation<ContentItem, { action: string }>("engagement", "POST", () => `/v1/contents/${content.id}/transitions`, {
    onSuccess: () => setError(null),
  });
  const duplicate = useLhMutation<ContentItem, undefined>("engagement", "POST", () => `/v1/contents/${content.id}/duplicate`);
  const router = useRouter();
  return (
    <div className="mb-4 flex flex-wrap items-center gap-2 rounded-md border border-[var(--color-bo-border)] bg-white px-3 py-2">
      <StatusPill status={content.status} />
      <CodeText>{content.code}</CodeText>
      <span className="text-xs text-[var(--color-bo-ink-2)]">versione {content.version}</span>
      <div className="ml-auto flex flex-wrap gap-2">
        {CONTENT_ACTIONS[content.status].map((a) => (
          <Can key={a.action} capability="content.write" mode="disable">
            <button
              onClick={() => transition.mutate({ action: a.action }, { onError: (e) => setError(`${e.code}: ${e.detail}`) })}
              disabled={transition.isPending}
              className={cn("rounded px-3 py-1 text-sm font-medium", a.action === "PUBLISH" || a.action === "RESUME" ? "bg-[var(--color-bo-accent)] text-white" : "border border-[var(--color-bo-border)]")}
            >
              {a.label}
            </button>
          </Can>
        ))}
        <Can capability="content.write" mode="disable">
          <button
            onClick={() => duplicate.mutate(undefined, { onSuccess: (c) => router.push(`/backoffice/content/${c.id}`) })}
            disabled={duplicate.isPending}
            className="rounded border border-[var(--color-bo-border)] px-3 py-1 text-sm"
          >
            Duplica
          </button>
        </Can>
      </div>
      {error ? <p className="w-full text-xs text-red-700">{error}</p> : null}
    </div>
  );
}
