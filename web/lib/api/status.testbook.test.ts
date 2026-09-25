import { expect, it } from "vitest";
import { rows } from "@/test/testbook";
import {
  displayState,
  entrancesReady,
  formatElapsed,
  kafkaLongDown,
  statusPollInterval,
  trackKafkaDown,
  wakeComplete,
  type DemoStatus,
  type ServiceState,
} from "./status";

// Testbook TB-WEB §HUB: Demo Hub (docs/07 §8, HUB-01; Q-133, Q-134, Q-135).

const CORE = ["ingestion", "member", "campaign", "wallet"];
const OTHERS = ["reward", "gamification", "engagement", "insight"];

function status(up: string[], extra: Partial<DemoStatus> = {}): DemoStatus {
  const services = [...CORE, ...OTHERS].map((code) => ({ code, name: code, state: (up.includes(code) ? "UP" : "SLEEPING") as ServiceState, latencyMs: null }));
  return { services, kafka: { state: "UP" }, db: { state: "UP" }, readyCount: up.length, totalCount: 10, checkedAt: "2026-09-18T10:00:00Z", ...extra };
}

// Tabella completa 2^4 = 16: ciascuno dei 4 servizi core UP o no; i 4 non core sempre addormentati (non contano).
const combos = Array.from({ length: 16 }, (_, mask) => {
  const up = CORE.filter((_, i) => mask & (1 << i));
  return {
    id: `TB-WEB-HUB-${String(mask + 1).padStart(3, "0")}`,
    desc: `core UP: ${up.length ? up.join(", ") : "nessuno"} → ingressi ${up.length === 4 ? "attivi" : "bloccati"}`,
    up,
    expected: up.length === 4,
  };
});

it.each(rows(combos))("[%s] %s", (_id, _desc, { up, expected }) => {
  expect(entrancesReady(status(up))).toBe(expected);
});

it("[TB-WEB-HUB-017] stato non ancora noto (in caricamento o in errore) → ingressi bloccati (Q-133)", () => {
  expect(entrancesReady(undefined)).toBe(false);
});

const STATES: ServiceState[] = ["UP", "WAKING", "DOWN", "SLEEPING"];
const display = STATES.flatMap((s, i) => [
  { id: `TB-WEB-HUB-0${18 + i * 2}`, desc: `${s}, senza risveglio in corso → ${s}`, s, waking: false, expected: s },
  { id: `TB-WEB-HUB-0${19 + i * 2}`, desc: `${s}, durante «Accendi la demo» → ${s === "UP" ? "UP" : "WAKING"}`, s, waking: true, expected: s === "UP" ? "UP" : "WAKING" },
]);
it.each(rows(display))("[%s] tessera: %s", (_id, _desc, { s, waking, expected }) => {
  expect(displayState(s, waking)).toBe(expected);
});
// HUB-018 … HUB-025.

it.each(
  rows([
    { id: "TB-WEB-HUB-026", desc: "9/10 pronti → risveglio non concluso", ready: 9, expected: false },
    { id: "TB-WEB-HUB-027", desc: "10/10 pronti → risveglio concluso", ready: 10, expected: true },
  ]),
)("[%s] %s", (_id, _desc, { ready, expected }) => {
  expect(wakeComplete(status(CORE, { readyCount: ready }))).toBe(expected);
});

it.each(
  rows([
    { id: "TB-WEB-HUB-028", desc: "dopo «Accendi la demo» → polling ogni 3 s", waking: true, idle: false, expected: 3000 as number | false },
    { id: "TB-WEB-HUB-029", desc: "a regime → ogni 8 s (Q-134)", waking: false, idle: false, expected: 8000 },
    { id: "TB-WEB-HUB-030", desc: "keep-alive fermo per inattività → nessun polling (Q-134)", waking: false, idle: true, expected: false },
    { id: "TB-WEB-HUB-031", desc: "inattivo durante il risveglio → nessun polling (Q-134)", waking: true, idle: true, expected: false },
  ]),
)("[%s] %s", (_id, _desc, { waking, idle, expected }) => {
  expect(statusPollInterval({ waking, idle })).toBe(expected);
});

const T0 = Date.parse("2026-09-18T10:00:00Z");
const at = (ms: number, kafka: ServiceState = "DOWN") => status(CORE, { kafka: { state: kafka }, checkedAt: new Date(T0 + ms).toISOString() });

it.each(
  rows([
    { id: "TB-WEB-HUB-032", desc: "Kafka DOWN da 1 min 59,999 s → nessun riquadro", ms: 119_999, expected: false },
    { id: "TB-WEB-HUB-033", desc: "Kafka DOWN da esattamente 2 min → nessun riquadro («più di 2 min»)", ms: 120_000, expected: false },
    { id: "TB-WEB-HUB-034", desc: "Kafka DOWN da 2 min + 1 ms → riquadro «riaccendi dalla console»", ms: 120_001, expected: true },
  ]),
)("[%s] %s", (_id, _desc, { ms, expected }) => {
  const since = trackKafkaDown(null, at(0));
  expect(kafkaLongDown(trackKafkaDown(since, at(ms)), at(ms))).toBe(expected);
});

it("[TB-WEB-HUB-035] Kafka SLEEPING (ingestion irraggiungibile) → non conta come DOWN (Q-135)", () => {
  expect(trackKafkaDown(T0, at(10_000, "SLEEPING"))).toBeNull();
});

it("[TB-WEB-HUB-036] Kafka torna UP → il conteggio riparte da zero", () => {
  const since = trackKafkaDown(null, at(0));
  const afterUp = trackKafkaDown(since, at(60_000, "UP"));
  expect([afterUp, kafkaLongDown(trackKafkaDown(afterUp, at(150_000)), at(150_000))]).toEqual([null, false]);
});

it.each(
  rows([
    { id: "TB-WEB-HUB-037", desc: "0 ms → «0:00»", ms: 0, expected: "0:00" },
    { id: "TB-WEB-HUB-038", desc: "61 s → «1:01»", ms: 61_000, expected: "1:01" },
    { id: "TB-WEB-HUB-039", desc: "valore negativo → «0:00»", ms: -5_000, expected: "0:00" },
  ]),
)("[%s] tempo trascorso %s", (_id, _desc, { ms, expected }) => {
  // TESTBOOK: ambiguo, vedi TB-WEB-HUB-037…039 — docs/07 §8 chiede il "tempo trascorso" senza formato; HUB-039 è un
  // ramo senza specifica (orologi sfasati).
  expect(formatElapsed(ms)).toBe(expected);
});
