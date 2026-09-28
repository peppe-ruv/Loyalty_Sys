// Membro solo dal token sulle API del portale (docs/18 §3.2 e §3.10, CLAUDE.md regole 6-bis e 18, ADR-042).
// Nel profilo enterprise il BFF non inoltra MAI un `memberId` scelto dal browser verso `/v1/portal/**`:
// - parametro di query `memberId`: rimosso;
// - campo `memberId` in testa a un corpo JSON: rimosso;
// - `memberId` nel percorso (`/v1/portal/wallets/{id}`, `/v1/portal/members/{id}`): richiesta rifiutata (403), perché
//   non si può togliere senza cambiare risorsa.
// Il servizio ricava il membro dal `sub` del token (`MemberPrincipal`, M8.10). Finché le API del portale chiedono il
// membro nella richiesta, nel profilo enterprise quelle chiamate non trovano il membro: è voluto (niente IDOR).
// SPEC-GAP: Q-410 (varianti delle API del portale senza memberId, legate al token).

const MEMBER_PARAM = "memberid";
/** Risorse del portale con l'id del membro come segmento dopo il nome (`/v1/portal/<risorsa>/{memberId}/…`). */
const MEMBER_PATH_RESOURCES = new Set(["wallets", "members"]);

export function isPortalPath(path: readonly string[]): boolean {
  return path[0] === "v1" && path[1] === "portal";
}

/** Il percorso porta un id di membro scelto dal client? */
export function hasMemberIdInPath(path: readonly string[]): boolean {
  return isPortalPath(path) && MEMBER_PATH_RESOURCES.has(path[2] ?? "") && path.length >= 4;
}

/** Toglie `memberId` (in ogni grafia di maiuscole) dai parametri di query; restituisce i parametri ripuliti. */
export function stripMemberQuery(params: URLSearchParams): URLSearchParams {
  const out = new URLSearchParams();
  for (const [key, value] of params) {
    if (key.toLowerCase() !== MEMBER_PARAM) out.append(key, value);
  }
  return out;
}

/**
 * Toglie `memberId` dal primo livello di un corpo JSON oggetto. Un corpo che non è un oggetto JSON passa com'è:
 * non contiene un `memberId` che il servizio leggerebbe (i DTO del portale lo hanno solo al primo livello).
 */
export function stripMemberBody(body: string): string {
  let parsed: unknown;
  try {
    parsed = JSON.parse(body);
  } catch {
    return body;
  }
  if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) return body;
  const entries = Object.entries(parsed as Record<string, unknown>);
  const kept = entries.filter(([key]) => key.toLowerCase() !== MEMBER_PARAM);
  return kept.length === entries.length ? body : JSON.stringify(Object.fromEntries(kept));
}
