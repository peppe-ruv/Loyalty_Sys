"use client";

import { useState } from "react";
import Link from "next/link";
import { useLhQuery, type Page } from "@/lib/api/client";
import type { MemberView } from "@/lib/api/types";
import type { ContentItem, ContentPlacement, ContentPreview, ContentStatus, PreviewPlacement } from "@/lib/content/types";
import { EXCLUSION_LABEL, PLACEMENT_LABEL, PLACEMENT_LIMIT } from "@/lib/content/links";
import { KIND_LABEL, PLACEMENTS, audienceLabel, effectiveOrder, scheduleLabel } from "@/lib/content/manage";
import { QueryState } from "@/components/bo/QueryState";
import { DataTable, type Column } from "@/components/bo/DataTable";
import { Can } from "@/components/bo/Can";
import { PhoneFrame } from "@/components/bo/PhoneFrame";
import { ContentCard, toneOf } from "@/components/shared/content/ContentCard";
import { PopupModal } from "@/components/shared/content/PopupModal";
import { Card, CardBody } from "@/components/ui/card";
import { CodeText, PageHeader, StatusPill } from "@/components/bo/primitives";
import { INPUT } from "@/components/bo/FormBits";
import { cn } from "@/lib/cn";

// Filtri affiancati: lo stile dei campi senza la larghezza piena.
const FILTER = INPUT.replace("w-full ", "");

// BO-18 Card, pop-up e banner (docs/08 §BO-18, F-CNT-01/02/03/04): elenco con filtri, vista "Per posizione" con l'ordine
// effettivo, anteprima per membro (cosa vede adesso e perché gli altri contenuti sono esclusi).
type View = "list" | "placement" | "member";

export default function ContentPage() {
  const [view, setView] = useState<View>("list");
  const [kind, setKind] = useState("");
  const [status, setStatus] = useState("");
  const [placement, setPlacement] = useState("");
  const [q, setQ] = useState("");
  const list = useLhQuery<ContentItem[]>("engagement", "/v1/contents", { kind, status, placement, q });
  const all = useLhQuery<ContentItem[]>("engagement", "/v1/contents", undefined, { enabled: view === "placement" });

  return (
    <div>
      <PageHeader
        title="Card, pop-up e banner"
        subtitle="Contenuti del portale: posizionamento, pubblico, calendario e priorità. Si pubblicano senza approvazione."
        actions={
          <Can capability="content.write" mode="disable">
            <Link href="/backoffice/content/new" className="rounded bg-[var(--color-bo-accent)] px-3 py-1.5 text-sm font-medium text-white hover:opacity-90">
              Nuovo contenuto
            </Link>
          </Can>
        }
      />
      <div className="mb-3 flex gap-1 border-b border-[var(--color-bo-border)]" role="tablist">
        {([["list", "Elenco"], ["placement", "Per posizione"], ["member", "Anteprima per membro"]] as [View, string][]).map(([v, label]) => (
          <button
            key={v}
            role="tab"
            aria-selected={view === v}
            onClick={() => setView(v)}
            className={cn("-mb-px border-b-2 px-3 py-2 text-sm", view === v ? "border-[var(--color-bo-accent)] font-medium" : "border-transparent text-[var(--color-bo-ink-2)]")}
          >
            {label}
          </button>
        ))}
      </div>

      {view === "list" ? (
        <>
          <div className="mb-3 flex flex-wrap gap-2">
            <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Cerca per titolo o codice" className={cn(FILTER, "w-56")} />
            <select aria-label="Tipo" value={kind} onChange={(e) => setKind(e.target.value)} className={cn(FILTER, "w-36")}>
              <option value="">Tutti i tipi</option>
              {Object.entries(KIND_LABEL).map(([k, l]) => <option key={k} value={k}>{l}</option>)}
            </select>
            <select aria-label="Posizionamento" value={placement} onChange={(e) => setPlacement(e.target.value)} className={cn(FILTER, "w-52")}>
              <option value="">Tutti i posizionamenti</option>
              {PLACEMENTS.map((p) => <option key={p} value={p}>{PLACEMENT_LABEL[p]}</option>)}
            </select>
            <select aria-label="Stato" value={status} onChange={(e) => setStatus(e.target.value)} className={cn(FILTER, "w-36")}>
              <option value="">Tutti gli stati</option>
              {(["DRAFT", "LIVE", "PAUSED", "ENDED", "ARCHIVED"] as ContentStatus[]).map((s) => <option key={s} value={s}>{s}</option>)}
            </select>
          </div>
          <QueryState query={list} service="engagement" isEmpty={(d) => d.length === 0} emptyTitle="Nessun contenuto" emptyHint="Nessun contenuto con questi filtri. Creane uno nuovo.">
            {(d) => <DataTable columns={COLUMNS} rows={d} rowKey={(c) => c.id} />}
          </QueryState>
        </>
      ) : null}

      {view === "placement" ? (
        <QueryState query={all} service="engagement">
          {(d) => (
            <div className="grid gap-4 lg:grid-cols-2">
              {PLACEMENTS.filter((p) => p !== "WIN").map((p) => (
                <PlacementColumn key={p} placement={p} items={effectiveOrder(d, p)} />
              ))}
            </div>
          )}
        </QueryState>
      ) : null}

      {view === "member" ? <MemberPreview /> : null}
    </div>
  );
}

