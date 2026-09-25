"use client";

import { useMemo, useState } from "react";
import type { LhError } from "@/lib/api/client";
import type { Contest } from "@/lib/api/types";
import { useCan } from "@/components/bo/Can";
import { Field, INPUT, Section } from "@/components/bo/FormBits";
import { DISTRIBUTION_LABEL, MECHANIC_LABEL, isLocked, isoToLocalInput, localInputToIso } from "@/lib/gamification/contests";

// Scheda `setup` di BO-14 (docs/08 §BO-14): generale, meccanica, periodo, giocata gratuita, limiti, regolamento.
// Prima del LIVE tutto è modificabile (cambiare periodo, distribuzione o seme cancella gli istanti generati); da LIVE
// solo i campi sicuri di docs/03 §3.6 — nome, descrizione, data di fine — senza toccare gli istanti (il resto, regolamento
// compreso, → 409 CONTEST_LIVE_LOCKED); ENDED e ARCHIVED in sola lettura. Si inviano solo i campi cambiati: una data
// riscritta identica non deve invalidare gli istanti.

export interface ContestInput {
  code?: string;
  name?: string;
  description?: string | null;
  rulesText?: string | null;
  mechanic?: string;
  startAt?: string;
  endAt?: string;
  freePlayDaily?: boolean;
  maxPlaysPerMemberPerDay?: number | null;
  maxWinsPerMember?: number | null;
  distribution?: string;
  seed?: number;
}

interface FormState {
  code: string;
  name: string;
  description: string;
  rulesText: string;
  mechanic: string;
  startAt: string;
  endAt: string;
  freePlayDaily: boolean;
  maxPlaysPerMemberPerDay: string;
  maxWinsPerMember: string;
  distribution: string;
  seed: string;
}

const LIVE_FIELDS = new Set<keyof FormState>(["name", "description", "endAt"]);
const INSTANT_FIELDS = new Set<keyof FormState>(["startAt", "endAt", "distribution", "seed"]);

function fromContest(c: Contest | null): FormState {
  return {
    code: c?.code ?? "",
    name: c?.name ?? "",
    description: c?.description ?? "",
    rulesText: c?.rulesText ?? "",
    mechanic: c?.mechanic ?? "WHEEL",
    startAt: isoToLocalInput(c?.startAt),
    endAt: isoToLocalInput(c?.endAt),
    freePlayDaily: c?.freePlayDaily ?? true,
    maxPlaysPerMemberPerDay: c?.maxPlaysPerMemberPerDay == null ? "" : String(c.maxPlaysPerMemberPerDay),
    maxWinsPerMember: c?.maxWinsPerMember == null ? "" : String(c.maxWinsPerMember),
    distribution: c?.distribution ?? "UNIFORM",
    seed: c ? String(c.seed) : "",
  };
}

function toInput(f: FormState, changed: Set<keyof FormState> | null): ContestInput {
  const all: ContestInput = {
    code: f.code.trim().toUpperCase() || undefined,
    name: f.name.trim(),
    description: f.description.trim() || null,
    rulesText: f.rulesText.trim() || null,
    mechanic: f.mechanic,
    startAt: localInputToIso(f.startAt),
    endAt: localInputToIso(f.endAt),
    freePlayDaily: f.freePlayDaily,
    maxPlaysPerMemberPerDay: f.maxPlaysPerMemberPerDay === "" ? null : Number(f.maxPlaysPerMemberPerDay),
    maxWinsPerMember: f.maxWinsPerMember === "" ? null : Number(f.maxWinsPerMember),
    distribution: f.distribution,
    seed: f.seed === "" ? undefined : Number(f.seed),
  };
  if (changed == null) return all;
  return Object.fromEntries(Object.entries(all).filter(([k]) => changed.has(k as keyof FormState))) as ContestInput;
}

