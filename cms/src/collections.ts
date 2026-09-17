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
    { name: "rejectUnknownActionTypes", type: "checkbox", defaultValue: false, admin: { description: "RF-98: rifiuta azioni senza schema" } },
    { name: "loyaltyCard", type: "group", fields: [ { name: "enabled", type: "checkbox", defaultValue: true }, { name: "format", type: "select", options: ["ALPHANUMERIC", "LETTERS", "DIGITS"], defaultValue: "ALPHANUMERIC" }, { name: "length", type: "number", defaultValue: 8 }, { name: "prefix", type: "text", defaultValue: "IREN" } ] },
    { name: "activeMember", type: "group", fields: [ { name: "days", type: "number", defaultValue: 365 }, { name: "actionTypes", type: "relationship", relationTo: "event-schemas", hasMany: true } ] },
    { name: "eurPerUnit", type: "number", defaultValue: 0.01, admin: { description: "RF-104 paga con i punti e buoni a conversione" } },
    { name: "limits", type: "group", admin: { description: "RF-116" }, fields: [ { name: "activeLeaderboards", type: "number", defaultValue: 3 }, { name: "automationCampaigns", type: "number", defaultValue: 4 }, { name: "automationAudience", type: "number", defaultValue: 200000 } ] },
  ],
};

/** RF-80..RF-86: campagne (dirette, referral, automazioni) con trigger, regole, effetti, limiti, visibilità, espressioni. */
const effectFields = [
  { name: "type", type: "select" as const, required: true, options: ["ADD_UNITS", "DEDUCT_UNITS", "GIVE_REWARD", "SET_ATTRIBUTE", "REMOVE_ATTRIBUTE", "GRANT_BADGE", "ASSIGN_TIER", "EMIT_EVENT"] },
  { name: "wallet", type: "relationship" as const, relationTo: "wallets" },
  { name: "fixed", type: "number" as const }, { name: "perEur", type: "number" as const },
  { name: "valueExpression", type: "text" as const, admin: { description: "es. #fn.round_down(#transaction['grossValue'] * 0.05)" } },
  { name: "reward", type: "relationship" as const, relationTo: "rewards" }, { name: "badge", type: "relationship" as const, relationTo: "badges" },
  { name: "tierCode", type: "text" as const }, { name: "attributeKey", type: "text" as const }, { name: "attributeValue", type: "text" as const },
  { name: "eventType", type: "text" as const }, { name: "level", type: "number" as const, admin: { description: "solo referral multilivello" } },
  { name: "expiresAtExpression", type: "text" as const, admin: { description: "RF-83, es. #fn.add_days_to_date(#transaction['occurredAt'], 30)" } },
  { name: "pendingUntilExpression", type: "text" as const },
];
const conditionFields = [
  { name: "attribute", type: "text" as const }, { name: "op", type: "select" as const, options: ["EQ", "NE", "GT", "GTE", "LT", "LTE", "IN", "CONTAINS", "MATCHES", "EXISTS", "GEO_WITHIN"] }, { name: "value", type: "text" as const },
  { name: "expression", type: "text" as const, admin: { description: "alternativa libera (SpEL): #member['tier'] == 'TOP' and #transaction['grossValue'] > 50" } },
];
export const Campaigns: CollectionConfig = {
  slug: "campaigns", versions: { drafts: true },
  fields: [
    { name: "campaignId", type: "text", required: true, unique: true }, { name: "name", type: "text", required: true, localized: true },
    { name: "kind", type: "select", required: true, options: ["DIRECT", "REFERRAL", "AUTOMATION"], defaultValue: "DIRECT" },
    { name: "trigger", type: "group", fields: [
      { name: "type", type: "select", required: true, options: ["PURCHASE_TRANSACTION", "RETURN_TRANSACTION", "INTERNAL_EVENT", "CUSTOM_EVENT", "ACHIEVEMENT", "REDEMPTION_CODE", "SCHEDULE"] },
      { name: "actionType", type: "relationship", relationTo: "event-schemas" }, { name: "reference", type: "text" },
      { name: "lineFilter", type: "group", fields: [
        { name: "includeSkus", type: "text", hasMany: true }, { name: "excludeSkus", type: "text", hasMany: true }, { name: "includeLabels", type: "text", hasMany: true }, { name: "excludeLabels", type: "text", hasMany: true },
        { name: "includeCategories", type: "text", hasMany: true }, { name: "excludeCategories", type: "text", hasMany: true }, { name: "includeBrands", type: "text", hasMany: true }, { name: "skuCollection", type: "relationship", relationTo: "collections" } ] } ] },
    { name: "startsAt", type: "date", required: true }, { name: "endsAt", type: "date" }, { name: "displayOrder", type: "number", defaultValue: 0 },
    { name: "visibility", type: "group", fields: [ { name: "mode", type: "select", options: ["EVERYONE", "SEGMENTS", "TIERS", "HIDDEN"], defaultValue: "EVERYONE" }, { name: "segments", type: "relationship", relationTo: "segments", hasMany: true }, { name: "tiers", type: "select", hasMany: true, options: ["BASE", "PLUS", "TOP"] } ] },
    { name: "rules", type: "array", maxRows: 6, fields: [ { name: "ruleId", type: "text", required: true }, { name: "conditions", type: "array", maxRows: 30, fields: conditionFields }, { name: "effects", type: "array", fields: effectFields } ] },
    { name: "limits", type: "group", fields: [
      { name: "perMemberTriggers", type: "number", defaultValue: 0 }, { name: "perMemberTriggersPeriod", type: "select", options: ["NONE", "HOURLY", "DAILY", "WEEKLY", "MONTHLY", "YEARLY", "TOTAL"], defaultValue: "NONE" },
      { name: "globalUnits", type: "number", defaultValue: 0 }, { name: "perMemberUnits", type: "number", defaultValue: 0 }, { name: "perMemberUnitsPeriod", type: "select", options: ["NONE", "HOURLY", "DAILY", "WEEKLY", "MONTHLY", "YEARLY", "TOTAL"], defaultValue: "NONE" } ] },
    { name: "schedule", type: "text", admin: { description: "solo AUTOMATION: DAILY | WEEKLY:MON,THU | MONTHLY:1,15 | BIRTHDAY | ANNIVERSARY (RF-86)" } },
    { name: "referralMaxLevels", type: "number", defaultValue: 1, admin: { description: "solo REFERRAL (RF-85)" } },
    { name: "customAttributes", type: "json" }, { name: "photo", type: "upload", relationTo: "media" },
    status(threeLevel),
  ],
};

