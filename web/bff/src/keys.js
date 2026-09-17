/**
 * Chiavi di idempotenza secondo la convenzione RI-01: `<fonte>:<riferimento>:<evento>`.
 *
 * Due regole imparate a spese del BFF:
 * 1. le chiavi costruite a mano finivano con cinque segmenti e l'ingresso le rifiutava tutte
 *    (`INVALID_IDEMPOTENCY_KEY`): nessun evento comportamentale né azione da sportello entrava
 *    davvero in piattaforma;
 * 2. il riferimento non può venire dall'orologio (`Date.now()`), altrimenti un ritentativo del
 *    client genera una chiave nuova e la deduplica a valle non scatta: l'operazione si ripete.
 */

/** Stessa espressione di `IdempotencyKeys` (services/common): tre segmenti, separatore `:`. */
export const KEY_PATTERN = /^[a-z0-9-]{2,32}:[A-Za-z0-9._-]{1,128}:[A-Z0-9_]{1,64}$/;

/** Il riferimento è un solo segmento: le parti si uniscono con `.` e ciò che non è ammesso diventa `-`. */
export function reference(parts) {
  return (Array.isArray(parts) ? parts : [parts])
    .filter((p) => p !== undefined && p !== null && `${p}` !== "")
    .join(".")
    .replace(/[^A-Za-z0-9._-]/g, "-")
    .slice(0, 128);
}

/** Compone la chiave e verifica che rispetti la convenzione: una chiave storta è un errore qui, non a valle. */
export function idempotencyKey(source, ref, event) {
  const evento = `${event}`.toUpperCase().replace(/[^A-Z0-9_]/g, "_").slice(0, 64);
  const key = `${source}:${reference(ref)}:${evento}`;
  if (!KEY_PATTERN.test(key)) throw new Error(`chiave di idempotenza non valida: ${key}`);
  return key;
}
