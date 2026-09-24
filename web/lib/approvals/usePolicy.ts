"use client";

import { useLhQuery } from "@/lib/api/client";
import type { PolicyView } from "./types";

/** Policy delle approvazioni (docs/06 §7), letta da campaign: è la stessa configurazione in ogni servizio proprietario. */
export function useApprovalPolicy() {
  return useLhQuery<PolicyView>("campaign", "/v1/approvals/policy");
}
