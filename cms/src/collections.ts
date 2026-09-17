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
    { name: "type", type: "select", required: true, admin: { description: "RF-74: tipi a parità con Open Loyalty" },
      options: ["VOUCHER", "PHYSICAL", "SERVICE", "CASHBACK", "DISCOUNT_PERCENT", "DISCOUNT_VALUE", "FREE_SERVICE", "EVENT_INVITATION", "GIFT", "DONATION"] },
    { name: "valueEur", type: "number", required: true },
    { name: "pointsCost", type: "number", required: true, admin: { description: "0 = premio senza costo (benefit di tier, instant reward)" } },
    tierTarget, { name: "stock", type: "number", defaultValue: -1, admin: { description: "-1 = illimitato" } }, { name: "supplier", type: "text" },
    { name: "category", type: "relationship", relationTo: "reward-categories" },
    { name: "targetSegments", type: "relationship", relationTo: "segments", hasMany: true },
    { name: "limitPerMember", type: "number", defaultValue: 0 }, { name: "limitPerMemberPerDay", type: "number", defaultValue: 0 },
    { name: "visibleFrom", type: "date" }, { name: "visibleTo", type: "date" },
    { name: "activeFrom", type: "date" }, { name: "activeTo", type: "date" },
    { name: "couponPool", type: "relationship", relationTo: "coupon-pools" },
    { name: "codeValidityDays", type: "number", defaultValue: 0 },
    { name: "discountPercent", type: "number", admin: { condition: (d) => d.type === "DISCOUNT_PERCENT" } },
    { name: "cashbackEur", type: "number", admin: { condition: (d) => d.type === "CASHBACK", description: "accredito in bolletta via SAP (RewardFulfiller)" } },
    { name: "photo", type: "upload", relationTo: "media" },
    { name: "featured", type: "checkbox", defaultValue: false },
    status(twoLevel),
  ],
};

export const PointRules: CollectionConfig = {
  slug: "point-rules", versions: { drafts: true },
  fields: [
    { name: "ruleId", type: "text", required: true },
    { name: "actionType", type: "text", required: true },
    { name: "conditions", type: "array", fields: [
      { name: "attribute", type: "text" }, { name: "op", type: "select", options: ["EQ", "NE", "GT", "GTE", "LT", "LTE", "IN", "CONTAINS", "MATCHES", "EXISTS", "GEO_WITHIN"] }, { name: "value", type: "text" }] },
    { name: "rewardPoints", type: "number", required: true },
    { name: "statusPoints", type: "number", required: true },
    { name: "earning", type: "group", admin: { description: "RF-63/RF-64: componente proporzionale, moltiplicatore di campagna, filtro righe, premio automatico" }, fields: [
      { name: "pointsPerEur", type: "number", defaultValue: 0 }, { name: "amountAttribute", type: "text", defaultValue: "amountEur" },
      { name: "multiplier", type: "number", defaultValue: 1 },
      { name: "lineFilter", type: "group", fields: [
        { name: "includeSkus", type: "text", hasMany: true }, { name: "excludeSkus", type: "text", hasMany: true },
        { name: "includeLabels", type: "text", hasMany: true }, { name: "excludeLabels", type: "text", hasMany: true },
        { name: "excludeCategories", type: "text", hasMany: true } ] },
      { name: "autoReward", type: "relationship", relationTo: "rewards", admin: { description: "RF-76 instant reward" } } ] },
    { name: "tierMultipliers", type: "json" },
    { name: "target", type: "group", admin: { description: "RF-65: vuoto = tutti" }, fields: [
      { name: "tiers", type: "select", hasMany: true, options: ["BASE", "PLUS", "TOP"] },
      { name: "segments", type: "relationship", relationTo: "segments", hasMany: true },
      { name: "channels", type: "select", hasMany: true, options: ["web", "app", "sportello", "negozio", "call-center", "partner"] } ] },
    { name: "capPerMemberPerPeriod", type: "number", defaultValue: 0 },
    { name: "limits", type: "group", admin: { description: "RF-66" }, fields: [
      { name: "maxUsesPerMemberPerPeriod", type: "number", defaultValue: 0 },
      { name: "lockDays", type: "number", defaultValue: 0, admin: { description: "punti in sospeso per N giorni (finestra di reso)" } },
      { name: "stopAfter", type: "checkbox", defaultValue: false, admin: { description: "ultima regola eseguita" } } ] },
    { name: "validFrom", type: "date" }, { name: "validTo", type: "date" },
    { name: "stackable", type: "checkbox", defaultValue: true },
    { name: "photo", type: "upload", relationTo: "media" },
    status(threeLevel),
  ],
};