/** RF-87: wallet configurabili. */
export const Wallets: CollectionConfig = {
  slug: "wallets",
  fields: [
    { name: "code", type: "text", required: true, unique: true, admin: { description: "UPPER_SNAKE; PREMIO e STATUS sono di sistema" } }, { name: "name", type: "text", required: true, localized: true },
    { name: "unitSingular", type: "text", required: true, localized: true }, { name: "unitPlural", type: "text", required: true, localized: true },
    { name: "expiration", type: "select", options: ["NONE", "AFTER_DAYS", "END_OF_MONTH", "END_OF_YEAR", "ANNUAL_DATE", "END_OF_NEXT_PROGRAM_YEAR"], defaultValue: "NONE" },
    { name: "expirationDays", type: "number" }, { name: "expirationAnnualDate", type: "text", admin: { description: "MM-DD" } },
    { name: "pendingDays", type: "number", defaultValue: 0 }, { name: "allowNegative", type: "checkbox", defaultValue: false },
    { name: "globalLimit", type: "number", defaultValue: 0 }, { name: "perMemberLimit", type: "number", defaultValue: 0 },
    { name: "spendable", type: "checkbox", defaultValue: true }, { name: "active", type: "checkbox", defaultValue: true },
  ],
};

/** RF-98: schemi dei tipi azione (catalogo RF-01). */
export const EventSchemas: CollectionConfig = {
  slug: "event-schemas", versions: { drafts: true },
  fields: [
    { name: "actionType", type: "text", required: true, unique: true, admin: { description: "UPPER_SNAKE, es. BILL_PAID_ON_TIME" } }, { name: "name", type: "text", required: true, localized: true },
    { name: "lenient", type: "checkbox", defaultValue: true, admin: { description: "accetta attributi non dichiarati" } },
    { name: "attributes", type: "array", fields: [ { name: "name", type: "text", required: true }, { name: "type", type: "select", required: true, options: ["BOOLEAN", "DATETIME", "NUMBER", "TEXT", "LIST"] }, { name: "required", type: "checkbox", defaultValue: false }, { name: "description", type: "text" } ] },
    status(twoLevel),
  ],
};

