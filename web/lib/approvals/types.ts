// Approvazioni (docs/06 §7, docs/08 §BO-21, F-APR-01/02/03): formato comune della coda di ogni servizio proprietario.

import type { ServiceCode } from "@/lib/api/services";

export type EntityType = "CAMPAIGN" | "REWARD" | "CONTEST";

export interface ApprovalItem {
  entityType: EntityType;
  id: string;
  code: string;
  name: string;
  status: string;
  submittedBy: string | null;
  submittedAt: string | null;
  requiredRole: string | null;
  reason: string | null;
  summary: string | null;
  decidedBy: string | null;
  decidedAt: string | null;
  decision: "APPROVE" | "REJECT" | null;
  comment: string | null;
}

export interface ApprovalHistoryRow {
  id: string;
  entityType: string;
  entityId: string;
  fromStatus: string | null;
  toStatus: string;
  action: string;
  actor: string;
  comment: string | null;
  createdAt: string;
}

export interface PolicyRow {
  entityType: string;
  when: string;
  approverRole: string | null;
}

export interface PolicyView {
  enabled: boolean;
  campaignBudgetThreshold: number;
  rows: PolicyRow[];
}

/** Da dove viene ogni tipo: servizio proprietario, risorsa REST, pagina di dettaglio nel backoffice. */
export const SOURCES: Record<EntityType, { service: ServiceCode; resource: string; label: string; href: (id: string) => string }> = {
  CAMPAIGN: { service: "campaign", resource: "campaigns", label: "Campagna", href: (id) => `/backoffice/campaigns/${id}` },
  REWARD: { service: "reward", resource: "rewards", label: "Premio", href: (id) => `/backoffice/rewards/${id}` },
  CONTEST: { service: "gamification", resource: "contests", label: "Concorso", href: (id) => `/backoffice/game/contests/${id}` },
};

export const ENTITY_TYPES: EntityType[] = ["CAMPAIGN", "REWARD", "CONTEST"];

/**
 * Oggetti con storico delle transizioni (docs/03 §3.6, docs/06 §7): i tre della coda di BO-21 più i contenuti, che non
 * richiedono approvazione (BO-18: *Pubblica* diretto) ma registrano comunque chi li ha pubblicati, messi in pausa o
 * archiviati (`GET /v1/contents/{id}/approval-history`, F-APR-01).
 */
export type HistoryEntityType = EntityType | "CONTENT";

export const HISTORY_SOURCES: Record<HistoryEntityType, { service: ServiceCode; resource: string }> = {
  ...SOURCES,
  CONTENT: { service: "engagement", resource: "contents" },
};
