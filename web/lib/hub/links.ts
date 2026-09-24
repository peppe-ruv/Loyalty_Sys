// Collegamenti del Demo Hub (docs/07 §8, HUB-01).

export const REPO_URL = "https://github.com/peppe-ruv/Loyalty_Sys";

/** docs/11 §3 "Kafka su Aiven": come riaccendere il cluster gratuito. */
export const KAFKA_RESTART_DOCS_URL = `${REPO_URL}/blob/main/docs/11-DEPLOY-COSTO-ZERO.md#3-kafka-su-aiven-unica-parte-manuale`;

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
