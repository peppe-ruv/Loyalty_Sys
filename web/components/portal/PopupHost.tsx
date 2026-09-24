"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { lhFetch } from "@/lib/api/client";
import type { PopupView } from "@/lib/content/types";
import { isExternal } from "@/lib/content/links";
import { PopupModal } from "@/components/shared/content/PopupModal";
import { useActiveMember } from "./MemberContext";

// Pop-up all'ingresso in PT-01 (docs/09 §1): al più uno per visita (una visita = la sessione del browser per quel
// membro). La vista si registra alla chiusura o al tocco sulla CTA (`POST …/seen`), non alla lettura: se qualcosa va
// storto prima, il pop-up non è consumato (docs/servizi/engagement-service.md §5). Servizio addormentato → niente pop-up.
const visitKey = (memberId: string) => `lh_popup_visit_${memberId}`;

export function PopupHost() {
  const memberId = useActiveMember();
  const router = useRouter();
  const [popup, setPopup] = useState<PopupView | null>(null);

  useEffect(() => {
    let alreadyThisVisit = false;
    try {
      alreadyThisVisit = sessionStorage.getItem(visitKey(memberId)) === "1";
    } catch {
      // storage non disponibile: si tenta comunque
    }
    if (alreadyThisVisit) return;
    let cancelled = false;
    lhFetch<PopupView | undefined>("engagement", "/v1/portal/popups/next", { query: { memberId } })
      .then((p) => {
        if (cancelled) return;
        try {
          sessionStorage.setItem(visitKey(memberId), "1");
        } catch {
          // ignorato
        }
        if (p && p.id) setPopup(p);
      })
      .catch(() => {
        // engagement dorme o risponde male: la visita prosegue senza pop-up
      });
    return () => {
      cancelled = true;
    };
  }, [memberId]);

  if (!popup) return null;

  const record = (dismissed: boolean) =>
    lhFetch("engagement", `/v1/portal/popups/${popup.id}/seen`, {
      method: "POST",
      body: JSON.stringify({ memberId, dismissed }),
    }).catch(() => undefined);

  return (
    <PopupModal
      content={popup}
      dismissible={popup.dismissible}
      onClose={() => {
        setPopup(null);
        void record(true);
      }}
      onCta={(href) => {
        setPopup(null);
        void record(false).then(() => {
          if (isExternal(href)) window.open(href, "_blank", "noopener,noreferrer");
          else router.push(href);
        });
      }}
    />
  );
}