const COLUMNS: Column<ContentItem>[] = [
  {
    key: "thumb",
    header: "",
    className: "w-12",
    render: (c) => <span className="block size-8 rounded" style={{ background: `var(--color-pt-${toneOf(c).toLowerCase()})` }} aria-hidden />,
  },
  {
    key: "title",
    header: "Titolo",
    render: (c) => (
      <Link href={`/backoffice/content/${c.id}`} className="font-medium hover:underline">
        {c.title} <span className="block"><CodeText>{c.code}</CodeText></span>
      </Link>
    ),
  },
  { key: "kind", header: "Tipo", render: (c) => KIND_LABEL[c.kind] },
  { key: "placement", header: "Posizionamento", render: (c) => (c.placement ? PLACEMENT_LABEL[c.placement] : "—") },
  { key: "status", header: "Stato", render: (c) => <StatusPill status={c.status} /> },
  { key: "schedule", header: "Calendario", render: (c) => scheduleLabel(c) },
  { key: "audience", header: "Pubblico", render: (c) => audienceLabel(c.audience) },
  { key: "priority", header: "Priorità", className: "text-right", render: (c) => <span className="tabular-nums">{c.priority}</span> },
];

function PlacementColumn({ placement, items }: { placement: ContentPlacement; items: ContentItem[] }) {
  const limit = PLACEMENT_LIMIT[placement];
  return (
    <Card>
      <CardBody>
        <h2 className="mb-2 text-sm font-semibold">
          {PLACEMENT_LABEL[placement]} <span className="font-normal text-[var(--color-bo-ink-2)]">· ne mostra {limit === 1 ? "1" : `fino a ${limit}`}</span>
        </h2>
        {items.length === 0 ? (
          <p className="text-sm text-[var(--color-bo-ink-2)]">Nessun contenuto pubblicato qui.</p>
        ) : (
          <ol className="space-y-1">
            {items.map((c, i) => (
              <li key={c.id} className={cn("flex items-center justify-between rounded px-2 py-1.5 text-sm", i < limit ? "bg-slate-50" : "opacity-50")}>
                <Link href={`/backoffice/content/${c.id}`} className="hover:underline">
                  <span className="mr-2 font-mono text-xs text-[var(--color-bo-ink-2)]">{i + 1}.</span>
                  {c.title}
                </Link>
                <span className="text-xs text-[var(--color-bo-ink-2)]">
                  priorità {c.priority} · {audienceLabel(c.audience)}{i >= limit ? " · oltre il limite" : ""}
                </span>
              </li>
            ))}
          </ol>
        )}
        <p className="mt-2 text-xs text-[var(--color-bo-ink-2)]">Il pubblico decide chi li vede: usa «Anteprima per membro» per un membro preciso.</p>
      </CardBody>
    </Card>
  );
}

