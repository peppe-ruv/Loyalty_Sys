import { buildConfig } from "payload";
import { postgresAdapter } from "@payloadcms/db-postgres";
import { ContestCards, WinCards, Popups, Rewards, PointRules, Tiers, Contests, Segments, PromoCodes, CouponPools, RewardCategories, Programs, MessageTemplates, Webhooks, Settings, Media } from "./collections";

export default buildConfig({
  serverURL: process.env.CMS_URL || "http://localhost:3000",
  collections: [ContestCards, WinCards, Popups, Rewards, PointRules, Tiers, Contests, Segments, PromoCodes, CouponPools, RewardCategories, Programs, MessageTemplates, Webhooks, Settings, Media],
  db: postgresAdapter({ pool: { connectionString: process.env.DATABASE_URI } }),
  secret: process.env.PAYLOAD_SECRET || "change-me",
  // SSO aziendale via OIDC (RF-43): strategia di autenticazione da collegare allo IAM Iren.
});
