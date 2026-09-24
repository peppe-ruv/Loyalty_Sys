// Tipi e regole condivise dello stato demo (docs/07 §8, HUB-01).

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

export function entrancesReady(status: DemoStatus | undefined): boolean {
  if (!status) return false;
  const up = new Set(status.services.filter((s) => s.state === "UP").map((s) => s.code));
  return CORE_SERVICES.every((c) => up.has(c));
}

/** Stato da mostrare in una tessera: durante "Accendi la demo" ciò che non è UP appare WAKING (docs/07 §8). */
export function displayState(state: ServiceState, waking: boolean): ServiceState {
  return waking && state !== "UP" ? "WAKING" : state;
}

/** Il risveglio è concluso quando tutte le 10 tessere sono UP. */
export function wakeComplete(status: DemoStatus): boolean {
  return status.readyCount >= status.totalCount;
}

/** Polling di /api/demo/status: 3 s durante il risveglio (docs/07 §8), 8 s a regime, fermo se inattivo. */
export const STATUS_POLL_WAKING_MS = 3000;
export const STATUS_POLL_STEADY_MS = 8000;

export function statusPollInterval(opts: { waking: boolean; idle: boolean }): number | false {
  // SPEC-GAP: Q-C3 — la spec fissa solo i 3 s dopo "Accendi la demo"; scelta prudente: 8 s a regime e nessun
  // polling quando il keep-alive si è fermato per inattività (le sonde di stato terrebbero svegli i servizi).
  if (opts.idle) return false;
  return opts.waking ? STATUS_POLL_WAKING_MS : STATUS_POLL_STEADY_MS;
}

/** Soglia oltre la quale Kafka DOWN fa comparire il riquadro "riaccendi dalla console" (docs/07 §8). */
export const KAFKA_DOWN_ALERT_MS = 2 * 60_000;

/**
 * Aggiorna l'istante da cui Kafka risulta DOWN, sulla linea temporale di `checkedAt`.
 * SPEC-GAP: Q-C4 — conta solo `DOWN`: `SLEEPING` significa che ingestion non risponde e lo stato di Kafka è ignoto.
 */
export function trackKafkaDown(since: number | null, status: DemoStatus): number | null {
  if (status.kafka.state !== "DOWN") return null;
  return since ?? Date.parse(status.checkedAt);
}

export function kafkaLongDown(since: number | null, status: DemoStatus): boolean {
  return (
    since !== null && status.kafka.state === "DOWN" && Date.parse(status.checkedAt) - since > KAFKA_DOWN_ALERT_MS
  );
}

/** Tempo trascorso "m:ss" (sotto la barra "pronti N/10"). */
export function formatElapsed(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000));
  const m = Math.floor(total / 60);
  const s = total % 60;
  return `${m}:${String(s).padStart(2, "0")}`;
}