function MemberPreview() {
  const members = useLhQuery<Page<MemberView>>("member", "/v1/members", { size: 100 });
  const [memberId, setMemberId] = useState("MBR-000001");
  const [placement, setPlacement] = useState<PreviewPlacement>("HOME_GRID");
  const preview = useLhQuery<ContentPreview>("engagement", "/v1/contents/preview", { memberId, placement });
  const variant = placement === "HOME_HERO" ? "hero" : placement === "HOME_GRID" ? "grid" : placement === "CATALOG_TOP" ? "banner" : "inline";
  const placementLabel = placement === "POPUP" ? "Pop-up all'ingresso" : PLACEMENT_LABEL[placement];
  return (
    <div className="grid gap-6 lg:grid-cols-[minmax(0,2fr)_minmax(0,3fr)]">
      <div className="space-y-3">
        <div className="flex flex-wrap gap-2">
          <select aria-label="Membro" value={memberId} onChange={(e) => setMemberId(e.target.value)} className={cn(FILTER, "w-60")}>
            {(members.data?.items ?? [{ id: memberId, firstName: null, lastName: null } as MemberView]).map((m) => (
              <option key={m.id} value={m.id}>{m.firstName ? `${m.firstName} ${m.lastName ?? ""} · ${m.tier}` : m.id}</option>
            ))}
          </select>
          <select aria-label="Posizionamento" value={placement} onChange={(e) => setPlacement(e.target.value as PreviewPlacement)} className={cn(FILTER, "w-52")}>
            {PLACEMENTS.map((p) => <option key={p} value={p}>{PLACEMENT_LABEL[p]}</option>)}
            <option value="POPUP">Pop-up all&apos;ingresso</option>
          </select>
        </div>
        <QueryState query={preview} service="engagement">
          {(p) => (
            <Card>
              <CardBody>
                <h2 className="mb-2 text-sm font-semibold">Esclusi per questo membro</h2>
                {p.excluded.length === 0 ? (
                  <p className="text-sm text-[var(--color-bo-ink-2)]">Nessuno: vede tutti i contenuti del posizionamento.</p>
                ) : (
                  <ul className="space-y-1 text-sm">
                    {p.excluded.map((e) => (
                      <li key={e.code} className="flex justify-between gap-2">
                        <span>{e.title} <CodeText>{e.code}</CodeText></span>
                        <span className="shrink-0 rounded bg-amber-50 px-1.5 py-0.5 text-xs text-amber-800">{EXCLUSION_LABEL[e.reason]}</span>
                      </li>
                    ))}
                  </ul>
                )}
              </CardBody>
            </Card>
          )}
        </QueryState>
      </div>
      <PhoneFrame label={`Cosa vede adesso in «${placementLabel}»`}>
        {preview.data ? (
          placement === "POPUP" && preview.data.shown[0] ? (
            <div className="relative min-h-[380px]">
              <PopupModal content={preview.data.shown[0]} dismissible preview />
            </div>
          ) : preview.data.shown.length === 0 ? (
            <p className="py-10 text-center text-xs text-[var(--color-pt-night)]/60">Niente da mostrare qui.</p>
          ) : (
            <div className={variant === "grid" ? "grid grid-cols-2 gap-2" : "space-y-2"}>
              {preview.data.shown.map((c) => <ContentCard key={c.code} content={c} variant={variant} preview />)}
            </div>
          )
        ) : null}
      </PhoneFrame>
    </div>
  );
}
