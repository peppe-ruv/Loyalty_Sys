package io.loyaltyhub.ingestion.domain;

import io.loyaltyhub.common.event.LhEventTypes;

import java.util.List;
import java.util.Set;

/**
 * Una riga del ponte interno fatto → azione (docs/05 §7), con i nomi brevi del seed
 * ({@code fact.tier.upgraded} → {@code tier.upgraded}).
 */
public record InternalMapping(String factType, String actionType, boolean enabled) {

    private static final String NAMESPACE = "io.loyaltyhub.";

    /** I soli fatti che il ponte può trasformare in azioni (docs/05 §7), come {@code type} completo. */
    public static final Set<String> BRIDGEABLE_FACTS = Set.of(
            LhEventTypes.Fact.MEMBER_REGISTERED,
            LhEventTypes.Fact.MEMBER_PROFILE_COMPLETED,
            LhEventTypes.Fact.MEMBER_BIRTHDAY,
            LhEventTypes.Fact.TIER_UPGRADED,
            LhEventTypes.Fact.CONTEST_WON,
            LhEventTypes.Fact.ACHIEVEMENT_COMPLETED,
            LhEventTypes.Fact.BADGE_AWARDED,
            LhEventTypes.Fact.REFERRAL_COMPLETED,
            LhEventTypes.Fact.REWARD_REDEMPTION_CONFIRMED);

    /** Famiglie mai mappabili (docs/05 §7), sul nome breve {@code fact.<famiglia>.<nome>}. */
    private static final List<String> FORBIDDEN_PREFIXES = List.of(
            "fact.wallet.points.", "fact.wallet.spend.", "fact.campaign.", "fact.message.");

    /** {@code io.loyaltyhub.fact.tier.upgraded} → {@code fact.tier.upgraded}. */
    public static String shortType(String fullType) {
        return fullType != null && fullType.startsWith(NAMESPACE) ? fullType.substring(NAMESPACE.length()) : fullType;
    }

    /** {@code tier.upgraded} → {@code io.loyaltyhub.action.tier.upgraded}. */
    public static String fullActionType(String shortActionType) {
        return shortActionType.startsWith(LhEventTypes.Action.PREFIX) ? shortActionType : LhEventTypes.Action.PREFIX + shortActionType;
    }

    /** Vero se il fatto (nome breve) appartiene a una famiglia che non può mai diventare un'azione. */
    public static boolean isForbidden(String shortFactType) {
        return shortFactType.endsWith(".status.changed") || FORBIDDEN_PREFIXES.stream().anyMatch(shortFactType::startsWith);
    }
}
