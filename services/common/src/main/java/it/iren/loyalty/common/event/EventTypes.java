package it.iren.loyalty.common.event;

/** Tipi CloudEvents e topic Kafka del dominio. Contratto pubblicato in docs/contracts/asyncapi.yaml. */
public final class EventTypes {
    private EventTypes() {}

    public static final String ACTION_V1 = "it.iren.loyalty.action.v1";
    public static final String MOVEMENT_V1 = "it.iren.loyalty.movement.v1";
    public static final String TIER_CHANGED_V1 = "it.iren.loyalty.tier-changed.v1";
    public static final String REDEMPTION_V1 = "it.iren.loyalty.redemption.v1";
    public static final String CONTEST_RESULT_V1 = "it.iren.loyalty.contest-result.v1";

    public static final String TOPIC_ACTIONS = "loyalty.actions.v1";
    public static final String TOPIC_MOVEMENTS = "loyalty.movements.v1";
    public static final String TOPIC_TIERS = "loyalty.tiers.v1";
    public static final String TOPIC_REDEMPTIONS = "loyalty.redemptions.v1";
    public static final String TOPIC_CONTESTS = "loyalty.contests.v1";
    public static final String TOPIC_DLQ = "loyalty.actions.dlq.v1";

    /** Azioni premianti interne (D08): stesso circuito delle esterne. */
    public static final String ACTION_CONTEST_PLAYED = "CONTEST_PLAYED";
    public static final String ACTION_CONTEST_WON = "CONTEST_WON";
    public static final String ACTION_MISSION_COMPLETED = "MISSION_COMPLETED";
    public static final String ACTION_TIER_CHANGED = "TIER_CHANGED";
}
