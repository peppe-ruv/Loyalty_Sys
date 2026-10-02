// Unico punto in cui il portale decide COME dice «chi sono» ai servizi (docs/18 §3.2 e §3.10, F2-SEC-09, PT-08, PT-16,
// ADR-048, ADR-051; CLAUDE.md regole 6-bis e 18). Le schermate non scrivono mai `memberId` a mano: chiedono percorsi,
// parametri e corpi a `portalApi(...)`.
// - profilo `enterprise`: il membro viene SOLO dal token della sessione del BFF. Percorsi `/v1/portal/me/**` o senza id,
//   nessun `memberId` in percorso, query o corpo (il BFF rifiuterebbe la richiesta, lib/auth/memberScope.ts). L'id che
//   `GET /v1/portal/me/profile` restituisce serve solo a mostrare e a chiavare le cache: non torna mai indietro.
// - profilo `demo`: invariato (regola 6-bis), persona del cookie e `memberId` esplicito come prima.
// Funzioni pure: i test verificano che in enterprise l'id non compaia da nessuna parte.
// SPEC-GAP: Q-410 (BFF e servizi: un solo contratto per i due profili).

type Scalar = string | number | undefined;

export interface PortalApi {
  /** `true` nel profilo enterprise (membro dal token). */
  readonly enterprise: boolean;
  /** Id del membro: in enterprise solo per mostrare e per le chiavi di cache, mai da inviare. */
  readonly memberId: string;
  /** Saldi e livello (wallet). */
  readonly wallet: string;
  /** Movimenti (wallet). */
  readonly walletActivity: string;
  /** Profilo del portale con completezza (member). */
  readonly profile: string;
  /** Nome e stato per intestazioni e tessera: nel demo la scheda del membro, in enterprise il profilo del portale. */
  readonly summary: string;
  /** Codice amico e invitati (member). */
  readonly referral: string;
  /** Categorie premio: in enterprise la variante del portale (`/v1/reward-categories` è del backoffice, realm operatori). */
  readonly rewardCategories: string;
  /** Edizioni per l'avviso di mantenimento: in enterprise la variante del portale (quella del backoffice non è dei membri). */
  readonly editions: string;
  /** Parametri di query del portale: in demo aggiunge `memberId`, in enterprise lo omette. */
  query(extra?: Record<string, Scalar>): Record<string, Scalar>;
  /** Corpo JSON del portale: in demo aggiunge `memberId`, in enterprise lo omette. */
  body<T extends Record<string, unknown>>(extra?: T): T & { memberId?: string };
  /** Percorso con il `memberId` in query solo in demo (es. annullamento di una richiesta premio). */
  withMember(path: string): string;
}

export function portalApi(enterprise: boolean, memberId: string): PortalApi {
  const id = encodeURIComponent(memberId);
  return {
    enterprise,
    memberId,
    wallet: enterprise ? "/v1/portal/me/wallet" : `/v1/portal/wallets/${id}`,
    walletActivity: enterprise ? "/v1/portal/me/wallet/activity" : `/v1/portal/wallets/${id}/activity`,
    profile: enterprise ? "/v1/portal/me/profile" : `/v1/portal/members/${id}`,
    summary: enterprise ? "/v1/portal/me/profile" : `/v1/members/${id}`,
    referral: enterprise ? "/v1/portal/me/referral" : `/v1/portal/members/${id}/referral`,
    rewardCategories: enterprise ? "/v1/portal/reward-categories" : "/v1/reward-categories",
    editions: enterprise ? "/v1/portal/editions" : "/v1/editions",
    query: (extra = {}) => (enterprise ? { ...extra } : { memberId, ...extra }),
    body: <T extends Record<string, unknown>>(extra = {} as T) =>
      (enterprise ? { ...extra } : { memberId, ...extra }) as T & { memberId?: string },
    withMember: (path) => (enterprise ? path : `${path}${path.includes("?") ? "&" : "?"}memberId=${id}`),
  };
}

/** Il 404 con cui i servizi dicono «questo account non ha ancora un membro» (PT-16). */
export const MEMBER_NOT_REGISTERED = "MEMBER_NOT_REGISTERED";
