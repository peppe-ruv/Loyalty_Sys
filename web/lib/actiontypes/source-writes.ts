import { lhFetch } from "@/lib/api/client";
import { withAllowedType, withoutAllowedType, type SourceRow } from "./sources";

// Scritture delle azioni ammesse di una fonte (BO-09; Q-433). `PUT /v1/sources/{code}` sostituisce l'elenco intero e
// il servizio non ha versioni né ETag: prima di ogni scrittura si rilegge la fonte e si calcola l'elenco su quella
// versione, così una modifica fatta nel frattempo da un altro amministratore non va persa. Resta una finestra di
// pochi millisecondi tra la lettura e la scrittura (Q-433).

/** Rilegge la fonte dal servizio. */
export function readSource(code: string): Promise<SourceRow> {
  return lhFetch<SourceRow>("ingestion", `/v1/sources/${encodeURIComponent(code)}`);
}

/** Scrive l'elenco completo delle azioni ammesse. */
export function writeAllowedTypes(code: string, allowedTypes: string[]): Promise<unknown> {
  return lhFetch("ingestion", `/v1/sources/${encodeURIComponent(code)}`, {
    method: "PUT",
    body: JSON.stringify({ allowedTypes }),
  });
}

/** Aggiunge l'azione alla fonte, partendo dall'elenco appena riletto. `false` = la fonte la accettava già. */
export async function addAllowedType(sourceCode: string, typeCode: string): Promise<boolean> {
  const next = withAllowedType(await readSource(sourceCode), typeCode);
  if (!next) return false;
  await writeAllowedTypes(sourceCode, next);
  return true;
}

/**
 * Toglie l'azione dalla fonte, partendo dall'elenco appena riletto. Se nel frattempo è diventata l'ultima (o la fonte
 * accetta ormai tutto), non scrive nulla e restituisce il motivo.
 */
export async function removeAllowedType(
  sourceCode: string,
  typeCode: string,
): Promise<{ ok: true } | { ok: false; blocked: "LAST_TYPE" | "ALL_TYPES" | "NOT_PRESENT" }> {
  const plan = withoutAllowedType(await readSource(sourceCode), typeCode);
  if ("blocked" in plan) return { ok: false, blocked: plan.blocked };
  await writeAllowedTypes(sourceCode, plan.allowedTypes);
  return { ok: true };
}

/** Stesso elenco, a prescindere dall'ordine. */
export function sameAllowedTypes(a: string[], b: string[]): boolean {
  return JSON.stringify([...a].sort()) === JSON.stringify([...b].sort());
}
