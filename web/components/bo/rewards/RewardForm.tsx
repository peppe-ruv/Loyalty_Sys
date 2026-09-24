"use client";

import { useState } from "react";
import type { LhError } from "@/lib/api/client";
import type { CouponPool, Reward, RewardBand, RewardCategory, Tier } from "@/lib/api/types";
import { Field, INPUT, Section } from "@/components/bo/FormBits";
import { useCan } from "@/components/bo/Can";
import { FULFILMENT_LABEL, TYPE_LABEL } from "./RewardBits";
import { formatPoints } from "@/lib/format/points";

// Editor premio (docs/08 §BO-10): generale · fascia (costo in sola lettura) · disponibilità · visibilità · evasione.
// Modificabile in DRAFT/REJECTED/PAUSED; in LIVE solo stock totale, fine validità e immagine (docs/servizi/
// reward-service.md §3, 409 REWARD_LIVE_LOCKED); negli altri stati in sola lettura.

export interface RewardInput {
  code?: string;
  name?: string;
  description?: string | null;
  terms?: string | null;
  imageUrl?: string | null;
  type?: string;
  category?: string | null;
  band?: string;
  fulfilment?: string;
  couponPoolId?: string | null;
  stockTotal?: number | null;
  perMemberLimit?: number | null;
  eligibleTiers?: string[];
  eligibleSegments?: string[];
  validFrom?: string | null;
  validTo?: string | null;
}

interface FormState {
  code: string;
  name: string;
  description: string;
  terms: string;
  imageUrl: string;
  type: string;
  category: string;
  band: string;
  fulfilment: string;
  couponPoolId: string;
  stockTotal: string;
  perMemberLimit: string;
  eligibleTiers: string[];
  eligibleSegments: string;
  validFrom: string;
  validTo: string;
}

const LIVE_FIELDS = new Set<keyof FormState>(["stockTotal", "validTo", "imageUrl"]);
const EDITABLE = new Set(["DRAFT", "REJECTED", "PAUSED"]);

function fromReward(r: Reward | null, bands: RewardBand[]): FormState {
  return {
    code: r?.code ?? "",
    name: r?.name ?? "",
    description: r?.description ?? "",
    terms: r?.terms ?? "",
    imageUrl: r?.imageUrl ?? "",
    type: r?.type ?? "PHYSICAL",
    category: r?.categoryCode ?? "",
    band: r?.bandCode ?? bands[0]?.code ?? "",
    fulfilment: r?.fulfilment ?? "MANUAL",
    couponPoolId: r?.couponPoolId ?? "",
    stockTotal: r?.stockTotal == null ? "" : String(r.stockTotal),
    perMemberLimit: r?.perMemberLimit == null ? "" : String(r.perMemberLimit),
    eligibleTiers: r?.eligibleTiers ?? [],
    eligibleSegments: (r?.eligibleSegments ?? []).join(", "),
    validFrom: toDateInput(r?.validFrom),
    validTo: toDateInput(r?.validTo),
  };
}

