/**
 * Collezioni Loyalty 4.0 (RF-125..RF-136): tutto ciò che valuta o decide è configurabile dal backoffice senza rilascio.
 * - decision-policies: policy del motore decisionale (azioni, vincoli, punteggio, canali)
 * - offers: catalogo delle offerte proponibili (Next Best Action)
 * - experiments: esperimenti controllo/varianti sulla policy
 * - prediction-providers: livello previsionale (a regole, modello locale, servizio esterno) e routing per chiave
 * - fraud-rules: segnali, pesi, soglie e blocco automatico del fraud-service
 * - delivery-routing: instradamento delle consegne per canale, limiti, ore di silenzio, modelli
 * - consent-purposes: catalogo delle finalità di consenso con base giuridica e validità
 * I servizi leggono solo i documenti "published" (cache 30 s); ogni modifica pubblicata incrementa la versione, che
 * finisce nel decision log e nelle valutazioni per la tracciabilità (RF-135).
 */
import type { CollectionConfig } from "payload";

const threeLevel = ["draft", "in_review", "approved", "legal_review", "scheduled", "published", "locked"] as const;
const status = (levels: readonly string[]) => ({
  name: "status", type: "select" as const, required: true, defaultValue: "draft",
  options: levels.map((v) => ({ label: v, value: v })),
});
/** Versione incrementata a ogni pubblicazione di un documento già pubblicato: i servizi la riportano nei log decisionali. */
const bumpVersion = ({ data, originalDoc }: any) => {
  if (originalDoc?.status === "published" && data.status === "published") {
    const { status: _s, version: _v, updatedAt: _u, ...rest } = data;
    const { status: _os, version: _ov, updatedAt: _ou, ...orest } = originalDoc;
    if (JSON.stringify(rest) !== JSON.stringify(orest)) data.version = (originalDoc.version || 1) + 1;
  }
  return data;
};
const channels = ["app", "web", "push", "email", "sms", "webhook", "operator"];
const riskLevels = ["LOW", "MEDIUM", "HIGH", "CRITICAL"];
const actionTypes = ["AWARD_POINTS", "UPGRADE_TIER", "GRANT_BADGE", "SET_ATTRIBUTE", "EMIT_EVENT", "ISSUE_REWARD", "ISSUE_COUPON", "SHOW_OFFER", "SEND_MESSAGE", "TRIGGER_CAMPAIGN", "ASK_FOR_FEEDBACK"];
const contactActions = ["ISSUE_REWARD", "ISSUE_COUPON", "SHOW_OFFER", "SEND_MESSAGE", "TRIGGER_CAMPAIGN", "ASK_FOR_FEEDBACK"];

