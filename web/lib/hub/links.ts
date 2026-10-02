// Collegamenti del Demo Hub (docs/07 §8, HUB-01).

export const REPO_URL = "https://github.com/peppe-ruv/Loyalty_Sys";

/** docs/11 §3 "Kafka su Aiven": come riaccendere il cluster gratuito. */
export const KAFKA_RESTART_DOCS_URL = `${REPO_URL}/blob/main/docs/11-DEPLOY-COSTO-ZERO.md#3-kafka-su-aiven-unica-parte-manuale`;

/** Guida della vetrina Enterprise su Mintlify (HUB-01, ADR-051): accensione, credenziali di test, console di Keycloak. */
export const VETRINA_GUIDE_URL = "https://poc-0ae60636.mintlify.app/concetti/vetrina-enterprise";

/** Motivi (chiusi, senza il valore) per cui `LH_HUB_ENTERPRISE_URL` è rifiutata. */
export type EnterpriseUrlProblem = "non_url" | "schema" | "credenziali" | "host_vuoto" | "percorso" | "query_o_fragment";

/**
 * Valida un'origine https (F2-DIST-09, ADR-049): schema https, host non vuoto, nessuna credenziale (nemmeno «@» vuota),
 * nessun percorso diverso da «/», nessuna query né fragment, nessun carattere di controllo, spazio o «\» nel testo.
 * Restituisce l'origine normalizzata (host in minuscolo, porta di default omessa) oppure il motivo del rifiuto. Mai il valore: potrebbe contenere credenziali.
 */
export function parseHttpsOrigin(raw: string): { origin: string } | { problem: EnterpriseUrlProblem } {
  const value = raw.trim();
  // Il parser WHATWG toglie in silenzio tab e a capo e converte «\» in «/»: il testo grezzo non deve contenerli.
  if (/[\s\u0000-\u001f\u007f\\]/.test(value)) return { problem: "non_url" };
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    return { problem: "non_url" };
  }
  if (url.protocol !== "https:") return { problem: "schema" };
  if (url.username !== "" || url.password !== "" || value.includes("@")) return { problem: "credenziali" };
  if (url.hostname === "") return { problem: "host_vuoto" };
  // `new URL("https://x.org?")` ha `search` vuoto: la query e il fragment si controllano anche sul testo.
  if (url.search !== "" || url.hash !== "" || value.includes("?") || value.includes("#")) {
    return { problem: "query_o_fragment" };
  }
  if (url.pathname !== "/") return { problem: "percorso" };
  // Forma del testo grezzo: «https://host[:porta]» con al più una «/» finale (niente «https:host», «/./», «/%2e»).
  if (!/^https:\/\/[^/]+\/?$/i.test(value)) {
    return { problem: /^https:\/\/[^/]+\/./i.test(value) ? "percorso" : "non_url" };
  }
  return { origin: url.origin };
}

let enterpriseUrlWarned = false;

/**
 * Origine della vetrina Enterprise (HUB-01, F2-DIST-09) da `LH_HUB_ENTERPRISE_URL`, SOLO lato server (mai `NEXT_PUBLIC_`).
 * Vuota o assente ⇒ `null` in silenzio. Valorizzata ma non valida ⇒ `null` e un solo `console.warn` per processo con il
 * motivo, mai con il valore (potrebbe contenere credenziali). Funzione pura rispetto all'`env` ricevuto.
 */
export function enterpriseShowcaseUrl(env: Readonly<Record<string, string | undefined>>): string | null {
  const raw = (env.LH_HUB_ENTERPRISE_URL ?? "").trim();
  if (raw === "") return null;
  const parsed = parseHttpsOrigin(raw);
  if ("origin" in parsed) return parsed.origin;
  if (!enterpriseUrlWarned) {
    enterpriseUrlWarned = true;
    console.warn(
      `LH_HUB_ENTERPRISE_URL ignorata (${parsed.problem}): serve un'origine https senza credenziali, percorso, query o fragment. Il pulsante Enterprise resta nascosto.`,
    );
  }
  return null;
}

let demoUrlWarned = false;

/**
 * Origine della demo per il collegamento di ritorno di HUB-02 (ADR-049, F2-DIST-09) da `LH_HUB_DEMO_URL`, SOLO lato
 * server (mai `NEXT_PUBLIC_`), con la stessa validazione di `LH_HUB_ENTERPRISE_URL`. Vuota o assente ⇒ `null` in
 * silenzio (nessun collegamento). Valorizzata ma non valida ⇒ `null` e un solo `console.warn` per processo con il
 * motivo, mai con il valore. Funzione pura rispetto all'`env` ricevuto.
 */
export function demoHubUrl(env: Readonly<Record<string, string | undefined>>): string | null {
  const raw = (env.LH_HUB_DEMO_URL ?? "").trim();
  if (raw === "") return null;
  const parsed = parseHttpsOrigin(raw);
  if ("origin" in parsed) return parsed.origin;
  if (!demoUrlWarned) {
    demoUrlWarned = true;
    console.warn(
      `LH_HUB_DEMO_URL ignorata (${parsed.problem}): serve un'origine https senza credenziali, percorso, query o fragment. Il collegamento di ritorno alla demo resta nascosto.`,
    );
  }
  return null;
}

export interface PathStep {
  screen: string;
  title: string;
  text: string;
  href: string;
}

/** Percorso consigliato: 5 passi reali, nell'ordine della spec (BO-29, BO-24, PT-01, BO-06, BO-30). */
export const RECOMMENDED_PATH: PathStep[] = [
  {
    screen: "BO-29",
    title: "Scenari guidati",
    text: "Lancia uno scenario: una sequenza di azioni reali entra nella pipeline.",
    href: "/backoffice/demo/scenarios",
  },
  {
    screen: "BO-24",
    title: "Flusso eventi live",
    text: "Guarda gli eventi scorrere sui topic, dall'azione ai punti.",
    href: "/backoffice/observe/live",
  },
  {
    screen: "PT-01",
    title: "Tessera nel portale",
    text: "Apri il portale: il saldo del membro sale con il count-up.",
    href: "/portal",
  },
  {
    screen: "BO-06",
    title: "Editor campagna",
    text: "Crea una campagna e simulane l'effetto prima di pubblicarla.",
    href: "/backoffice/campaigns/new",
  },
  {
    screen: "BO-30",
    title: "Console demo",
    text: "Riporta i dati allo stato iniziale per ricominciare.",
    href: "/backoffice/demo/console",
  },
];