/** ISO → "YYYY-MM-DD" nel fuso del browser (il backoffice lavora in Europe/Rome). */
export function toDateInput(iso: string | null | undefined): string {
  if (!iso) return "";
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

/** "YYYY-MM-DD" → ISO: inizio giornata per l'apertura, fine giornata per la chiusura. */
export function fromDateInput(value: string, endOfDay: boolean): string | null {
  if (!value) return null;
  const [y, m, d] = value.split("-").map(Number);
  const date = endOfDay ? new Date(y, m - 1, d, 23, 59, 59) : new Date(y, m - 1, d, 0, 0, 0);
  return date.toISOString();
}

function toInput(f: FormState): RewardInput {
  const num = (s: string) => (s.trim() === "" ? null : Number(s));
  return {
    code: f.code.trim(),
    name: f.name.trim(),
    description: f.description.trim() || null,
    terms: f.terms.trim() || null,
    imageUrl: f.imageUrl.trim() || null,
    type: f.type,
    category: f.category || null,
    band: f.band,
    fulfilment: f.fulfilment,
    couponPoolId: f.couponPoolId.trim() || null,
    stockTotal: num(f.stockTotal),
    perMemberLimit: num(f.perMemberLimit),
    eligibleTiers: f.eligibleTiers,
    eligibleSegments: f.eligibleSegments
      .split(",")
      .map((s) => s.trim())
      .filter(Boolean),
    validFrom: fromDateInput(f.validFrom, false),
    validTo: fromDateInput(f.validTo, true),
  };
}

/**
 * Solo i campi cambiati rispetto al premio salvato: il backend tratta i campi assenti come invariati, così una
 * modifica su un premio LIVE non tocca i campi bloccati.
 */
export function changedFields(before: RewardInput, after: RewardInput): RewardInput {
  const out: Record<string, unknown> = {};
  for (const key of Object.keys(after) as (keyof RewardInput)[]) {
    if (key === "code") continue;
    if (JSON.stringify(before[key] ?? null) !== JSON.stringify(after[key] ?? null)) {
      out[key] = after[key];
    }
  }
  return out as RewardInput;
}

export function RewardForm({
  reward,
  bands,
  categories,
  tiers,
  pools,
  saving,
  error,
  onSubmit,
}: {
  reward: Reward | null;
  bands: RewardBand[];
  categories: RewardCategory[];
  tiers: Tier[];
  pools: CouponPool[];
  saving: boolean;
  error: LhError | null;
  onSubmit: (input: RewardInput, changed: RewardInput) => void;
}) {
  const initial = fromReward(reward, bands);
  const [f, setF] = useState<FormState>(initial);
  const canEdit = useCan("object.edit");
  const status = reward?.status ?? "DRAFT";
  const creating = reward == null;
  const live = status === "LIVE";

  const editable = (field: keyof FormState) =>
    canEdit && (creating || EDITABLE.has(status) || (live && LIVE_FIELDS.has(field)));
  const set = <K extends keyof FormState>(k: K, v: FormState[K]) => setF((prev) => ({ ...prev, [k]: v }));
  const band = bands.find((b) => b.code === f.band);
  const changed = changedFields(toInput(initial), toInput(f));
  const dirty = creating || Object.keys(changed).length > 0;
  const anyEditable = canEdit && (creating || EDITABLE.has(status) || live);

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        onSubmit(toInput(f), changed);
      }}
      className="space-y-4"
    >
      {live ? (
        <p className="rounded border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-900">
          Premio LIVE: si modificano solo stock totale, fine validità e immagine. Per il resto mettilo in pausa o duplicalo.
        </p>
      ) : !creating && !EDITABLE.has(status) ? (
        <p className="rounded border border-[var(--color-bo-border)] bg-[var(--color-bo-bg)] px-3 py-2 text-xs text-[var(--color-bo-ink-2)]">
          Un premio {status} è in sola lettura.
        </p>
      ) : null}

      <Section title="Generale">
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Nome">
            <input required value={f.name} disabled={!editable("name")} onChange={(e) => set("name", e.target.value)} className={INPUT} />
          </Field>
          <Field label="Codice" hint={creating ? "Es. RWD-TAZZA. Non si cambia dopo la creazione." : undefined}>
            <input
              required
              value={f.code}
              disabled={!creating || !canEdit}
              onChange={(e) => set("code", e.target.value.toUpperCase())}
              className={`${INPUT} font-mono`}
            />
          </Field>
          <Field label="Tipo">
            <select value={f.type} disabled={!editable("type")} onChange={(e) => set("type", e.target.value)} className={INPUT}>
              {Object.entries(TYPE_LABEL).map(([v, l]) => (
                <option key={v} value={v}>
                  {l}
                </option>
              ))}
            </select>
          </Field>
          <Field label="Categoria">
            <select value={f.category} disabled={!editable("category")} onChange={(e) => set("category", e.target.value)} className={INPUT}>
              <option value="">—</option>
              {categories.map((c) => (
                <option key={c.code} value={c.code}>
                  {c.name}
                </option>
              ))}
            </select>
          </Field>
          <Field label="Immagine" hint="Percorso in public/demo/ o URL">
            <input value={f.imageUrl} disabled={!editable("imageUrl")} onChange={(e) => set("imageUrl", e.target.value)} className={INPUT} />
          </Field>
        </div>
        <Field label="Descrizione">
          <textarea rows={2} value={f.description} disabled={!editable("description")} onChange={(e) => set("description", e.target.value)} className={INPUT} />
        </Field>
        <Field label="Termini e condizioni">
          <textarea rows={2} value={f.terms} disabled={!editable("terms")} onChange={(e) => set("terms", e.target.value)} className={INPUT} />
        </Field>
      </Section>

      <Section title="Fascia">
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Fascia">
            <select value={f.band} disabled={!editable("band")} onChange={(e) => set("band", e.target.value)} className={INPUT}>
              {bands.map((b) => (
                <option key={b.code} value={b.code}>
                  {b.code} · {b.name}
                </option>
              ))}
            </select>
          </Field>
          <Field label="Costo" hint="È la soglia della fascia: si cambia in «Fasce».">
            <input readOnly disabled value={band ? `${formatPoints(band.pointsThreshold)} PTS` : "—"} className={`${INPUT} tabular-nums`} />
          </Field>
        </div>
      </Section>

      <Section title="Disponibilità">
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Stock totale" hint={reward ? `Residuo attuale: ${reward.stockRemaining ?? "illimitato"}. Vuoto = illimitato.` : "Vuoto = illimitato."}>
            <input type="number" min={0} value={f.stockTotal} disabled={!editable("stockTotal")} onChange={(e) => set("stockTotal", e.target.value)} className={INPUT} />
          </Field>
          <Field label="Limite per membro" hint="Vuoto = nessun limite.">
            <input type="number" min={1} value={f.perMemberLimit} disabled={!editable("perMemberLimit")} onChange={(e) => set("perMemberLimit", e.target.value)} className={INPUT} />
          </Field>
          <Field label="Valido dal">
            <input type="date" value={f.validFrom} disabled={!editable("validFrom")} onChange={(e) => set("validFrom", e.target.value)} className={INPUT} />
          </Field>
          <Field label="Valido fino al">
            <input type="date" value={f.validTo} disabled={!editable("validTo")} onChange={(e) => set("validTo", e.target.value)} className={INPUT} />
          </Field>
        </div>
      </Section>

      <Section title="Visibilità">
        <Field group label="Livelli ammessi" hint="Nessuno selezionato = tutti i livelli. Gli altri lo vedono col lucchetto.">
          <div className="flex flex-wrap gap-3">
            {tiers.map((t) => (
              <label key={t.code} className="inline-flex items-center gap-1.5 text-sm">
                <input
                  type="checkbox"
                  checked={f.eligibleTiers.includes(t.code)}
                  disabled={!editable("eligibleTiers")}
                  onChange={(e) =>
                    set(
                      "eligibleTiers",
                      e.target.checked ? [...f.eligibleTiers, t.code] : f.eligibleTiers.filter((c) => c !== t.code),
                    )
                  }
                />
                {t.name}
              </label>
            ))}
          </div>
        </Field>
        <Field label="Segmenti ammessi" hint="Codici separati da virgola; vuoto = tutti. Chi è fuori segmento non vede il premio.">
          <input value={f.eligibleSegments} disabled={!editable("eligibleSegments")} onChange={(e) => set("eligibleSegments", e.target.value)} className={`${INPUT} font-mono`} />
        </Field>
      </Section>

      <Section title="Evasione">
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Modalità">
            <select value={f.fulfilment} disabled={!editable("fulfilment")} onChange={(e) => set("fulfilment", e.target.value)} className={INPUT}>
              {Object.entries(FULFILMENT_LABEL).map(([v, l]) => (
                <option key={v} value={v}>
                  {l}
                </option>
              ))}
            </select>
          </Field>
          {f.fulfilment === "AUTO_COUPON" ? (
            <Field label="Pool coupon" hint="Obbligatorio per l'evasione automatica; i codici si gestiscono in «Coupon».">
              <select value={f.couponPoolId} disabled={!editable("couponPoolId")} onChange={(e) => set("couponPoolId", e.target.value)} className={INPUT}>
                <option value="">—</option>
                {pools.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.name} · {p.prefix} · {p.counts.AVAILABLE} disponibili
                  </option>
                ))}
              </select>
            </Field>
          ) : null}
        </div>
      </Section>

      {anyEditable ? (
        <div className="flex items-center gap-3">
          <button
            type="submit"
            disabled={saving || !dirty}
            className="rounded bg-[var(--color-bo-accent)] px-3 py-1.5 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50"
          >
            {creating ? "Crea premio" : "Salva modifiche"}
          </button>
          {error ? (
            <span className="text-xs text-red-700" role="alert">
              {error.detail || error.code}
            </span>
          ) : null}
        </div>
      ) : null}
    </form>
  );
}