/** RF-99: schemi dei campi custom. */
export const CustomFieldSchemas: CollectionConfig = {
  slug: "custom-field-schemas",
  fields: [
    { name: "entity", type: "select", required: true, options: ["MEMBER", "CAMPAIGN", "REWARD", "WHEEL", "TRANSACTION"] }, { name: "group", type: "text", required: true }, { name: "name", type: "text", required: true, localized: true },
    { name: "repeatable", type: "checkbox", defaultValue: false, admin: { description: "solo MEMBER" } }, { name: "editRole", type: "select", options: ["customer_care", "marketing", "platform_admin"], defaultValue: "customer_care" },
    { name: "fields", type: "array", fields: [ { name: "name", type: "text", required: true }, { name: "type", type: "select", required: true, options: ["STRING", "NUMBER", "BOOLEAN", "DATE", "SINGLE_SELECT", "MULTI_SELECT"] }, { name: "required", type: "checkbox" }, { name: "maxLength", type: "number" }, { name: "regex", type: "text" }, { name: "min", type: "number" }, { name: "max", type: "number" }, { name: "collection", type: "relationship", relationTo: "collections" } ] },
  ],
};

/** RF-100: collezioni di valori (il contenuto è in segment-service; qui nome, descrizione e file). */
export const Collections: CollectionConfig = {
  slug: "collections",
  fields: [ { name: "collectionId", type: "text", required: true, unique: true }, { name: "description", type: "text" }, { name: "csv", type: "upload", relationTo: "media", admin: { description: "un valore per riga; l'hook lo carica in segment-service" } } ],
};

/** RF-61: canali. */
export const Channels: CollectionConfig = {
  slug: "channels",
  fields: [ { name: "code", type: "text", required: true, unique: true }, { name: "name", type: "text", required: true, localized: true }, { name: "kind", type: "select", options: ["ONLINE", "STORE", "CALL_CENTER", "PARTNER"], defaultValue: "ONLINE" }, { name: "address", type: "text" }, { name: "lat", type: "number" }, { name: "lon", type: "number" }, { name: "active", type: "checkbox", defaultValue: true } ],
};

/** RF-90: achievement. */
const achievementFields = [
  { name: "actionType", type: "relationship" as const, relationTo: "event-schemas", required: true },
  { name: "conditions", type: "array" as const, fields: [ { name: "attribute", type: "text" as const }, { name: "op", type: "select" as const, options: ["EQ", "NE", "GT", "GTE", "LT", "IN"] }, { name: "value", type: "text" as const } ] },
  { name: "metric", type: "select" as const, required: true, options: ["OCCURRENCES", "ATTRIBUTE_SUM", "UNIQUE_ATTRIBUTE_VALUES"], defaultValue: "OCCURRENCES" }, { name: "attribute", type: "text" as const },
  { name: "goal", type: "group" as const, fields: [ { name: "type", type: "select" as const, required: true, options: ["OVERALL", "LAST_DAYS", "CONSECUTIVE"], defaultValue: "OVERALL" }, { name: "target", type: "number" as const, required: true }, { name: "windowDays", type: "number" as const }, { name: "period", type: "select" as const, options: ["HOUR", "DAY", "WEEK", "MONTH", "YEAR"] }, { name: "consecutivePeriods", type: "number" as const } ] },
  { name: "eventLimit", type: "group" as const, fields: [ { name: "max", type: "number" as const, defaultValue: 0 }, { name: "period", type: "select" as const, options: ["HOUR", "DAY", "WEEK", "MONTH", "YEAR", "TOTAL"], defaultValue: "TOTAL" } ] },
  { name: "completionLimit", type: "group" as const, fields: [ { name: "max", type: "number" as const, defaultValue: 0 }, { name: "period", type: "select" as const, options: ["HOUR", "DAY", "WEEK", "MONTH", "YEAR", "TOTAL"], defaultValue: "TOTAL" } ] },
];
export const Achievements: CollectionConfig = {
  slug: "achievements", versions: { drafts: true },
  fields: [ { name: "achievementId", type: "text", required: true, unique: true }, { name: "name", type: "text", required: true, localized: true }, ...achievementFields, { name: "photo", type: "upload", relationTo: "media" }, status(twoLevel) ],
  hooks: { beforeChange: [({ data, originalDoc }) => { if (originalDoc?.status === "published" && data.status === "published" && JSON.stringify(data.goal) !== JSON.stringify(originalDoc.goal)) data.version = (originalDoc.version || 1) + 1; return data; }] },
};