/** RF-127: policy del motore decisionale. */
export const DecisionPolicies: CollectionConfig = {
  slug: "decision-policies", versions: { drafts: true },
  admin: { useAsTitle: "name", group: "Decisioni", description: "Come il motore sceglie tra premi, coupon, offerte e messaggi: azioni ammesse con priorità/valore/costo/limiti, vincoli (consensi, pressione commerciale, ore di silenzio, rischio), strategia di punteggio." },
  hooks: { beforeChange: [bumpVersion] },
  fields: [
    { name: "code", type: "text", required: true, unique: true, admin: { description: "es. default, black-friday, retention-q4" } },
    { name: "name", type: "text", required: true, localized: true },
    { name: "isDefault", type: "checkbox", defaultValue: false, admin: { description: "una sola policy di default attiva; le altre sono usate dagli esperimenti" } },
    { name: "version", type: "number", defaultValue: 1, admin: { readOnly: true } },
    { name: "actions", type: "array", required: true, admin: { description: "una riga per tipo di azione; le azioni assenti sono disabilitate" }, fields: [
      { name: "type", type: "select", required: true, options: actionTypes },
      { name: "enabled", type: "checkbox", defaultValue: true },
      { name: "priority", type: "number", defaultValue: 50, admin: { description: "strategia PRIORITY: vince la più alta" } },
      { name: "baseValue", type: "number", defaultValue: 1, admin: { description: "valore di business (unità arbitrarie: margine atteso, valore di retention)" } },
      { name: "cost", type: "number", defaultValue: 0, admin: { description: "costo stimato (premio, contatto)" } },
      { name: "requiredConsents", type: "text", hasMany: true, admin: { description: "finalità richieste (es. marketing); vedi consent-purposes" } },
      { name: "maxRiskLevel", type: "select", options: riskLevels, defaultValue: "MEDIUM", admin: { description: "oltre questo livello di rischio l'azione non è proposta" } },
      { name: "maxPerMemberPerPeriod", type: "number", defaultValue: 0 }, { name: "period", type: "select", options: ["HOUR", "DAY", "WEEK", "MONTH"], defaultValue: "MONTH" },
      { name: "cooldownHours", type: "number", defaultValue: 0 },
      { name: "channels", type: "select", hasMany: true, options: channels, admin: { description: "canali ammessi (vuoto = qualsiasi)" } },
    ] },
    { name: "constraints", type: "group", fields: [
      // `enumName` esplicito: il nome che Postgres riceverebbe per difetto
      // (enum_decision_policies_constraints_contact_cap7d_by_channel_channel) supera i 63 caratteri
      // consentiti a un identificatore, e lo schema non si lascia nemmeno costruire.
      { name: "contactCap7dByChannel", type: "array", dbName: "contact_cap_7d", fields: [ { name: "channel", type: "select", options: channels, required: true, enumName: "enum_policy_contact_cap_channel" }, { name: "max", type: "number", required: true } ], admin: { description: "pressione commerciale: contatti massimi in 7 giorni per canale" } },
      { name: "quietHoursFrom", type: "number", min: 0, max: 23, defaultValue: 21 }, { name: "quietHoursTo", type: "number", min: 0, max: 23, defaultValue: 8 },
      { name: "minHoursBetweenOffers", type: "number", defaultValue: 6 },
      { name: "dailyUnitsBudget", type: "number", defaultValue: 0 },
      { name: "suppressionSegments", type: "text", hasMany: true, admin: { description: "segmenti esclusi da ogni azione discrezionale (es. contenziosi, opt-out)" } },
      { name: "blockRiskLevel", type: "select", options: riskLevels, defaultValue: "CRITICAL", admin: { description: "da questo livello solo azioni contrattuali (punti, tier, badge)" } },
    ] },
    { name: "scoring", type: "group", fields: [
      { name: "strategy", type: "select", options: ["PRIORITY", "WEIGHTED", "EXPRESSION"], defaultValue: "WEIGHTED" },
      { name: "valueWeight", type: "number", defaultValue: 1 }, { name: "costWeight", type: "number", defaultValue: 0.8 }, { name: "propensityWeight", type: "number", defaultValue: 2 },
      { name: "churnWeight", type: "number", defaultValue: 1.5 }, { name: "recencyWeight", type: "number", defaultValue: 0.5 }, { name: "channelPreferenceBonus", type: "number", defaultValue: 1 },
      { name: "tierBoost", type: "array", fields: [ { name: "tier", type: "text", required: true }, { name: "boost", type: "number", required: true } ] },
      { name: "expression", type: "text", admin: { description: "strategia EXPRESSION (SpEL): #value * (1 + #predictions['offerPropensity']) - #cost; variabili: value, cost, priority, tierBoost, propensity, churn, predictions, tier, channel, recencyDays, frequency90d, monetary365d, action" } },
    ] },
    { name: "alwaysApply", type: "select", hasMany: true, options: actionTypes, defaultValue: ["AWARD_POINTS", "UPGRADE_TIER", "GRANT_BADGE", "SET_ATTRIBUTE", "EMIT_EVENT"], admin: { description: "azioni contrattuali applicate sempre, senza arbitrato (mai toccate da AI o esperimenti)" } },
    { name: "maxArbitratedPerEvent", type: "number", defaultValue: 1, admin: { description: "quante azioni discrezionali al massimo per evento" } },
    { name: "channelPreferenceOrder", type: "array", dbName: "channel_pref", fields: [ { name: "channel", type: "select", options: channels, required: true, enumName: "enum_policy_channel_pref" } ] },
    status(threeLevel),
  ],
};

