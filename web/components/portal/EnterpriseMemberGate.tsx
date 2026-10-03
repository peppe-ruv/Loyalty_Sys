"use client";

import { createContext, useContext, useEffect } from "react";
import { usePathname, useRouter } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { LhError, lhFetch } from "@/lib/api/client";
import type { PortalProfile } from "@/lib/api/types";
import { DegradedBox } from "@/components/shared/QueryState";
import { errorCodeOf, InlineErrorCode } from "@/components/shared/ErrorNotice";
import { it } from "@/lib/i18n/it";
import { MEMBER_NOT_REGISTERED } from "@/lib/portal/memberApi";
import { MemberProvider } from "./MemberContext";

// Ingresso del portale nel profilo enterprise (PT-08, PT-16, F2-SEC-09, ADR-048, ADR-051; regole 6-bis e 18).
// Chiede `GET /v1/portal/me/profile`: il membro è quello del token, mai scelto dal browser.
// - 200: l'id restituito alimenta il contesto (solo per mostrare, non si rimanda mai ai servizi);
//   su /portal/join un membro già iscritto va alla Home.
// - 404 MEMBER_NOT_REGISTERED: l'account esiste ma non ha un membro → /portal/join (registrazione, PT-16).
// - altro errore: riquadro d'errore con «Riprova» (docs/07 §6); 401 lo gestisce già app/providers.tsx (login).
// Nome ed e-mail dell'account (claim dell'ID token, letti dal server: mai i token) arrivano come prop e li legge
// solo la pagina di registrazione.
// SPEC-GAP: Q-557.

export const JOIN_PATH = "/portal/join";
export const ME_PROFILE_PATH = "/v1/portal/me/profile";

/** Dati dell'account di accesso (claim dell'ID token validato lato server). `null` = claim non comunicato. */
export interface AccountIdentity {
  givenName: string | null;
  familyName: string | null;
  name: string | null;
  email: string | null;
}

const AccountContext = createContext<AccountIdentity>({ givenName: null, familyName: null, name: null, email: null });

/** Dati dell'account per precompilare la registrazione (enterprise). */
export function useAccountIdentity(): AccountIdentity {
  return useContext(AccountContext);
}

export function EnterpriseMemberGate({ account, children }: { account: AccountIdentity; children: React.ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  // Stessa chiave di useLhQuery("member", ME_PROFILE_PATH): la Home e il profilo riusano la risposta già in cache.
  // L'id restituito serve solo a mostrare (tessera): non si rimanda mai ai servizi.
  const me = useQuery<PortalProfile, LhError>({
    queryKey: ["member", ME_PROFILE_PATH, {}],
    queryFn: () => lhFetch<PortalProfile>("member", ME_PROFILE_PATH),
    // 404, 401 e servizio addormentato non si riprovano qui (il degraded riprova da solo); un altro errore una volta sola.
    retry: (failures, error) => !(error instanceof LhError && (error.status === 404 || error.unauthenticated || error.asleep)) && failures < 1,
  });

  const onJoin = pathname === JOIN_PATH;
  // Un dato già noto vale più di un errore di un aggiornamento in secondo piano (es. dopo il salvataggio del profilo,
  // che invalida ["member"]): il portale resta in piedi, gli stati d'errore compaiono solo senza dato.
  const data = me.data;
  const unregistered = !data && me.error instanceof LhError && me.error.status === 404 && me.error.code === MEMBER_NOT_REGISTERED;
  const registered = data !== undefined;

  useEffect(() => {
    if (unregistered && !onJoin) router.replace(JOIN_PATH);
    if (registered && onJoin) router.replace("/portal");
  }, [unregistered, registered, onJoin, router]);

  if (me.isLoading) return <GateMessage text={it.portalMember.loading} />;

  if (unregistered) {
    // Sulla registrazione le viste dei servizi restano ferme (nessun membro): altrove si sta andando lì.
    if (!onJoin) return <GateMessage text={it.portalMember.toJoin} />;
    return (
      <AccountContext.Provider value={account}>
        <MemberProvider memberId="" enterprise unregistered>
          {children}
        </MemberProvider>
      </AccountContext.Provider>
    );
  }

  if (!data && me.isError && me.error.asleep) {
    // Degraded (docs/07 §6): member-service dorme sul piano gratuito; riprova ogni 5 s.
    return (
      <div className="mx-auto max-w-md p-6">
        <DegradedBox service="member" onRetry={() => void me.refetch()} autoRetry />
      </div>
    );
  }

  if (!data && me.isError) {
    return (
      <div role="alert" className="mx-auto max-w-md space-y-2 p-6 text-center text-sm text-red-800">
        <p className="font-medium">{it.portalMember.errorTitle}</p>
        <p className="text-xs">{it.errorCode.portalHint}</p>
        <button type="button" onClick={() => void me.refetch()} className="rounded border border-red-300 px-3 py-1.5 text-xs hover:bg-red-50">
          {it.portalMember.retry}
        </button>
        <InlineErrorCode code={errorCodeOf(me.error)} className="text-xs opacity-80" />
      </div>
    );
  }

  if (!data) return null;
  if (onJoin) return <GateMessage text={it.portalMember.toHome} />;

  return (
    <AccountContext.Provider value={account}>
      <MemberProvider memberId={data.memberId} enterprise>
        {children}
      </MemberProvider>
    </AccountContext.Provider>
  );
}

function GateMessage({ text }: { text: string }) {
  return (
    <div className="mx-auto flex min-h-dvh max-w-md flex-col items-center justify-center gap-3 p-6 text-center" aria-busy="true">
      <div className="size-10 animate-spin rounded-full border-4 border-slate-200 border-t-slate-500" aria-hidden />
      <p role="status" className="text-sm text-slate-600">
        {text}
      </p>
    </div>
  );
}
