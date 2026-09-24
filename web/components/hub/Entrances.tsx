"use client";

import Link from "next/link";
import { useState } from "react";
import { ArrowRight, Lock, Users } from "lucide-react";
import { Card, CardBody } from "@/components/ui/card";
import { EmptyState, QueryState } from "@/components/shared/QueryState";
import { useLhQuery } from "@/lib/api/client";
import { entrancesReady } from "@/lib/api/status";
import { formatPoints } from "@/lib/format/points";
import { it } from "@/lib/i18n/it";
import { BACKOFFICE_PERSONAS } from "@/lib/persona/personas";
import { useDemoStatus } from "./useDemoStatus";

// HUB-01 — due ingressi (docs/07 §8): attivi solo con ingestion, member, campaign e wallet UP. Backoffice con
// scelta della persona (5 schede), Portale con scelta del membro (nome, tier, saldo, storia). La scelta scrive il
// cookie lh_persona (docs/07 §4) e ricarica l'area: la cache di Query riparte da zero.

/**
 * Membro in evidenza da member `GET /v1/demo/personas` (docs/servizi/member-service.md §3).
 * SPEC-GAP: Q-136 — docs/07 §8 dice "12 schede", ma l'endpoint esclude i membri anonimizzati (MBR-000012 nel seed):
 * si mostrano quelli che restituisce (11 a demo appena accesa), senza filtri o aggiunte lato web.
 */
interface HubMember {
  memberId: string;
  name: string;
  tier: string;
  story: string | null;
  balancePts?: number;
}

type PersonaBody = { kind: "BO"; username: string } | { kind: "MEMBER"; memberId: string };

