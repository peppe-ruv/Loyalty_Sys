import path from "path";
import { fileURLToPath } from "url";

import { buildConfig } from "payload";
import { postgresAdapter } from "@payloadcms/db-postgres";
import { lexicalEditor } from "@payloadcms/richtext-lexical";
import { ContestCards, WinCards, Popups, Rewards, PointRules, Tiers, Contests, Segments, PromoCodes, CouponPools, RewardCategories, Programs, MessageTemplates, Webhooks, Settings, Campaigns, Wallets, EventSchemas, CustomFieldSchemas, Collections, Channels, Achievements, Challenges, Badges, Leaderboards, FortuneWheels, TierSets, Roles, Media } from "./collections";

import { DecisionPolicies, Offers, Experiments, PredictionProviders, FraudRules, DeliveryRouting, ConsentPurposes } from "./collections-decisions";
import { biGuestToken } from "./endpoints/biGuestToken";
import { simulateDecision, simulateRisk, decisionLog } from "./endpoints/simulate";

const dirname = path.dirname(fileURLToPath(import.meta.url));

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
  db: postgresAdapter({ pool: { connectionString: process.env.DATABASE_URI } }),
  // RF-79/RF-115: multilingua dei contenuti (it di default, en); le traduzioni dell'interfaccia sono nel pannello.
  localization: { locales: ["it", "en"], defaultLocale: "it", fallback: true },
  secret: process.env.PAYLOAD_SECRET || "change-me",
  // SSO aziendale via OIDC (RF-43): strategia di autenticazione da collegare allo IAM Iren.
});
