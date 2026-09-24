// Tipi dei webhook in uscita (docs/servizi/engagement-service.md §2–§3; F-WBH-01, BO-23). Specchio dei record del
// servizio (Webhook, WebhookDelivery): `secret` arriva solo nella risposta di creazione.

export type DeliveryStatus = "PENDING" | "OK" | "FAILED" | "GAVE_UP";

export interface WebhookStats {
  total: number;
  ok: number;
  failed: number;
  gaveUp: number;
  pending: number;
  lastDeliveryAt?: string | null;
  lastStatus?: DeliveryStatus | null;
}

export interface Webhook {
  id: string;
  code: string;
  name: string;
  url: string;
  /** Tipi di fatto in forma breve (es. `wallet.points.earned`). */
  factTypes: string[];
  enabled: boolean;
  version: number;
  createdAt: string;
  createdBy?: string | null;
  updatedAt: string;
  updatedBy?: string | null;
  stats: WebhookStats;
  /** Solo nella risposta di `POST /v1/webhooks`: non si rilegge più. */
  secret?: string;
}

export interface WebhookDelivery {
  id: string;
  webhookId: string;
  eventId: string;
  factType: string;
  memberId?: string | null;
  /** Consegna nata da "Invia evento di prova". */
  test: boolean;
  /** Corpo esatto inviato e firmato (CloudEvent JSON). */
  payload: string;
  /** Valore dell'header X-LH-Signature (`sha256=…`). */
  signature: string;
  attempt: number;
  maxAttempts: number;
  status: DeliveryStatus;
  httpStatus?: number | null;
  responseExcerpt?: string | null;
  /** TIMEOUT | CONNECTION_FAILED | BLOCKED_ADDRESS | HTTP_ERROR */
  error?: string | null;
  durationMs?: number | null;
  nextAttemptAt?: string | null;
  lastAttemptAt?: string | null;
  createdAt: string;
}

/** Corpo di POST/PUT `/v1/webhooks`: in modifica i campi assenti restano invariati. */
export interface WebhookRequest {
  code?: string;
  name?: string;
  url?: string;
  factTypes?: string[];
  enabled?: boolean;
  version?: number;
}
