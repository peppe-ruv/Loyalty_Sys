"use client";

import { useState } from "react";
import { useSearchParams } from "next/navigation";
import { lhFetch, LhError } from "@/lib/api/client";
import { joinErrorFromApi, normalizeCode, validateJoin, type JoinErrors } from "@/lib/member/profile";
import { accountNames } from "@/lib/portal/account";
import { cn } from "@/lib/cn";
import { it } from "@/lib/i18n/it";
import { useAccountIdentity } from "./EnterpriseMemberGate";
import { inputCls, JoinField, Preparing, waitForWallet } from "./joinParts";

// PT-16 Registrazione da zero in enterprise (docs/09 §PT-16, F2-SEC-09, ADR-048, ADR-051, Q-673; regole 6-bis, 18, 20).
// L'account esiste già nel realm dei membri ma non ha un membro (404 MEMBER_NOT_REGISTERED → EnterpriseMemberGate porta
// qui). Nome ed e-mail vengono dall'ID token (letti lato server, sola lettura: nessun campo per scegliere un altro
// membro); si aggiungono codice amico facoltativo (da ?ref=), termini obbligatori e marketing facoltativo.
// `POST /v1/portal/members` NON porta memberId, canale né stato (PortalRegistrationRequest): il membro nasce dal `sub`
// del token. Poi l'attesa del wallet da `/v1/portal/me/wallet` e la Home col benvenuto. Nessun cookie persona.
// SPEC-GAP: Q-557 (l'e-mail non è verificata: arriva dall'account, non da un controllo del programma).
export function EnterpriseJoin() {
  const params = useSearchParams();
  const account = useAccountIdentity();
  const { firstName, lastName } = accountNames(account);
  const email = (account.email ?? "").trim();
  const [referralCode, setReferralCode] = useState(normalizeCode(params.get("ref") ?? ""));
  const [terms, setTerms] = useState(false);
  const [marketing, setMarketing] = useState(false);
  const [errors, setErrors] = useState<JoinErrors>({});
  const [phase, setPhase] = useState<"form" | "sending" | "preparing">("form");
  // Senza nome, cognome o e-mail dall'account non c'è nulla da inviare: lo si dice (Degraded) e l'invio resta spento.
  const missingAccount = !firstName || !lastName || !email;

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    const found = validateJoin({ firstName, lastName, email, referralCode, terms, marketing });
    setErrors(found);
    if (Object.keys(found).length > 0) return;
    setPhase("sending");
    try {
      await lhFetch("member", "/v1/portal/members", {
        method: "POST",
        body: JSON.stringify({
          firstName,
          lastName,
          email,
          referralCode: normalizeCode(referralCode) || undefined,
          consents: { marketing, profiling: false },
        }),
      });
    } catch (err) {
      setPhase("form");
      setErrors(err instanceof LhError ? joinErrorFromApi(err.code, err.detail) : { firstName: "Iscrizione non riuscita, riprova" });
      return;
    }
    setPhase("preparing");
    await waitForWallet("/v1/portal/me/wallet");
    window.location.href = "/portal?welcome=1";
  }

  if (phase === "preparing") return <Preparing />;

  // Gli errori su nome o e-mail (dell'account, non modificabili qui) stanno in un riquadro in testa.
  const general = errors.firstName ?? errors.lastName ?? errors.email;
  const pt = it.portalMember;
  return (
    <form onSubmit={submit} className="space-y-4" noValidate data-testid="join-enterprise">
      <div>
        <h1 className="text-lg font-semibold text-[var(--color-pt-night)]">{pt.joinTitle(firstName)}</h1>
        <p className="text-sm text-[var(--color-pt-night)]/70">{pt.joinLead}</p>
      </div>
      {missingAccount ? (
        <p role="alert" className="rounded-xl bg-amber-100 p-3 text-sm text-amber-900">
          {pt.unavailable}
        </p>
      ) : null}
      {general ? (
        <p role="alert" className="rounded-xl bg-red-50 p-3 text-sm text-red-800">
          {general}
        </p>
      ) : null}

      <div className="space-y-3 rounded-xl border border-[var(--color-bo-border)] bg-white p-4">
        <JoinField label={pt.fullName} hint={pt.fromAccount}>
          <input value={`${firstName} ${lastName}`.trim()} readOnly autoComplete="name" className={cn(inputCls(), "bg-slate-50")} />
        </JoinField>
        <JoinField label={pt.email} hint={pt.fromAccount}>
          <input type="email" value={email} readOnly autoComplete="email" className={cn(inputCls(), "bg-slate-50")} />
        </JoinField>
        <JoinField label="Codice amico (facoltativo)" error={errors.referralCode} hint="Se un amico ti ha invitato, al tuo primo acquisto ricevete un premio entrambi.">
          <input
            value={referralCode}
            onChange={(e) => {
              setReferralCode(e.target.value.toUpperCase());
              setErrors((x) => ({ ...x, referralCode: undefined }));
            }}
            maxLength={12}
            className={cn(inputCls(errors.referralCode), "font-mono tracking-widest")}
          />
        </JoinField>
        <label className="flex items-start gap-2 text-sm text-[var(--color-pt-night)]">
          <input
            type="checkbox"
            checked={terms}
            onChange={(e) => {
              setTerms(e.target.checked);
              setErrors((x) => ({ ...x, terms: undefined }));
            }}
            className="mt-1"
          />
          <span>Accetto il regolamento e l&apos;informativa privacy</span>
        </label>
        {errors.terms ? <p className="text-xs text-red-700">{errors.terms}</p> : null}
        <label className="flex items-start gap-2 text-sm text-[var(--color-pt-night)]/80">
          <input type="checkbox" checked={marketing} onChange={(e) => setMarketing(e.target.checked)} className="mt-1" />
          <span>Voglio ricevere offerte e novità (facoltativo)</span>
        </label>
      </div>

      <button
        type="submit"
        disabled={phase === "sending" || missingAccount}
        className="w-full rounded-xl bg-[var(--color-pt-primary)] py-3 text-sm font-semibold text-white disabled:opacity-60"
      >
        {phase === "sending" ? "Iscrizione in corso…" : "Iscriviti"}
      </button>
    </form>
  );
}
