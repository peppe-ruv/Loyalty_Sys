// Messaggi del programma (docs/servizi/engagement-service.md §2-3; F-MSG-01/02; BO-19, PT-12 e campanella).
// Tipi separati da lib/api/types.ts: condivisi tra la gestione (BO-19) e l'inbox del portale.

export type MessageChannel = "INAPP" | "EMAIL_FAKE";
export type MessageCategory = "POINTS" | "TIER" | "REWARD" | "GAME" | "PROGRAM";

/** {@code GET /v1/message-templates}: titolo e testo con segnaposto {{…}}. */
export interface MessageTemplate {
  code: string;
  name: string;
  channel: MessageChannel;
  titleTpl: string;
  bodyTpl: string;
  icon: string | null;
  linkTarget: string | null;
  category: MessageCategory;
  version: number;
  updatedAt: string | null;
  updatedBy: string | null;
}

/** Corpo di {@code POST/PUT /v1/message-templates}. */
export interface TemplateRequest {
  code?: string;
  name: string;
  channel: MessageChannel;
  titleTpl: string;
  bodyTpl: string;
  icon: string;
  linkTarget: string;
  category: MessageCategory;
  version?: number;
}

/** {@code POST /v1/message-templates/{code}/render}: anteprima con i segnaposto risolti. */
export interface RenderRequest {
  sampleEvent: SampleEvent;
  memberId?: string;
  titleTpl?: string;
  bodyTpl?: string;
}

export interface RenderResult {
  code: string;
  channel: MessageChannel;
  category: MessageCategory;
  icon: string | null;
  linkTarget: string | null;
  title: string;
  body: string;
  /** Percorsi {{…}} che l'evento campione non risolve (resi come stringa vuota). */
  missing: string[];
}

/** Evento campione per l'anteprima (CloudEvent anche parziale). */
export interface SampleEvent {
  id: string;
  type: string;
  subject: string;
  time: string;
  source: string;
  data: Record<string, unknown>;
}

/** Foglia di condizione ({@code docs/03 §3.3}) sullo spazio {@code data.*}. */
export interface ConditionLeaf {
  field: string;
  cmp: string;
  value?: unknown;
}

export interface ConditionGroup {
  op: "all" | "any" | "not";
  rules: ConditionNode[];
}

export type ConditionNode = ConditionLeaf | ConditionGroup;

/** {@code GET /v1/notification-rules}: fatto (+ condizione) → template. */
export interface NotificationRule {
  id: string;
  code: string;
  factType: string;
  condition: ConditionNode | null;
  templateCode: string;
  enabled: boolean;
  version: number;
  updatedAt: string | null;
  updatedBy: string | null;
}

/** Corpo di {@code POST/PUT /v1/notification-rules}: {@code condition: {}} (o null) = nessuna condizione. */
export interface RuleRequest {
  code?: string;
  factType?: string;
  condition?: ConditionNode | Record<string, never> | null;
  templateCode?: string;
  enabled?: boolean;
  version?: number;
}

/** Voce del registro {@code GET /v1/messages} (BO-19 {@code log}): tutti i canali. */
export interface MessageLogEntry {
  id: string;
  memberId: string;
  templateCode: string;
  channel: MessageChannel;
  title: string;
  body: string;
  icon: string | null;
  linkTarget: string | null;
  category: MessageCategory;
  sourceEventId: string;
  sourceType: string | null;
  correlationId: string | null;
  createdAt: string;
  readAt: string | null;
}

/** Voce dell'inbox del portale ({@code GET /v1/portal/inbox}, solo {@code INAPP}). */
export interface PortalMessage {
  id: string;
  category: MessageCategory;
  title: string;
  body: string;
  icon: string | null;
  linkTarget: string | null;
  createdAt: string;
  read: boolean;
  readAt: string | null;
}

export interface UnreadCount {
  memberId: string;
  unread: number;
}

export interface ReadAllOutcome {
  memberId: string;
  marked: number;
  unread: number;
}
