package it.iren.loyalty.common.event;

/** Tipi CloudEvents e topic Kafka del dominio. Contratto pubblicato in docs/contracts/asyncapi.yaml. */
public final class EventTypes {
    private EventTypes() {}

    public static final String ACTION_V1 = "it.iren.loyalty.action.v1";
    public static final String MOVEMENT_V1 = "it.iren.loyalty.movement.v1";
    public static final String TIER_CHANGED_V1 = "it.iren.loyalty.tier-changed.v1";
    public static final String REDEMPTION_V1 = "it.iren.loyalty.redemption.v1";
    public static final String CONTEST_RESULT_V1 = "it.iren.loyalty.contest-result.v1";
    public static final String MEMBER_V1 = "it.iren.loyalty.member.v1";
    public static final String SEGMENT_MEMBERSHIP_V1 = "it.iren.loyalty.segment-membership.v1";

    public static final String TOPIC_ACTIONS = "loyalty.actions.v1";
    public static final String TOPIC_MOVEMENTS = "loyalty.movements.v1";
    public static final String TOPIC_TIERS = "loyalty.tiers.v1";
    public static final String TOPIC_REDEMPTIONS = "loyalty.redemptions.v1";
    public static final String TOPIC_CONTESTS = "loyalty.contests.v1";
    public static final String TOPIC_MEMBERS = "loyalty.members.v1";
    public static final String TOPIC_SEGMENTS = "loyalty.segments.v1";
    public static final String TOPIC_DLQ = "loyalty.actions.dlq.v1";

    /** Azioni premianti interne (D08): stesso circuito delle esterne. */
    public static final String ACTION_CONTEST_PLAYED = "CONTEST_PLAYED";
    public static final String ACTION_CONTEST_WON = "CONTEST_WON";
    public static final String ACTION_MISSION_COMPLETED = "MISSION_COMPLETED";
    public static final String ACTION_TIER_CHANGED = "TIER_CHANGED";

    /**
     * Azioni di ciclo di vita del membro e di engagement (RF-60..RF-79, parità con le "event rules" di Open Loyalty).
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

    /** Attributi canonici riconosciuti dalle regole (RF-63): importo, canale, posizione, righe, codice. */
    public static final String ATTR_AMOUNT_EUR = "amountEur";
    public static final String ATTR_CHANNEL = "channel";
    public static final String ATTR_LAT = "lat";
    public static final String ATTR_LON = "lon";
    public static final String ATTR_LINES = "lines";
    public static final String ATTR_CODE = "code";
    public static final String ATTR_LABELS = "labels";
}
