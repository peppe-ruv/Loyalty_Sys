import { buildConfig } from "payload";
import { postgresAdapter } from "@payloadcms/db-postgres";
import { ContestCards, WinCards, Popups, Rewards, PointRules, Tiers, Contests, Segments, PromoCodes, CouponPools, RewardCategories, Programs, MessageTemplates, Webhooks, Settings, Campaigns, Wallets, EventSchemas, CustomFieldSchemas, Collections, Channels, Achievements, Challenges, Badges, Leaderboards, FortuneWheels, TierSets, Roles, Media } from "./collections";

export default buildConfig({
  serverURL: process.env.CMS_URL || "http://localhost:3000",
  collections: [Campaigns, PointRules, Achievements, Challenges, Badges, Leaderboards, FortuneWheels, Contests, ContestCards, WinCards, Popups, Rewards, RewardCategories, CouponPools, PromoCodes, Wallets, TierSets, Tiers, Segments, Collections, EventSchemas, CustomFieldSchemas, Channels, Programs, MessageTemplates, Webhooks, Roles, Settings, Media],
  db: postgresAdapter({ pool: { connectionString: process.env.DATABASE_URI } }),
  // RF-79/RF-115: multilingua dei contenuti (it di default, en); le traduzioni dell'interfaccia sono nel pannello.
  localization: { locales: ["it", "en"], defaultLocale: "it", fallback: true },
  secret: process.env.PAYLOAD_SECRET || "change-me",
  // SSO aziendale via OIDC (RF-43): strategia di autenticazione da collegare allo IAM Iren.
});
