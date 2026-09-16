/**
 * Collezioni del backoffice (RF-40..RF-46). Il workflow (D14) è un campo `status` con transizioni
 * controllate da hook: card e pop-up = due livelli; regole, tier, concorsi e programma = tre livelli con Legal.
 * Le versioni sono attive su ogni collezione (RF-41); `publishAt` gestisce la programmazione (RF-42).
 */
import type { CollectionConfig } from "payload";

const twoLevel = ["draft", "in_review", "approved", "scheduled", "published"] as const;
const threeLevel = ["draft", "in_review", "approved", "legal_review", "scheduled", "published", "locked"] as const;

const status = (levels: readonly string[]) => ({
  name: "status", type: "select" as const, required: true, defaultValue: "draft",
  options: levels.map((v) => ({ label: v, value: v })),
});
const schedule = [
  { name: "publishAt", type: "date" as const },
  { name: "unpublishAt", type: "date" as const },
];
const tierTarget = { name: "minTier", type: "select" as const, options: ["BASE", "PLUS", "TOP"], defaultValue: "BASE" };

export const ContestCards: CollectionConfig = {
  slug: "contest-cards", versions: { drafts: true },
  fields: [
    { name: "title", type: "text", required: true },
    { name: "image", type: "upload", relationTo: "media" },
    { name: "period", type: "group", fields: [{ name: "from", type: "date", required: true }, { name: "to", type: "date", required: true }] },
    { name: "body", type: "richText" },
    { name: "regulation", type: "upload", relationTo: "media" },
    { name: "contestId", type: "text", admin: { description: "UUID del concorso in contest-service" } },
    tierTarget, { name: "cta", type: "text" },
    status(threeLevel), ...schedule,
  ],
};

export const WinCards: CollectionConfig = {
  slug: "win-cards", versions: { drafts: true },
  fields: [
    { name: "prizeCode", type: "text", required: true },
    { name: "title", type: "text", required: true },
    { name: "image", type: "upload", relationTo: "media" },
    { name: "instructions", type: "richText" },
    status(twoLevel),
  ],
};

export const Popups: CollectionConfig = {
  slug: "popups", versions: { drafts: true },
  fields: [
    { name: "title", type: "text", required: true },
    { name: "trigger", type: "select", required: true, options: ["login", "tier_changed", "contest_won", "action_received"] },
    { name: "segment", type: "group", fields: [tierTarget, { name: "requiresProfilingConsent", type: "checkbox", defaultValue: true }] },
    { name: "maxPerMemberPerWeek", type: "number", defaultValue: 1 },
    { name: "priority", type: "number", defaultValue: 100 },
    { name: "body", type: "richText" },
    status(twoLevel), ...schedule,
  ],
};

export const Rewards: CollectionConfig = {
  slug: "rewards", versions: { drafts: true },
  fields: [
    { name: "name", type: "text", required: true },
    { name: "type", type: "select", required: true, options: ["VOUCHER", "PHYSICAL", "SERVICE"] },
    { name: "valueEur", type: "number", required: true },
    { name: "pointsCost", type: "number", required: true },
    tierTarget, { name: "stock", type: "number", defaultValue: 0 }, { name: "supplier", type: "text" },
    status(twoLevel),
  ],
};

export const PointRules: CollectionConfig = {
  slug: "point-rules", versions: { drafts: true },
  fields: [
    { name: "ruleId", type: "text", required: true },
    { name: "actionType", type: "text", required: true },
    { name: "conditions", type: "array", fields: [
      { name: "attribute", type: "text" }, { name: "op", type: "select", options: ["EQ", "NE", "GT", "GTE", "LT", "LTE", "IN"] }, { name: "value", type: "text" }] },
    { name: "rewardPoints", type: "number", required: true },
    { name: "statusPoints", type: "number", required: true },
    { name: "tierMultipliers", type: "json" },
    { name: "capPerMemberPerPeriod", type: "number", defaultValue: 0 },
    { name: "validFrom", type: "date" }, { name: "validTo", type: "date" },
    { name: "stackable", type: "checkbox", defaultValue: true },
    status(threeLevel),
  ],
};

export const Tiers: CollectionConfig = {
  slug: "tiers",
  fields: [
    { name: "code", type: "text", required: true }, { name: "order", type: "number", required: true },
    { name: "statusThreshold", type: "number", required: true }, { name: "benefits", type: "richText" },
    status(threeLevel),
  ],
};

export const Contests: CollectionConfig = {
  slug: "contests", versions: { drafts: true },
  fields: [
    { name: "name", type: "text", required: true },
    { name: "startsAt", type: "date", required: true }, { name: "endsAt", type: "date", required: true },
    { name: "prizes", type: "array", fields: [{ name: "prizeCode", type: "text" }, { name: "quantity", type: "number" }, { name: "valueEur", type: "number" }] },
    { name: "maxPlaysPerMemberPerDay", type: "number", defaultValue: 1 },
    { name: "weightedSlots", type: "array", fields: [{ name: "fromHour", type: "number" }, { name: "toHour", type: "number" }, { name: "weight", type: "number" }] },
    { name: "premaProtocol", type: "text", admin: { description: "RC-03: obbligatorio per pubblicare" } },
    { name: "premaNotifiedAt", type: "date" },
    { name: "bondReference", type: "text", admin: { description: "RC-04: fideiussione 100% montepremi" } },
    { name: "onlus", type: "text", required: true },
    { name: "regulation", type: "upload", relationTo: "media", required: true },
    status(threeLevel),
  ],
  hooks: {
    beforeChange: [({ data, originalDoc }) => {
      if (originalDoc?.status === "locked") throw new Error("Concorso avviato: configurazione bloccata (RF-35)");
      if (data.status === "published") {
        if (!data.premaProtocol || !data.premaNotifiedAt) throw new Error("Protocollo e data comunicazione PREMA obbligatori (RC-03)");
        const days = (new Date(data.startsAt).getTime() - new Date(data.premaNotifiedAt).getTime()) / 86400000;
        if (days < 15) throw new Error("Inizio concorso almeno 15 giorni dopo la comunicazione (RC-03)");
        const maxEnd = new Date(data.startsAt); maxEnd.setFullYear(maxEnd.getFullYear() + 1);
        if (new Date(data.endsAt) > maxEnd) throw new Error("Durata massima 1 anno (RC-05)");
      }
      return data;
    }],
  },
};

export const Media: CollectionConfig = { slug: "media", upload: true, fields: [{ name: "alt", type: "text" }] };
