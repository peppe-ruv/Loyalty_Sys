// Destinazione dopo il login (`/api/auth/login?returnTo=`): solo un percorso relativo della stessa origine, mai un
// open redirect (ASVS 5.0 V3.7, CWE-601). Tutto ciò che non è sicuro con certezza torna alla pagina predefinita.

const MAX_LENGTH = 2048;
const BASE = "https://lh.invalid";

/** Percorso sicuro (con query e frammento) oppure `fallback`. */
export function safeReturnTo(raw: string | null | undefined, fallback = "/"): string {
  if (typeof raw !== "string" || raw.length === 0 || raw.length > MAX_LENGTH) return fallback;
  // Solo "/qualcosa": niente URL assoluti, niente "//host" o "/\host" (i browser trattano "\" come "/"), niente
  // caratteri di controllo o spazi che i browser scartano prima di interpretare l'URL.
  if (!raw.startsWith("/") || raw.startsWith("//") || /[\\\s\u0000-\u001f\u007f]/.test(raw)) return fallback;
  let url: URL;
  try {
    url = new URL(raw, BASE);
  } catch {
    return fallback;
  }
  if (url.origin !== BASE) return fallback;
  // Gli endpoint /api non sono pagine: tornarci dopo il login (es. /api/auth/logout) non ha senso ed è un rischio.
  if (url.pathname === "/api" || url.pathname.startsWith("/api/")) return fallback;
  return `${url.pathname}${url.search}${url.hash}`;
}
