// Tipi condivisi dello stato demo (docs/07 §8).

export type ServiceState = "UP" | "WAKING" | "DOWN" | "SLEEPING";

export interface ServiceStatus {
  code: string;
  name: string;
  state: ServiceState;
  latencyMs: number | null;
  version?: string;
}

export interface DemoStatus {
  services: ServiceStatus[];
  kafka: { state: ServiceState };
  db: { state: ServiceState };
  readyCount: number;
  totalCount: number;
  checkedAt: string;
}

/** Ingressi attivi quando ingestion, member, campaign e wallet sono UP (docs/07 §8). */
export const CORE_SERVICES = ["ingestion", "member", "campaign", "wallet"];

export function entrancesReady(status: DemoStatus): boolean {
  const up = new Set(status.services.filter((s) => s.state === "UP").map((s) => s.code));
  return CORE_SERVICES.every((c) => up.has(c));
}