export function ContestSetupForm({
  contest,
  saving,
  error,
  onSubmit,
}: {
  contest: Contest | null;
  saving: boolean;
  error: LhError | null;
  onSubmit: (input: ContestInput) => void;
}) {
  const initial = useMemo(() => fromContest(contest), [contest]);
  const [f, setF] = useState<FormState>(initial);
  const canEdit = useCan("object.edit");
  const creating = contest == null;
  const status = contest?.status ?? "DRAFT";
  const locked = isLocked(status);
  const readOnly = status === "ENDED" || status === "ARCHIVED";

  const editable = (k: keyof FormState) => canEdit && !readOnly && (!locked || LIVE_FIELDS.has(k)) && (k !== "code" || creating);
  const changed = new Set((Object.keys(f) as (keyof FormState)[]).filter((k) => f[k] !== initial[k]));
  const dirty = creating || changed.size > 0;
  const touchesInstants =
    !creating && !locked && contest?.instantsGeneratedAt != null && [...changed].some((k) => INSTANT_FIELDS.has(k));
  const set = <K extends keyof FormState>(k: K, v: FormState[K]) => setF((prev) => ({ ...prev, [k]: v }));

  return (
    <form
      className="space-y-4"
      onSubmit={(e) => {
        e.preventDefault();
        onSubmit(toInput(f, creating ? null : changed));
      }}
    >
      {locked && !readOnly ? (
        <p className="rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-900">
          Concorso {status}: inizio, meccanica, regole di gioco, regolamento, premi e istanti sono bloccati. Si modificano
          solo nome, descrizione e data di fine (gli istanti restano quelli generati); per il resto duplica il concorso.
        </p>
      ) : null}

      <Section title="Generale">
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Nome">
            <input required value={f.name} disabled={!editable("name")} onChange={(e) => set("name", e.target.value)} className={INPUT} />
          </Field>
          <Field label="Codice" hint={creating ? "Es. IW-PRIMAVERA. Non si cambia dopo la creazione." : undefined}>
            <input
              required
              value={f.code}
              disabled={!editable("code")}
              onChange={(e) => set("code", e.target.value.toUpperCase())}
              className={`${INPUT} font-mono`}
            />
          </Field>
          <Field label="Meccanica" hint="Solo la grafica del gioco nel portale: il risultato lo decidono gli istanti.">
            <select value={f.mechanic} disabled={!editable("mechanic")} onChange={(e) => set("mechanic", e.target.value)} className={INPUT}>
              {Object.entries(MECHANIC_LABEL).map(([v, l]) => (
                <option key={v} value={v}>
                  {l}
                </option>
              ))}
            </select>
          </Field>
        </div>
        <Field label="Descrizione">
          <textarea rows={2} value={f.description} disabled={!editable("description")} onChange={(e) => set("description", e.target.value)} className={INPUT} />
        </Field>
      </Section>

      <Section title="Periodo e istanti">
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Inizio">
            <input type="datetime-local" required value={f.startAt} disabled={!editable("startAt")} onChange={(e) => set("startAt", e.target.value)} className={INPUT} />
          </Field>
          <Field label="Fine">
            <input type="datetime-local" required value={f.endAt} disabled={!editable("endAt")} onChange={(e) => set("endAt", e.target.value)} className={INPUT} />
          </Field>
          <Field label="Distribuzione degli istanti">
            <select value={f.distribution} disabled={!editable("distribution")} onChange={(e) => set("distribution", e.target.value)} className={INPUT}>
              {Object.entries(DISTRIBUTION_LABEL).map(([v, l]) => (
                <option key={v} value={v}>
                  {l}
                </option>
              ))}
            </select>
          </Field>
          <Field label="Seme" hint={creating ? "Vuoto = derivato dal codice. Stesso seme e stessi parametri → stessi istanti." : "Stesso seme e stessi parametri → stessi istanti."}>
            <input type="number" min={0} value={f.seed} disabled={!editable("seed")} onChange={(e) => set("seed", e.target.value)} className={`${INPUT} font-mono`} />
          </Field>
        </div>
      </Section>

      <Section title="Regole di gioco">
        <div className="grid gap-3 sm:grid-cols-3">
          <Field group label="Giocata gratuita">
            <label className="inline-flex items-center gap-2 text-sm">
              <input type="checkbox" checked={f.freePlayDaily} disabled={!editable("freePlayDaily")} onChange={(e) => set("freePlayDaily", e.target.checked)} />
              Una al giorno per ogni membro
            </label>
          </Field>
          <Field label="Massimo giocate al giorno" hint="Vuoto = nessun limite oltre ai crediti.">
            <input type="number" min={1} value={f.maxPlaysPerMemberPerDay} disabled={!editable("maxPlaysPerMemberPerDay")} onChange={(e) => set("maxPlaysPerMemberPerDay", e.target.value)} className={INPUT} />
          </Field>
          <Field label="Massimo vincite per membro" hint="Vuoto = nessun limite.">
            <input type="number" min={1} value={f.maxWinsPerMember} disabled={!editable("maxWinsPerMember")} onChange={(e) => set("maxWinsPerMember", e.target.value)} className={INPUT} />
          </Field>
        </div>
      </Section>

      <Section title="Regolamento">
        <Field label="Testo mostrato nel portale">
          <textarea rows={5} value={f.rulesText} disabled={!editable("rulesText")} onChange={(e) => set("rulesText", e.target.value)} className={INPUT} />
        </Field>
      </Section>

      {canEdit && !readOnly ? (
        <div className="flex flex-wrap items-center gap-3">
          <button
            type="submit"
            disabled={saving || !dirty}
            className="rounded bg-[var(--color-bo-accent)] px-3 py-1.5 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50"
          >
            {creating ? "Crea concorso" : "Salva modifiche"}
          </button>
          {touchesInstants ? (
            <span className="text-xs text-amber-800">Salvando, gli istanti generati vengono cancellati: rigenerali prima di pubblicare.</span>
          ) : null}
          {error ? (
            <span className="text-xs text-red-700" role="alert">
              {error.detail || error.code}
            </span>
          ) : null}
        </div>
      ) : null}

      <p className="text-xs text-[var(--color-bo-ink-2)]">
        Nota normativa (docs/03 §7): un concorso a premi reale richiede regolamento depositato, garanzie sul montepremi,
        perizia sul software di assegnazione e verbali. Il PoC non li gestisce, ma seme riproducibile, istanti immutabili
        dall&apos;avvio, audit e ruolo LEGAL sono pensati per non ostacolarli.
      </p>
    </form>
  );
}
