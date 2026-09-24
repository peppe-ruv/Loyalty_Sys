"use client";

import { useMemo, useState } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useQueryClient } from "@tanstack/react-query";
import { useLhMutation, useLhQuery, type LhError } from "@/lib/api/client";
import type { MemberPlay, PlayResult, PortalContest, PortalCoupon } from "@/lib/api/types";
import { useActiveMember } from "@/components/portal/MemberContext";
import { usePending } from "@/components/portal/PendingContext";
import { QueryState } from "@/components/bo/QueryState";
import { Wheel } from "@/components/portal/game/Wheel";
import { ScratchCard } from "@/components/portal/game/ScratchCard";
import { GiftBoxes } from "@/components/portal/game/GiftBoxes";
import { Confetti } from "@/components/portal/game/Confetti";
import { PLAY_ERRORS, freePlayLine, shortPrize, targetRotation, wheelSegments, winFollowUp } from "@/lib/gamification/play";
import { formatDateTime } from "@/lib/format/dates";
import { cn } from "@/lib/cn";

// PT-06 Giocata instant win (docs/09 §PT-06): l'esito lo decide il server (risposta sincrona), l'animazione lo rivela.
// Sempre presente un pulsante equivalente per tastiera, lettori di schermo e movimento ridotto (rivelazione diretta).
type Phase = "idle" | "requesting" | "revealing" | "done";

export default function PlayContestPage() {
  const code = String(useParams().code);
  const memberId = useActiveMember();
  const contests = useLhQuery<PortalContest[]>("gamification", "/v1/portal/contests", { memberId });
  return (
    <div className="space-y-4">
      <Link href="/portal/play" className="text-xs font-medium text-[var(--color-pt-primary)]">
        ← Gioca
      </Link>
      <QueryState query={contests} service="gamification">
        {(list) => {
          const c = list.find((x) => x.code === code);
          if (!c) {
            return (
              <div className="rounded-2xl bg-white p-5 text-sm shadow-sm ring-1 ring-black/5">
                <p className="font-medium text-[var(--color-pt-night)]">Questo concorso non è in corso.</p>
                <Link href="/portal/play" className="mt-2 inline-block text-[var(--color-pt-primary)]">
                  Vedi i concorsi attivi →
                </Link>
              </div>
            );
          }
          return <Game key={`${c.code}-${memberId}`} contest={c} memberId={memberId} />;
        }}
      </QueryState>
    </div>
  );
}

