package io.loyaltyhub.common.event;

/**
 * Catalogo dei {@code type} evento (docs/05 §3–§6) — contratto immutabile tra i servizi.
 * Centralizzati qui perché i nomi dei {@code type} non si toccano (CLAUDE.md §6, "Non toccare").
 */
public final class LhEventTypes {

    private LhEventTypes() {
    }

    /** Azioni premianti — {@code lh.actions.v1}, prefisso {@code io.loyaltyhub.action.} */
    public static final class Action {
        public static final String PREFIX = "io.loyaltyhub.action.";
        public static final String PURCHASE_COMPLETED = PREFIX + "purchase.completed";
        public static final String PURCHASE_RETURNED = PREFIX + "purchase.returned";
        public static final String EBILL_ACTIVATED = PREFIX + "ebill.activated";
        public static final String DIRECTDEBIT_ACTIVATED = PREFIX + "directdebit.activated";
        public static final String SELFREADING_SUBMITTED = PREFIX + "selfreading.submitted";
        public static final String APP_LOGIN_DAILY = PREFIX + "app.login.daily";
        public static final String SURVEY_COMPLETED = PREFIX + "survey.completed";
        public static final String QUIZ_COMPLETED = PREFIX + "quiz.completed";
        public static final String REVIEW_SUBMITTED = PREFIX + "review.submitted";
        public static final String NEWSLETTER_SUBSCRIBED = PREFIX + "newsletter.subscribed";
        public static final String MEMBER_REGISTERED = PREFIX + "member.registered";
        public static final String MEMBER_PROFILE_COMPLETED = PREFIX + "member.profile.completed";
        public static final String MEMBER_BIRTHDAY = PREFIX + "member.birthday";
        public static final String TIER_UPGRADED = PREFIX + "tier.upgraded";
        public static final String INSTANTWIN_WON = PREFIX + "instantwin.won";
        public static final String ACHIEVEMENT_COMPLETED = PREFIX + "achievement.completed";
        public static final String BADGE_AWARDED = PREFIX + "badge.awarded";
        public static final String REFERRAL_COMPLETED = PREFIX + "referral.completed";
        public static final String REWARD_REDEEMED = PREFIX + "reward.redeemed";

        private Action() {
        }
    }

    /** Effetti decisi dal motore — {@code lh.effects.v1}, prefisso {@code io.loyaltyhub.effect.} */
    public static final class Effect {
        public static final String PREFIX = "io.loyaltyhub.effect.";
        public static final String POINTS_GRANT = PREFIX + "points.grant";
        public static final String PLAYS_GRANT = PREFIX + "plays.grant";
        public static final String COUPON_ISSUE = PREFIX + "coupon.issue";
        public static final String BADGE_AWARD = PREFIX + "badge.award";
        public static final String MESSAGE_SEND = PREFIX + "message.send";

        private Effect() {
        }
    }

    /** Fatti di dominio — {@code lh.facts.v1}, prefisso {@code io.loyaltyhub.fact.} */
    public static final class Fact {
        public static final String PREFIX = "io.loyaltyhub.fact.";
        public static final String MEMBER_REGISTERED = PREFIX + "member.registered";
        public static final String MEMBER_UPDATED = PREFIX + "member.updated";
        public static final String MEMBER_STATUS_CHANGED = PREFIX + "member.status.changed";
        public static final String MEMBER_PROFILE_COMPLETED = PREFIX + "member.profile.completed";
        public static final String MEMBER_BIRTHDAY = PREFIX + "member.birthday";
        public static final String MEMBER_SEGMENT_ENTERED = PREFIX + "member.segment.entered";
        public static final String MEMBER_SEGMENT_LEFT = PREFIX + "member.segment.left";
        public static final String REFERRAL_COMPLETED = PREFIX + "referral.completed";
        public static final String CAMPAIGN_EVALUATED = PREFIX + "campaign.evaluated";
        public static final String CAMPAIGN_STATUS_CHANGED = PREFIX + "campaign.status.changed";
        public static final String WALLET_POINTS_EARNED = PREFIX + "wallet.points.earned";
        public static final String WALLET_POINTS_SPENT = PREFIX + "wallet.points.spent";
        public static final String WALLET_SPEND_REJECTED = PREFIX + "wallet.spend.rejected";
        public static final String WALLET_POINTS_REFUNDED = PREFIX + "wallet.points.refunded";
        public static final String WALLET_POINTS_EXPIRED = PREFIX + "wallet.points.expired";
        public static final String WALLET_POINTS_EXPIRING = PREFIX + "wallet.points.expiring";
        public static final String WALLET_POINTS_ADJUSTED = PREFIX + "wallet.points.adjusted";
        public static final String WALLET_POINTS_RELEASED = PREFIX + "wallet.points.released";
        public static final String TIER_UPGRADED = PREFIX + "tier.upgraded";
        public static final String TIER_DOWNGRADED = PREFIX + "tier.downgraded";
        public static final String TIER_RETAINED = PREFIX + "tier.retained";
        public static final String EDITION_CLOSED = PREFIX + "edition.closed";
        public static final String REWARD_REDEMPTION_REQUESTED = PREFIX + "reward.redemption.requested";
        public static final String REWARD_REDEMPTION_CONFIRMED = PREFIX + "reward.redemption.confirmed";
        public static final String REWARD_REDEMPTION_FULFILLED = PREFIX + "reward.redemption.fulfilled";
        public static final String REWARD_REDEMPTION_REJECTED = PREFIX + "reward.redemption.rejected";
        public static final String REWARD_REDEMPTION_CANCELLED = PREFIX + "reward.redemption.cancelled";
        public static final String COUPON_ISSUED = PREFIX + "coupon.issued";
        public static final String COUPON_USED = PREFIX + "coupon.used";
        public static final String CONTEST_PLAYS_GRANTED = PREFIX + "contest.plays.granted";
        public static final String CONTEST_PLAYED = PREFIX + "contest.played";
        public static final String CONTEST_WON = PREFIX + "contest.won";
        public static final String ACHIEVEMENT_PROGRESSED = PREFIX + "achievement.progressed";
        public static final String ACHIEVEMENT_COMPLETED = PREFIX + "achievement.completed";
        public static final String BADGE_AWARDED = PREFIX + "badge.awarded";
        public static final String MESSAGE_DELIVERED = PREFIX + "message.delivered";
        public static final String CONTENT_STATUS_CHANGED = PREFIX + "content.status.changed";

        private Fact() {
        }
    }

    /** Audit — {@code lh.audit.v1}, tipo unico (docs/05 §6). */
    public static final class Audit {
        public static final String ENTRY = "io.loyaltyhub.audit.entry";

        private Audit() {
        }
    }
}