/** RF-91: challenge (le missioni del programma RF-21 sono challenge con programYear). */
export const Challenges: CollectionConfig = {
  slug: "challenges", versions: { drafts: true },
  fields: [
    { name: "challengeId", type: "text", required: true, unique: true }, { name: "name", type: "text", required: true, localized: true },
    { name: "startsAt", type: "date" }, { name: "endsAt", type: "date" }, { name: "programYear", type: "number" }, { name: "badge", type: "relationship", relationTo: "badges" },
    { name: "availability", type: "group", fields: [ { name: "daysOfWeek", type: "select", hasMany: true, options: ["1", "2", "3", "4", "5", "6", "7"] }, { name: "hourFrom", type: "number" }, { name: "hourTo", type: "number" }, { name: "dates", type: "text", hasMany: true } ] },
    { name: "visibility", type: "group", fields: [ { name: "mode", type: "select", options: ["EVERYONE", "SEGMENTS", "TIERS", "HIDDEN"], defaultValue: "EVERYONE" }, { name: "segments", type: "relationship", relationTo: "segments", hasMany: true }, { name: "tiers", type: "select", hasMany: true, options: ["BASE", "PLUS", "TOP"] } ] },
    { name: "milestones", type: "array", required: true, maxRows: 6, fields: [ { name: "milestoneId", type: "text", required: true }, { name: "name", type: "text", required: true, localized: true }, { name: "kind", type: "select", options: ["DIRECT", "REFERRAL"], defaultValue: "DIRECT" }, ...achievementFields ] },
    { name: "rules", type: "array", fields: [ { name: "ruleId", type: "text", required: true }, { name: "trigger", type: "select", options: ["MILESTONE_PROGRESSED", "CHALLENGE_COMPLETED"], defaultValue: "CHALLENGE_COMPLETED" }, { name: "maxCompletionCount", type: "number" }, { name: "effects", type: "array", fields: effectFields } ] },
    { name: "completionLimit", type: "group", fields: [ { name: "max", type: "number", defaultValue: 0 }, { name: "period", type: "select", options: ["HOUR", "DAY", "WEEK", "MONTH", "YEAR", "TOTAL"], defaultValue: "TOTAL" } ] },
    { name: "photo", type: "upload", relationTo: "media" }, status(threeLevel),
  ],
};

/** RF-92: badge. */
export const Badges: CollectionConfig = {
  slug: "badges",
  fields: [ { name: "code", type: "text", required: true, unique: true, admin: { description: "immutabile dopo la creazione" } }, { name: "name", type: "text", required: true, localized: true }, { name: "description", type: "textarea", localized: true }, { name: "image", type: "upload", relationTo: "media" }, { name: "stackable", type: "checkbox", defaultValue: false }, { name: "active", type: "checkbox", defaultValue: true } ],
  hooks: { beforeChange: [({ data, originalDoc }) => { if (originalDoc?.code && data.code !== originalDoc.code) throw new Error("Il codice del badge non si cambia (RF-92)"); return data; }] },
};

/** RF-93/RF-94: classifiche (max 3 attive). */
export const Leaderboards: CollectionConfig = {
  slug: "leaderboards", versions: { drafts: true },
  fields: [
    { name: "leaderboardId", type: "text", required: true, unique: true }, { name: "name", type: "text", required: true, localized: true },
    { name: "metric", type: "select", required: true, options: ["UNITS_EARNED", "TRANSACTIONS_COUNT", "TRANSACTIONS_VALUE", "CUSTOM_EVENTS_COUNT", "ACHIEVEMENT_PROGRESS"] }, { name: "reference", type: "text", admin: { description: "wallet, tipo evento o achievement" } },
    { name: "startsAt", type: "date", required: true }, { name: "endsAt", type: "date" }, { name: "groupBy", type: "text", admin: { description: "etichetta o campo custom del membro, es. provincia (<500 gruppi)" } }, { name: "topN", type: "number", defaultValue: 1000 },
    { name: "rewardingCycle", type: "group", fields: [ { name: "period", type: "select", options: ["DAY", "WEEK", "MONTH", "YEAR"] }, { name: "rewards", type: "array", fields: [ { name: "fromRank", type: "number", required: true }, { name: "toRank", type: "number", required: true }, { name: "reward", type: "relationship", relationTo: "rewards" }, { name: "wallet", type: "relationship", relationTo: "wallets" }, { name: "units", type: "number" }, { name: "badge", type: "relationship", relationTo: "badges" } ] } ] },
    { name: "visibility", type: "select", options: ["EVERYONE", "HIDDEN"], defaultValue: "EVERYONE" }, status(twoLevel),
  ],
};

