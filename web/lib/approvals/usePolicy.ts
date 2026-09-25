"use client";

import { useLhQuery } from "@/lib/api/client";
import type { ServiceCode } from "@/lib/api/services";
import type { ApprovalItem, PolicyView } from "./types";

/** Policy delle approvazioni (docs/06 §7), letta da campaign: è la stessa configurazione in ogni servizio proprietario. */
export function useApprovalPolicy() {
  return useLhQuery<PolicyView>("campaign", "/v1/approvals/policy");
}

/**
 * Voce della coda approvazioni per un oggetto in revisione (docs/06 §7: `GET /v1/approvals?status=IN_REVIEW`): da qui la
 * barra del ciclo di vita ricava ruolo richiesto e invio ("In attesa di LEGAL da 2 h", docs/08 §3.3).
 */
export function useReviewEntry(service: ServiceCode, id: string, status: string | undefined): ApprovalItem | undefined {
  const queue = useLhQuery<ApprovalItem[]>(service, "/v1/approvals", { status: "IN_REVIEW" }, {
    enabled: status === "IN_REVIEW",
  });
  return queue.data?.find((i) => i.id === id);
}