export const Tiers: CollectionConfig = {
  slug: "tiers",
  fields: [
    { name: "code", type: "text", required: true }, { name: "order", type: "number", required: true },
    { name: "statusThreshold", type: "number", required: true }, { name: "benefits", type: "richText" },
    { name: "discountPercent", type: "number", admin: { description: "sconto di tier su offerte e negozio (RF-12)" } },
    { name: "welcomeRewards", type: "relationship", relationTo: "rewards", hasMany: true, admin: { description: "premi assegnati all'ingresso nel tier (RF-76)" } },
    { name: "photo", type: "upload", relationTo: "media" },
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


/** RF-65/RF-71: segmenti dinamici e statici; i criteri sono quelli di segment-service (Segment.Type). */
export const Segments: CollectionConfig = {
  slug: "segments", versions: { drafts: true },
  fields: [
    { name: "segmentId", type: "text", required: true, unique: true },
    { name: "name", type: "text", required: true },
    { name: "match", type: "select", options: ["ALL", "ANY"], defaultValue: "ALL" },
    { name: "criteria", type: "array", required: true, fields: [
      { name: "type", type: "select", required: true, options: ["ANNIVERSARY", "AVG_ACTION_VALUE", "ACTION_COUNT", "ACTION_VALUE", "LAST_ACTION_DAYS_AGO", "ACTION_PERIOD",
        "BOUGHT_SKU", "BOUGHT_LABEL", "BOUGHT_BRAND", "BOUGHT_CATEGORY", "ACTION_IN_CHANNEL", "CHANNEL_SHARE", "HAS_LABEL", "LABEL_VALUE", "STATIC_LIST", "TIER", "POINTS_BALANCE", "CONSENT"] },
      { name: "params", type: "json", admin: { description: "min, max, days, actionType, values[], key, value, channel, minPercent, from, to" } } ] },
    { name: "staticList", type: "upload", relationTo: "media", admin: { description: "CSV di id membro per STATIC_LIST" } },
    status(twoLevel),
  ],
};

/** RF-69: codici promozionali e QR; i lotti monouso si generano dal backoffice (hook) e si esportano in CSV. */
export const PromoCodes: CollectionConfig = {
  slug: "promo-codes", versions: { drafts: true },
  fields: [
    { name: "campaign", type: "text", required: true },
    { name: "kind", type: "select", required: true, options: ["QR", "PROMO", "SINGLE_USE"] },
    { name: "code", type: "text", admin: { description: "vuoto per SINGLE_USE: generato in lotto" } },
    { name: "batchSize", type: "number", admin: { condition: (d) => d.kind === "SINGLE_USE" } },
    { name: "validFrom", type: "date" }, { name: "validTo", type: "date" },
    { name: "maxUses", type: "number", defaultValue: 0 }, { name: "maxUsesPerMember", type: "number", defaultValue: 1 },
    { name: "qrImage", type: "upload", relationTo: "media" },
    status(twoLevel),
  ],
};

/** RF-74: lotti di codici buono caricati come file (un codice per riga); l'inventario residuo è nel cruscotto. */
export const CouponPools: CollectionConfig = {
  slug: "coupon-pools",
  fields: [
    { name: "poolId", type: "text", required: true, unique: true },
    { name: "supplier", type: "text" },
    { name: "codesFile", type: "upload", relationTo: "media" },
    { name: "alertBelow", type: "number", defaultValue: 100 },
  ],
};

export const RewardCategories: CollectionConfig = {
  slug: "reward-categories",
  fields: [{ name: "name", type: "text", required: true }, { name: "order", type: "number", defaultValue: 0 }, { name: "photo", type: "upload", relationTo: "media" }],
};

/** RF-20/RF-21: programma annuale e missioni (sequenze di azioni con premio al completamento). */
export const Programs: CollectionConfig = {
  slug: "programs", versions: { drafts: true },
  fields: [
    { name: "year", type: "number", required: true, unique: true },
    { name: "startsAt", type: "date", required: true }, { name: "endsAt", type: "date", required: true },
    { name: "regulation", type: "upload", relationTo: "media", required: true },
    { name: "bondReference", type: "text", admin: { description: "cauzione 20% (operazione a premio)" } },
    { name: "missions", type: "array", fields: [
      { name: "missionId", type: "text", required: true }, { name: "name", type: "text", required: true },
      { name: "requiredActionTypes", type: "text", hasMany: true, required: true },
      { name: "ordered", type: "checkbox", defaultValue: false },
      { name: "windowDays", type: "number", defaultValue: 0 },
      { name: "reward", type: "relationship", relationTo: "rewards" },
      { name: "badge", type: "upload", relationTo: "media" } ] },
    status(threeLevel),
  ],
};

/** RF-77: modelli di messaggio per evento e canale, con segnaposto {{event.campo}}. */
export const MessageTemplates: CollectionConfig = {
  slug: "message-templates", versions: { drafts: true },
  fields: [
    { name: "eventType", type: "text", required: true },
    { name: "channel", type: "select", required: true, options: ["EMAIL", "SMS", "PUSH", "IN_APP"] },
    { name: "locale", type: "select", options: ["it", "en"], defaultValue: "it" },
    { name: "subject", type: "text" }, { name: "body", type: "textarea", required: true },
    status(twoLevel),
  ],
};

/** RF-78: sottoscrizioni webhook per sistemi esterni; il segreto è in Secrets Manager, qui solo il riferimento. */
export const Webhooks: CollectionConfig = {
  slug: "webhooks",
  fields: [
    { name: "name", type: "text", required: true }, { name: "url", type: "text", required: true },
    { name: "secretRef", type: "text", required: true, admin: { description: "chiave in AWS Secrets Manager" } },
    { name: "eventTypes", type: "text", hasMany: true },
    { name: "headers", type: "json" },
    { name: "maxRetries", type: "number", defaultValue: 5 },
    { name: "active", type: "checkbox", defaultValue: true },
  ],
};

/** RF-79 impostazioni globali del programma (documento singolo). */
export const Settings: CollectionConfig = {
  slug: "settings",
  fields: [
    { name: "programName", type: "text", defaultValue: "Loyalty Iren" },
    { name: "pointsName", type: "group", fields: [{ name: "singular", type: "text", defaultValue: "punto" }, { name: "plural", type: "text", defaultValue: "punti" }] },
    { name: "timezone", type: "text", defaultValue: "Europe/Rome" },
    { name: "locales", type: "select", hasMany: true, options: ["it", "en"], defaultValue: ["it"] },
    { name: "pointsExpiry", type: "select", options: ["END_OF_NEXT_PROGRAM_YEAR", "DAYS_AFTER_EARNING", "NEVER"], defaultValue: "END_OF_NEXT_PROGRAM_YEAR" },
    { name: "pointsExpiryDays", type: "number" },
    { name: "tierDowngrade", type: "select", options: ["ANNUAL_ONE_LEVEL", "ANNUAL_TO_QUALIFIED", "NONE"], defaultValue: "ANNUAL_ONE_LEVEL", admin: { description: "RF-11" } },
    { name: "redemptionCancelHours", type: "number", defaultValue: 48 },
    { name: "manualPostingFourEyesAbove", type: "number", defaultValue: 1000, admin: { description: "RF-18" } },
    { name: "referral", type: "group", fields: [
      { name: "trigger", type: "select", options: ["ON_ENROLLMENT", "ON_FIRST_ACTION", "ON_FIRST_TRANSACTION"], defaultValue: "ON_FIRST_ACTION" },
      { name: "graceDays", type: "number", defaultValue: 30 }, { name: "maxReferralsPerYear", type: "number", defaultValue: 10 } ] },
    { name: "identificationPriority", type: "select", hasMany: true, options: ["oidcSub", "crmId", "sapBusinessPartner", "email", "phone", "loyaltyCard"], defaultValue: ["oidcSub", "crmId", "sapBusinessPartner"], admin: { description: "ordine di risoluzione in identity-mapping (RI-02)" } },
    { name: "channels", type: "text", hasMany: true, defaultValue: ["web", "app", "sportello", "negozio", "call-center", "partner"] },
  ],
};

export const Media: CollectionConfig = { slug: "media", upload: true, fields: [{ name: "alt", type: "text" }] };
