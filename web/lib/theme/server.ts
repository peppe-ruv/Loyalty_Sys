import { serviceBaseUrl } from "@/lib/api/services";
import { AURORA, normalizeTheme, type PortalTheme } from "./theme";

/**
 * Tema per il layout del portale, letto lato server (docs/09 §1): niente lampo di colori sbagliati al primo disegno.
 * Cache di 60 s come la risposta del servizio; se engagement dorme o tarda oltre 1,5 s, Aurora (docs/07 §5.3).
 */
export async function getPortalTheme(): Promise<PortalTheme> {
  try {
    const res = await fetch(`${serviceBaseUrl("engagement")}/v1/portal/theme`, {
      next: { revalidate: 60 },
      signal: AbortSignal.timeout(1500),
    });
    if (!res.ok) return AURORA;
    return normalizeTheme((await res.json()) as Partial<PortalTheme>);
  } catch {
    return AURORA;
  }
}
