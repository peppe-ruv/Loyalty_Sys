"use client";

import { useState } from "react";
import { useLhMutation, useLhQuery } from "@/lib/api/client";
import type { PortalCampaign, PortalProfile, ProfileField } from "@/lib/api/types";
import { usePending } from "@/components/portal/PendingContext";
import { QueryState } from "@/components/shared/QueryState";
import { completenessPct, missingFieldsSentence } from "@/lib/member/profile";
import { cn } from "@/lib/cn";

// PT-08 "I tuoi dati" (docs/09 §PT-08, F-MBR-07): dati personali e consensi modificabili, indicatore di completezza coi
// campi mancanti e il premio previsto (valore reale di CMP-PROFILE). Al salvataggio dell'ultimo campo il member-service
// emette member.profile.completed: il portale mostra "+150 punti in arrivo…".
const PROFILE_CAMPAIGN = "CMP-PROFILE";

type Draft = Record<ProfileField, string> & { marketing: boolean; profiling: boolean };

const FIELDS: { key: ProfileField; label: string; type?: string; autoComplete?: string }[] = [
  { key: "firstName", label: "Nome", autoComplete: "given-name" },
  { key: "lastName", label: "Cognome", autoComplete: "family-name" },
  { key: "email", label: "E-mail", type: "email", autoComplete: "email" },
  { key: "phone", label: "Telefono", type: "tel", autoComplete: "tel" },
  { key: "birthDate", label: "Data di nascita", type: "date", autoComplete: "bday" },
  { key: "city", label: "Città", autoComplete: "address-level2" },
];

export function ProfileSection({ memberId }: { memberId: string }) {
  const profile = useLhQuery<PortalProfile>("member", `/v1/portal/members/${memberId}`);
  // Il messaggio vive qui: il form si rimonta a ogni nuova versione del profilo (dopo il salvataggio).
  const [message, setMessage] = useState<string | null>(null);
  return (
    <section>
      <h2 className="mb-2 text-sm font-semibold text-[var(--color-pt-night)]">I tuoi dati</h2>
      <QueryState query={profile} service="member">
        {(p) => <ProfileForm key={p.version} memberId={memberId} profile={p} message={message} onMessage={setMessage} />}
      </QueryState>
    </section>
  );
}

