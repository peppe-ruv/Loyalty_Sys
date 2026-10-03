"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { call, HttpError } from "@/lib/vetrina/client";
import { it as t } from "@/lib/i18n/it";
import { useBoPersona } from "@/components/bo/PersonaContext";
import type { ActionContext, ActionResult } from "@/lib/vetrina/azione";

// BO-32 (V11) — «Invia un'azione» (F2-ING-02, ADR-051, Q-675, Q-725). Modulo di una riga sopra l'elenco degli import,
// solo per ADMIN e CARE nell'ambiente di test dichiarato (il layout decide con `sendAction`). Parla solo con
// /api/vetrina/azione: contesto (GET), invio (POST con CSRF) ed esito (GET ?import=, ogni 1,5 s fino a «finale»).
// Il browser non vede mai token né identificativi di membri. Importa SOLO i tipi del modulo server (`import type`).

const s = t.sendAction;
const POLL_MS = 1500;
const CONTEXT_KEY = ["vetrina", "azione"] as const;

/** Il modulo, solo se il layout lo ha abilitato (ADMIN o CARE, enterprise, ambiente di test). */
export function SendActionForm() {
  const persona = useBoPersona();
  if (!persona.sendAction) return null;
  return <Form actor={persona.username} />;
}

function Form({ actor }: { actor: string }) {
  const qc = useQueryClient();
  const [username, setUsername] = useState("");
  const [type, setType] = useState("");
  const [amount, setAmount] = useState("");
  const [formError, setFormError] = useState<string | null>(null);
  const [importId, setImportId] = useState<string | null>(null);

  const ctx = useQuery<ActionContext, HttpError>({
    queryKey: CONTEXT_KEY,
    queryFn: () => call<ActionContext>("/api/vetrina/azione"),
    retry: false,
    refetchOnWindowFocus: false,
  });

  const result = useQuery<ActionResult, HttpError>({
    queryKey: [...CONTEXT_KEY, "import", importId],
    queryFn: () => call<ActionResult>(`/api/vetrina/azione?import=${encodeURIComponent(importId ?? "")}`),
    enabled: importId !== null,
    retry: false,
    refetchOnWindowFocus: false,
    // Si ferma alla risposta «finale» o a un errore di lettura (niente raffiche: l'operatore apre l'elenco degli import).
    refetchInterval: (q) => (q.state.status === "error" ? false : q.state.data?.final ? false : POLL_MS),
  });

  // Elaborato: l'elenco degli import si rilegge, così mostra la riga appena creata.
  const finalId = result.data?.final ? result.data.importId : null;
  const refreshed = useRef<string | null>(null);
  useEffect(() => {
    if (finalId && refreshed.current !== finalId) {
      refreshed.current = finalId;
      void qc.invalidateQueries({ queryKey: ["ingestion"] });
    }
  }, [finalId, qc]);

  const send = useMutation<{ importId: string }, HttpError, void>({
    mutationFn: () =>
      call<{ importId: string }>("/api/vetrina/azione", {
        method: "POST",
        body: JSON.stringify({ username, type, ...(valued(ctx.data, type) ? { amount } : {}) }),
      }),
    onSuccess: (r) => {
      setFormError(null);
      setImportId(r.importId);
    },
    onError: (e) => {
      if (e.status === 409) setFormError(s.programMissing);
      else if (e.code === "MEMBER_NOT_REGISTERED") setFormError(s.memberNotRegistered);
      else if (e.status === 400 && valued(ctx.data, type)) setFormError(s.badAmount);
      else setFormError(s.sendFailed);
    },
  });

  const body = (() => {
    if (ctx.isPending) {
      return (
        <p role="status" className="text-sm text-[var(--color-bo-ink-2)]">
          {s.loading}
        </p>
      );
    }
    if (ctx.isError) {
      const degraded = ctx.error.status === 503;
      return (
        <div role="alert" data-testid={degraded ? "send-degraded" : "send-error"}>
          <p className="text-sm font-medium text-[var(--color-bo-ink)]">{degraded ? s.degradedTitle : s.errorTitle}</p>
          <p className="mb-2 text-sm text-[var(--color-bo-ink-2)]">{degraded ? s.degradedText : s.errorText}</p>
          <button type="button" onClick={() => void ctx.refetch()} className={secondary}>
            {s.retry}
          </button>
        </div>
      );
    }
    if (!ctx.data.ready) {
      return (
        <div data-testid="send-need-program">
          <p className="text-sm font-medium text-[var(--color-bo-ink)]">{s.needProgramTitle}</p>
          <p className="mb-2 text-sm text-[var(--color-bo-ink-2)]">{s.needProgramText}</p>
          <Link href="/backoffice" className="text-sm underline text-[var(--color-bo-ink)]">
            {s.needProgramLink}
          </Link>
        </div>
      );
    }
    const needsAmount = valued(ctx.data, type);
    const ready = username !== "" && type !== "" && (!needsAmount || amount.trim() !== "");
    return (
      <form
        onSubmit={(e) => {
          e.preventDefault();
          setFormError(null);
          send.mutate();
        }}
        className="flex flex-wrap items-end gap-3"
      >
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-xs text-[var(--color-bo-ink-2)]">{s.member}</span>
          <select value={username} onChange={(e) => setUsername(e.target.value)} className={field}>
            <option value="">{s.choose}</option>
            {ctx.data.members.map((m) => (
              <option key={m.key} value={m.key} disabled={!m.registered}>
                {m.registered ? s.memberOption(m.name, m.tier) : `${m.name} · ${s.memberMissing}`}
              </option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-xs text-[var(--color-bo-ink-2)]">{s.action}</span>
          <select
            value={type}
            onChange={(e) => {
              setType(e.target.value);
              if (!valued(ctx.data, e.target.value)) setAmount("");
            }}
            className={field}
          >
            <option value="">{s.choose}</option>
            {ctx.data.actions.map((a) => (
              <option key={a.type} value={a.type}>
                {a.label}
              </option>
            ))}
          </select>
        </label>
        {needsAmount ? (
          <div className="flex flex-col gap-1 text-sm">
            <label htmlFor="send-action-amount" className="text-xs text-[var(--color-bo-ink-2)]">
              {s.amount}
            </label>
            <input
              id="send-action-amount"
              type="text"
              inputMode="decimal"
              value={amount}
              onChange={(e) => setAmount(e.target.value)}
              aria-describedby="send-action-amount-hint"
              className={`${field} w-28`}
            />
            <span id="send-action-amount-hint" className="sr-only">
              {s.amountHint}
            </span>
          </div>
        ) : null}
        <button type="submit" disabled={!ready || send.isPending} className="rounded-md bg-[var(--color-bo-accent)] px-3 py-1.5 text-sm font-medium text-white disabled:opacity-50">
          {send.isPending ? s.sending : s.submit}
        </button>
        <p className="basis-full text-xs text-[var(--color-bo-ink-2)]">{s.note(actor)}</p>
        {formError ? (
          <p role="alert" data-testid="send-form-error" className="basis-full text-sm text-[var(--color-state-down)]">
            {formError}
          </p>
        ) : null}
      </form>
    );
  })();

  return (
    <section aria-labelledby="send-action-title" data-testid="send-action" className="mb-4 rounded-lg border border-[var(--color-bo-border)] bg-white p-4">
      <div className="mb-2 flex flex-wrap items-center gap-2">
        <h2 id="send-action-title" className="text-sm font-semibold text-[var(--color-bo-ink)]">
          {s.title}
        </h2>
        <span className="rounded-full bg-amber-100 px-2 py-0.5 text-[11px] font-medium text-amber-900">{s.source}</span>
      </div>
      {body}
      {importId !== null ? <Last result={result} /> : null}
    </section>
  );
}

const field = "rounded-md border border-[var(--color-bo-border)] bg-white px-2 py-1.5 text-sm text-[var(--color-bo-ink)]";
const secondary = "rounded-md border border-[var(--color-bo-border)] px-3 py-1.5 text-sm font-medium text-[var(--color-bo-ink)]";

function valued(ctx: ActionContext | undefined, type: string): boolean {
  return ctx?.actions.find((a) => a.type === type)?.valued ?? false;
}

/** «Ultimo invio»: membro · azione · importo → esito, punti e livello (letti dal portafoglio, mai inventati), audit. */
function Last({ result }: { result: ReturnType<typeof useQuery<ActionResult, HttpError>> }) {
  // Regione viva sempre presente: l'esito cambia più volte (in elaborazione → elaborato → audit verificato).
  return (
    <div role="status" aria-live="polite" data-testid="send-last" className="mt-3 border-t border-[var(--color-bo-border)] pt-3 text-sm">
      <p className="mb-1 text-xs text-[var(--color-bo-ink-2)]">{s.lastTitle}</p>
      <LastBody result={result} />
    </div>
  );
}

function LastBody({ result }: { result: ReturnType<typeof useQuery<ActionResult, HttpError>> }) {
  if (result.isError) {
    return (
      <div role="alert">
        <p className="mb-2 text-[var(--color-bo-ink)]">{result.error.status === 404 ? s.lost : s.readError}</p>
        <div className="flex flex-wrap gap-3">
          {result.error.status !== 404 ? (
            <button type="button" onClick={() => void result.refetch()} className={secondary}>
              {s.retry}
            </button>
          ) : null}
          <Link href="/backoffice/observe/imports" className="text-sm underline text-[var(--color-bo-ink)]">
            {s.openImport}
          </Link>
        </div>
      </div>
    );
  }
  const r = result.data;
  if (!r || r.status === "running") {
    return <p className="text-[var(--color-bo-ink-2)]">{r ? s.summary(r.member.name, r.label, r.amount) : ""} {s.processing}</p>;
  }
  const outcome = { accepted: s.outcomeAccepted, duplicate: s.outcomeDuplicate, invalid: s.outcomeInvalid, failed: s.outcomeFailed, pending: s.processing }[r.outcome];
  return (
    <div data-testid="send-result">
      <p className="text-[var(--color-bo-ink)]">
        <span className="font-medium">{s.summary(r.member.name, r.label, r.amount)}</span>
        {" → "}
        <span className={r.status === "error" ? "text-[var(--color-state-down)]" : ""}>{outcome}</span>
        {r.pointsDelta !== null ? <strong className="ml-1 text-[var(--color-state-up)]">{s.points(r.pointsDelta)}</strong> : null}
        {r.tierChanged && r.after?.tier ? <span>{`, ${s.newTier(r.after.tier)}`}</span> : null}
      </p>
      {r.outcome === "accepted" ? (
        <p className="text-xs text-[var(--color-bo-ink-2)]">
          {r.pointsDelta !== null ? "" : r.final ? (r.after ? s.pointsNone : s.pointsUnknown) : s.pointsPending}
        </p>
      ) : null}
      {r.problems.length ? (
        <ul className="list-disc pl-5 text-[var(--color-state-down)]">
          {r.problems.map((p) => (
            <li key={p}>{p}</li>
          ))}
        </ul>
      ) : null}
      <p className="text-xs text-[var(--color-bo-ink-2)]">
        {r.audit.state === "verified" ? s.auditVerified(r.audit.actor) : r.audit.state === "pending" ? s.auditPending : s.auditMissing(r.audit.reason ?? "voce mancante")}
      </p>
      <p className="mt-1 flex flex-wrap gap-x-4 gap-y-1">
        <Link href={`/backoffice/observe/imports?i=${encodeURIComponent(r.importId)}`} className="text-sm underline text-[var(--color-bo-ink)]">
          {s.openImport}
        </Link>
        <Link href="/backoffice/members" className="text-sm underline text-[var(--color-bo-ink)]">
          {s.openMembers}
        </Link>
      </p>
    </div>
  );
}
