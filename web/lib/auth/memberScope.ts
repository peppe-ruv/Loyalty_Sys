// Membro solo dal token sulle API del portale (docs/18 §3.2 e §3.10, CLAUDE.md regole 6-bis e 18, ADR-042).
// Nel profilo enterprise il BFF non inoltra MAI un `memberId` scelto dal browser verso `/v1/portal/**`, e non prova a
// «ripulirlo»: la richiesta che lo contiene è RIFIUTATA (una pulizia parziale è aggirabile).
// - `memberId` in query (qualunque grafia di maiuscole, anche codificato) ⇒ 400 `MEMBER_FROM_TOKEN`;
// - `memberId` nel percorso (`/v1/portal/wallets/{id}`, `/v1/portal/members/{id}`) ⇒ 403 `MEMBER_FROM_TOKEN`;
// - corpo non vuoto: solo `application/json` (415 altrimenti: un corpo form o multipart verrebbe letto da Spring come
//   `@RequestParam`), un solo oggetto JSON valido (400 altrimenti, anche con testo in coda), nessuna chiave `memberId`
//   a nessun livello (400 `MEMBER_FROM_TOKEN`).
// Il percorso è già stato validato segmento per segmento (lib/api/proxyPath.ts).
// Il servizio ricava il membro dal `sub` del token solo con `MemberPrincipal` (M8.10): fino ad allora questi controlli
// sono l'unica difesa contro l'accesso ai dati di un altro membro e il portale enterprise NON va esposto.
// SPEC-GAP: Q-410.

const MEMBER_PARAM = "memberid";
/** Risorse del portale con l'id del membro come segmento dopo il nome (`/v1/portal/<risorsa>/{memberId}/…`). */
const MEMBER_PATH_RESOURCES = new Set(["wallets", "members"]);

export interface PortalRejection {
  status: 400 | 415;
  code: "MEMBER_FROM_TOKEN" | "INVALID_BODY" | "UNSUPPORTED_MEDIA_TYPE";
  detail: string;
}

export function isPortalPath(path: readonly string[]): boolean {
  return path[0] === "v1" && path[1] === "portal";
}

/** Il percorso porta un id di membro scelto dal client? */
export function hasMemberIdInPath(path: readonly string[]): boolean {
  return isPortalPath(path) && MEMBER_PATH_RESOURCES.has(path[2] ?? "") && path.length >= 4;
}

/** C'è un parametro `memberId` (in ogni grafia) nella query? I nomi sono già decodificati da URLSearchParams. */
export function hasMemberIdInQuery(params: URLSearchParams): boolean {
  for (const key of params.keys()) if (key.toLowerCase() === MEMBER_PARAM) return true;
  return false;
}

/** Controllo del corpo di una richiesta verso `/v1/portal/**`; `null` = ammesso. Corpo vuoto sempre ammesso. */
export function checkPortalBody(contentType: string | null, body: Uint8Array): PortalRejection | null {
  if (body.byteLength === 0) return null;
  const mediaType = (contentType ?? "").split(";")[0].trim().toLowerCase();
  if (mediaType !== "application/json") {
    return { status: 415, code: "UNSUPPORTED_MEDIA_TYPE", detail: "Le funzioni del portale accettano solo corpi JSON." };
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(body));
  } catch {
    return { status: 400, code: "INVALID_BODY", detail: "Il corpo della richiesta non è un JSON valido." };
  }
  if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
    return { status: 400, code: "INVALID_BODY", detail: "Il corpo della richiesta deve essere un oggetto JSON." };
  }
  let member: boolean;
  try {
    member = containsMemberKey(parsed);
  } catch {
    // Annidamento patologico (stack esaurito): rifiutato, mai un 500.
    return { status: 400, code: "INVALID_BODY", detail: "Il corpo della richiesta è annidato troppo in profondità." };
  }
  if (member) {
    return { status: 400, code: "MEMBER_FROM_TOKEN", detail: "Il membro si ricava dall'accesso, non dalla richiesta." };
  }
  return null;
}

function containsMemberKey(value: unknown): boolean {
  if (Array.isArray(value)) return value.some(containsMemberKey);
  if (typeof value !== "object" || value === null) return false;
  return Object.entries(value).some(([key, inner]) => key.toLowerCase() === MEMBER_PARAM || containsMemberKey(inner));
}
