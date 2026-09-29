// Identità di fonte nel profilo demo (Q-492, M8.2f). L'ingresso delle azioni (`POST /v1/events`, `/v1/events/batch`,
// `/v1/transactions`) accetta solo il ruolo SOURCE, e il client di una fonte è `src-<codice>` (docs/06 §3.2). Il
// pannello demo del portale invia un'azione «dalla fonte» come farebbe un sistema esterno: il proxy, solo nel profilo
// demo, presenta l'identità simulata `SOURCE:src-<codice>` con il codice che l'evento stesso dichiara. Nel profilo
// enterprise non esiste: l'identità viene dal token e il browser non presenta mai una fonte (CLAUDE.md regole 6-bis, 18).

const INGRESS_PATHS = ["v1/events", "v1/events/batch", "v1/transactions"];
const SOURCE_URN_PREFIX = "urn:loyaltyhub:source:";
/** Codice di fonte ammesso: minuscole, cifre e trattini (nessun carattere che possa spezzare un header). */
const SOURCE_CODE = /^[a-z0-9][a-z0-9-]{0,62}$/;

function codeOf(source: unknown): string | null {
  if (typeof source !== "string") return null;
  const code = source.startsWith(SOURCE_URN_PREFIX) ? source.slice(SOURCE_URN_PREFIX.length) : source;
  return SOURCE_CODE.test(code) ? code : null;
}

/**
 * Valore di `X-LH-Actor` per una chiamata di ingresso del profilo demo, o `null` se la richiesta non è un ingresso o le
 * fonti dichiarate non sono un solo codice valido (allora resta l'identità della persona, e il servizio risponde 403).
 */
export function demoSourceActor(service: string, method: string, path: string[], body: string | undefined): string | null {
  if (service !== "ingestion" || method !== "POST" || body === undefined) return null;
  if (!INGRESS_PATHS.includes(path.join("/"))) return null;
  let parsed: unknown;
  try {
    parsed = JSON.parse(body);
  } catch {
    return null;
  }
  const items = Array.isArray(parsed) ? parsed : [parsed];
  if (items.length === 0) return null;
  const codes = new Set(items.map((item) => codeOf((item as { source?: unknown } | null)?.source)));
  const [code] = codes;
  return codes.size === 1 && code ? `SOURCE:src-${code}` : null;
}
