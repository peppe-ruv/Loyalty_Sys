package it.iren.loyalty.memberservice.domain;

import it.iren.loyalty.common.event.EventTypes;
import it.iren.loyalty.common.event.RewardingAction;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Presenta un amico (RF-68), equivalente della "customer referral" di Open Loyalty.
 * Il codice è derivato dall'id membro (stabile, non indovinabile senza il segreto); il presentato lo inserisce
 * all'adesione o entro {@code graceDays}. Il premio scatta all'evento configurato ({@link Trigger}) e produce due azioni
 * interne: REFERRAL_COMPLETED per chi presenta e REFERRED_ENROLLED per il presentato; i punti li decidono le regole.
 */
public record ReferralPolicy(Trigger trigger, int graceDays, int maxReferralsPerYear, String secret) {
    /** Quando il referral si considera completato. */
    public enum Trigger { ON_ENROLLMENT, ON_FIRST_ACTION, ON_FIRST_TRANSACTION }

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // senza 0/O/1/I

    public String codeFor(String memberId) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest((secret + ":" + memberId).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(8);
            for (int i = 0; i < 8; i++) sb.append(ALPHABET.charAt(Byte.toUnsignedInt(h[i]) % ALPHABET.length()));
            return sb.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public boolean completes(String actionType) {
        return switch (trigger) {
            case ON_ENROLLMENT -> EventTypes.ACTION_MEMBER_ENROLLED.equals(actionType);
            case ON_FIRST_ACTION -> !EventTypes.ACTION_MEMBER_ENROLLED.equals(actionType);
            case ON_FIRST_TRANSACTION -> EventTypes.ACTION_TRANSACTION.equals(actionType);
        };
    }

    /** Le due azioni interne, idempotenti per coppia (presentatore, presentato). */
    public List<RewardingAction> completed(String referrerId, String referredId, Instant at) {
        String pair = referrerId + ":" + referredId;
        return List.of(
                new RewardingAction(EventTypes.ACTION_REFERRAL_COMPLETED, "referral:" + pair + ":REFERRER", referredId, at, null, Map.of("referredId", referredId)),
                new RewardingAction(EventTypes.ACTION_REFERRED_ENROLLED, "referral:" + pair + ":REFERRED", referrerId, at, null, Map.of("referrerId", referrerId)));
    }

    public static ReferralPolicy example() { return new ReferralPolicy(Trigger.ON_FIRST_ACTION, 30, 10, System.getenv().getOrDefault("REFERRAL_SECRET", "dev-only")); }
}