/** RF-128: catalogo offerte per Next Best Action. */
export const Offers: CollectionConfig = {
  slug: "offers", versions: { drafts: true },
  admin: { useAsTitle: "name", group: "Decisioni", description: "Offerte proponibili dal motore (premio, coupon, messaggio, offerta a schermo, campagna) con condizione di eligibilità sul Customer 360." },
  fields: [
    { name: "code", type: "text", required: true, unique: true }, { name: "name", type: "text", required: true, localized: true },
    { name: "action", type: "select", required: true, options: contactActions },
    { name: "reference", type: "text", admin: { description: "id premio/coupon pool/campagna/modello messaggio a seconda dell'azione" } },
    { name: "wallet", type: "text" }, { name: "units", type: "number", defaultValue: 0 },
    { name: "condition", type: "text", admin: { description: "SpEL sul contesto: #tier == 'TOP' && #recencyDays > 30 && #predictions['churnRisk'] > 0.5; variabili: ctx, tier, segments, wallets, predictions, riskLevel, recencyDays, frequency90d, monetary365d, event" } },
    { name: "value", type: "number", defaultValue: 1 }, { name: "cost", type: "number", defaultValue: 0 },
    { name: "channels", type: "select", hasMany: true, options: channels },
    { name: "validFrom", type: "date" }, { name: "validTo", type: "date" },
    { name: "params", type: "json", admin: { description: "titolo, testo, templateId, deep link… passati al canale" } },
    { name: "photo", type: "upload", relationTo: "media" },
    status(threeLevel),
  ],
};

/** RF-134: esperimenti. */
export const Experiments: CollectionConfig = {
  slug: "experiments", versions: { drafts: true },
  admin: { useAsTitle: "name", group: "Decisioni", description: "Controllo vs varianti: assegnazione deterministica per membro, quota di traffico, sovrascritture della policy; le metriche incrementali sono nel cruscotto BI per variante." },
  fields: [
    { name: "code", type: "text", required: true, unique: true }, { name: "name", type: "text", required: true },
    { name: "hypothesis", type: "textarea" }, { name: "primaryMetric", type: "text", admin: { description: "es. conversion, redemption_rate, retention_30d" } },
    { name: "startsAt", type: "date" }, { name: "endsAt", type: "date" },
    { name: "trafficPercent", type: "number", min: 0, max: 100, defaultValue: 100 },
    { name: "eventTypes", type: "text", hasMany: true, admin: { description: "vuoto = tutti; next-best-action per le API NBA" } },
    { name: "segments", type: "text", hasMany: true },
    { name: "variants", type: "array", required: true, minRows: 2, fields: [
      { name: "name", type: "text", required: true }, { name: "weight", type: "number", defaultValue: 50 }, { name: "control", type: "checkbox", defaultValue: false },
      { name: "policy", type: "text", admin: { description: "code di una decision-policy alternativa (vuoto = default)" } },
      { name: "overrides", type: "array", fields: [ { name: "key", type: "select", required: true, options: ["scoring.strategy", "scoring.valueWeight", "scoring.costWeight", "scoring.propensityWeight", "scoring.churnWeight", "scoring.recencyWeight", "scoring.channelPreferenceBonus", "scoring.expression", "maxArbitratedPerEvent", "constraints.minHoursBetweenOffers"] }, { name: "value", type: "text", required: true } ] },
    ] },
    status(threeLevel),
  ],
  hooks: { beforeChange: [({ data }) => { if (data.variants && !data.variants.some((v: any) => v.control)) throw new Error("Serve una variante di controllo (RF-134)"); return data; }] },
};

