// Percorso inoltrato dal proxy /api/lh in entrambi i profili (ADR-042, docs/07 §4-bis). Il percorso deciso dal browser
// non deve poter diventare un altro percorso a valle: Next decodifica i segmenti (`%2F` → `/`, `%2e%2e` → `..`),
// `new URL()` normalizza `..` e Spring ignora i parametri di matrice (`wallets;x` ≡ `wallets`). Quindi:
// - ogni segmento ammette solo caratteri non riservati RFC 3986 `[A-Za-z0-9._~-]`, e non può essere vuoto, `.` o `..`;
// - l'URL a valle si costruisce codificando ogni segmento e si verifica che il percorso finale sia esattamente quello
//   atteso (nessuna normalizzazione avvenuta).
// Vale anche nel profilo demo (F2-SEC-03): il percorso validato è quello su cui si decidono identità e intestazioni.

const SEGMENT = /^[A-Za-z0-9._~-]+$/;

/** Tutti i segmenti sono sicuri da inoltrare così come sono. */
export function safeSegments(path: readonly string[]): boolean {
  return path.length > 0 && path.every((s) => SEGMENT.test(s) && s !== "." && s !== "..");
}

/** URL del servizio per quei segmenti, oppure `null` se l'URL costruito non ha esattamente il percorso atteso. */
export function upstreamUrl(base: string, path: readonly string[]): URL | null {
  if (!safeSegments(path)) return null;
  const root = new URL(base);
  const prefix = root.pathname === "/" ? "" : root.pathname.replace(/\/+$/, "");
  const expected = `${prefix}/${path.join("/")}`;
  const target = new URL(`${root.origin}${prefix}/${path.map(encodeURIComponent).join("/")}`);
  return target.pathname === expected ? target : null;
}
