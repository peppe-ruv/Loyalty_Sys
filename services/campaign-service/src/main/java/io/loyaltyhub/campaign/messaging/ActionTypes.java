package io.loyaltyhub.campaign.messaging;

import io.loyaltyhub.common.event.LhEventTypes.Action;

import java.util.Set;

/** Tutti i {@code type} azione (docs/05 §3): il motore consuma ogni azione (docs/servizi/campaign-service.md §4). */
final class ActionTypes {

    static final Set<String> ALL = Set.of(
            Action.PURCHASE_COMPLETED, Action.PURCHASE_RETURNED, Action.EBILL_ACTIVATED,
            Action.DIRECTDEBIT_ACTIVATED, Action.SELFREADING_SUBMITTED, Action.APP_LOGIN_DAILY,
            Action.SURVEY_COMPLETED, Action.QUIZ_COMPLETED, Action.REVIEW_SUBMITTED, Action.NEWSLETTER_SUBSCRIBED,
            Action.MEMBER_REGISTERED, Action.MEMBER_PROFILE_COMPLETED, Action.MEMBER_BIRTHDAY, Action.TIER_UPGRADED,
            Action.INSTANTWIN_WON, Action.ACHIEVEMENT_COMPLETED, Action.BADGE_AWARDED, Action.REFERRAL_COMPLETED,
            Action.REWARD_REDEEMED);

    private ActionTypes() {
    }
}
