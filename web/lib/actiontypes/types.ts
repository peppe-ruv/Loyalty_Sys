// Tipi azione di ingestion (docs/servizi/ingestion-service.md §3, BO-09, F-ING-06). Il costruttore di condizioni di
// BO-06 usa le stesse API; qui le forme che servono alla gestione dei tipi.

export type ActionTypeOrigin = "SYSTEM" | "CUSTOM";

export interface ActionType {
  code: string;
  name: string;
  description: string | null;
  origin: ActionTypeOrigin;
  category: string | null;
  icon: string | null;
  enabled: boolean;
  dataSchema: Record<string, unknown> | null;
  sampleData: Record<string, unknown> | null;
}

/** Campo `data.*` dedotto dallo schema (`GET /v1/event-types/{code}/fields`). */
export interface ActionField {
  path: string;
  type: string;
  required: boolean;
  enum?: string[];
  format?: string;
}

export interface ActionTypeRequest {
  code?: string;
  name: string;
  description?: string | null;
  category?: string | null;
  icon?: string | null;
  enabled?: boolean;
  dataSchema?: Record<string, unknown> | null;
  sampleData?: Record<string, unknown> | null;
}

export const CUSTOM_CATEGORIES = ["ENGAGEMENT", "SERVICE", "TRANSACTION"] as const;

export const CATEGORY_LABEL: Record<string, string> = {
  TRANSACTION: "Transazioni",
  ENGAGEMENT: "Coinvolgimento",
  SERVICE: "Servizio",
  INTERNAL: "Interni (ponte)",
};