async function enterAs(body: PersonaBody, href: string): Promise<void> {
  const res = await fetch("/api/persona", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  window.location.assign(href);
}

function useEnter() {
  const [pending, setPending] = useState<string | null>(null);
  const [error, setError] = useState(false);
  async function enter(key: string, body: PersonaBody, href: string) {
    setPending(key);
    setError(false);
    try {
      await enterAs(body, href);
    } catch {
      setError(true);
      setPending(null);
    }
  }
  return { pending, error, enter };
}

const choiceClass =
  "flex w-full flex-col items-start rounded-md border border-[var(--color-bo-border)] px-3 py-2 text-left text-sm transition-colors enabled:hover:border-[var(--color-bo-accent)] enabled:hover:bg-[var(--color-bo-bg)] disabled:cursor-not-allowed disabled:opacity-50";

function Locked() {
  return (
    <p className="mb-3 flex items-center gap-1.5 text-xs text-[var(--color-bo-ink-2)]">
      <Lock className="h-3.5 w-3.5" /> {it.hub.entrancesLocked}
    </p>
  );
}

function BackofficeEntrance({ ready }: { ready: boolean }) {
  const { pending, error, enter } = useEnter();
  return (
    <Card>
      <CardBody className="pt-4">
        <div className="mb-1 flex items-center justify-between">
          <h3 className="font-semibold">{it.hub.backoffice}</h3>
          <ArrowRight className="h-4 w-4 text-[var(--color-bo-ink-2)]" aria-hidden />
        </div>
        <p className="mb-3 text-xs text-[var(--color-bo-ink-2)]">{it.hub.choosePersona}</p>
        {!ready && <Locked />}
        <ul className="space-y-2">
          {BACKOFFICE_PERSONAS.map((p) => (
            <li key={p.username}>
              <button
                type="button"
                className={choiceClass}
                disabled={!ready || pending !== null}
                title={ready ? undefined : it.hub.entrancesLocked}
                onClick={() => enter(p.username, { kind: "BO", username: p.username }, "/backoffice")}
              >
                <span className="flex items-center gap-2">
                  <span className="font-medium">{p.displayName}</span>
                  <span className="rounded bg-[var(--color-bo-bg)] px-1.5 py-0.5 text-xs font-semibold text-[var(--color-bo-ink-2)]">
                    {p.role}
                  </span>
                  {pending === p.username ? <span className="text-xs text-[var(--color-bo-ink-2)]">…</span> : null}
                </span>
                <span className="text-[var(--color-bo-ink-2)]">{p.summary}</span>
              </button>
            </li>
          ))}
        </ul>
        {error && <p role="alert" className="mt-2 text-xs text-[var(--color-state-down)]">{it.hub.enterError}</p>}
      </CardBody>
    </Card>
  );
}

function MembersSkeleton() {
  return (
    <ul className="grid gap-2 sm:grid-cols-2" aria-hidden>
      {Array.from({ length: 6 }).map((_, i) => (
        <li key={i} className="h-[68px] animate-pulse rounded-md bg-[var(--color-bo-bg)]" />
      ))}
    </ul>
  );
}

function PortalMembers() {
  const members = useLhQuery<HubMember[]>("member", "/v1/demo/personas");
  const { pending, error, enter } = useEnter();

  if (members.isLoading) return <MembersSkeleton />;
  return (
    <QueryState query={members} service="member">
      {(data) =>
        data.length === 0 ? (
          <div className="space-y-2">
            <EmptyState title={it.hub.membersEmpty} hint={it.hub.membersEmptyHint} />
            <Link href="/backoffice/demo/console" className="inline-flex items-center gap-1 text-sm font-medium text-[var(--color-bo-accent)]">
              {it.hub.openConsole} <ArrowRight className="h-4 w-4" />
            </Link>
          </div>
        ) : (
        <>
          <ul className="grid gap-2 sm:grid-cols-2">
            {data.map((m) => (
              <li key={m.memberId}>
                <button
                  type="button"
                  className={choiceClass}
                  disabled={pending !== null}
                  onClick={() => enter(m.memberId, { kind: "MEMBER", memberId: m.memberId }, "/portal")}
                >
                  <span className="flex w-full items-center justify-between gap-2">
                    <span className="truncate font-medium">{m.name}</span>
                    <span className="shrink-0 rounded bg-[var(--color-bo-bg)] px-1.5 py-0.5 text-xs font-semibold text-[var(--color-bo-ink-2)]">
                      {m.tier}
                    </span>
                  </span>
                  <span className="tabular text-xs font-medium">
                    {m.balancePts != null ? `${formatPoints(m.balancePts)} punti` : "—"}
                  </span>
                  {m.story ? <span className="text-xs text-[var(--color-bo-ink-2)]">{m.story}</span> : null}
                </button>
              </li>
            ))}
          </ul>
          {error && <p role="alert" className="mt-2 text-xs text-[var(--color-state-down)]">{it.hub.enterError}</p>}
        </>
        )
      }
    </QueryState>
  );
}

function PortalEntrance({ ready }: { ready: boolean }) {
  return (
    <Card>
      <CardBody className="pt-4">
        <div className="mb-1 flex items-center justify-between">
          <h3 className="font-semibold">{it.hub.portal}</h3>
          <ArrowRight className="h-4 w-4 text-[var(--color-bo-ink-2)]" aria-hidden />
        </div>
        <p className="mb-3 text-xs text-[var(--color-bo-ink-2)]">{it.hub.chooseMember}</p>
        {ready ? (
          <PortalMembers />
        ) : (
          <>
            <Locked />
            <div className="flex items-center justify-center rounded-md border border-dashed border-[var(--color-bo-border)] p-6">
              <Users className="h-6 w-6 text-[var(--color-bo-ink-2)]/60" aria-hidden />
            </div>
          </>
        )}
      </CardBody>
    </Card>
  );
}

export function Entrances() {
  const status = useDemoStatus();
  // SPEC-GAP: Q-133 — stato ignoto (caricamento o errore di /api/demo/status) ⇒ ingressi spenti, come con i
  // servizi core non UP; nessun ingresso "alla cieca".
  const ready = entrancesReady(status.data);
  return (
    <section className="mb-8" data-ready={ready}>
      <h2 className="mb-3 text-lg font-semibold">{it.hub.entrancesTitle}</h2>
      <div className="grid gap-4 md:grid-cols-2">
        <BackofficeEntrance ready={ready} />
        <PortalEntrance ready={ready} />
      </div>
    </section>
  );
}
