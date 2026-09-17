/**
 * Token di servizio per il BFF (RF-43): flusso client credentials verso l'IAM, con il token tenuto in memoria fino a
 * poco prima della scadenza. Il BFF parla con tutti i servizi interni: senza questo, accendere l'autenticazione sulle
 * API interne spegnerebbe l'area membro e la console operatore.
 *
 * Se l'IAM non è configurato (ambiente locale) non si aggiunge nessuna intestazione: i servizi in locale non la
 * pretendono. Se è configurato ma non risponde, la chiamata parte senza token e il servizio risponderà 401: meglio un
 * errore che si vede che una chiamata che passa senza credenziali.
 */

const TOKEN_URI = process.env.OIDC_TOKEN_URI || "";
const CLIENT_ID = process.env.OIDC_CLIENT_ID || "";
const CLIENT_SECRET = process.env.OIDC_CLIENT_SECRET || "";
const SCOPE = process.env.INTERNAL_AUTH_SCOPE || "loyalty.internal";
/** Margine: si rinnova prima della scadenza, così nessuna chiamata parte con un token appena scaduto. */
const MARGIN_MS = 30_000;

let cached = null;
let expiresAt = 0;

export function configured() {
  return Boolean(TOKEN_URI && CLIENT_ID);
}

/** Token valido, rinnovato se serve; null se non configurato o se l'IAM non lo rilascia. */
export async function serviceToken(now = Date.now(), fetchImpl = fetch) {
  if (!configured()) return null;
  if (cached && now < expiresAt) return cached;
  const body = new URLSearchParams({ grant_type: "client_credentials", client_id: CLIENT_ID, client_secret: CLIENT_SECRET, scope: SCOPE });
  try {
    const res = await fetchImpl(TOKEN_URI, { method: "POST", headers: { "content-type": "application/x-www-form-urlencoded" }, body });
    if (!res.ok) throw new Error(`token endpoint -> ${res.status}`);
    const json = await res.json();
    if (!json.access_token) throw new Error("risposta senza access_token");
    cached = json.access_token;
    expiresAt = now + Math.max(1000, (Number(json.expires_in) || 300) * 1000) - MARGIN_MS;
    return cached;
  } catch (e) {
    cached = null;
    expiresAt = 0;
    console.warn(`token di servizio non ottenuto: ${e.message}`);
    return null;
  }
}

/** Intestazioni da aggiungere a una chiamata verso i servizi interni ({} quando non serve un token). */
export async function authHeaders() {
  const token = await serviceToken();
  return token ? { authorization: `Bearer ${token}` } : {};
}

/** Solo per i test: dimentica il token in cache. */
export function reset() {
  cached = null;
  expiresAt = 0;
}
