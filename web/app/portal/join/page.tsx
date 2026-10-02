"use client";

import { Suspense, useState } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { lhFetch, LhError } from "@/lib/api/client";
import type { MemberView } from "@/lib/api/types";
import { joinErrorFromApi, normalizeCode, validateJoin, type JoinErrors, type JoinForm } from "@/lib/member/profile";
import { cn } from "@/lib/cn";
import { usePortalTheme } from "@/components/shared/ThemeContext";
import { usePortalApi } from "@/components/portal/MemberContext";
import { EnterpriseJoin } from "@/components/portal/EnterpriseJoin";
import { inputCls, JoinField, Preparing, waitForWallet } from "@/components/portal/joinParts";

// PT-08 Registrazione (docs/09 §PT-08, F-MBR-06, F-REF-01): nome, cognome, e-mail, codice amico opzionale (da ?ref=),
// consenso. Invio → member POST /v1/members → il nuovo membro diventa la persona attiva → PT-01 col benvenuto.
// Prima di aprire la Home si attende che il wallet esista (i 100 punti di benvenuto arrivano via campagna).
// Profilo enterprise (PT-16, F2-SEC-09, ADR-051): variante `EnterpriseJoin`, con nome ed e-mail dal token e senza memberId.
export default function JoinPage() {
  return (
    <Suspense fallback={null}>
      <JoinGate />
    </Suspense>
  );
}

function JoinGate() {
  return usePortalApi().enterprise ? <EnterpriseJoin /> : <JoinForm />;
}

function JoinForm() {
  const params = useSearchParams();
  const theme = usePortalTheme();
  const [form, setForm] = useState<JoinForm>({
    firstName: "",
    lastName: "",
    email: "",
    referralCode: normalizeCode(params.get("ref") ?? ""),
    terms: false,
    marketing: false,
  });
  const [errors, setErrors] = useState<JoinErrors>({});
  const [phase, setPhase] = useState<"form" | "sending" | "preparing">("form");

  const set = <K extends keyof JoinForm>(k: K, v: JoinForm[K]) => {
    setForm((f) => ({ ...f, [k]: v }));
    setErrors((e) => ({ ...e, [k]: undefined }));
  };

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    const found = validateJoin(form);
    setErrors(found);
    if (Object.keys(found).length > 0) return;
    setPhase("sending");
    let created: MemberView;
    try {
      created = await lhFetch<MemberView>("member", "/v1/members", {
        method: "POST",
        body: JSON.stringify({
          firstName: form.firstName.trim(),
          lastName: form.lastName.trim(),
          email: form.email.trim(),
          channel: "PORTAL",
          referralCode: normalizeCode(form.referralCode) || undefined,
          consents: { marketing: form.marketing, profiling: false },
        }),
      });
    } catch (err) {
      setPhase("form");
      setErrors(err instanceof LhError ? joinErrorFromApi(err.code, err.detail) : { firstName: "Iscrizione non riuscita, riprova" });
      return;
    }
    await fetch("/api/persona", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ kind: "MEMBER", memberId: created.id }),
    });
    setPhase("preparing");
    await waitForWallet(`/v1/portal/wallets/${created.id}`);
    window.location.href = "/portal?welcome=1";
  }

  if (phase === "preparing") return <Preparing />;

  return (
    <form onSubmit={submit} className="space-y-4" noValidate>
      <div>
        <h1 className="text-lg font-semibold text-[var(--color-pt-night)]">Entra nel {theme.programName}</h1>
        <p className="text-sm text-[var(--color-pt-night)]/70">Iscriversi è gratis: ricevi subito 100 punti di benvenuto.</p>
      </div>

      <div className="space-y-3 rounded-xl border border-[var(--color-bo-border)] bg-white p-4">
        <JoinField label="Nome" error={errors.firstName}>
          <input value={form.firstName} onChange={(e) => set("firstName", e.target.value)} autoComplete="given-name" className={inputCls(errors.firstName)} />
        </JoinField>
        <JoinField label="Cognome" error={errors.lastName}>
          <input value={form.lastName} onChange={(e) => set("lastName", e.target.value)} autoComplete="family-name" className={inputCls(errors.lastName)} />
        </JoinField>
        <JoinField label="E-mail" error={errors.email}>
          <input type="email" value={form.email} onChange={(e) => set("email", e.target.value)} autoComplete="email" className={inputCls(errors.email)} />
        </JoinField>
        <JoinField label="Codice amico (facoltativo)" error={errors.referralCode} hint="Se un amico ti ha invitato, al tuo primo acquisto ricevete un premio entrambi.">
          <input
            value={form.referralCode}
            onChange={(e) => set("referralCode", e.target.value.toUpperCase())}
            maxLength={12}
            className={cn(inputCls(errors.referralCode), "font-mono tracking-widest")}
          />
        </JoinField>
        <label className="flex items-start gap-2 text-sm text-[var(--color-pt-night)]">
          <input type="checkbox" checked={form.terms} onChange={(e) => set("terms", e.target.checked)} className="mt-1" />
          <span>Accetto il regolamento del Club e l&apos;informativa sulla privacy</span>
        </label>
        {errors.terms ? <p className="text-xs text-red-700">{errors.terms}</p> : null}
        <label className="flex items-start gap-2 text-sm text-[var(--color-pt-night)]/80">
          <input type="checkbox" checked={form.marketing} onChange={(e) => set("marketing", e.target.checked)} className="mt-1" />
          <span>Voglio ricevere offerte e novità (facoltativo)</span>
        </label>
      </div>

      <button
        type="submit"
        disabled={phase === "sending"}
        className="w-full rounded-xl bg-[var(--color-pt-primary)] py-3 text-sm font-semibold text-white disabled:opacity-60"
      >
        {phase === "sending" ? "Iscrizione in corso…" : "Iscriviti"}
      </button>
      <p className="text-center text-xs text-[var(--color-pt-night)]/50">
        Sei già iscritto? <Link href="/portal" className="underline">Torna alla Home</Link>
      </p>
    </form>
  );
}