/** RF-130: provider di previsione e routing per chiave. */
export const PredictionProviders: CollectionConfig = {
  slug: "prediction-providers", versions: { drafts: true },
  admin: { useAsTitle: "name", group: "Decisioni", description: "Da dove arrivano churnRisk, purchasePropensity, rewardAcceptance, offerPropensity, engagement, customerValue: a regole (RFM), modello locale o servizio esterno. L'AI propone soltanto: mai punti, saldi o status." },
  fields: [
    { name: "code", type: "text", required: true, unique: true }, { name: "name", type: "text", required: true },
    { name: "kind", type: "select", required: true, options: ["RULE_BASED", "LOCAL_ML", "EXTERNAL"], defaultValue: "RULE_BASED" },
    { name: "active", type: "checkbox", defaultValue: true }, { name: "isDefault", type: "checkbox", defaultValue: false },
    { name: "servesKeys", type: "select", hasMany: true, options: ["churnRisk", "purchasePropensity", "rewardAcceptance", "offerPropensity", "engagement", "customerValue"], admin: { description: "chiavi servite da questo provider; le altre vanno al provider di default" } },
    { name: "url", type: "text", admin: { description: "EXTERNAL/LOCAL_ML: endpoint POST che riceve il contesto pseudonimo e risponde {predictions: {chiave: 0..1}}" } },
    { name: "timeoutMs", type: "number", defaultValue: 300 }, { name: "apiKeyEnv", type: "text", admin: { description: "nome della variabile d'ambiente con la chiave (mai il segreto qui)" } },
    { name: "thresholds", type: "group", admin: { description: "solo RULE_BASED" }, fields: [
      { name: "churnRecencyDays", type: "number", defaultValue: 120 }, { name: "activeFrequency90d", type: "number", defaultValue: 6 }, { name: "highValueEur", type: "number", defaultValue: 1500 },
      { name: "baseRewardAcceptance", type: "number", defaultValue: 0.35 }, { name: "baseOfferPropensity", type: "number", defaultValue: 0.3 } ] },
    status(threeLevel),
  ],
};

/** RF-131: regole antifrode. */
export const FraudRules: CollectionConfig = {
  slug: "fraud-rules", versions: { drafts: true },
  admin: { useAsTitle: "name", group: "Decisioni", description: "Segnali con peso e soglie, fasce di livello, blocco automatico e decadimento. Il punteggio è la somma dei contributi (peso × intensità) fino a 100." },
  hooks: { beforeChange: [bumpVersion] },
  fields: [
    { name: "code", type: "text", required: true, unique: true }, { name: "name", type: "text", required: true }, { name: "version", type: "number", defaultValue: 1, admin: { readOnly: true } },
    { name: "signals", type: "array", required: true, fields: [
      { name: "signal", type: "select", required: true, options: ["REDEMPTION_FREQUENCY", "MULTI_ACCOUNT_DEVICE", "ABNORMAL_EARNING", "RAPID_ACCOUNT_CREATION", "IMPOSSIBLE_TRAVEL", "CODE_ABUSE", "REFUND_RATIO", "DEVICE_ANOMALY", "VELOCITY"] },
      { name: "enabled", type: "checkbox", defaultValue: true },
      { name: "weight", type: "number", required: true, admin: { description: "contributo massimo al punteggio (0..100)" } },
      { name: "threshold", type: "number", required: true, admin: { description: "valore osservato da cui il segnale inizia" } },
      { name: "saturation", type: "number", required: true, admin: { description: "valore osservato a cui il segnale è pieno" } },
      { name: "description", type: "text" } ] },
    { name: "mediumFrom", type: "number", defaultValue: 30 }, { name: "highFrom", type: "number", defaultValue: 60 }, { name: "criticalFrom", type: "number", defaultValue: 85 },
    { name: "autoBlockLevel", type: "select", options: ["NONE", "HIGH", "CRITICAL"], defaultValue: "CRITICAL", admin: { description: "da questo livello le unità del membro sono bloccate sul ledger (sblocco automatico quando il livello scende)" } },
    { name: "decayHours", type: "number", defaultValue: 72 }, { name: "minTransactionsForRefundRatio", type: "number", defaultValue: 5 },
    status(threeLevel),
  ],
};