function Game({ contest, memberId }: { contest: PortalContest; memberId: string }) {
  const qc = useQueryClient();
  const { markPending } = usePending();
  const [phase, setPhase] = useState<Phase>("idle");
  const [result, setResult] = useState<PlayResult | null>(null);
  const [error, setError] = useState<LhError | null>(null);
  const [rotation, setRotation] = useState(0);
  const [chosenBox, setChosenBox] = useState<number | null>(null);
  const segments = useMemo(() => wheelSegments(contest.prizes), [contest.prizes]);
  const history = useLhQuery<MemberPlay[]>("gamification", `/v1/portal/contests/${contest.code}/plays`, { memberId });
  const play = useLhMutation<PlayResult, { memberId: string }>("gamification", "POST", () => `/v1/portal/contests/${contest.code}/play`, {
    invalidate: false,
  });

  const reduced = typeof window !== "undefined" && window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;
  const plays = result ? result.playsAvailable : contest.playsAvailable;
  const busy = phase === "requesting" || phase === "revealing";

  function finish(r: PlayResult) {
    setPhase("done");
    qc.invalidateQueries({ queryKey: ["gamification"] });
    if (r.outcome === "WIN" && r.prize?.type === "POINTS") {
      markPending([`Vincita: ${shortPrize({ type: "POINTS", name: r.prize.name, points: r.prize.points })}`], r.correlationId);
    }
  }

  function start(direct: boolean) {
    setError(null);
    setResult(null);
    setPhase("requesting");
    play.mutate(
      { memberId },
      {
        onSuccess: (r) => {
          setResult(r);
          // La ruota si posiziona comunque sull'esito; senza animazione il salto è istantaneo (niente transizione).
          if (contest.mechanic === "WHEEL") {
            setRotation((cur) => targetRotation(segments, r.prize?.code ?? null, cur, r.playId));
          }
          if (direct || reduced) {
            finish(r);
            return;
          }
          setPhase("revealing");
        },
        onError: (e) => {
          setError(e);
          setPhase("idle");
          setChosenBox(null);
        },
      },
    );
  }

  const revealed = phase === "done" && result;

  return (
    <div className="relative space-y-5">
      <header>
        <h1 className="text-xl font-semibold text-[var(--color-pt-night)]">{contest.name}</h1>
        <p className="text-sm text-[var(--color-pt-night)]/70" aria-live="polite">
          <strong className="tabular-nums">{plays}</strong> {plays === 1 ? "giocata disponibile" : "giocate disponibili"}
          {phase === "idle" && freePlayLine(contest) ? ` · ${freePlayLine(contest)}` : ""}
        </p>
      </header>

      {contest.mechanic === "WHEEL" ? (
        <Wheel segments={segments} rotation={rotation} spinning={phase === "revealing"} onStopped={() => result && finish(result)} />
      ) : contest.mechanic === "SCRATCH" ? (
        result && phase !== "idle" ? (
          <ScratchCard key={result.playId} disabled={phase === "done"} onRevealed={() => finish(result)}>
            <Outcome result={result} compact />
          </ScratchCard>
        ) : (
          <div className="mx-auto flex aspect-[3/2] w-full max-w-[320px] items-center justify-center rounded-2xl bg-gradient-to-br from-[#c9d3dc] to-[#9fb0bf] text-sm font-semibold text-[var(--color-pt-night)] shadow-md">
            Tocca «Gioca» per ricevere il biglietto
          </div>
        )
      ) : (
        <GiftBoxes
          chosen={chosenBox}
          opened={phase === "done"}
          disabled={busy || phase === "done" || plays <= 0}
          onChoose={(i) => {
            setChosenBox(i);
            setError(null);
            setResult(null);
            setPhase("requesting");
            play.mutate({ memberId }, {
              onSuccess: (r) => {
                setResult(r);
                finish(r);
              },
              onError: (e) => {
                setError(e);
                setPhase("idle");
                setChosenBox(null);
              },
            });
          }}
        />
      )}

      <div aria-live="assertive">{revealed ? <Outcome result={result} /> : null}</div>
      {revealed && result.outcome === "WIN" ? <Confetti /> : null}

      <div className="flex flex-col items-center gap-2">
        {phase === "done" ? (
          plays > 0 ? (
            <button
              type="button"
              onClick={() => {
                setPhase("idle");
                setResult(null);
                setChosenBox(null);
              }}
              className="rounded-full bg-[var(--color-pt-night)] px-6 py-3 text-sm font-semibold text-white"
            >
              Gioca ancora
            </button>
          ) : null
        ) : contest.mechanic === "BOX" ? (
          <p className="text-sm text-[var(--color-pt-night)]/70">{busy ? "Apro il pacco…" : "Scegli un pacco: uno vale l'altro, l'esito è già scritto."}</p>
        ) : (
          <>
            <button
              type="button"
              disabled={busy || plays <= 0}
              onClick={() => start(false)}
              className="rounded-full bg-[var(--color-pt-coin)] px-8 py-3 text-base font-semibold text-[var(--color-pt-night)] shadow disabled:opacity-50"
            >
              {phase === "requesting" ? "…" : contest.mechanic === "WHEEL" ? "Gira" : phase === "revealing" ? "Gratta la patina" : "Gioca"}
            </button>
            {phase === "idle" && plays > 0 ? (
              <button type="button" onClick={() => start(true)} className="text-xs text-[var(--color-pt-night)]/60 underline">
                Gioca senza animazione
              </button>
            ) : null}
            {phase === "revealing" && contest.mechanic === "SCRATCH" && result ? (
              <button type="button" onClick={() => finish(result)} className="text-xs text-[var(--color-pt-night)]/60 underline">
                Scopri subito
              </button>
            ) : null}
          </>
        )}
      </div>

      {error ? (
        <div role="alert" className="rounded-2xl bg-[var(--color-pt-coin)]/20 p-4 text-sm text-[var(--color-pt-night)]">
          <p>{PLAY_ERRORS[error.code] ?? error.detail ?? "Qualcosa è andato storto: riprova."}</p>
          {error.code === "NO_PLAYS_AVAILABLE" ? (
            <Link href="/portal/earn" className="mt-1 inline-block font-medium text-[var(--color-pt-primary)]">
              Scopri come guadagnare giocate →
            </Link>
          ) : null}
        </div>
      ) : null}


      <section>
        <h2 className="mb-2 text-sm font-semibold text-[var(--color-pt-night)]">Le tue giocate</h2>
        <QueryState
          query={history}
          service="gamification"
          isEmpty={(d) => d.length === 0}
          emptyTitle="Ancora nessuna giocata"
          emptyHint="La prima è gratis: prova la fortuna!"
        >
          {(d) => (
            <ul className="divide-y divide-black/5 rounded-2xl bg-white shadow-sm ring-1 ring-black/5">
              {d.slice(0, 10).map((p) => (
                <li key={p.playId} className="flex items-center justify-between gap-3 px-4 py-2.5 text-sm">
                  <span className="text-xs text-[var(--color-pt-night)]/60">{formatDateTime(p.playedAt)}</span>
                  <span className={cn("font-medium", p.outcome === "WIN" ? "text-[var(--color-pt-primary)]" : "text-[var(--color-pt-night)]/60")}>
                    {p.outcome === "WIN" ? `Vinto: ${p.prizeName}` : "Non vinto"}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </QueryState>
      </section>

      {contest.rulesText ? (
        <details className="rounded-2xl bg-white p-4 text-sm shadow-sm ring-1 ring-black/5">
          <summary className="cursor-pointer font-medium text-[var(--color-pt-night)]">Regolamento</summary>
          <p className="mt-2 whitespace-pre-line text-[var(--color-pt-night)]/70">{contest.rulesText}</p>
        </details>
      ) : null}
    </div>
  );
}

function Outcome({ result, compact = false }: { result: PlayResult; compact?: boolean }) {
  if (result.outcome === "LOSE") {
    return (
      <div className={cn(!compact && "rounded-2xl bg-white p-5 text-center shadow-sm ring-1 ring-black/5")}>
        <p className="text-lg font-semibold text-[var(--color-pt-night)]">Non è andata</p>
        {!compact ? (
          <p className="text-sm text-[var(--color-pt-night)]/70">
            {result.playsAvailable > 0 ? "Hai ancora giocate: ritenta!" : "Riprova domani con la giocata gratuita."}
          </p>
        ) : null}
      </div>
    );
  }
  const p = result.prize;
  return (
    <div className={cn(!compact && "rounded-2xl bg-[var(--color-pt-primary)] p-5 text-center text-white shadow-md")}>
      <p className={cn("text-sm", compact ? "text-[var(--color-pt-night)]/70" : "text-white/80")}>Hai vinto!</p>
      <p className={cn("text-2xl font-bold", compact && "text-[var(--color-pt-night)]")}>{p ? shortPrize(p) : "Un premio"}</p>
      {!compact ? (
        <>
          <p className="mt-1 text-sm text-white/90">{winFollowUp(p?.type)}</p>
          {p?.type === "COUPON" && p.rewardCode ? <CouponArrival rewardCode={p.rewardCode} /> : null}
        </>
      ) : null}
    </div>
  );
}

/**
 * Il coupon vinto arriva in modo asincrono (ponte → CMP-IW-PRIZE-COUPON → reward): si interroga PT-13 finché compare
 * un codice nuovo di quel premio, poi si mostra con il collegamento ai coupon.
 */
function CouponArrival({ rewardCode }: { rewardCode: string }) {
  const memberId = useActiveMember();
  const [since] = useState(() => Date.now() - 5_000);
  const [stopAt] = useState(() => Date.now() + 30_000);
  const coupons = useLhQuery<PortalCoupon[]>("reward", "/v1/portal/coupons", { memberId }, { refetchInterval: 2_000 });
  const arrived = (coupons.data ?? []).find(
    (c) => c.rewardCode === rewardCode && c.issuedAt != null && new Date(c.issuedAt).getTime() >= since,
  );
  const waiting = !arrived && Date.now() < stopAt;
  return (
    <div className="mt-3 rounded-xl bg-white/15 px-3 py-2 text-sm">
      {arrived ? (
        <>
          <p className="text-white/80">Il tuo codice</p>
          <p className="font-mono text-lg font-semibold tracking-wider">{arrived.code}</p>
        </>
      ) : (
        <p>{waiting ? "Sto preparando il tuo codice…" : "Il codice arriva tra poco nei tuoi coupon."}</p>
      )}
      <Link href="/portal/my-rewards" className="mt-1 inline-block font-semibold underline">
        I miei coupon →
      </Link>
    </div>
  );
}
