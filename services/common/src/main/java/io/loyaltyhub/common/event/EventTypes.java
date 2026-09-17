package io.loyaltyhub.common.event;

/** Tipi CloudEvents e topic Kafka del dominio. Contratto pubblicato in docs/contracts/asyncapi.yaml. */
public final class EventTypes {
    private EventTypes() {}

    public static final String ACTION_V1 = "io.loyaltyhub.action.v1";
    public static final String MOVEMENT_V1 = "io.loyaltyhub.movement.v1";
    public static final String TIER_CHANGED_V1 = "io.loyaltyhub.tier-changed.v1";
    public static final String REDEMPTION_V1 = "io.loyaltyhub.redemption.v1";
    public static final String CONTEST_RESULT_V1 = "io.loyaltyhub.contest-result.v1";
    public static final String MEMBER_V1 = "io.loyaltyhub.member.v1";
    public static final String SEGMENT_MEMBERSHIP_V1 = "io.loyaltyhub.segment-membership.v1";
    /** Decisione del motore decisionale (RF-127): azione scelta, motivi, alternative scartate. */
    public static final String DECISION_V1 = "io.loyaltyhub.decision.v1";
    /** Valutazione del rischio frode (RF-131): punteggio, livello, codici motivo. */
    public static final String RISK_V1 = "io.loyaltyhub.risk.v1";
    /** Consegna di un messaggio/offerta su un canale (RF-132): inviato, esposto, cliccato. */
    public static final String DELIVERY_V1 = "io.loyaltyhub.delivery.v1";
    /** Consenso cambiato (RF-135) e identità risolta/unita (RF-136). */
    public static final String CONSENT_V1 = "io.loyaltyhub.consent.v1";
    public static final String IDENTITY_V1 = "io.loyaltyhub.identity.v1";

    public static final String TOPIC_ACTIONS = "loyalty.actions.v1";
    public static final String TOPIC_MOVEMENTS = "loyalty.movements.v1";
    public static final String TOPIC_TIERS = "loyalty.tiers.v1";
    public static final String TOPIC_REDEMPTIONS = "loyalty.redemptions.v1";
    public static final String TOPIC_CONTESTS = "loyalty.contests.v1";
    public static final String TOPIC_MEMBERS = "loyalty.members.v1";
    public static final String TOPIC_SEGMENTS = "loyalty.segments.v1";
    public static final String TOPIC_DECISIONS = "loyalty.decisions.v1";
    public static final String TOPIC_RISK = "loyalty.risk.v1";
    public static final String TOPIC_DELIVERIES = "loyalty.deliveries.v1";
    public static final String TOPIC_CONSENTS = "loyalty.consents.v1";
    public static final String TOPIC_IDENTITIES = "loyalty.identities.v1";
    public static final String TOPIC_DLQ = "loyalty.actions.dlq.v1";

    /** Azioni premianti interne (ADR-008): stesso circuito delle esterne. */
    public static final String ACTION_CONTEST_PLAYED = "CONTEST_PLAYED";
    public static final String ACTION_CONTEST_WON = "CONTEST_WON";
    public static final String ACTION_MISSION_COMPLETED = "MISSION_COMPLETED";
    public static final String ACTION_TIER_CHANGED = "TIER_CHANGED";

    /**
     * Azioni di ciclo di vita del membro e di engagement (RF-60..RF-79).
     * Sono emesse da member-service, ingress-adapters (codici, check-in) e segment-service; le regole le trattano come le altre.
     */
    public static final String ACTION_MEMBER_ENROLLED = "MEMBER_ENROLLED";
    public static final String ACTION_PROFILE_COMPLETED = "PROFILE_COMPLETED";
    public static final String ACTION_NEWSLETTER_SUBSCRIBED = "NEWSLETTER_SUBSCRIBED";
    public static final String ACTION_FIRST_ACTION = "FIRST_ACTION";
    public static final String ACTION_ANNIVERSARY = "MEMBERSHIP_ANNIVERSARY";
    public static final String ACTION_REFERRAL_COMPLETED = "REFERRAL_COMPLETED";
    public static final String ACTION_REFERRED_ENROLLED = "REFERRED_ENROLLED";
    public static final String ACTION_CODE_REDEEMED = "CODE_REDEEMED";
    public static final String ACTION_CHECK_IN = "CHECK_IN";
    public static final String ACTION_SEGMENT_ENTERED = "SEGMENT_ENTERED";
    /** Transazione con righe (RF-62): l'attributo {@code lines} è una lista di {@link TransactionLine}. */
    public static final String ACTION_TRANSACTION = "TRANSACTION";
    public static final String ACTION_TRANSACTION_RETURNED = "TRANSACTION_RETURNED";
    /** Eventi del ciclo decisionale e comportamentali (scenario Loyalty 4.0, RF-125). */
    public static final String ACTION_CAMPAIGN_ENTERED = "CAMPAIGN_ENTERED";
    public static final String ACTION_CAMPAIGN_COMPLETED = "CAMPAIGN_COMPLETED";
    public static final String ACTION_CUSTOMER_IDENTIFIED = "CUSTOMER_IDENTIFIED";
    public static final String ACTION_CHURN_RISK_CHANGED = "CHURN_RISK_CHANGED";
    public static final String ACTION_PRODUCT_VIEWED = "PRODUCT_VIEWED";
    public static final String ACTION_PRODUCT_ADDED_TO_CART = "PRODUCT_ADDED_TO_CART";
    public static final String ACTION_OFFER_PRESENTED = "OFFER_PRESENTED";
    public static final String ACTION_OFFER_ACCEPTED = "OFFER_ACCEPTED";
    public static final String ACTION_MESSAGE_SENT = "MESSAGE_SENT";
    public static final String ACTION_FEEDBACK_REQUESTED = "FEEDBACK_REQUESTED";
    public static final String ACTION_EXPERIMENT_EXPOSED = "EXPERIMENT_EXPOSED";
    public static final String ACTION_CONSENT_CHANGED = "CONSENT_CHANGED";
    public static final String ACTION_IDENTITY_MERGED = "IDENTITY_MERGED";

    /** Attributi canonici riconosciuti dalle regole (RF-61, RF-63): canale, importo, posizione, righe, codice, etichette. */
    public static final String ATTR_AMOUNT_EUR = "amountEur";
    public static final String ATTR_CHANNEL = "channel";
    public static final String ATTR_LAT = "lat";
    public static final String ATTR_LON = "lon";
    public static final String ATTR_LINES = "lines";
    public static final String ATTR_CODE = "code";
    public static final String ATTR_LABELS = "labels";
}