/** RF-132: instradamento delle consegne. */
export const DeliveryRouting: CollectionConfig = {
  slug: "delivery-routing", versions: { drafts: true },
  admin: { useAsTitle: "name", group: "Decisioni", description: "Per ogni azione di contatto l'ordine dei canali (il canale deciso è provato per primo), canali abilitati, limiti giornalieri, ore di silenzio con canale di ripiego, modelli di messaggio." },
  fields: [
    { name: "code", type: "text", required: true, unique: true }, { name: "name", type: "text", required: true },
    { name: "routes", type: "array", fields: [ { name: "action", type: "select", required: true, options: ["SEND_MESSAGE", "SHOW_OFFER", "ASK_FOR_FEEDBACK"] }, { name: "channels", type: "array", fields: [ { name: "channel", type: "select", required: true, options: channels } ] } ] },
    { name: "enabledChannels", type: "select", hasMany: true, options: channels },
    // Come sopra: senza `dbName` l'indice della tabella delle versioni
    // (_delivery_routing_v_version_max_per_day_by_channel_parent_id_idx) sfora i 63 caratteri e
    // Postgres lo tronca in silenzio, lasciando database e schema Payload disallineati.
    { name: "maxPerDayByChannel", type: "array", dbName: "max_per_day", fields: [ { name: "channel", type: "select", required: true, options: channels, enumName: "enum_routing_max_per_day_channel" }, { name: "max", type: "number", required: true } ] },
    { name: "quietHoursFrom", type: "number", min: 0, max: 23 }, { name: "quietHoursTo", type: "number", min: 0, max: 23 },
    { name: "quietHoursFallback", type: "select", options: ["app", "web", "operator"], admin: { description: "canale usato al posto di push/sms/email nelle ore di silenzio (vuoto = rinvia)" } },
    { name: "templates", type: "array", fields: [ { name: "action", type: "select", required: true, options: ["SEND_MESSAGE", "SHOW_OFFER", "ASK_FOR_FEEDBACK"] }, { name: "reference", type: "text", admin: { description: "vuoto = per tutte le offerte di quell'azione" } }, { name: "templateId", type: "text", required: true, admin: { description: "eventType del message-template (es. decision.offer)" } } ] },
    { name: "emitActions", type: "checkbox", defaultValue: true, admin: { description: "emette OFFER_PRESENTED / MESSAGE_SENT / FEEDBACK_REQUESTED sul bus eventi" } },
    status(threeLevel),
  ],
};

/** RF-135: finalità di consenso. */
export const ConsentPurposes: CollectionConfig = {
  slug: "consent-purposes",
  admin: { useAsTitle: "name", group: "Decisioni", description: "Catalogo delle finalità: base giuridica, validità, obbligatorietà, versione dell'informativa. Il motore non contatta senza il consenso richiesto dall'azione." },
  fields: [
    { name: "code", type: "text", required: true, unique: true }, { name: "name", type: "text", required: true, localized: true },
    { name: "legalBasis", type: "select", required: true, options: ["consent", "contract", "legitimate_interest", "legal_obligation"], defaultValue: "consent" },
    { name: "validityMonths", type: "number", admin: { description: "vuoto = senza scadenza" } },
    { name: "required", type: "checkbox", defaultValue: false, admin: { description: "necessario al programma: non revocabile senza chiudere l'adesione" } },
    { name: "version", type: "text", defaultValue: "1", admin: { description: "versione dell'informativa; alzarla richiede un nuovo consenso" } },
    { name: "notice", type: "richText", localized: true },
  ],
};
