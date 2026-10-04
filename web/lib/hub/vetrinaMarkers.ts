import { readFile } from "node:fs/promises";
import { join } from "node:path";
import { testMode } from "@/lib/hub/testMode";

// HUB-01 — marcatore «membri di test registrati» della vetrina (Q-728, F2-DIST-09, ADR-051). SOLO LATO SERVER.
// `vetrina.sh membri` scrive il file `membri-di-test` (contenuto `ready`) in una cartella dell'host che il compose del
// codespace monta in sola lettura nel ruolo `web` (deploy/vetrina/compose.codespace.yml); `avvio.sh` e `vetrina.sh` lo
// tolgono a ogni avvio e a ogni azzeramento. `GET /api/demo/status` lo riporta nel campo `vetrina.testMembers`, SOLO
// nell'ambiente di test dichiarato (enterprise con LH_TEST_USERS_ALLOWED=true e LH_ENVIRONMENT=test, Q-676): altrove il
// campo non esiste. Dal marcatore non esce nient'altro che `ready` o `pending`: nessun nome, e-mail o dato personale.

export const DEFAULT_STATE_DIR = "/run/lh-vetrina";
export const MEMBERS_MARKER = "membri-di-test";

export type TestMembersState = "ready" | "pending";

export interface VetrinaFlags {
  testMembers: TestMembersState;
}

export type Env = Readonly<Record<string, string | undefined>>;

/** Percorso assoluto della cartella dei marcatori (`LH_VETRINA_STATE_DIR`, default `/run/lh-vetrina`). */
export function stateDir(env: Env): string {
  const raw = (env.LH_VETRINA_STATE_DIR ?? "").trim();
  return raw.startsWith("/") && !raw.includes("\0") ? raw : DEFAULT_STATE_DIR;
}

/**
 * `null` fuori dall'ambiente di test (il campo non compare). Nell'ambiente di test `ready` solo se il marcatore esiste
 * e contiene esattamente `ready`; file assente, illeggibile o diverso ⇒ `pending`.
 */
export async function readVetrinaFlags(env: Env = process.env): Promise<VetrinaFlags | null> {
  if (testMode(env) === null) return null;
  try {
    const text = await readFile(join(stateDir(env), MEMBERS_MARKER), { encoding: "utf8" });
    return { testMembers: text.length <= 16 && text.trim() === "ready" ? "ready" : "pending" };
  } catch {
    return { testMembers: "pending" };
  }
}
