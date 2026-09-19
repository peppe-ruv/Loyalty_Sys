// Registro dei microservizi (docs/04 §3, docs/07 §3). Gli URL vengono da LH_SVC_<SERVICE>_URL;
// default locali per lo sviluppo con ./mvnw. Il browser non li vede mai: passa dal proxy /api/lh.

export type ServiceCode =
  | "ingestion"
  | "member"
  | "campaign"
  | "wallet"
  | "reward"
  | "gamification"
  | "engagement"
  | "insight";

export interface ServiceInfo {
  code: ServiceCode;
  name: string;
  port: number;
}

export const SERVICES: ServiceInfo[] = [
  { code: "ingestion", name: "Ingestion", port: 8081 },
  { code: "member", name: "Member", port: 8082 },
  { code: "campaign", name: "Campaign", port: 8083 },
  { code: "wallet", name: "Wallet", port: 8084 },
  { code: "reward", name: "Reward", port: 8085 },
  { code: "gamification", name: "Gamification", port: 8086 },
  { code: "engagement", name: "Engagement", port: 8087 },
  { code: "insight", name: "Insight", port: 8088 },
];

const BY_CODE = new Map(SERVICES.map((s) => [s.code, s]));

export function isServiceCode(value: string): value is ServiceCode {
  return BY_CODE.has(value as ServiceCode);
}

/** URL base del servizio: variabile d'ambiente o default locale. */
export function serviceBaseUrl(code: ServiceCode): string {
  const envKey = `LH_SVC_${code.toUpperCase()}_URL`;
  const fromEnv = process.env[envKey];
  const svc = BY_CODE.get(code);
  return (fromEnv && fromEnv.replace(/\/$/, "")) || `http://localhost:${svc?.port ?? 8080}`;
}
