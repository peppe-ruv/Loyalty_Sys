import { LhError } from "./client";

// Regola di nuovo tentativo di TanStack Query per gli errori dei servizi (docs/07 §6, F2-SEC-09, Q-553).
// Subito dopo la registrazione i servizi rispondono 409 MEMBER_NOT_LINKED (Retry-After 2) finché non hanno consumato
// `member.registered`: è transitorio, si riprova ogni 2 s fino a 5 volte. Una sessione scaduta non si ripara riprovando;
// ogni altro errore si riprova una volta sola.
export const NOT_LINKED_CODE = "MEMBER_NOT_LINKED";
export const NOT_LINKED_MAX_RETRIES = 5;
export const NOT_LINKED_DELAY_MS = 2_000;

export function isNotLinked(error: unknown): boolean {
  return error instanceof LhError && error.status === 409 && error.code === NOT_LINKED_CODE;
}

export function shouldRetry(failures: number, error: unknown): boolean {
  if (error instanceof LhError && error.unauthenticated) return false;
  if (isNotLinked(error)) return failures < NOT_LINKED_MAX_RETRIES;
  return failures < 1;
}

export function retryDelay(failures: number, error: unknown): number {
  return isNotLinked(error) ? NOT_LINKED_DELAY_MS : Math.min(1000 * 2 ** failures, 30_000);
}