/** RF-95: ruote della fortuna. In Italia con premi = concorso a premi: modalità INSTANT_WIN_BACKED e concorso collegato obbligatori. */
export const FortuneWheels: CollectionConfig = {
  slug: "fortune-wheels", versions: { drafts: true },
  fields: [
    { name: "wheelId", type: "text", required: true, unique: true }, { name: "name", type: "text", required: true, localized: true },
    { name: "mode", type: "select", required: true, options: ["PROBABILITY", "INSTANT_WIN_BACKED"], defaultValue: "INSTANT_WIN_BACKED" }, { name: "contest", type: "relationship", relationTo: "contests" },
    { name: "slots", type: "array", required: true, fields: [ { name: "slotId", type: "text", required: true }, { name: "label", type: "text", required: true, localized: true }, { name: "weight", type: "number", required: true }, { name: "reward", type: "relationship", relationTo: "rewards" }, { name: "wallet", type: "relationship", relationTo: "wallets" }, { name: "units", type: "number", defaultValue: 0 }, { name: "stock", type: "number", defaultValue: -1 }, { name: "winning", type: "checkbox", defaultValue: true } ] },
    { name: "costWallet", type: "relationship", relationTo: "wallets" }, { name: "costUnits", type: "number", defaultValue: 0 },
    { name: "spinsPerMember", type: "number", defaultValue: 1 }, { name: "spinsPeriod", type: "select", options: ["HOUR", "DAY", "WEEK", "MONTH", "TOTAL"], defaultValue: "DAY" },
    { name: "startsAt", type: "date" }, { name: "endsAt", type: "date" }, { name: "budgetUnits", type: "number", defaultValue: 0 }, { name: "maxWinsPerMemberPerDay", type: "number", defaultValue: 0 },
    { name: "visibility", type: "select", options: ["EVERYONE", "HIDDEN"], defaultValue: "EVERYONE" }, status(threeLevel),
  ],
  hooks: { beforeChange: [({ data }) => {
    const prizes = (data.slots || []).some((s: any) => s.reward);
    if (prizes && data.mode !== "INSTANT_WIN_BACKED") throw new Error("Ruota con premi: serve la modalità agganciata all'instant win e un concorso (DPR 430/2001)");
    if (data.mode === "INSTANT_WIN_BACKED" && !data.contest) throw new Error("Indica il concorso collegato");
    return data; }] },
};

/** RF-105..RF-107: tier set. */
export const TierSets: CollectionConfig = {
  slug: "tier-sets", versions: { drafts: true },
  fields: [
    { name: "setId", type: "text", required: true, unique: true }, { name: "name", type: "text", required: true, localized: true },
    { name: "conditions", type: "array", required: true, maxRows: 8, fields: [ { name: "conditionId", type: "text", required: true }, { name: "metric", type: "select", required: true, options: ["ACTIVE_UNITS", "TOTAL_EARNED_UNITS", "TOTAL_SPENDING", "MONTHS_SINCE_JOINING", "EARNED_UNITS_IN_PERIOD", "CUSTOM_FIELD"] }, { name: "reference", type: "text" }, { name: "periodMonths", type: "number", defaultValue: 12 } ] },
    { name: "match", type: "select", options: ["ALL", "ANY"], defaultValue: "ALL" },
    { name: "tiers", type: "array", required: true, fields: [ { name: "code", type: "text", required: true }, { name: "order", type: "number", required: true }, { name: "thresholds", type: "json", required: true, admin: { description: "{ conditionId: soglia }" } }, { name: "discountPercent", type: "number" }, { name: "multiplier", type: "number", defaultValue: 1 }, { name: "welcomeRewards", type: "relationship", relationTo: "rewards", hasMany: true }, { name: "benefitsDescription", type: "richText", localized: true }, { name: "photo", type: "upload", relationTo: "media" } ] },
    { name: "downgrade", type: "group", fields: [ { name: "mode", type: "select", options: ["NONE", "AUTOMATIC", "ANNIVERSARY", "CUSTOM_DATES", "INTERVAL_MONTHS", "ANNUAL_ONE_LEVEL"], defaultValue: "ANNUAL_ONE_LEVEL" }, { name: "customDates", type: "text", hasMany: true, admin: { description: "MM-DD" } }, { name: "intervalMonths", type: "number" } ] },
    status(threeLevel),
  ],
};

/** RF-113: ruoli con permessi granulari. */
export const Roles: CollectionConfig = {
  slug: "roles",
  fields: [ { name: "code", type: "text", required: true, unique: true }, { name: "name", type: "text", required: true },
    { name: "permissions", type: "select", hasMany: true, options: ["members.read", "members.write", "members.anonymize", "ledger.manual_posting", "ledger.block", "campaigns.edit", "campaigns.publish", "rewards.edit", "rewards.fulfill", "contests.edit", "contests.legal", "content.edit", "content.publish", "segments.edit", "settings.edit", "roles.edit", "api_keys.edit", "exports.run", "audit.read", "operator.console", "decisions.edit", "decisions.publish", "decisions.simulate", "fraud.edit", "fraud.review", "consents.edit"] } ],
};

export const Media: CollectionConfig = { slug: "media", upload: true, fields: [{ name: "alt", type: "text" }] };
