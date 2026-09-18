import path from "path";
import { fileURLToPath } from "url";

import { buildConfig } from "payload";
import { postgresAdapter } from "@payloadcms/db-postgres";
import { lexicalEditor } from "@payloadcms/richtext-lexical";
import { vercelBlobStorage } from "@payloadcms/storage-vercel-blob";
import { ContestCards, WinCards, Popups, Rewards, PointRules, Tiers, Contests, Segments, PromoCodes, CouponPools, RewardCategories, Programs, MessageTemplates, Webhooks, Settings, Campaigns, Wallets, EventSchemas, CustomFieldSchemas, Collections, Channels, Achievements, Challenges, Badges, Leaderboards, FortuneWheels, TierSets, Roles, Media } from "./collections";

import { DecisionPolicies, Offers, Experiments, PredictionProviders, FraudRules, DeliveryRouting, ConsentPurposes } from "./collections-decisions";
import { biGuestToken } from "./endpoints/biGuestToken";
import { simulateDecision, simulateRisk, decisionLog } from "./endpoints/simulate";

const dirname = path.dirname(fileURLToPath(import.meta.url));

/**
 * Dove finiscono i file caricati (collezione `media`).
 *
 * Per difetto Payload scrive su disco accanto al codice: va bene su una macchina o in un
 * contenitore con un volume, non su una piattaforma serverless, dove il filesystem è di sola
 * lettura e comunque sparisce a ogni invocazione. Quando c'è un token di Vercel Blob gli upload
 * ci vanno, altrimenti resta il disco: è la presenza della configurazione a decidere, non un
 * flag da ricordare di impostare.
 */
const storage = process.env.BLOB_READ_WRITE_TOKEN
  ? [
      vercelBlobStorage({
        enabled: true,
        // Il blob store è pubblico: chi ha l'URL scarica. Senza suffisso casuale l'URL è il nome del file, e
        // `.../coupon-natale.csv` si indovina. Il suffisso non è autenticazione, ma toglie l'indovinello.
        addRandomSuffix: true,
        collections: { media: true },
        token: process.env.BLOB_READ_WRITE_TOKEN,
      }),
    ]
  : [];

export default buildConfig({
  serverURL: process.env.CMS_URL || "http://localhost:3000",
  // RF-123: cruscotti BI incorporati (Apache Superset) come vista del pannello; RF-46: link alle metriche tecniche (Grafana)
  admin: {
    // La mappa degli import è generata da `npm run generate:importmap`; i percorsi dei componenti
    // sono scritti come "/src/views/...", quindi la base è la radice del CMS.
    importMap: { baseDir: path.resolve(dirname, "..") },
    components: {
      views: { analytics: { Component: "/src/views/Analytics", path: "/analytics" } },
      afterNavLinks: ["/src/views/AnalyticsNavLink"],
    },
  },
  // Payload 3 richiede un editor esplicito per i campi richText (descrizioni premi, informative,
  // corpo delle card): senza questo la configurazione non viene nemmeno caricata.
  editor: lexicalEditor(),
  endpoints: [biGuestToken, simulateDecision, simulateRisk, decisionLog],
  collections: [Campaigns, DecisionPolicies, Offers, Experiments, PredictionProviders, FraudRules, DeliveryRouting, ConsentPurposes, PointRules, Achievements, Challenges, Badges, Leaderboards, FortuneWheels, Contests, ContestCards, WinCards, Popups, Rewards, RewardCategories, CouponPools, PromoCodes, Wallets, TierSets, Tiers, Segments, Collections, EventSchemas, CustomFieldSchemas, Channels, Programs, MessageTemplates, Webhooks, Roles, Settings, Media],
  // `DATABASE_URI` è il nome nostro; `POSTGRES_URL` e `DATABASE_URL` sono quelli che i database
  // gestiti (Neon, Supabase, Vercel Postgres) iniettano da soli quando li si collega a un progetto.
  // Accettarli tutti e tre evita di dover ricopiare a mano una stringa di connessione che la
  // piattaforma ha già messo lì — e una stringa ricopiata è una stringa che prima o poi diverge.
  db: postgresAdapter({
    pool: { connectionString: process.env.DATABASE_URI || process.env.POSTGRES_URL || process.env.DATABASE_URL },
    // Lo schema nasce dalle migrazioni in `src/migrations/`, mai dal push automatico — nemmeno in
    // sviluppo. Il push non è solo una comodità: lascia in `payload_migrations` una riga `dev` con
    // `batch = -1`, e alla migrazione successiva Payload apre un **prompt interattivo** («hai girato
    // in dev mode, procedo con possibile perdita di dati?»). In una build senza terminale quella
    // domanda non riceve risposta: basta un avvio con push per rendere impubblicabile ogni rilascio
    // successivo. Con `push: false` quella riga non nasce mai.
    push: false,
  }),
  plugins: storage,
  // RF-79/RF-115: multilingua dei contenuti (it di default, en); le traduzioni dell'interfaccia sono nel pannello.
  localization: { locales: ["it", "en"], defaultLocale: "it", fallback: true },
  // Il ripiego serve solo a far costruire il pacchetto dove un segreto non c'è (la CI costruisce il backoffice
  // senza variabili): dice da sé che non è una password. Il rilascio lo pretende davvero — `build:vercel` si
  // rifiuta di partire se `PAYLOAD_SECRET` manca, perché un pannello che firma le sessioni con una costante
  // pubblica del repository è un pannello senza sessioni.
  secret: process.env.PAYLOAD_SECRET || "solo-per-la-build-non-e-un-segreto",
  // SSO aziendale via OIDC (RF-43): strategia di autenticazione da collegare allo IAM aziendale.
});