function ProfileForm({
  memberId,
  profile,
  message,
  onMessage: setMessage,
}: {
  memberId: string;
  profile: PortalProfile;
  message: string | null;
  onMessage: (m: string | null) => void;
}) {
  const { markPending } = usePending();
  const campaigns = useLhQuery<PortalCampaign[]>("campaign", "/v1/portal/campaigns", { memberId, codes: PROFILE_CAMPAIGN }, {
    enabled: !profile.completeness.completed,
  });
  const reward = campaigns.data?.[0]?.rewardSummary;
  const [draft, setDraft] = useState<Draft>(() => toDraft(profile));
  const [editing, setEditing] = useState(false);

  const save = useLhMutation<PortalProfile, Record<string, unknown>>("member", "PATCH", () => `/v1/portal/members/${memberId}`, {
    onSuccess: (saved) => {
      setEditing(false);
      if (!profile.completeness.completed && saved.completeness.completed) {
        markPending([`${reward ?? "Premio"} in arrivo per il profilo completo…`]);
        setMessage("Profilo completo, grazie!");
      } else {
        setMessage("Dati salvati");
      }
    },
  });

  function submit(e: React.FormEvent) {
    e.preventDefault();
    const body: Record<string, unknown> = { version: profile.version, consents: { marketing: draft.marketing, profiling: draft.profiling } };
    for (const f of FIELDS) {
      const value = draft[f.key].trim();
      const before = profile[f.key] ?? "";
      if (value !== before && value !== "") body[f.key] = value;
    }
    save.mutate(body);
  }

  const { completed, missingFields } = profile.completeness;
  const pct = completenessPct(missingFields);

  return (
    <div className="space-y-3">
      {!completed ? (
        <div className="rounded-xl bg-[var(--color-pt-coin)]/20 p-3 text-sm text-[var(--color-pt-night)]">
          <p className="font-medium">Completa il profilo{reward ? `: ${reward}` : ""}</p>
          <p className="text-xs text-[var(--color-pt-night)]/70">{missingFieldsSentence(missingFields)}</p>
          <div className="mt-2 h-1.5 overflow-hidden rounded bg-white/70" aria-label={`profilo completo al ${pct}%`}>
            <div className="h-full bg-[var(--color-pt-coin)]" style={{ width: `${pct}%` }} />
          </div>
        </div>
      ) : null}

      <form onSubmit={submit} className="rounded-xl border border-[var(--color-bo-border)] bg-white p-3 text-sm">
        {FIELDS.map((f) => {
          const missing = missingFields.includes(f.key);
          return (
            <label key={f.key} className="flex items-center justify-between gap-3 border-b border-slate-100 py-1.5 last:border-0">
              <span className={cn("shrink-0 text-[var(--color-pt-night)]/60", missing && "font-medium text-[var(--color-pt-night)]")}>
                {f.label}
                {missing ? <span className="ml-1 text-[var(--color-pt-coin)]">●</span> : null}
              </span>
              {editing ? (
                <input
                  type={f.type ?? "text"}
                  value={draft[f.key]}
                  autoComplete={f.autoComplete}
                  onChange={(e) => setDraft((d) => ({ ...d, [f.key]: e.target.value }))}
                  className="w-44 rounded-md border border-[var(--color-bo-border)] px-2 py-1 text-right text-sm"
                />
              ) : (
                <span className="truncate text-right text-[var(--color-pt-night)]">{display(f.key, profile[f.key])}</span>
              )}
            </label>
          );
        })}
        <div className="mt-2 space-y-1 border-t border-slate-100 pt-2">
          <Consent label="Offerte e novità" checked={draft.marketing} disabled={!editing} onChange={(v) => setDraft((d) => ({ ...d, marketing: v }))} />
          <Consent label="Offerte su misura (profilazione)" checked={draft.profiling} disabled={!editing} onChange={(v) => setDraft((d) => ({ ...d, profiling: v }))} />
        </div>
        {save.isError ? (
          <p className="mt-2 text-xs text-red-700">
            {save.error.code === "VERSION_CONFLICT" ? "I dati sono cambiati nel frattempo: ricarica la pagina." : save.error.code === "EMAIL_TAKEN" ? "Questa e-mail è già usata da un altro iscritto." : save.error.detail}
          </p>
        ) : null}
        <div className="mt-3 flex items-center justify-end gap-2">
          {message && !editing ? <span className="mr-auto text-xs text-emerald-700">{message}</span> : null}
          {editing ? (
            <>
              <button type="button" onClick={() => { setDraft(toDraft(profile)); setEditing(false); }} className="rounded-full px-3 py-1.5 text-xs text-[var(--color-pt-night)]/70">
                Annulla
              </button>
              <button type="submit" disabled={save.isPending} className="rounded-full bg-[var(--color-pt-primary)] px-4 py-1.5 text-xs font-semibold text-white disabled:opacity-60">
                {save.isPending ? "Salvataggio…" : "Salva"}
              </button>
            </>
          ) : (
            <button type="button" onClick={() => { setMessage(null); setEditing(true); }} className="rounded-full border border-[var(--color-pt-primary)] px-4 py-1.5 text-xs font-semibold text-[var(--color-pt-primary)]">
              Modifica
            </button>
          )}
        </div>
      </form>
    </div>
  );
}

function Consent({ label, checked, disabled, onChange }: { label: string; checked: boolean; disabled: boolean; onChange: (v: boolean) => void }) {
  return (
    <label className="flex items-center justify-between text-xs text-[var(--color-pt-night)]/80">
      {label}
      <input type="checkbox" checked={checked} disabled={disabled} onChange={(e) => onChange(e.target.checked)} />
    </label>
  );
}

function toDraft(p: PortalProfile): Draft {
  return {
    firstName: p.firstName ?? "",
    lastName: p.lastName ?? "",
    email: p.email ?? "",
    phone: p.phone ?? "",
    birthDate: p.birthDate ?? "",
    city: p.city ?? "",
    marketing: p.consents.marketing,
    profiling: p.consents.profiling,
  };
}

function display(field: ProfileField, value: string | null): string {
  if (!value) return "—";
  if (field === "birthDate") return new Date(value + "T00:00:00").toLocaleDateString("it-IT");
  return value;
}
