// Tipi dei segmenti (docs/servizi/member-service.md §3, BO-04; docs/03 §10). File a parte da lib/api/types.ts.

export type SegmentType = "STATIC" | "DYNAMIC";
export type SegmentStatus = "ACTIVE" | "ARCHIVED";

/** Comparatori delle condizioni (docs/03 §3.3). */
export type Comparator =
  | "eq"
  | "neq"
  | "gt"
  | "gte"
  | "lt"
  | "lte"
  | "in"
  | "nin"
  | "contains"
  | "ncontains"
  | "exists"
  | "nexists"
  | "between"
  | "startsWith";

export type CriteriaValue = string | number | boolean | (string | number)[] | null | undefined;

/** Foglia `{field, cmp, value}`. */
export interface CriteriaLeaf {
  field: string;
  cmp: Comparator;
  value?: CriteriaValue;
}

/** Gruppo `{op, rules}`. */
export interface CriteriaGroup {
  op: "all" | "any" | "not";
  rules: CriteriaNode[];
}

export type CriteriaNode = CriteriaGroup | CriteriaLeaf;

export interface Segment {
  id: string;
  code: string;
  name: string;
  description: string | null;
  type: SegmentType;
  criteria: CriteriaNode | null;
  status: SegmentStatus;
  memberCount: number;
  refreshedAt: string | null;
  version: number;
  createdAt: string;
  updatedAt: string;
  createdBy: string | null;
  updatedBy: string | null;
}

/** Corpo di POST/PUT `/v1/segments`. */
export interface SegmentRequest {
  code?: string;
  name?: string;
  description?: string | null;
  type?: SegmentType;
  criteria?: CriteriaNode | null;
  status?: SegmentStatus;
  memberIds?: string[];
  version?: number;
}

export interface MemberSample {
  memberId: string;
  name: string;
  tier: string;
  status: string;
  enteredAt?: string | null;
}

export interface PreviewResult {
  count: number;
  sample: MemberSample[];
}

export interface RefreshResult {
  code: string;
  entered: number;
  left: number;
  total: number;
}

/** Etichette del membro (campo `labels` di `GET /v1/members/{id}`, usate dai criteri `member.labels`). */
export interface MemberLabels {
  labels: string[];
}

/** Appartenenza di un membro (BO-03, scheda `segments`). */
export interface MemberSegment {
  id: string;
  code: string;
  name: string;
  type: SegmentType;
  status: SegmentStatus;
  enteredAt: string;
}
