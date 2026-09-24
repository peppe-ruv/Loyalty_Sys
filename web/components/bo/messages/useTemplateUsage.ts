"use client";

import { useQueries } from "@tanstack/react-query";
import { lhFetch, useLhQuery, type LhError } from "@/lib/api/client";
import type { Campaign, CampaignSummary } from "@/lib/api/types";
import type { CampaignLike } from "@/lib/messages/templates";
import type { NotificationRule } from "@/lib/messages/types";

/**
 * Chi usa i template (BO-19): le regole di notifica di engagement e le campagne con effetto SEND_MESSAGE. L'elenco delle
 * campagne non porta gli effetti: si leggono i dettagli (stessa chiave di cache di BO-06), esclusi gli archivi.
 * Campaign che dorme → nessuna campagna (la UI lo dice), le regole restano.
 * SPEC-GAP: Q-85 — `GET /v1/campaigns` non espone gli effetti né un filtro per template: una lettura di dettaglio per
 * campagna (~20 nel seed, in cache 60 s). Un filtro `?templateCode=` lato campaign renderebbe superfluo il giro.
 */
export function useTemplateUsageSources() {
  const rules = useLhQuery<NotificationRule[]>("engagement", "/v1/notification-rules");
  const list = useLhQuery<CampaignSummary[]>("campaign", "/v1/campaigns");
  const details = useQueries({
    queries: (list.data ?? [])
      .filter((c) => c.status !== "ARCHIVED")
      .map((c) => ({
        queryKey: ["campaign", `/v1/campaigns/${c.id}`, {}],
        queryFn: () => lhFetch<Campaign>("campaign", `/v1/campaigns/${c.id}`),
        staleTime: 60_000,
      })),
  });
  const campaigns: CampaignLike[] = details
    .map((d) => d.data)
    .filter((c): c is Campaign => c != null)
    .map((c) => ({ code: c.code, name: c.name, status: c.status, effects: (c.effects ?? []) as CampaignLike["effects"] }));
  return {
    rules: rules.data ?? [],
    campaigns,
    campaignsUnavailable: list.isError as boolean,
    campaignsError: list.error as LhError | null,
  };
}
