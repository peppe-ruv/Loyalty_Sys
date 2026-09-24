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

// RFC 9457 Problem Details for HTTP APIs
export interface Rfc9457Error {
  type: string;
  status: number;
  title?: string;
  detail?: string;
  violations?: { field: string; message: string }[];
  errors?: { field: string; message: string }[]; // Alias per proxy lhFetch
  code?: string;
}

export function isRfc9457(error: any): error is Rfc9457Error {
  return error != null && typeof error === "object" && typeof error.type === "string" && typeof error.status === "number";
}

export function mapRfc9457ToFormErrors(error: any, setError: (field: any, error: any) => void) {
  if (isRfc9457(error)) {
    const list = error.violations || error.errors || [];
    list.forEach(v => {
      setError(v.field, { type: "server", message: v.message });
    });
  } else if (error && typeof error === "object" && Array.isArray(error.errors)) {
    // Compatibilità con LhError
    error.errors.forEach((v: any) => {
      setError(v.field, { type: "server", message: v.message });
    });
  }
}
