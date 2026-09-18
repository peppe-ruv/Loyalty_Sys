import { MigrateUpArgs, MigrateDownArgs, sql } from '@payloadcms/db-postgres'

export async function up({ db, payload, req }: MigrateUpArgs): Promise<void> {
  await db.execute(sql`
   CREATE TYPE "public"."_locales" AS ENUM('it', 'en');
  CREATE TYPE "public"."enum_campaigns_visibility_tiers" AS ENUM('BASE', 'PLUS', 'TOP');
  CREATE TYPE "public"."enum_campaigns_rules_conditions_op" AS ENUM('EQ', 'NE', 'GT', 'GTE', 'LT', 'LTE', 'IN', 'CONTAINS', 'MATCHES', 'EXISTS', 'GEO_WITHIN');
  CREATE TYPE "public"."enum_campaigns_rules_effects_type" AS ENUM('ADD_UNITS', 'DEDUCT_UNITS', 'GIVE_REWARD', 'SET_ATTRIBUTE', 'REMOVE_ATTRIBUTE', 'GRANT_BADGE', 'ASSIGN_TIER', 'EMIT_EVENT');
  CREATE TYPE "public"."enum_campaigns_kind" AS ENUM('DIRECT', 'REFERRAL', 'AUTOMATION');
  CREATE TYPE "public"."enum_campaigns_trigger_type" AS ENUM('PURCHASE_TRANSACTION', 'RETURN_TRANSACTION', 'INTERNAL_EVENT', 'CUSTOM_EVENT', 'ACHIEVEMENT', 'REDEMPTION_CODE', 'SCHEDULE');
  CREATE TYPE "public"."enum_campaigns_visibility_mode" AS ENUM('EVERYONE', 'SEGMENTS', 'TIERS', 'HIDDEN');
  CREATE TYPE "public"."enum_campaigns_limits_per_member_triggers_period" AS ENUM('NONE', 'HOURLY', 'DAILY', 'WEEKLY', 'MONTHLY', 'YEARLY', 'TOTAL');
  CREATE TYPE "public"."enum_campaigns_limits_per_member_units_period" AS ENUM('NONE', 'HOURLY', 'DAILY', 'WEEKLY', 'MONTHLY', 'YEARLY', 'TOTAL');
  CREATE TYPE "public"."enum_campaigns_status" AS ENUM('draft', 'published');
  CREATE TYPE "public"."enum__campaigns_v_version_visibility_tiers" AS ENUM('BASE', 'PLUS', 'TOP');
  CREATE TYPE "public"."enum__campaigns_v_version_rules_conditions_op" AS ENUM('EQ', 'NE', 'GT', 'GTE', 'LT', 'LTE', 'IN', 'CONTAINS', 'MATCHES', 'EXISTS', 'GEO_WITHIN');
  CREATE TYPE "public"."enum__campaigns_v_version_rules_effects_type" AS ENUM('ADD_UNITS', 'DEDUCT_UNITS', 'GIVE_REWARD', 'SET_ATTRIBUTE', 'REMOVE_ATTRIBUTE', 'GRANT_BADGE', 'ASSIGN_TIER', 'EMIT_EVENT');
  CREATE TYPE "public"."enum__campaigns_v_version_kind" AS ENUM('DIRECT', 'REFERRAL', 'AUTOMATION');
  CREATE TYPE "public"."enum__campaigns_v_version_trigger_type" AS ENUM('PURCHASE_TRANSACTION', 'RETURN_TRANSACTION', 'INTERNAL_EVENT', 'CUSTOM_EVENT', 'ACHIEVEMENT', 'REDEMPTION_CODE', 'SCHEDULE');
  CREATE TYPE "public"."enum__campaigns_v_version_visibility_mode" AS ENUM('EVERYONE', 'SEGMENTS', 'TIERS', 'HIDDEN');
  CREATE TYPE "public"."enum__campaigns_v_version_limits_per_member_triggers_period" AS ENUM('NONE', 'HOURLY', 'DAILY', 'WEEKLY', 'MONTHLY', 'YEARLY', 'TOTAL');
  CREATE TYPE "public"."enum__campaigns_v_version_limits_per_member_units_period" AS ENUM('NONE', 'HOURLY', 'DAILY', 'WEEKLY', 'MONTHLY', 'YEARLY', 'TOTAL');
  CREATE TYPE "public"."enum__campaigns_v_version_status" AS ENUM('draft', 'published');
  CREATE TYPE "public"."enum__campaigns_v_published_locale" AS ENUM('it', 'en');
  CREATE TYPE "public"."enum_decision_policies_actions_channels" AS ENUM('app', 'web', 'push', 'email', 'sms', 'webhook', 'operator');
  CREATE TYPE "public"."enum_decision_policies_actions_type" AS ENUM('AWARD_POINTS', 'UPGRADE_TIER', 'GRANT_BADGE', 'SET_ATTRIBUTE', 'EMIT_EVENT', 'ISSUE_REWARD', 'ISSUE_COUPON', 'SHOW_OFFER', 'SEND_MESSAGE', 'TRIGGER_CAMPAIGN', 'ASK_FOR_FEEDBACK');
  CREATE TYPE "public"."enum_decision_policies_actions_max_risk_level" AS ENUM('LOW', 'MEDIUM', 'HIGH', 'CRITICAL');
  CREATE TYPE "public"."enum_decision_policies_actions_period" AS ENUM('HOUR', 'DAY', 'WEEK', 'MONTH');
  CREATE TYPE "public"."enum_policy_contact_cap_channel" AS ENUM('app', 'web', 'push', 'email', 'sms', 'webhook', 'operator');
  CREATE TYPE "public"."enum_decision_policies_always_apply" AS ENUM('AWARD_POINTS', 'UPGRADE_TIER', 'GRANT_BADGE', 'SET_ATTRIBUTE', 'EMIT_EVENT', 'ISSUE_REWARD', 'ISSUE_COUPON', 'SHOW_OFFER', 'SEND_MESSAGE', 'TRIGGER_CAMPAIGN', 'ASK_FOR_FEEDBACK');
  CREATE TYPE "public"."enum_policy_channel_pref" AS ENUM('app', 'web', 'push', 'email', 'sms', 'webhook', 'operator');
  CREATE TYPE "public"."enum_decision_policies_constraints_block_risk_level" AS ENUM('LOW', 'MEDIUM', 'HIGH', 'CRITICAL');
  CREATE TYPE "public"."enum_decision_policies_scoring_strategy" AS ENUM('PRIORITY', 'WEIGHTED', 'EXPRESSION');
  CREATE TYPE "public"."enum_decision_policies_status" AS ENUM('draft', 'published');
  CREATE TYPE "public"."enum__decision_policies_v_version_actions_channels" AS ENUM('app', 'web', 'push', 'email', 'sms', 'webhook', 'operator');
  CREATE TYPE "public"."enum__decision_policies_v_version_actions_type" AS ENUM('AWARD_POINTS', 'UPGRADE_TIER', 'GRANT_BADGE', 'SET_ATTRIBUTE', 'EMIT_EVENT', 'ISSUE_REWARD', 'ISSUE_COUPON', 'SHOW_OFFER', 'SEND_MESSAGE', 'TRIGGER_CAMPAIGN', 'ASK_FOR_FEEDBACK');
  CREATE TYPE "public"."enum__decision_policies_v_version_actions_max_risk_level" AS ENUM('LOW', 'MEDIUM', 'HIGH', 'CRITICAL');
  CREATE TYPE "public"."enum__decision_policies_v_version_actions_period" AS ENUM('HOUR', 'DAY', 'WEEK', 'MONTH');
  CREATE TYPE "public"."enum__decision_policies_v_version_always_apply" AS ENUM('AWARD_POINTS', 'UPGRADE_TIER', 'GRANT_BADGE', 'SET_ATTRIBUTE', 'EMIT_EVENT', 'ISSUE_REWARD', 'ISSUE_COUPON', 'SHOW_OFFER', 'SEND_MESSAGE', 'TRIGGER_CAMPAIGN', 'ASK_FOR_FEEDBACK');
  CREATE TYPE "public"."enum__decision_policies_v_version_constraints_block_risk_level" AS ENUM('LOW', 'MEDIUM', 'HIGH', 'CRITICAL');
  CREATE TYPE "public"."enum__decision_policies_v_version_scoring_strategy" AS ENUM('PRIORITY', 'WEIGHTED', 'EXPRESSION');
  CREATE TYPE "public"."enum__decision_policies_v_version_status" AS ENUM('draft', 'published');
  CREATE TYPE "public"."enum__decision_policies_v_published_locale" AS ENUM('it', 'en');
  CREATE TYPE "public"."enum_offers_channels" AS ENUM('app', 'web', 'push', 'email', 'sms', 'webhook', 'operator');
  CREATE TYPE "public"."enum_offers_action" AS ENUM('ISSUE_REWARD', 'ISSUE_COUPON', 'SHOW_OFFER', 'SEND_MESSAGE', 'TRIGGER_CAMPAIGN', 'ASK_FOR_FEEDBACK');
  CREATE TYPE "public"."enum_offers_status" AS ENUM('draft', 'published');
  CREATE TYPE "public"."enum__offers_v_version_channels" AS ENUM('app', 'web', 'push', 'email', 'sms', 'webhook', 'operator');
  CREATE TYPE "public"."enum__offers_v_version_action" AS ENUM('ISSUE_REWARD', 'ISSUE_COUPON', 'SHOW_OFFER', 'SEND_MESSAGE', 'TRIGGER_CAMPAIGN', 'ASK_FOR_FEEDBACK');
  CREATE TYPE "public"."enum__offers_v_version_status" AS ENUM('draft', 'published');
  CREATE TYPE "public"."enum__offers_v_published_locale" AS ENUM('it', 'en');
  CREATE TYPE "public"."enum_experiments_variants_overrides_key" AS ENUM('scoring.strategy', 'scoring.valueWeight', 'scoring.costWeight', 'scoring.propensityWeight', 'scoring.churnWeight', 'scoring.recencyWeight', 'scoring.channelPreferenceBonus', 'scoring.expression', 'maxArbitratedPerEvent', 'constraints.minHoursBetweenOffers');
  CREATE TYPE "public"."enum_experiments_status" AS ENUM('draft', 'published');
  CREATE TYPE "public"."enum__experiments_v_version_variants_overrides_key" AS ENUM('scoring.strategy', 'scoring.valueWeight', 'scoring.costWeight', 'scoring.propensityWeight', 'scoring.churnWeight', 'scoring.recencyWeight', 'scoring.channelPreferenceBonus', 'scoring.expression', 'maxArbitratedPerEvent', 'constraints.minHoursBetweenOffers');
  CREATE TYPE "public"."enum__experiments_v_version_status" AS ENUM('draft', 'published');
  CREATE TYPE "public"."enum__experiments_v_published_locale" AS ENUM('it', 'en');
  CREATE TYPE "public"."enum_prediction_providers_serves_keys" AS ENUM('churnRisk', 'purchasePropensity', 'rewardAcceptance', 'offerPropensity', 'engagement', 'customerValue');
  CREATE TYPE "public"."enum_prediction_providers_kind" AS ENUM('RULE_BASED', 'LOCAL_ML', 'EXTERNAL');
  CREATE TYPE "public"."enum_prediction_providers_status" AS ENUM('draft', 'published');
  CREATE TYPE "public"."enum__prediction_providers_v_version_serves_keys" AS ENUM('churnRisk', 'purchasePropensity', 'rewardAcceptance', 'offerPropensity', 'engagement', 'customerValue');
  CREATE TYPE "public"."enum__prediction_providers_v_version_kind" AS ENUM('RULE_BASED', 'LOCAL_ML', 'EXTERNAL');
  CREATE TYPE "public"."enum__prediction_providers_v_version_status" AS ENUM('draft', 'published');
  CREATE TYPE "public"."enum__prediction_providers_v_published_locale" AS ENUM('it', 'en');
  CREATE TYPE "public"."enum_fraud_rules_signals_signal" AS ENUM('REDEMPTION_FREQUENCY', 'MULTI_ACCOUNT_DEVICE', 'ABNORMAL_EARNING', 'RAPID_ACCOUNT_CREATION', 'IMPOSSIBLE_TRAVEL', 'CODE_ABUSE', 'REFUND_RATIO', 'DEVICE_ANOMALY', 'VELOCITY');
  CREATE TYPE "public"."enum_fraud_rules_auto_block_level" AS ENUM('NONE', 'HIGH', 'CRITICAL');
  CREATE TYPE "public"."enum_fraud_rules_status" AS ENUM('draft', 'published');
  CREATE TYPE "public"."enum__fraud_rules_v_version_signals_signal" AS ENUM('REDEMPTION_FREQUENCY', 'MULTI_ACCOUNT_DEVICE', 'ABNORMAL_EARNING', 'RAPID_ACCOUNT_CREATION', 'IMPOSSIBLE_TRAVEL', 'CODE_ABUSE', 'REFUND_RATIO', 'DEVICE_ANOMALY', 'VELOCITY');
  CREATE TYPE "public"."enum__fraud_rules_v_version_auto_block_level" AS ENUM('NONE', 'HIGH', 'CRITICAL');
  CREATE TYPE "public"."enum__fraud_rules_v_version_status" AS ENUM('draft', 'published');
  CREATE TYPE "public"."enum__fraud_rules_v_published_locale" AS ENUM('it', 'en');
  CREATE TYPE "public"."enum_delivery_routing_routes_channels_channel" AS ENUM('app', 'web', 'push', 'email', 'sms', 'webhook', 'operator');
  CREATE TYPE "public"."enum_delivery_routing_routes_action" AS ENUM('SEND_MESSAGE', 'SHOW_OFFER', 'ASK_FOR_FEEDBACK');
  CREATE TYPE "public"."enum_delivery_routing_enabled_channels" AS ENUM('app', 'web', 'push', 'email', 'sms', 'webhook', 'operator');
  CREATE TYPE "public"."enum_routing_max_per_day_channel" AS ENUM('app', 'web', 'push', 'email', 'sms', 'webhook', 'operator');
  CREATE TYPE "public"."enum_delivery_routing_templates_action" AS ENUM('SEND_MESSAGE', 'SHOW_OFFER', 'ASK_FOR_FEEDBACK');
  CREATE TYPE "public"."enum_delivery_routing_quiet_hours_fallback" AS ENUM('app', 'web', 'operator');
  CREATE TYPE "public"."enum_delivery_routing_status" AS ENUM('draft', 'published');
  CREATE TYPE "public"."enum__delivery_routing_v_version_routes_channels_channel" AS ENUM('app', 'web', 'push', 'email', 'sms', 'webhook', 'operator');
  CREATE TYPE "public"."enum__delivery_routing_v_version_routes_action" AS ENUM('SEND_MESSAGE', 'SHOW_OFFER', 'ASK_FOR_FEEDBACK');
  CREATE TYPE "public"."enum__delivery_routing_v_version_enabled_channels" AS ENUM('app', 'web', 'push', 'email', 'sms', 'webhook', 'operator');
  CREATE TYPE "public"."enum__delivery_routing_v_version_templates_action" AS ENUM('SEND_MESSAGE', 'SHOW_OFFER', 'ASK_FOR_FEEDBACK');
  CREATE TYPE "public"."enum__delivery_routing_v_version_quiet_hours_fallback" AS ENUM('app', 'web', 'operator');
  CREATE TYPE "public"."enum__delivery_routing_v_version_status" AS ENUM('draft', 'published');
  CREATE TYPE "public"."enum__delivery_routing_v_published_locale" AS ENUM('it', 'en');
  CREATE TYPE "public"."enum_consent_purposes_legal_basis" AS ENUM('consent', 'contract', 'legitimate_interest', 'legal_obligation');
  CREATE TYPE "public"."enum_point_rules_conditions_op" AS ENUM('EQ', 'NE', 'GT', 'GTE', 'LT', 'LTE', 'IN', 'CONTAINS', 'MATCHES', 'EXISTS', 'GEO_WITHIN');
  CREATE TYPE "public"."enum_point_rules_target_tiers" AS ENUM('BASE', 'PLUS', 'TOP');
  CREATE TYPE "public"."enum_point_rules_target_channels" AS ENUM('web', 'app', 'sportello', 'negozio', 'call-center', 'partner');
  CREATE TYPE "public"."enum_point_rules_status" AS ENUM('draft', 'published');
  CREATE TYPE "public"."enum__point_rules_v_version_conditions_op" AS ENUM('EQ', 'NE', 'GT', 'GTE', 'LT', 'LTE', 'IN', 'CONTAINS', 'MATCHES', 'EXISTS', 'GEO_WITHIN');
  CREATE TYPE "public"."enum__point_rules_v_version_target_tiers" AS ENUM('BASE', 'PLUS', 'TOP');
  CREATE TYPE "public"."enum__point_rules_v_version_target_channels" AS ENUM('web', 'app', 'sportello', 'negozio', 'call-center', 'partner');
  CREATE TYPE "public"."enum__point_rules_v_version_status" AS ENUM('draft', 'published');
  CREATE TYPE "public"."enum__point_rules_v_published_locale" AS ENUM('it', 'en');
  CREATE TYPE "public"."enum_achievements_conditions_op" AS ENUM('EQ', 'NE', 'GT', 'GTE', 'LT', 'IN');
  CREATE TYPE "public"."enum_achievements_metric" AS ENUM('OCCURRENCES', 'ATTRIBUTE_SUM', 'UNIQUE_ATTRIBUTE_VALUES');
  CREATE TYPE "public"."enum_achievements_goal_type" AS ENUM('OVERALL', 'LAST_DAYS', 'CONSECUTIVE');
  CREATE TYPE "public"."enum_achievements_goal_period" AS ENUM('HOUR', 'DAY', 'WEEK', 'MONTH', 'YEAR');
  CREATE TYPE "public"."enum_achievements_event_limit_period" AS ENUM('HOUR', 'DAY', 'WEEK', 'MONTH', 'YEAR', 'TOTAL');
  CREATE TYPE "public"."enum_achievements_completion_limit_period" AS ENUM('HOUR', 'DAY', 'WEEK', 'MONTH', 'YEAR', 'TOTAL');
  CREATE TYPE "public"."enum_achievements_status" AS ENUM('draft', 'published');
  CREATE TYPE "public"."enum__achievements_v_version_conditions_op" AS ENUM('EQ', 'NE', 'GT', 'GTE', 'LT', 'IN');
  CREATE TYPE "public"."enum__achievements_v_version_metric" AS ENUM('OCCURRENCES', 'ATTRIBUTE_SUM', 'UNIQUE_ATTRIBUTE_VALUES');
  CREATE TYPE "public"."enum__achievements_v_version_goal_type" AS ENUM('OVERALL', 'LAST_DAYS', 'CONSECUTIVE');
  CREATE TYPE "public"."enum__achievements_v_version_goal_period" AS ENUM('HOUR', 'DAY', 'WEEK', 'MONTH', 'YEAR');
  CREATE TYPE "public"."enum__achievements_v_version_event_limit_period" AS ENUM('HOUR', 'DAY', 'WEEK', 'MONTH', 'YEAR', 'TOTAL');
  CREATE TYPE "public"."enum__achievements_v_version_completion_limit_period" AS ENUM('HOUR', 'DAY', 'WEEK', 'MONTH', 'YEAR', 'TOTAL');
  CREATE TYPE "public"."enum__achievements_v_version_status" AS ENUM('draft', 'published');
  CREATE TYPE "public"."enum__achievements_v_published_locale" AS ENUM('it', 'en');
  CREATE TYPE "public"."enum_challenges_availability_days_of_week" AS ENUM('1', '2', '3', '4', '5', '6', '7');
  CREATE TYPE "public"."enum_challenges_visibility_tiers" AS ENUM('BASE', 'PLUS', 'TOP');
  CREATE TYPE "public"."enum_challenges_milestones_conditions_op" AS ENUM('EQ', 'NE', 'GT', 'GTE', 'LT', 'IN');
  CREATE TYPE "public"."enum_challenges_milestones_kind" AS ENUM('DIRECT', 'REFERRAL');
  CREATE TYPE "public"."enum_challenges_milestones_metric" AS ENUM('OCCURRENCES', 'ATTRIBUTE_SUM', 'UNIQUE_ATTRIBUTE_VALUES');
  CREATE TYPE "public"."enum_challenges_milestones_goal_type" AS ENUM('OVERALL', 'LAST_DAYS', 'CONSECUTIVE');
  CREATE TYPE "public"."enum_challenges_milestones_goal_period" AS ENUM('HOUR', 'DAY', 'WEEK', 'MONTH', 'YEAR');
  CREATE TYPE "public"."enum_challenges_milestones_event_limit_period" AS ENUM('HOUR', 'DAY', 'WEEK', 'MONTH', 'YEAR', 'TOTAL');
  CREATE TYPE "public"."enum_challenges_milestones_completion_limit_period" AS ENUM('HOUR', 'DAY', 'WEEK', 'MONTH', 'YEAR', 'TOTAL');
  CREATE TYPE "public"."enum_challenges_rules_effects_type" AS ENUM('ADD_UNITS', 'DEDUCT_UNITS', 'GIVE_REWARD', 'SET_ATTRIBUTE', 'REMOVE_ATTRIBUTE', 'GRANT_BADGE', 'ASSIGN_TIER', 'EMIT_EVENT');
  CREATE TYPE "public"."enum_challenges_rules_trigger" AS ENUM('MILESTONE_PROGRESSED', 'CHALLENGE_COMPLETED');
  CREATE TYPE "public"."enum_challenges_visibility_mode" AS ENUM('EVERYONE', 'SEGMENTS', 'TIERS', 'HIDDEN');
  CREATE TYPE "public"."enum_challenges_completion_limit_period" AS ENUM('HOUR', 'DAY', 'WEEK', 'MONTH', 'YEAR', 'TOTAL');
  CREATE TYPE "public"."enum_challenges_status" AS ENUM('draft', 'published');
  CREATE TYPE "public"."enum__challenges_v_version_availability_days_of_week" AS ENUM('1', '2', '3', '4', '5', '6', '7');
  CREATE TYPE "public"."enum__challenges_v_version_visibility_tiers" AS ENUM('BASE', 'PLUS', 'TOP');
  CREATE TYPE "public"."enum__challenges_v_version_milestones_conditions_op" AS ENUM('EQ', 'NE', 'GT', 'GTE', 'LT', 'IN');
  CREATE TYPE "public"."enum__challenges_v_version_milestones_kind" AS ENUM('DIRECT', 'REFERRAL');
  CREATE TYPE "public"."enum__challenges_v_version_milestones_metric" AS ENUM('OCCURRENCES', 'ATTRIBUTE_SUM', 'UNIQUE_ATTRIBUTE_VALUES');
  CREATE TYPE "public"."enum__challenges_v_version_milestones_goal_type" AS ENUM('OVERALL', 'LAST_DAYS', 'CONSECUTIVE');
  CREATE TYPE "public"."enum__challenges_v_version_milestones_goal_period" AS ENUM('HOUR', 'DAY', 'WEEK', 'MONTH', 'YEAR');
  CREATE TYPE "public"."enum__challenges_v_version_milestones_event_limit_period" AS ENUM('HOUR', 'DAY', 'WEEK', 'MONTH', 'YEAR', 'TOTAL');
  CREATE TYPE "public"."enum__challenges_v_version_milestones_completion_limit_period" AS ENUM('HOUR', 'DAY', 'WEEK', 'MONTH', 'YEAR', 'TOTAL');
  CREATE TYPE "public"."enum__challenges_v_version_rules_effects_type" AS ENUM('ADD_UNITS', 'DEDUCT_UNITS', 'GIVE_REWARD', 'SET_ATTRIBUTE', 'REMOVE_ATTRIBUTE', 'GRANT_BADGE', 'ASSIGN_TIER', 'EMIT_EVENT');
  CREATE TYPE "public"."enum__challenges_v_version_rules_trigger" AS ENUM('MILESTONE_PROGRESSED', 'CHALLENGE_COMPLETED');
  CREATE TYPE "public"."enum__challenges_v_version_visibility_mode" AS ENUM('EVERYONE', 'SEGMENTS', 'TIERS', 'HIDDEN');
  CREATE TYPE "public"."enum__challenges_v_version_completion_limit_period" AS ENUM('HOUR', 'DAY', 'WEEK', 'MONTH', 'YEAR', 'TOTAL');
  CREATE TYPE "public"."enum__challenges_v_version_status" AS ENUM('draft', 'published');
  CREATE TYPE "public"."enum__challenges_v_published_locale" AS ENUM('it', 'en');
  CREATE TYPE "public"."enum_leaderboards_metric" AS ENUM('UNITS_EARNED', 'TRANSACTIONS_COUNT', 'TRANSACTIONS_VALUE', 'CUSTOM_EVENTS_COUNT', 'ACHIEVEMENT_PROGRESS');
  CREATE TYPE "public"."enum_leaderboards_rewarding_cycle_period" AS ENUM('DAY', 'WEEK', 'MONTH', 'YEAR');
  CREATE TYPE "public"."enum_leaderboards_visibility" AS ENUM('EVERYONE', 'HIDDEN');
  CREATE TYPE "public"."enum_leaderboards_status" AS ENUM('draft', 'published');
  CREATE TYPE "public"."enum__leaderboards_v_version_metric" AS ENUM('UNITS_EARNED', 'TRANSACTIONS_COUNT', 'TRANSACTIONS_VALUE', 'CUSTOM_EVENTS_COUNT', 'ACHIEVEMENT_PROGRESS');
  CREATE TYPE "public"."enum__leaderboards_v_version_rewarding_cycle_period" AS ENUM('DAY', 'WEEK', 'MONTH', 'YEAR');
  CREATE TYPE "public"."enum__leaderboards_v_version_visibility" AS ENUM('EVERYONE', 'HIDDEN');
  CREATE TYPE "public"."enum__leaderboards_v_version_status" AS ENUM('draft', 'published');
  CREATE TYPE "public"."enum__leaderboards_v_published_locale" AS ENUM('it', 'en');
  CREATE TYPE "public"."enum_fortune_wheels_mode" AS ENUM('PROBABILITY', 'INSTANT_WIN_BACKED');
  CREATE TYPE "public"."enum_fortune_wheels_spins_period" AS ENUM('HOUR', 'DAY', 'WEEK', 'MONTH', 'TOTAL');
  CREATE TYPE "public"."enum_fortune_wheels_visibility" AS ENUM('EVERYONE', 'HIDDEN');
  CREATE TYPE "public"."enum_fortune_wheels_status" AS ENUM('draft', 'published');
  CREATE TYPE "public"."enum__fortune_wheels_v_version_mode" AS ENUM('PROBABILITY', 'INSTANT_WIN_BACKED');
  CREATE TYPE "public"."enum__fortune_wheels_v_version_spins_period" AS ENUM('HOUR', 'DAY', 'WEEK', 'MONTH', 'TOTAL');
  CREATE TYPE "public"."enum__fortune_wheels_v_version_visibility" AS ENUM('EVERYONE', 'HIDDEN');
  CREATE TYPE "public"."enum__fortune_wheels_v_version_status" AS ENUM('draft', 'published');
  CREATE TYPE "public"."enum__fortune_wheels_v_published_locale" AS ENUM('it', 'en');
  CREATE TYPE "public"."enum_contests_status" AS ENUM('draft', 'published');
  CREATE TYPE "public"."enum__contests_v_version_status" AS ENUM('draft', 'published');
  CREATE TYPE "public"."enum__contests_v_published_locale" AS ENUM('it', 'en');
  CREATE TYPE "public"."enum_contest_cards_min_tier" AS ENUM('BASE', 'PLUS', 'TOP');
  CREATE TYPE "public"."enum_contest_cards_status" AS ENUM('draft', 'published');
  CREATE TYPE "public"."enum__contest_cards_v_version_min_tier" AS ENUM('BASE', 'PLUS', 'TOP');
  CREATE TYPE "public"."enum__contest_cards_v_version_status" AS ENUM('draft', 'published');
  CREATE TYPE "public"."enum__contest_cards_v_published_locale" AS ENUM('it', 'en');
  CREATE TYPE "public"."enum_win_cards_status" AS ENUM('draft', 'published');
  CREATE TYPE "public"."enum__win_cards_v_version_status" AS ENUM('draft', 'published');
  CREATE TYPE "public"."enum__win_cards_v_published_locale" AS ENUM('it', 'en');
  CREATE TYPE "public"."enum_popups_trigger" AS ENUM('login', 'tier_changed', 'contest_won', 'action_received');
  CREATE TYPE "public"."enum_popups_segment_min_tier" AS ENUM('BASE', 'PLUS', 'TOP');
  CREATE TYPE "public"."enum_popups_status" AS ENUM('draft', 'published');
  CREATE TYPE "public"."enum__popups_v_version_trigger" AS ENUM('login', 'tier_changed', 'contest_won', 'action_received');
  CREATE TYPE "public"."enum__popups_v_version_segment_min_tier" AS ENUM('BASE', 'PLUS', 'TOP');
  CREATE TYPE "public"."enum__popups_v_version_status" AS ENUM('draft', 'published');
  CREATE TYPE "public"."enum__popups_v_published_locale" AS ENUM('it', 'en');
  CREATE TYPE "public"."enum_rewards_type" AS ENUM('VOUCHER', 'PHYSICAL', 'SERVICE', 'CASHBACK', 'DISCOUNT_PERCENT', 'DISCOUNT_VALUE', 'FREE_SERVICE', 'EVENT_INVITATION', 'GIFT', 'DONATION');
  CREATE TYPE "public"."enum_rewards_min_tier" AS ENUM('BASE', 'PLUS', 'TOP');
  CREATE TYPE "public"."enum_rewards_status" AS ENUM('draft', 'published');
  CREATE TYPE "public"."enum__rewards_v_version_type" AS ENUM('VOUCHER', 'PHYSICAL', 'SERVICE', 'CASHBACK', 'DISCOUNT_PERCENT', 'DISCOUNT_VALUE', 'FREE_SERVICE', 'EVENT_INVITATION', 'GIFT', 'DONATION');
  CREATE TYPE "public"."enum__rewards_v_version_min_tier" AS ENUM('BASE', 'PLUS', 'TOP');
  CREATE TYPE "public"."enum__rewards_v_version_status" AS ENUM('draft', 'published');
  CREATE TYPE "public"."enum__rewards_v_published_locale" AS ENUM('it', 'en');
  CREATE TYPE "public"."enum_promo_codes_kind" AS ENUM('QR', 'PROMO', 'SINGLE_USE');
  CREATE TYPE "public"."enum_promo_codes_status" AS ENUM('draft', 'published');
  CREATE TYPE "public"."enum__promo_codes_v_version_kind" AS ENUM('QR', 'PROMO', 'SINGLE_USE');
  CREATE TYPE "public"."enum__promo_codes_v_version_status" AS ENUM('draft', 'published');
  CREATE TYPE "public"."enum__promo_codes_v_published_locale" AS ENUM('it', 'en');
  CREATE TYPE "public"."enum_wallets_expiration" AS ENUM('NONE', 'AFTER_DAYS', 'END_OF_MONTH', 'END_OF_YEAR', 'ANNUAL_DATE', 'END_OF_NEXT_PROGRAM_YEAR');
  CREATE TYPE "public"."enum_tier_sets_conditions_metric" AS ENUM('ACTIVE_UNITS', 'TOTAL_EARNED_UNITS', 'TOTAL_SPENDING', 'MONTHS_SINCE_JOINING', 'EARNED_UNITS_IN_PERIOD', 'CUSTOM_FIELD');
  CREATE TYPE "public"."enum_tier_sets_match" AS ENUM('ALL', 'ANY');
  CREATE TYPE "public"."enum_tier_sets_downgrade_mode" AS ENUM('NONE', 'AUTOMATIC', 'ANNIVERSARY', 'CUSTOM_DATES', 'INTERVAL_MONTHS', 'ANNUAL_ONE_LEVEL');
  CREATE TYPE "public"."enum_tier_sets_status" AS ENUM('draft', 'published');
  CREATE TYPE "public"."enum__tier_sets_v_version_conditions_metric" AS ENUM('ACTIVE_UNITS', 'TOTAL_EARNED_UNITS', 'TOTAL_SPENDING', 'MONTHS_SINCE_JOINING', 'EARNED_UNITS_IN_PERIOD', 'CUSTOM_FIELD');
  CREATE TYPE "public"."enum__tier_sets_v_version_match" AS ENUM('ALL', 'ANY');
  CREATE TYPE "public"."enum__tier_sets_v_version_downgrade_mode" AS ENUM('NONE', 'AUTOMATIC', 'ANNIVERSARY', 'CUSTOM_DATES', 'INTERVAL_MONTHS', 'ANNUAL_ONE_LEVEL');
  CREATE TYPE "public"."enum__tier_sets_v_version_status" AS ENUM('draft', 'published');
  CREATE TYPE "public"."enum__tier_sets_v_published_locale" AS ENUM('it', 'en');
  CREATE TYPE "public"."enum_tiers_status" AS ENUM('draft', 'in_review', 'approved', 'legal_review', 'scheduled', 'published', 'locked');
  CREATE TYPE "public"."enum_segments_criteria_type" AS ENUM('ANNIVERSARY', 'AVG_ACTION_VALUE', 'ACTION_COUNT', 'ACTION_VALUE', 'LAST_ACTION_DAYS_AGO', 'ACTION_PERIOD', 'BOUGHT_SKU', 'BOUGHT_LABEL', 'BOUGHT_BRAND', 'BOUGHT_CATEGORY', 'ACTION_IN_CHANNEL', 'CHANNEL_SHARE', 'HAS_LABEL', 'LABEL_VALUE', 'STATIC_LIST', 'TIER', 'POINTS_BALANCE', 'CONSENT');
  CREATE TYPE "public"."enum_segments_match" AS ENUM('ALL', 'ANY');
  CREATE TYPE "public"."enum_segments_status" AS ENUM('draft', 'published');
  CREATE TYPE "public"."enum__segments_v_version_criteria_type" AS ENUM('ANNIVERSARY', 'AVG_ACTION_VALUE', 'ACTION_COUNT', 'ACTION_VALUE', 'LAST_ACTION_DAYS_AGO', 'ACTION_PERIOD', 'BOUGHT_SKU', 'BOUGHT_LABEL', 'BOUGHT_BRAND', 'BOUGHT_CATEGORY', 'ACTION_IN_CHANNEL', 'CHANNEL_SHARE', 'HAS_LABEL', 'LABEL_VALUE', 'STATIC_LIST', 'TIER', 'POINTS_BALANCE', 'CONSENT');
  CREATE TYPE "public"."enum__segments_v_version_match" AS ENUM('ALL', 'ANY');
  CREATE TYPE "public"."enum__segments_v_version_status" AS ENUM('draft', 'published');
  CREATE TYPE "public"."enum__segments_v_published_locale" AS ENUM('it', 'en');
  CREATE TYPE "public"."enum_event_schemas_attributes_type" AS ENUM('BOOLEAN', 'DATETIME', 'NUMBER', 'TEXT', 'LIST');
  CREATE TYPE "public"."enum_event_schemas_status" AS ENUM('draft', 'published');
  CREATE TYPE "public"."enum__event_schemas_v_version_attributes_type" AS ENUM('BOOLEAN', 'DATETIME', 'NUMBER', 'TEXT', 'LIST');
  CREATE TYPE "public"."enum__event_schemas_v_version_status" AS ENUM('draft', 'published');
  CREATE TYPE "public"."enum__event_schemas_v_published_locale" AS ENUM('it', 'en');
  CREATE TYPE "public"."enum_custom_field_schemas_fields_type" AS ENUM('STRING', 'NUMBER', 'BOOLEAN', 'DATE', 'SINGLE_SELECT', 'MULTI_SELECT');
  CREATE TYPE "public"."enum_custom_field_schemas_entity" AS ENUM('MEMBER', 'CAMPAIGN', 'REWARD', 'WHEEL', 'TRANSACTION');
  CREATE TYPE "public"."enum_custom_field_schemas_edit_role" AS ENUM('customer_care', 'marketing', 'platform_admin');
  CREATE TYPE "public"."enum_channels_kind" AS ENUM('ONLINE', 'STORE', 'CALL_CENTER', 'PARTNER');
  CREATE TYPE "public"."enum_programs_status" AS ENUM('draft', 'published');
  CREATE TYPE "public"."enum__programs_v_version_status" AS ENUM('draft', 'published');
  CREATE TYPE "public"."enum__programs_v_published_locale" AS ENUM('it', 'en');
  CREATE TYPE "public"."enum_message_templates_channel" AS ENUM('EMAIL', 'SMS', 'PUSH', 'IN_APP');
  CREATE TYPE "public"."enum_message_templates_locale" AS ENUM('it', 'en');
  CREATE TYPE "public"."enum_message_templates_status" AS ENUM('draft', 'published');
  CREATE TYPE "public"."enum__message_templates_v_version_channel" AS ENUM('EMAIL', 'SMS', 'PUSH', 'IN_APP');
  CREATE TYPE "public"."enum__message_templates_v_version_locale" AS ENUM('it', 'en');
  CREATE TYPE "public"."enum__message_templates_v_version_status" AS ENUM('draft', 'published');
  CREATE TYPE "public"."enum__message_templates_v_published_locale" AS ENUM('it', 'en');
  CREATE TYPE "public"."enum_roles_permissions" AS ENUM('members.read', 'members.write', 'members.anonymize', 'ledger.manual_posting', 'ledger.block', 'campaigns.edit', 'campaigns.publish', 'rewards.edit', 'rewards.fulfill', 'contests.edit', 'contests.legal', 'content.edit', 'content.publish', 'segments.edit', 'settings.edit', 'roles.edit', 'api_keys.edit', 'exports.run', 'audit.read', 'operator.console', 'decisions.edit', 'decisions.publish', 'decisions.simulate', 'fraud.edit', 'fraud.review', 'consents.edit');
  CREATE TYPE "public"."enum_settings_locales" AS ENUM('it', 'en');
  CREATE TYPE "public"."enum_settings_identification_priority" AS ENUM('oidcSub', 'crmId', 'sapBusinessPartner', 'email', 'phone', 'loyaltyCard');
  CREATE TYPE "public"."enum_settings_points_expiry" AS ENUM('END_OF_NEXT_PROGRAM_YEAR', 'DAYS_AFTER_EARNING', 'NEVER');
  CREATE TYPE "public"."enum_settings_tier_downgrade" AS ENUM('ANNUAL_ONE_LEVEL', 'ANNUAL_TO_QUALIFIED', 'NONE');
  CREATE TYPE "public"."enum_settings_referral_trigger" AS ENUM('ON_ENROLLMENT', 'ON_FIRST_ACTION', 'ON_FIRST_TRANSACTION');
  CREATE TYPE "public"."enum_settings_loyalty_card_format" AS ENUM('ALPHANUMERIC', 'LETTERS', 'DIGITS');
  CREATE TABLE "campaigns_visibility_tiers" (
  	"order" integer NOT NULL,
  	"parent_id" integer NOT NULL,
  	"value" "enum_campaigns_visibility_tiers",
  	"id" serial PRIMARY KEY NOT NULL
  );
  
  CREATE TABLE "campaigns_rules_conditions" (
  	"_order" integer NOT NULL,
  	"_parent_id" varchar NOT NULL,
  	"id" varchar PRIMARY KEY NOT NULL,
  	"attribute" varchar,
  	"op" "enum_campaigns_rules_conditions_op",
  	"value" varchar,
  	"expression" varchar
  );
  
  CREATE TABLE "campaigns_rules_effects" (
  	"_order" integer NOT NULL,
  	"_parent_id" varchar NOT NULL,
  	"id" varchar PRIMARY KEY NOT NULL,
  	"type" "enum_campaigns_rules_effects_type",
  	"wallet_id" integer,
  	"fixed" numeric,
  	"per_eur" numeric,
  	"value_expression" varchar,
  	"reward_id" integer,
  	"badge_id" integer,
  	"tier_code" varchar,
  	"attribute_key" varchar,
  	"attribute_value" varchar,
  	"event_type" varchar,
  	"level" numeric,
  	"expires_at_expression" varchar,
  	"pending_until_expression" varchar
  );
  
  CREATE TABLE "campaigns_rules" (
  	"_order" integer NOT NULL,
  	"_parent_id" integer NOT NULL,
  	"id" varchar PRIMARY KEY NOT NULL,
  	"rule_id" varchar
  );
  
  CREATE TABLE "campaigns" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"campaign_id" varchar,
  	"kind" "enum_campaigns_kind" DEFAULT 'DIRECT',
  	"trigger_type" "enum_campaigns_trigger_type",
  	"trigger_action_type_id" integer,
  	"trigger_reference" varchar,
  	"trigger_line_filter_sku_collection_id" integer,
  	"starts_at" timestamp(3) with time zone,
  	"ends_at" timestamp(3) with time zone,
  	"display_order" numeric DEFAULT 0,
  	"visibility_mode" "enum_campaigns_visibility_mode" DEFAULT 'EVERYONE',
  	"limits_per_member_triggers" numeric DEFAULT 0,
  	"limits_per_member_triggers_period" "enum_campaigns_limits_per_member_triggers_period" DEFAULT 'NONE',
  	"limits_global_units" numeric DEFAULT 0,
  	"limits_per_member_units" numeric DEFAULT 0,
  	"limits_per_member_units_period" "enum_campaigns_limits_per_member_units_period" DEFAULT 'NONE',
  	"schedule" varchar,
  	"referral_max_levels" numeric DEFAULT 1,
  	"custom_attributes" jsonb,
  	"photo_id" integer,
  	"status" "enum_campaigns_status" DEFAULT 'draft',
  	"updated_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"created_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"_status" "enum_campaigns_status" DEFAULT 'draft'
  );
  
  CREATE TABLE "campaigns_locales" (
  	"name" varchar,
  	"id" serial PRIMARY KEY NOT NULL,
  	"_locale" "_locales" NOT NULL,
  	"_parent_id" integer NOT NULL
  );
  
  CREATE TABLE "campaigns_texts" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"order" integer NOT NULL,
  	"parent_id" integer NOT NULL,
  	"path" varchar NOT NULL,
  	"text" varchar
  );
  
  CREATE TABLE "campaigns_rels" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"order" integer,
  	"parent_id" integer NOT NULL,
  	"path" varchar NOT NULL,
  	"segments_id" integer
  );
  
  CREATE TABLE "_campaigns_v_version_visibility_tiers" (
  	"order" integer NOT NULL,
  	"parent_id" integer NOT NULL,
  	"value" "enum__campaigns_v_version_visibility_tiers",
  	"id" serial PRIMARY KEY NOT NULL
  );
  
  CREATE TABLE "_campaigns_v_version_rules_conditions" (
  	"_order" integer NOT NULL,
  	"_parent_id" integer NOT NULL,
  	"id" serial PRIMARY KEY NOT NULL,
  	"attribute" varchar,
  	"op" "enum__campaigns_v_version_rules_conditions_op",
  	"value" varchar,
  	"expression" varchar,
  	"_uuid" varchar
  );
  
  CREATE TABLE "_campaigns_v_version_rules_effects" (
  	"_order" integer NOT NULL,
  	"_parent_id" integer NOT NULL,
  	"id" serial PRIMARY KEY NOT NULL,
  	"type" "enum__campaigns_v_version_rules_effects_type",
  	"wallet_id" integer,
  	"fixed" numeric,
  	"per_eur" numeric,
  	"value_expression" varchar,
  	"reward_id" integer,
  	"badge_id" integer,
  	"tier_code" varchar,
  	"attribute_key" varchar,
  	"attribute_value" varchar,
  	"event_type" varchar,
  	"level" numeric,
  	"expires_at_expression" varchar,
  	"pending_until_expression" varchar,
  	"_uuid" varchar
  );
  
  CREATE TABLE "_campaigns_v_version_rules" (
  	"_order" integer NOT NULL,
  	"_parent_id" integer NOT NULL,
  	"id" serial PRIMARY KEY NOT NULL,
  	"rule_id" varchar,
  	"_uuid" varchar
  );
  
  CREATE TABLE "_campaigns_v" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"parent_id" integer,
  	"version_campaign_id" varchar,
  	"version_kind" "enum__campaigns_v_version_kind" DEFAULT 'DIRECT',
  	"version_trigger_type" "enum__campaigns_v_version_trigger_type",
  	"version_trigger_action_type_id" integer,
  	"version_trigger_reference" varchar,
  	"version_trigger_line_filter_sku_collection_id" integer,
  	"version_starts_at" timestamp(3) with time zone,
  	"version_ends_at" timestamp(3) with time zone,
  	"version_display_order" numeric DEFAULT 0,
  	"version_visibility_mode" "enum__campaigns_v_version_visibility_mode" DEFAULT 'EVERYONE',
  	"version_limits_per_member_triggers" numeric DEFAULT 0,
  	"version_limits_per_member_triggers_period" "enum__campaigns_v_version_limits_per_member_triggers_period" DEFAULT 'NONE',
  	"version_limits_global_units" numeric DEFAULT 0,
  	"version_limits_per_member_units" numeric DEFAULT 0,
  	"version_limits_per_member_units_period" "enum__campaigns_v_version_limits_per_member_units_period" DEFAULT 'NONE',
  	"version_schedule" varchar,
  	"version_referral_max_levels" numeric DEFAULT 1,
  	"version_custom_attributes" jsonb,
  	"version_photo_id" integer,
  	"version_status" "enum__campaigns_v_version_status" DEFAULT 'draft',
  	"version_updated_at" timestamp(3) with time zone,
  	"version_created_at" timestamp(3) with time zone,
  	"version__status" "enum__campaigns_v_version_status" DEFAULT 'draft',
  	"created_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"updated_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"snapshot" boolean,
  	"published_locale" "enum__campaigns_v_published_locale",
  	"latest" boolean
  );
  
  CREATE TABLE "_campaigns_v_locales" (
  	"version_name" varchar,
  	"id" serial PRIMARY KEY NOT NULL,
  	"_locale" "_locales" NOT NULL,
  	"_parent_id" integer NOT NULL
  );
  
  CREATE TABLE "_campaigns_v_texts" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"order" integer NOT NULL,
  	"parent_id" integer NOT NULL,
  	"path" varchar NOT NULL,
  	"text" varchar
  );
  
  CREATE TABLE "_campaigns_v_rels" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"order" integer,
  	"parent_id" integer NOT NULL,
  	"path" varchar NOT NULL,
  	"segments_id" integer
  );
  
  CREATE TABLE "decision_policies_actions_channels" (
  	"order" integer NOT NULL,
  	"parent_id" varchar NOT NULL,
  	"value" "enum_decision_policies_actions_channels",
  	"id" serial PRIMARY KEY NOT NULL
  );
  
  CREATE TABLE "decision_policies_actions" (
  	"_order" integer NOT NULL,
  	"_parent_id" integer NOT NULL,
  	"id" varchar PRIMARY KEY NOT NULL,
  	"type" "enum_decision_policies_actions_type",
  	"enabled" boolean DEFAULT true,
  	"priority" numeric DEFAULT 50,
  	"base_value" numeric DEFAULT 1,
  	"cost" numeric DEFAULT 0,
  	"max_risk_level" "enum_decision_policies_actions_max_risk_level" DEFAULT 'MEDIUM',
  	"max_per_member_per_period" numeric DEFAULT 0,
  	"period" "enum_decision_policies_actions_period" DEFAULT 'MONTH',
  	"cooldown_hours" numeric DEFAULT 0
  );
  
  CREATE TABLE "contact_cap_7d" (
  	"_order" integer NOT NULL,
  	"_parent_id" integer NOT NULL,
  	"id" varchar PRIMARY KEY NOT NULL,
  	"channel" "enum_policy_contact_cap_channel",
  	"max" numeric
  );
  
  CREATE TABLE "decision_policies_scoring_tier_boost" (
  	"_order" integer NOT NULL,
  	"_parent_id" integer NOT NULL,
  	"id" varchar PRIMARY KEY NOT NULL,
  	"tier" varchar,
  	"boost" numeric
  );
  
  CREATE TABLE "decision_policies_always_apply" (
  	"order" integer NOT NULL,
  	"parent_id" integer NOT NULL,
  	"value" "enum_decision_policies_always_apply",
  	"id" serial PRIMARY KEY NOT NULL
  );
  
  CREATE TABLE "channel_pref" (
  	"_order" integer NOT NULL,
  	"_parent_id" integer NOT NULL,
  	"id" varchar PRIMARY KEY NOT NULL,
  	"channel" "enum_policy_channel_pref"
  );
  
  CREATE TABLE "decision_policies" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"code" varchar,
  	"is_default" boolean DEFAULT false,
  	"version" numeric DEFAULT 1,
  	"constraints_quiet_hours_from" numeric DEFAULT 21,
  	"constraints_quiet_hours_to" numeric DEFAULT 8,
  	"constraints_min_hours_between_offers" numeric DEFAULT 6,
  	"constraints_daily_units_budget" numeric DEFAULT 0,
  	"constraints_block_risk_level" "enum_decision_policies_constraints_block_risk_level" DEFAULT 'CRITICAL',
  	"scoring_strategy" "enum_decision_policies_scoring_strategy" DEFAULT 'WEIGHTED',
  	"scoring_value_weight" numeric DEFAULT 1,
  	"scoring_cost_weight" numeric DEFAULT 0.8,
  	"scoring_propensity_weight" numeric DEFAULT 2,
  	"scoring_churn_weight" numeric DEFAULT 1.5,
  	"scoring_recency_weight" numeric DEFAULT 0.5,
  	"scoring_channel_preference_bonus" numeric DEFAULT 1,
  	"scoring_expression" varchar,
  	"max_arbitrated_per_event" numeric DEFAULT 1,
  	"status" "enum_decision_policies_status" DEFAULT 'draft',
  	"updated_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"created_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"_status" "enum_decision_policies_status" DEFAULT 'draft'
  );
  
  CREATE TABLE "decision_policies_locales" (
  	"name" varchar,
  	"id" serial PRIMARY KEY NOT NULL,
  	"_locale" "_locales" NOT NULL,
  	"_parent_id" integer NOT NULL
  );
  
  CREATE TABLE "decision_policies_texts" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"order" integer NOT NULL,
  	"parent_id" integer NOT NULL,
  	"path" varchar NOT NULL,
  	"text" varchar
  );
  
  CREATE TABLE "_decision_policies_v_version_actions_channels" (
  	"order" integer NOT NULL,
  	"parent_id" integer NOT NULL,
  	"value" "enum__decision_policies_v_version_actions_channels",
  	"id" serial PRIMARY KEY NOT NULL
  );
  
  CREATE TABLE "_decision_policies_v_version_actions" (
  	"_order" integer NOT NULL,
  	"_parent_id" integer NOT NULL,
  	"id" serial PRIMARY KEY NOT NULL,
  	"type" "enum__decision_policies_v_version_actions_type",
  	"enabled" boolean DEFAULT true,
  	"priority" numeric DEFAULT 50,
  	"base_value" numeric DEFAULT 1,
  	"cost" numeric DEFAULT 0,
  	"max_risk_level" "enum__decision_policies_v_version_actions_max_risk_level" DEFAULT 'MEDIUM',
  	"max_per_member_per_period" numeric DEFAULT 0,
  	"period" "enum__decision_policies_v_version_actions_period" DEFAULT 'MONTH',
  	"cooldown_hours" numeric DEFAULT 0,
  	"_uuid" varchar
  );
  
  CREATE TABLE "_contact_cap_7d_v" (
  	"_order" integer NOT NULL,
  	"_parent_id" integer NOT NULL,
  	"id" serial PRIMARY KEY NOT NULL,
  	"channel" "enum_policy_contact_cap_channel",
  	"max" numeric,
  	"_uuid" varchar
  );
  
  CREATE TABLE "_decision_policies_v_version_scoring_tier_boost" (
  	"_order" integer NOT NULL,
  	"_parent_id" integer NOT NULL,
  	"id" serial PRIMARY KEY NOT NULL,
  	"tier" varchar,
  	"boost" numeric,
  	"_uuid" varchar
  );
  
  CREATE TABLE "_decision_policies_v_version_always_apply" (
  	"order" integer NOT NULL,
  	"parent_id" integer NOT NULL,
  	"value" "enum__decision_policies_v_version_always_apply",
  	"id" serial PRIMARY KEY NOT NULL
  );
  
  CREATE TABLE "_channel_pref_v" (
  	"_order" integer NOT NULL,
  	"_parent_id" integer NOT NULL,
  	"id" serial PRIMARY KEY NOT NULL,
  	"channel" "enum_policy_channel_pref",
  	"_uuid" varchar
  );
  
  CREATE TABLE "_decision_policies_v" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"parent_id" integer,
  	"version_code" varchar,
  	"version_is_default" boolean DEFAULT false,
  	"version_version" numeric DEFAULT 1,
  	"version_constraints_quiet_hours_from" numeric DEFAULT 21,
  	"version_constraints_quiet_hours_to" numeric DEFAULT 8,
  	"version_constraints_min_hours_between_offers" numeric DEFAULT 6,
  	"version_constraints_daily_units_budget" numeric DEFAULT 0,
  	"version_constraints_block_risk_level" "enum__decision_policies_v_version_constraints_block_risk_level" DEFAULT 'CRITICAL',
  	"version_scoring_strategy" "enum__decision_policies_v_version_scoring_strategy" DEFAULT 'WEIGHTED',
  	"version_scoring_value_weight" numeric DEFAULT 1,
  	"version_scoring_cost_weight" numeric DEFAULT 0.8,
  	"version_scoring_propensity_weight" numeric DEFAULT 2,
  	"version_scoring_churn_weight" numeric DEFAULT 1.5,
  	"version_scoring_recency_weight" numeric DEFAULT 0.5,
  	"version_scoring_channel_preference_bonus" numeric DEFAULT 1,
  	"version_scoring_expression" varchar,
  	"version_max_arbitrated_per_event" numeric DEFAULT 1,
  	"version_status" "enum__decision_policies_v_version_status" DEFAULT 'draft',
  	"version_updated_at" timestamp(3) with time zone,
  	"version_created_at" timestamp(3) with time zone,
  	"version__status" "enum__decision_policies_v_version_status" DEFAULT 'draft',
  	"created_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"updated_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"snapshot" boolean,
  	"published_locale" "enum__decision_policies_v_published_locale",
  	"latest" boolean
  );
  
  CREATE TABLE "_decision_policies_v_locales" (
  	"version_name" varchar,
  	"id" serial PRIMARY KEY NOT NULL,
  	"_locale" "_locales" NOT NULL,
  	"_parent_id" integer NOT NULL
  );
  
  CREATE TABLE "_decision_policies_v_texts" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"order" integer NOT NULL,
  	"parent_id" integer NOT NULL,
  	"path" varchar NOT NULL,
  	"text" varchar
  );
  
  CREATE TABLE "offers_channels" (
  	"order" integer NOT NULL,
  	"parent_id" integer NOT NULL,
  	"value" "enum_offers_channels",
  	"id" serial PRIMARY KEY NOT NULL
  );
  
  CREATE TABLE "offers" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"code" varchar,
  	"action" "enum_offers_action",
  	"reference" varchar,
  	"wallet" varchar,
  	"units" numeric DEFAULT 0,
  	"condition" varchar,
  	"value" numeric DEFAULT 1,
  	"cost" numeric DEFAULT 0,
  	"valid_from" timestamp(3) with time zone,
  	"valid_to" timestamp(3) with time zone,
  	"params" jsonb,
  	"photo_id" integer,
  	"status" "enum_offers_status" DEFAULT 'draft',
  	"updated_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"created_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"_status" "enum_offers_status" DEFAULT 'draft'
  );
  
  CREATE TABLE "offers_locales" (
  	"name" varchar,
  	"id" serial PRIMARY KEY NOT NULL,
  	"_locale" "_locales" NOT NULL,
  	"_parent_id" integer NOT NULL
  );
  
  CREATE TABLE "_offers_v_version_channels" (
  	"order" integer NOT NULL,
  	"parent_id" integer NOT NULL,
  	"value" "enum__offers_v_version_channels",
  	"id" serial PRIMARY KEY NOT NULL
  );
  
  CREATE TABLE "_offers_v" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"parent_id" integer,
  	"version_code" varchar,
  	"version_action" "enum__offers_v_version_action",
  	"version_reference" varchar,
  	"version_wallet" varchar,
  	"version_units" numeric DEFAULT 0,
  	"version_condition" varchar,
  	"version_value" numeric DEFAULT 1,
  	"version_cost" numeric DEFAULT 0,
  	"version_valid_from" timestamp(3) with time zone,
  	"version_valid_to" timestamp(3) with time zone,
  	"version_params" jsonb,
  	"version_photo_id" integer,
  	"version_status" "enum__offers_v_version_status" DEFAULT 'draft',
  	"version_updated_at" timestamp(3) with time zone,
  	"version_created_at" timestamp(3) with time zone,
  	"version__status" "enum__offers_v_version_status" DEFAULT 'draft',
  	"created_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"updated_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"snapshot" boolean,
  	"published_locale" "enum__offers_v_published_locale",
  	"latest" boolean
  );
  
  CREATE TABLE "_offers_v_locales" (
  	"version_name" varchar,
  	"id" serial PRIMARY KEY NOT NULL,
  	"_locale" "_locales" NOT NULL,
  	"_parent_id" integer NOT NULL
  );
  
  CREATE TABLE "experiments_variants_overrides" (
  	"_order" integer NOT NULL,
  	"_parent_id" varchar NOT NULL,
  	"id" varchar PRIMARY KEY NOT NULL,
  	"key" "enum_experiments_variants_overrides_key",
  	"value" varchar
  );
  
  CREATE TABLE "experiments_variants" (
  	"_order" integer NOT NULL,
  	"_parent_id" integer NOT NULL,
  	"id" varchar PRIMARY KEY NOT NULL,
  	"name" varchar,
  	"weight" numeric DEFAULT 50,
  	"control" boolean DEFAULT false,
  	"policy" varchar
  );
  
  CREATE TABLE "experiments" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"code" varchar,
  	"name" varchar,
  	"hypothesis" varchar,
  	"primary_metric" varchar,
  	"starts_at" timestamp(3) with time zone,
  	"ends_at" timestamp(3) with time zone,
  	"traffic_percent" numeric DEFAULT 100,
  	"status" "enum_experiments_status" DEFAULT 'draft',
  	"updated_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"created_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"_status" "enum_experiments_status" DEFAULT 'draft'
  );
  
  CREATE TABLE "experiments_texts" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"order" integer NOT NULL,
  	"parent_id" integer NOT NULL,
  	"path" varchar NOT NULL,
  	"text" varchar
  );
  
  CREATE TABLE "_experiments_v_version_variants_overrides" (
  	"_order" integer NOT NULL,
  	"_parent_id" integer NOT NULL,
  	"id" serial PRIMARY KEY NOT NULL,
  	"key" "enum__experiments_v_version_variants_overrides_key",
  	"value" varchar,
  	"_uuid" varchar
  );
  
  CREATE TABLE "_experiments_v_version_variants" (
  	"_order" integer NOT NULL,
  	"_parent_id" integer NOT NULL,
  	"id" serial PRIMARY KEY NOT NULL,
  	"name" varchar,
  	"weight" numeric DEFAULT 50,
  	"control" boolean DEFAULT false,
  	"policy" varchar,
  	"_uuid" varchar
  );
  
  CREATE TABLE "_experiments_v" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"parent_id" integer,
  	"version_code" varchar,
  	"version_name" varchar,
  	"version_hypothesis" varchar,
  	"version_primary_metric" varchar,
  	"version_starts_at" timestamp(3) with time zone,
  	"version_ends_at" timestamp(3) with time zone,
  	"version_traffic_percent" numeric DEFAULT 100,
  	"version_status" "enum__experiments_v_version_status" DEFAULT 'draft',
  	"version_updated_at" timestamp(3) with time zone,
  	"version_created_at" timestamp(3) with time zone,
  	"version__status" "enum__experiments_v_version_status" DEFAULT 'draft',
  	"created_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"updated_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"snapshot" boolean,
  	"published_locale" "enum__experiments_v_published_locale",
  	"latest" boolean
  );
  
  CREATE TABLE "_experiments_v_texts" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"order" integer NOT NULL,
  	"parent_id" integer NOT NULL,
  	"path" varchar NOT NULL,
  	"text" varchar
  );
  
  CREATE TABLE "prediction_providers_serves_keys" (
  	"order" integer NOT NULL,
  	"parent_id" integer NOT NULL,
  	"value" "enum_prediction_providers_serves_keys",
  	"id" serial PRIMARY KEY NOT NULL
  );
  
  CREATE TABLE "prediction_providers" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"code" varchar,
  	"name" varchar,
  	"kind" "enum_prediction_providers_kind" DEFAULT 'RULE_BASED',
  	"active" boolean DEFAULT true,
  	"is_default" boolean DEFAULT false,
  	"url" varchar,
  	"timeout_ms" numeric DEFAULT 300,
  	"api_key_env" varchar,
  	"thresholds_churn_recency_days" numeric DEFAULT 120,
  	"thresholds_active_frequency90d" numeric DEFAULT 6,
  	"thresholds_high_value_eur" numeric DEFAULT 1500,
  	"thresholds_base_reward_acceptance" numeric DEFAULT 0.35,
  	"thresholds_base_offer_propensity" numeric DEFAULT 0.3,
  	"status" "enum_prediction_providers_status" DEFAULT 'draft',
  	"updated_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"created_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"_status" "enum_prediction_providers_status" DEFAULT 'draft'
  );
  
  CREATE TABLE "_prediction_providers_v_version_serves_keys" (
  	"order" integer NOT NULL,
  	"parent_id" integer NOT NULL,
  	"value" "enum__prediction_providers_v_version_serves_keys",
  	"id" serial PRIMARY KEY NOT NULL
  );
  
  CREATE TABLE "_prediction_providers_v" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"parent_id" integer,
  	"version_code" varchar,
  	"version_name" varchar,
  	"version_kind" "enum__prediction_providers_v_version_kind" DEFAULT 'RULE_BASED',
  	"version_active" boolean DEFAULT true,
  	"version_is_default" boolean DEFAULT false,
  	"version_url" varchar,
  	"version_timeout_ms" numeric DEFAULT 300,
  	"version_api_key_env" varchar,
  	"version_thresholds_churn_recency_days" numeric DEFAULT 120,
  	"version_thresholds_active_frequency90d" numeric DEFAULT 6,
  	"version_thresholds_high_value_eur" numeric DEFAULT 1500,
  	"version_thresholds_base_reward_acceptance" numeric DEFAULT 0.35,
  	"version_thresholds_base_offer_propensity" numeric DEFAULT 0.3,
  	"version_status" "enum__prediction_providers_v_version_status" DEFAULT 'draft',
  	"version_updated_at" timestamp(3) with time zone,
  	"version_created_at" timestamp(3) with time zone,
  	"version__status" "enum__prediction_providers_v_version_status" DEFAULT 'draft',
  	"created_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"updated_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"snapshot" boolean,
  	"published_locale" "enum__prediction_providers_v_published_locale",
  	"latest" boolean
  );
  
  CREATE TABLE "fraud_rules_signals" (
  	"_order" integer NOT NULL,
  	"_parent_id" integer NOT NULL,
  	"id" varchar PRIMARY KEY NOT NULL,
  	"signal" "enum_fraud_rules_signals_signal",
  	"enabled" boolean DEFAULT true,
  	"weight" numeric,
  	"threshold" numeric,
  	"saturation" numeric,
  	"description" varchar
  );
  
  CREATE TABLE "fraud_rules" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"code" varchar,
  	"name" varchar,
  	"version" numeric DEFAULT 1,
  	"medium_from" numeric DEFAULT 30,
  	"high_from" numeric DEFAULT 60,
  	"critical_from" numeric DEFAULT 85,
  	"auto_block_level" "enum_fraud_rules_auto_block_level" DEFAULT 'CRITICAL',
  	"decay_hours" numeric DEFAULT 72,
  	"min_transactions_for_refund_ratio" numeric DEFAULT 5,
  	"status" "enum_fraud_rules_status" DEFAULT 'draft',
  	"updated_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"created_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"_status" "enum_fraud_rules_status" DEFAULT 'draft'
  );
  
  CREATE TABLE "_fraud_rules_v_version_signals" (
  	"_order" integer NOT NULL,
  	"_parent_id" integer NOT NULL,
  	"id" serial PRIMARY KEY NOT NULL,
  	"signal" "enum__fraud_rules_v_version_signals_signal",
  	"enabled" boolean DEFAULT true,
  	"weight" numeric,
  	"threshold" numeric,
  	"saturation" numeric,
  	"description" varchar,
  	"_uuid" varchar
  );
  
  CREATE TABLE "_fraud_rules_v" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"parent_id" integer,
  	"version_code" varchar,
  	"version_name" varchar,
  	"version_version" numeric DEFAULT 1,
  	"version_medium_from" numeric DEFAULT 30,
  	"version_high_from" numeric DEFAULT 60,
  	"version_critical_from" numeric DEFAULT 85,
  	"version_auto_block_level" "enum__fraud_rules_v_version_auto_block_level" DEFAULT 'CRITICAL',
  	"version_decay_hours" numeric DEFAULT 72,
  	"version_min_transactions_for_refund_ratio" numeric DEFAULT 5,
  	"version_status" "enum__fraud_rules_v_version_status" DEFAULT 'draft',
  	"version_updated_at" timestamp(3) with time zone,
  	"version_created_at" timestamp(3) with time zone,
  	"version__status" "enum__fraud_rules_v_version_status" DEFAULT 'draft',
  	"created_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"updated_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"snapshot" boolean,
  	"published_locale" "enum__fraud_rules_v_published_locale",
  	"latest" boolean
  );
  
  CREATE TABLE "delivery_routing_routes_channels" (
  	"_order" integer NOT NULL,
  	"_parent_id" varchar NOT NULL,
  	"id" varchar PRIMARY KEY NOT NULL,
  	"channel" "enum_delivery_routing_routes_channels_channel"
  );
  
  CREATE TABLE "delivery_routing_routes" (
  	"_order" integer NOT NULL,
  	"_parent_id" integer NOT NULL,
  	"id" varchar PRIMARY KEY NOT NULL,
  	"action" "enum_delivery_routing_routes_action"
  );
  
  CREATE TABLE "delivery_routing_enabled_channels" (
  	"order" integer NOT NULL,
  	"parent_id" integer NOT NULL,
  	"value" "enum_delivery_routing_enabled_channels",
  	"id" serial PRIMARY KEY NOT NULL
  );
  
  CREATE TABLE "max_per_day" (
  	"_order" integer NOT NULL,
  	"_parent_id" integer NOT NULL,
  	"id" varchar PRIMARY KEY NOT NULL,
  	"channel" "enum_routing_max_per_day_channel",
  	"max" numeric
  );
  
  CREATE TABLE "delivery_routing_templates" (
  	"_order" integer NOT NULL,
  	"_parent_id" integer NOT NULL,
  	"id" varchar PRIMARY KEY NOT NULL,
  	"action" "enum_delivery_routing_templates_action",
  	"reference" varchar,
  	"template_id" varchar
  );
  
  CREATE TABLE "delivery_routing" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"code" varchar,
  	"name" varchar,
  	"quiet_hours_from" numeric,
  	"quiet_hours_to" numeric,
  	"quiet_hours_fallback" "enum_delivery_routing_quiet_hours_fallback",
  	"emit_actions" boolean DEFAULT true,
  	"status" "enum_delivery_routing_status" DEFAULT 'draft',
  	"updated_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"created_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"_status" "enum_delivery_routing_status" DEFAULT 'draft'
  );
  
  CREATE TABLE "_delivery_routing_v_version_routes_channels" (
  	"_order" integer NOT NULL,
  	"_parent_id" integer NOT NULL,
  	"id" serial PRIMARY KEY NOT NULL,
  	"channel" "enum__delivery_routing_v_version_routes_channels_channel",
  	"_uuid" varchar
  );
  
  CREATE TABLE "_delivery_routing_v_version_routes" (
  	"_order" integer NOT NULL,
  	"_parent_id" integer NOT NULL,
  	"id" serial PRIMARY KEY NOT NULL,
  	"action" "enum__delivery_routing_v_version_routes_action",
  	"_uuid" varchar
  );
  
  CREATE TABLE "_delivery_routing_v_version_enabled_channels" (
  	"order" integer NOT NULL,
  	"parent_id" integer NOT NULL,
  	"value" "enum__delivery_routing_v_version_enabled_channels",
  	"id" serial PRIMARY KEY NOT NULL
  );
  
  CREATE TABLE "_max_per_day_v" (
  	"_order" integer NOT NULL,
  	"_parent_id" integer NOT NULL,
  	"id" serial PRIMARY KEY NOT NULL,
  	"channel" "enum_routing_max_per_day_channel",
  	"max" numeric,
  	"_uuid" varchar
  );
  
  CREATE TABLE "_delivery_routing_v_version_templates" (
  	"_order" integer NOT NULL,
  	"_parent_id" integer NOT NULL,
  	"id" serial PRIMARY KEY NOT NULL,
  	"action" "enum__delivery_routing_v_version_templates_action",
  	"reference" varchar,
  	"template_id" varchar,
  	"_uuid" varchar
  );
  
  CREATE TABLE "_delivery_routing_v" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"parent_id" integer,
  	"version_code" varchar,
  	"version_name" varchar,
  	"version_quiet_hours_from" numeric,
  	"version_quiet_hours_to" numeric,
  	"version_quiet_hours_fallback" "enum__delivery_routing_v_version_quiet_hours_fallback",
  	"version_emit_actions" boolean DEFAULT true,
  	"version_status" "enum__delivery_routing_v_version_status" DEFAULT 'draft',
  	"version_updated_at" timestamp(3) with time zone,
  	"version_created_at" timestamp(3) with time zone,
  	"version__status" "enum__delivery_routing_v_version_status" DEFAULT 'draft',
  	"created_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"updated_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"snapshot" boolean,
  	"published_locale" "enum__delivery_routing_v_published_locale",
  	"latest" boolean
  );
  
  CREATE TABLE "consent_purposes" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"code" varchar NOT NULL,
  	"legal_basis" "enum_consent_purposes_legal_basis" DEFAULT 'consent' NOT NULL,
  	"validity_months" numeric,
  	"required" boolean DEFAULT false,
  	"version" varchar DEFAULT '1',
  	"updated_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"created_at" timestamp(3) with time zone DEFAULT now() NOT NULL
  );
  
  CREATE TABLE "consent_purposes_locales" (
  	"name" varchar NOT NULL,
  	"notice" jsonb,
  	"id" serial PRIMARY KEY NOT NULL,
  	"_locale" "_locales" NOT NULL,
  	"_parent_id" integer NOT NULL
  );
  
  CREATE TABLE "point_rules_conditions" (
  	"_order" integer NOT NULL,
  	"_parent_id" integer NOT NULL,
  	"id" varchar PRIMARY KEY NOT NULL,
  	"attribute" varchar,
  	"op" "enum_point_rules_conditions_op",
  	"value" varchar
  );
  
  CREATE TABLE "point_rules_target_tiers" (
  	"order" integer NOT NULL,
  	"parent_id" integer NOT NULL,
  	"value" "enum_point_rules_target_tiers",
  	"id" serial PRIMARY KEY NOT NULL
  );
  
  CREATE TABLE "point_rules_target_channels" (
  	"order" integer NOT NULL,
  	"parent_id" integer NOT NULL,
  	"value" "enum_point_rules_target_channels",
  	"id" serial PRIMARY KEY NOT NULL
  );
  
  CREATE TABLE "point_rules" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"rule_id" varchar,
  	"action_type" varchar,
  	"reward_points" numeric,
  	"status_points" numeric,
  	"earning_points_per_eur" numeric DEFAULT 0,
  	"earning_amount_attribute" varchar DEFAULT 'amountEur',
  	"earning_multiplier" numeric DEFAULT 1,
  	"earning_auto_reward_id" integer,
  	"tier_multipliers" jsonb,
  	"cap_per_member_per_period" numeric DEFAULT 0,
  	"limits_max_uses_per_member_per_period" numeric DEFAULT 0,
  	"limits_lock_days" numeric DEFAULT 0,
  	"limits_stop_after" boolean DEFAULT false,
  	"valid_from" timestamp(3) with time zone,
  	"valid_to" timestamp(3) with time zone,
  	"stackable" boolean DEFAULT true,
  	"photo_id" integer,
  	"status" "enum_point_rules_status" DEFAULT 'draft',
  	"updated_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"created_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"_status" "enum_point_rules_status" DEFAULT 'draft'
  );
  
  CREATE TABLE "point_rules_texts" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"order" integer NOT NULL,
  	"parent_id" integer NOT NULL,
  	"path" varchar NOT NULL,
  	"text" varchar
  );
  
  CREATE TABLE "point_rules_rels" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"order" integer,
  	"parent_id" integer NOT NULL,
  	"path" varchar NOT NULL,
  	"segments_id" integer
  );
  
  CREATE TABLE "_point_rules_v_version_conditions" (
  	"_order" integer NOT NULL,
  	"_parent_id" integer NOT NULL,
  	"id" serial PRIMARY KEY NOT NULL,
  	"attribute" varchar,
  	"op" "enum__point_rules_v_version_conditions_op",
  	"value" varchar,
  	"_uuid" varchar
  );
  
  CREATE TABLE "_point_rules_v_version_target_tiers" (
  	"order" integer NOT NULL,
  	"parent_id" integer NOT NULL,
  	"value" "enum__point_rules_v_version_target_tiers",
  	"id" serial PRIMARY KEY NOT NULL
  );
  
  CREATE TABLE "_point_rules_v_version_target_channels" (
  	"order" integer NOT NULL,
  	"parent_id" integer NOT NULL,
  	"value" "enum__point_rules_v_version_target_channels",
  	"id" serial PRIMARY KEY NOT NULL
  );
  
  CREATE TABLE "_point_rules_v" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"parent_id" integer,
  	"version_rule_id" varchar,
  	"version_action_type" varchar,
  	"version_reward_points" numeric,
  	"version_status_points" numeric,
  	"version_earning_points_per_eur" numeric DEFAULT 0,
  	"version_earning_amount_attribute" varchar DEFAULT 'amountEur',
  	"version_earning_multiplier" numeric DEFAULT 1,
  	"version_earning_auto_reward_id" integer,
  	"version_tier_multipliers" jsonb,
  	"version_cap_per_member_per_period" numeric DEFAULT 0,
  	"version_limits_max_uses_per_member_per_period" numeric DEFAULT 0,
  	"version_limits_lock_days" numeric DEFAULT 0,
  	"version_limits_stop_after" boolean DEFAULT false,
  	"version_valid_from" timestamp(3) with time zone,
  	"version_valid_to" timestamp(3) with time zone,
  	"version_stackable" boolean DEFAULT true,
  	"version_photo_id" integer,
  	"version_status" "enum__point_rules_v_version_status" DEFAULT 'draft',
  	"version_updated_at" timestamp(3) with time zone,
  	"version_created_at" timestamp(3) with time zone,
  	"version__status" "enum__point_rules_v_version_status" DEFAULT 'draft',
  	"created_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"updated_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"snapshot" boolean,
  	"published_locale" "enum__point_rules_v_published_locale",
  	"latest" boolean
  );
  
  CREATE TABLE "_point_rules_v_texts" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"order" integer NOT NULL,
  	"parent_id" integer NOT NULL,
  	"path" varchar NOT NULL,
  	"text" varchar
  );
  
  CREATE TABLE "_point_rules_v_rels" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"order" integer,
  	"parent_id" integer NOT NULL,
  	"path" varchar NOT NULL,
  	"segments_id" integer
  );
  
  CREATE TABLE "achievements_conditions" (
  	"_order" integer NOT NULL,
  	"_parent_id" integer NOT NULL,
  	"id" varchar PRIMARY KEY NOT NULL,
  	"attribute" varchar,
  	"op" "enum_achievements_conditions_op",
  	"value" varchar
  );
  
  CREATE TABLE "achievements" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"achievement_id" varchar,
  	"action_type_id" integer,
  	"metric" "enum_achievements_metric" DEFAULT 'OCCURRENCES',
  	"attribute" varchar,
  	"goal_type" "enum_achievements_goal_type" DEFAULT 'OVERALL',
  	"goal_target" numeric,
  	"goal_window_days" numeric,
  	"goal_period" "enum_achievements_goal_period",
  	"goal_consecutive_periods" numeric,
  	"event_limit_max" numeric DEFAULT 0,
  	"event_limit_period" "enum_achievements_event_limit_period" DEFAULT 'TOTAL',
  	"completion_limit_max" numeric DEFAULT 0,
  	"completion_limit_period" "enum_achievements_completion_limit_period" DEFAULT 'TOTAL',
  	"photo_id" integer,
  	"status" "enum_achievements_status" DEFAULT 'draft',
  	"updated_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"created_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"_status" "enum_achievements_status" DEFAULT 'draft'
  );
  
  CREATE TABLE "achievements_locales" (
  	"name" varchar,
  	"id" serial PRIMARY KEY NOT NULL,
  	"_locale" "_locales" NOT NULL,
  	"_parent_id" integer NOT NULL
  );
  
  CREATE TABLE "_achievements_v_version_conditions" (
  	"_order" integer NOT NULL,
  	"_parent_id" integer NOT NULL,
  	"id" serial PRIMARY KEY NOT NULL,
  	"attribute" varchar,
  	"op" "enum__achievements_v_version_conditions_op",
  	"value" varchar,
  	"_uuid" varchar
  );
  
  CREATE TABLE "_achievements_v" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"parent_id" integer,
  	"version_achievement_id" varchar,
  	"version_action_type_id" integer,
  	"version_metric" "enum__achievements_v_version_metric" DEFAULT 'OCCURRENCES',
  	"version_attribute" varchar,
  	"version_goal_type" "enum__achievements_v_version_goal_type" DEFAULT 'OVERALL',
  	"version_goal_target" numeric,
  	"version_goal_window_days" numeric,
  	"version_goal_period" "enum__achievements_v_version_goal_period",
  	"version_goal_consecutive_periods" numeric,
  	"version_event_limit_max" numeric DEFAULT 0,
  	"version_event_limit_period" "enum__achievements_v_version_event_limit_period" DEFAULT 'TOTAL',
  	"version_completion_limit_max" numeric DEFAULT 0,
  	"version_completion_limit_period" "enum__achievements_v_version_completion_limit_period" DEFAULT 'TOTAL',
  	"version_photo_id" integer,
  	"version_status" "enum__achievements_v_version_status" DEFAULT 'draft',
  	"version_updated_at" timestamp(3) with time zone,
  	"version_created_at" timestamp(3) with time zone,
  	"version__status" "enum__achievements_v_version_status" DEFAULT 'draft',
  	"created_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"updated_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"snapshot" boolean,
  	"published_locale" "enum__achievements_v_published_locale",
  	"latest" boolean
  );
  
  CREATE TABLE "_achievements_v_locales" (
  	"version_name" varchar,
  	"id" serial PRIMARY KEY NOT NULL,
  	"_locale" "_locales" NOT NULL,
  	"_parent_id" integer NOT NULL
  );
  
  CREATE TABLE "challenges_availability_days_of_week" (
  	"order" integer NOT NULL,
  	"parent_id" integer NOT NULL,
  	"value" "enum_challenges_availability_days_of_week",
  	"id" serial PRIMARY KEY NOT NULL
  );
  
  CREATE TABLE "challenges_visibility_tiers" (
  	"order" integer NOT NULL,
  	"parent_id" integer NOT NULL,
  	"value" "enum_challenges_visibility_tiers",
  	"id" serial PRIMARY KEY NOT NULL
  );
  
  CREATE TABLE "challenges_milestones_conditions" (
  	"_order" integer NOT NULL,
  	"_parent_id" varchar NOT NULL,
  	"id" varchar PRIMARY KEY NOT NULL,
  	"attribute" varchar,
  	"op" "enum_challenges_milestones_conditions_op",
  	"value" varchar
  );
  
  CREATE TABLE "challenges_milestones" (
  	"_order" integer NOT NULL,
  	"_parent_id" integer NOT NULL,
  	"id" varchar PRIMARY KEY NOT NULL,
  	"milestone_id" varchar,
  	"kind" "enum_challenges_milestones_kind" DEFAULT 'DIRECT',
  	"action_type_id" integer,
  	"metric" "enum_challenges_milestones_metric" DEFAULT 'OCCURRENCES',
  	"attribute" varchar,
  	"goal_type" "enum_challenges_milestones_goal_type" DEFAULT 'OVERALL',
  	"goal_target" numeric,
  	"goal_window_days" numeric,
  	"goal_period" "enum_challenges_milestones_goal_period",
  	"goal_consecutive_periods" numeric,
  	"event_limit_max" numeric DEFAULT 0,
  	"event_limit_period" "enum_challenges_milestones_event_limit_period" DEFAULT 'TOTAL',
  	"completion_limit_max" numeric DEFAULT 0,
  	"completion_limit_period" "enum_challenges_milestones_completion_limit_period" DEFAULT 'TOTAL'
  );
  
  CREATE TABLE "challenges_milestones_locales" (
  	"name" varchar,
  	"id" serial PRIMARY KEY NOT NULL,
  	"_locale" "_locales" NOT NULL,
  	"_parent_id" varchar NOT NULL
  );
  
  CREATE TABLE "challenges_rules_effects" (
  	"_order" integer NOT NULL,
  	"_parent_id" varchar NOT NULL,
  	"id" varchar PRIMARY KEY NOT NULL,
  	"type" "enum_challenges_rules_effects_type",
  	"wallet_id" integer,
  	"fixed" numeric,
  	"per_eur" numeric,
  	"value_expression" varchar,
  	"reward_id" integer,
  	"badge_id" integer,
  	"tier_code" varchar,
  	"attribute_key" varchar,
  	"attribute_value" varchar,
  	"event_type" varchar,
  	"level" numeric,
  	"expires_at_expression" varchar,
  	"pending_until_expression" varchar
  );
  
  CREATE TABLE "challenges_rules" (
  	"_order" integer NOT NULL,
  	"_parent_id" integer NOT NULL,
  	"id" varchar PRIMARY KEY NOT NULL,
  	"rule_id" varchar,
  	"trigger" "enum_challenges_rules_trigger" DEFAULT 'CHALLENGE_COMPLETED',
  	"max_completion_count" numeric
  );
  
  CREATE TABLE "challenges" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"challenge_id" varchar,
  	"starts_at" timestamp(3) with time zone,
  	"ends_at" timestamp(3) with time zone,
  	"program_year" numeric,
  	"badge_id" integer,
  	"availability_hour_from" numeric,
  	"availability_hour_to" numeric,
  	"visibility_mode" "enum_challenges_visibility_mode" DEFAULT 'EVERYONE',
  	"completion_limit_max" numeric DEFAULT 0,
  	"completion_limit_period" "enum_challenges_completion_limit_period" DEFAULT 'TOTAL',
  	"photo_id" integer,
  	"status" "enum_challenges_status" DEFAULT 'draft',
  	"updated_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"created_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"_status" "enum_challenges_status" DEFAULT 'draft'
  );
  
  CREATE TABLE "challenges_locales" (
  	"name" varchar,
  	"id" serial PRIMARY KEY NOT NULL,
  	"_locale" "_locales" NOT NULL,
  	"_parent_id" integer NOT NULL
  );
  
  CREATE TABLE "challenges_texts" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"order" integer NOT NULL,
  	"parent_id" integer NOT NULL,
  	"path" varchar NOT NULL,
  	"text" varchar
  );
  
  CREATE TABLE "challenges_rels" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"order" integer,
  	"parent_id" integer NOT NULL,
  	"path" varchar NOT NULL,
  	"segments_id" integer
  );
  
  CREATE TABLE "_challenges_v_version_availability_days_of_week" (
  	"order" integer NOT NULL,
  	"parent_id" integer NOT NULL,
  	"value" "enum__challenges_v_version_availability_days_of_week",
  	"id" serial PRIMARY KEY NOT NULL
  );
  
  CREATE TABLE "_challenges_v_version_visibility_tiers" (
  	"order" integer NOT NULL,
  	"parent_id" integer NOT NULL,
  	"value" "enum__challenges_v_version_visibility_tiers",
  	"id" serial PRIMARY KEY NOT NULL
  );
  
  CREATE TABLE "_challenges_v_version_milestones_conditions" (
  	"_order" integer NOT NULL,
  	"_parent_id" integer NOT NULL,
  	"id" serial PRIMARY KEY NOT NULL,
  	"attribute" varchar,
  	"op" "enum__challenges_v_version_milestones_conditions_op",
  	"value" varchar,
  	"_uuid" varchar
  );
  
  CREATE TABLE "_challenges_v_version_milestones" (
  	"_order" integer NOT NULL,
  	"_parent_id" integer NOT NULL,
  	"id" serial PRIMARY KEY NOT NULL,
  	"milestone_id" varchar,
  	"kind" "enum__challenges_v_version_milestones_kind" DEFAULT 'DIRECT',
  	"action_type_id" integer,
  	"metric" "enum__challenges_v_version_milestones_metric" DEFAULT 'OCCURRENCES',
  	"attribute" varchar,
  	"goal_type" "enum__challenges_v_version_milestones_goal_type" DEFAULT 'OVERALL',
  	"goal_target" numeric,
  	"goal_window_days" numeric,
  	"goal_period" "enum__challenges_v_version_milestones_goal_period",
  	"goal_consecutive_periods" numeric,
  	"event_limit_max" numeric DEFAULT 0,
  	"event_limit_period" "enum__challenges_v_version_milestones_event_limit_period" DEFAULT 'TOTAL',
  	"completion_limit_max" numeric DEFAULT 0,
  	"completion_limit_period" "enum__challenges_v_version_milestones_completion_limit_period" DEFAULT 'TOTAL',
  	"_uuid" varchar
  );
  
  CREATE TABLE "_challenges_v_version_milestones_locales" (
  	"name" varchar,
  	"id" serial PRIMARY KEY NOT NULL,
  	"_locale" "_locales" NOT NULL,
  	"_parent_id" integer NOT NULL
  );
  
  CREATE TABLE "_challenges_v_version_rules_effects" (
  	"_order" integer NOT NULL,
  	"_parent_id" integer NOT NULL,
  	"id" serial PRIMARY KEY NOT NULL,
  	"type" "enum__challenges_v_version_rules_effects_type",
  	"wallet_id" integer,
  	"fixed" numeric,
  	"per_eur" numeric,
  	"value_expression" varchar,
  	"reward_id" integer,
  	"badge_id" integer,
  	"tier_code" varchar,
  	"attribute_key" varchar,
  	"attribute_value" varchar,
  	"event_type" varchar,
  	"level" numeric,
  	"expires_at_expression" varchar,
  	"pending_until_expression" varchar,
  	"_uuid" varchar
  );
  
  CREATE TABLE "_challenges_v_version_rules" (
  	"_order" integer NOT NULL,
  	"_parent_id" integer NOT NULL,
  	"id" serial PRIMARY KEY NOT NULL,
  	"rule_id" varchar,
  	"trigger" "enum__challenges_v_version_rules_trigger" DEFAULT 'CHALLENGE_COMPLETED',
  	"max_completion_count" numeric,
  	"_uuid" varchar
  );
  
  CREATE TABLE "_challenges_v" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"parent_id" integer,
  	"version_challenge_id" varchar,
  	"version_starts_at" timestamp(3) with time zone,
  	"version_ends_at" timestamp(3) with time zone,
  	"version_program_year" numeric,
  	"version_badge_id" integer,
  	"version_availability_hour_from" numeric,
  	"version_availability_hour_to" numeric,
  	"version_visibility_mode" "enum__challenges_v_version_visibility_mode" DEFAULT 'EVERYONE',
  	"version_completion_limit_max" numeric DEFAULT 0,
  	"version_completion_limit_period" "enum__challenges_v_version_completion_limit_period" DEFAULT 'TOTAL',
  	"version_photo_id" integer,
  	"version_status" "enum__challenges_v_version_status" DEFAULT 'draft',
  	"version_updated_at" timestamp(3) with time zone,
  	"version_created_at" timestamp(3) with time zone,
  	"version__status" "enum__challenges_v_version_status" DEFAULT 'draft',
  	"created_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"updated_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"snapshot" boolean,
  	"published_locale" "enum__challenges_v_published_locale",
  	"latest" boolean
  );
  
  CREATE TABLE "_challenges_v_locales" (
  	"version_name" varchar,
  	"id" serial PRIMARY KEY NOT NULL,
  	"_locale" "_locales" NOT NULL,
  	"_parent_id" integer NOT NULL
  );
  
  CREATE TABLE "_challenges_v_texts" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"order" integer NOT NULL,
  	"parent_id" integer NOT NULL,
  	"path" varchar NOT NULL,
  	"text" varchar
  );
  
  CREATE TABLE "_challenges_v_rels" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"order" integer,
  	"parent_id" integer NOT NULL,
  	"path" varchar NOT NULL,
  	"segments_id" integer
  );
  
  CREATE TABLE "badges" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"code" varchar NOT NULL,
  	"image_id" integer,
  	"stackable" boolean DEFAULT false,
  	"active" boolean DEFAULT true,
  	"updated_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"created_at" timestamp(3) with time zone DEFAULT now() NOT NULL
  );
  
  CREATE TABLE "badges_locales" (
  	"name" varchar NOT NULL,
  	"description" varchar,
  	"id" serial PRIMARY KEY NOT NULL,
  	"_locale" "_locales" NOT NULL,
  	"_parent_id" integer NOT NULL
  );
  
  CREATE TABLE "leaderboards_rewarding_cycle_rewards" (
  	"_order" integer NOT NULL,
  	"_parent_id" integer NOT NULL,
  	"id" varchar PRIMARY KEY NOT NULL,
  	"from_rank" numeric,
  	"to_rank" numeric,
  	"reward_id" integer,
  	"wallet_id" integer,
  	"units" numeric,
  	"badge_id" integer
  );
  
  CREATE TABLE "leaderboards" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"leaderboard_id" varchar,
  	"metric" "enum_leaderboards_metric",
  	"reference" varchar,
  	"starts_at" timestamp(3) with time zone,
  	"ends_at" timestamp(3) with time zone,
  	"group_by" varchar,
  	"top_n" numeric DEFAULT 1000,
  	"rewarding_cycle_period" "enum_leaderboards_rewarding_cycle_period",
  	"visibility" "enum_leaderboards_visibility" DEFAULT 'EVERYONE',
  	"status" "enum_leaderboards_status" DEFAULT 'draft',
  	"updated_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"created_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"_status" "enum_leaderboards_status" DEFAULT 'draft'
  );
  
  CREATE TABLE "leaderboards_locales" (
  	"name" varchar,
  	"id" serial PRIMARY KEY NOT NULL,
  	"_locale" "_locales" NOT NULL,
  	"_parent_id" integer NOT NULL
  );
  
  CREATE TABLE "_leaderboards_v_version_rewarding_cycle_rewards" (
  	"_order" integer NOT NULL,
  	"_parent_id" integer NOT NULL,
  	"id" serial PRIMARY KEY NOT NULL,
  	"from_rank" numeric,
  	"to_rank" numeric,
  	"reward_id" integer,
  	"wallet_id" integer,
  	"units" numeric,
  	"badge_id" integer,
  	"_uuid" varchar
  );
  
  CREATE TABLE "_leaderboards_v" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"parent_id" integer,
  	"version_leaderboard_id" varchar,
  	"version_metric" "enum__leaderboards_v_version_metric",
  	"version_reference" varchar,
  	"version_starts_at" timestamp(3) with time zone,
  	"version_ends_at" timestamp(3) with time zone,
  	"version_group_by" varchar,
  	"version_top_n" numeric DEFAULT 1000,
  	"version_rewarding_cycle_period" "enum__leaderboards_v_version_rewarding_cycle_period",
  	"version_visibility" "enum__leaderboards_v_version_visibility" DEFAULT 'EVERYONE',
  	"version_status" "enum__leaderboards_v_version_status" DEFAULT 'draft',
  	"version_updated_at" timestamp(3) with time zone,
  	"version_created_at" timestamp(3) with time zone,
  	"version__status" "enum__leaderboards_v_version_status" DEFAULT 'draft',
  	"created_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"updated_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"snapshot" boolean,
  	"published_locale" "enum__leaderboards_v_published_locale",
  	"latest" boolean
  );
  
  CREATE TABLE "_leaderboards_v_locales" (
  	"version_name" varchar,
  	"id" serial PRIMARY KEY NOT NULL,
  	"_locale" "_locales" NOT NULL,
  	"_parent_id" integer NOT NULL
  );
  
  CREATE TABLE "fortune_wheels_slots" (
  	"_order" integer NOT NULL,
  	"_parent_id" integer NOT NULL,
  	"id" varchar PRIMARY KEY NOT NULL,
  	"slot_id" varchar,
  	"weight" numeric,
  	"reward_id" integer,
  	"wallet_id" integer,
  	"units" numeric DEFAULT 0,
  	"stock" numeric DEFAULT -1,
  	"winning" boolean DEFAULT true
  );
  
  CREATE TABLE "fortune_wheels_slots_locales" (
  	"label" varchar,
  	"id" serial PRIMARY KEY NOT NULL,
  	"_locale" "_locales" NOT NULL,
  	"_parent_id" varchar NOT NULL
  );
  
  CREATE TABLE "fortune_wheels" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"wheel_id" varchar,
  	"mode" "enum_fortune_wheels_mode" DEFAULT 'INSTANT_WIN_BACKED',
  	"contest_id" integer,
  	"cost_wallet_id" integer,
  	"cost_units" numeric DEFAULT 0,
  	"spins_per_member" numeric DEFAULT 1,
  	"spins_period" "enum_fortune_wheels_spins_period" DEFAULT 'DAY',
  	"starts_at" timestamp(3) with time zone,
  	"ends_at" timestamp(3) with time zone,
  	"budget_units" numeric DEFAULT 0,
  	"max_wins_per_member_per_day" numeric DEFAULT 0,
  	"visibility" "enum_fortune_wheels_visibility" DEFAULT 'EVERYONE',
  	"status" "enum_fortune_wheels_status" DEFAULT 'draft',
  	"updated_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"created_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"_status" "enum_fortune_wheels_status" DEFAULT 'draft'
  );
  
  CREATE TABLE "fortune_wheels_locales" (
  	"name" varchar,
  	"id" serial PRIMARY KEY NOT NULL,
  	"_locale" "_locales" NOT NULL,
  	"_parent_id" integer NOT NULL
  );
  
  CREATE TABLE "_fortune_wheels_v_version_slots" (
  	"_order" integer NOT NULL,
  	"_parent_id" integer NOT NULL,
  	"id" serial PRIMARY KEY NOT NULL,
  	"slot_id" varchar,
  	"weight" numeric,
  	"reward_id" integer,
  	"wallet_id" integer,
  	"units" numeric DEFAULT 0,
  	"stock" numeric DEFAULT -1,
  	"winning" boolean DEFAULT true,
  	"_uuid" varchar
  );
  
  CREATE TABLE "_fortune_wheels_v_version_slots_locales" (
  	"label" varchar,
  	"id" serial PRIMARY KEY NOT NULL,
  	"_locale" "_locales" NOT NULL,
  	"_parent_id" integer NOT NULL
  );
  
  CREATE TABLE "_fortune_wheels_v" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"parent_id" integer,
  	"version_wheel_id" varchar,
  	"version_mode" "enum__fortune_wheels_v_version_mode" DEFAULT 'INSTANT_WIN_BACKED',
  	"version_contest_id" integer,
  	"version_cost_wallet_id" integer,
  	"version_cost_units" numeric DEFAULT 0,
  	"version_spins_per_member" numeric DEFAULT 1,
  	"version_spins_period" "enum__fortune_wheels_v_version_spins_period" DEFAULT 'DAY',
  	"version_starts_at" timestamp(3) with time zone,
  	"version_ends_at" timestamp(3) with time zone,
  	"version_budget_units" numeric DEFAULT 0,
  	"version_max_wins_per_member_per_day" numeric DEFAULT 0,
  	"version_visibility" "enum__fortune_wheels_v_version_visibility" DEFAULT 'EVERYONE',
  	"version_status" "enum__fortune_wheels_v_version_status" DEFAULT 'draft',
  	"version_updated_at" timestamp(3) with time zone,
  	"version_created_at" timestamp(3) with time zone,
  	"version__status" "enum__fortune_wheels_v_version_status" DEFAULT 'draft',
  	"created_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"updated_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"snapshot" boolean,
  	"published_locale" "enum__fortune_wheels_v_published_locale",
  	"latest" boolean
  );
  
  CREATE TABLE "_fortune_wheels_v_locales" (
  	"version_name" varchar,
  	"id" serial PRIMARY KEY NOT NULL,
  	"_locale" "_locales" NOT NULL,
  	"_parent_id" integer NOT NULL
  );
  
  CREATE TABLE "contests_prizes" (
  	"_order" integer NOT NULL,
  	"_parent_id" integer NOT NULL,
  	"id" varchar PRIMARY KEY NOT NULL,
  	"prize_code" varchar,
  	"quantity" numeric,
  	"value_eur" numeric
  );
  
  CREATE TABLE "contests_weighted_slots" (
  	"_order" integer NOT NULL,
  	"_parent_id" integer NOT NULL,
  	"id" varchar PRIMARY KEY NOT NULL,
  	"from_hour" numeric,
  	"to_hour" numeric,
  	"weight" numeric
  );
  
  CREATE TABLE "contests" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"name" varchar,
  	"starts_at" timestamp(3) with time zone,
  	"ends_at" timestamp(3) with time zone,
  	"max_plays_per_member_per_day" numeric DEFAULT 1,
  	"prema_protocol" varchar,
  	"prema_notified_at" timestamp(3) with time zone,
  	"bond_reference" varchar,
  	"onlus" varchar,
  	"regulation_id" integer,
  	"status" "enum_contests_status" DEFAULT 'draft',
  	"updated_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"created_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"_status" "enum_contests_status" DEFAULT 'draft'
  );
  
  CREATE TABLE "_contests_v_version_prizes" (
  	"_order" integer NOT NULL,
  	"_parent_id" integer NOT NULL,
  	"id" serial PRIMARY KEY NOT NULL,
  	"prize_code" varchar,
  	"quantity" numeric,
  	"value_eur" numeric,
  	"_uuid" varchar
  );
  
  CREATE TABLE "_contests_v_version_weighted_slots" (
  	"_order" integer NOT NULL,
  	"_parent_id" integer NOT NULL,
  	"id" serial PRIMARY KEY NOT NULL,
  	"from_hour" numeric,
  	"to_hour" numeric,
  	"weight" numeric,
  	"_uuid" varchar
  );
  
  CREATE TABLE "_contests_v" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"parent_id" integer,
  	"version_name" varchar,
  	"version_starts_at" timestamp(3) with time zone,
  	"version_ends_at" timestamp(3) with time zone,
  	"version_max_plays_per_member_per_day" numeric DEFAULT 1,
  	"version_prema_protocol" varchar,
  	"version_prema_notified_at" timestamp(3) with time zone,
  	"version_bond_reference" varchar,
  	"version_onlus" varchar,
  	"version_regulation_id" integer,
  	"version_status" "enum__contests_v_version_status" DEFAULT 'draft',
  	"version_updated_at" timestamp(3) with time zone,
  	"version_created_at" timestamp(3) with time zone,
  	"version__status" "enum__contests_v_version_status" DEFAULT 'draft',
  	"created_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"updated_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"snapshot" boolean,
  	"published_locale" "enum__contests_v_published_locale",
  	"latest" boolean
  );
  
  CREATE TABLE "contest_cards" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"title" varchar,
  	"image_id" integer,
  	"period_from" timestamp(3) with time zone,
  	"period_to" timestamp(3) with time zone,
  	"body" jsonb,
  	"regulation_id" integer,
  	"contest_id" varchar,
  	"min_tier" "enum_contest_cards_min_tier" DEFAULT 'BASE',
  	"cta" varchar,
  	"status" "enum_contest_cards_status" DEFAULT 'draft',
  	"publish_at" timestamp(3) with time zone,
  	"unpublish_at" timestamp(3) with time zone,
  	"updated_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"created_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"_status" "enum_contest_cards_status" DEFAULT 'draft'
  );
  
  CREATE TABLE "_contest_cards_v" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"parent_id" integer,
  	"version_title" varchar,
  	"version_image_id" integer,
  	"version_period_from" timestamp(3) with time zone,
  	"version_period_to" timestamp(3) with time zone,
  	"version_body" jsonb,
  	"version_regulation_id" integer,
  	"version_contest_id" varchar,
  	"version_min_tier" "enum__contest_cards_v_version_min_tier" DEFAULT 'BASE',
  	"version_cta" varchar,
  	"version_status" "enum__contest_cards_v_version_status" DEFAULT 'draft',
  	"version_publish_at" timestamp(3) with time zone,
  	"version_unpublish_at" timestamp(3) with time zone,
  	"version_updated_at" timestamp(3) with time zone,
  	"version_created_at" timestamp(3) with time zone,
  	"version__status" "enum__contest_cards_v_version_status" DEFAULT 'draft',
  	"created_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"updated_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"snapshot" boolean,
  	"published_locale" "enum__contest_cards_v_published_locale",
  	"latest" boolean
  );
  
  CREATE TABLE "win_cards" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"prize_code" varchar,
  	"title" varchar,
  	"image_id" integer,
  	"instructions" jsonb,
  	"status" "enum_win_cards_status" DEFAULT 'draft',
  	"updated_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"created_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"_status" "enum_win_cards_status" DEFAULT 'draft'
  );
  
  CREATE TABLE "_win_cards_v" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"parent_id" integer,
  	"version_prize_code" varchar,
  	"version_title" varchar,
  	"version_image_id" integer,
  	"version_instructions" jsonb,
  	"version_status" "enum__win_cards_v_version_status" DEFAULT 'draft',
  	"version_updated_at" timestamp(3) with time zone,
  	"version_created_at" timestamp(3) with time zone,
  	"version__status" "enum__win_cards_v_version_status" DEFAULT 'draft',
  	"created_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"updated_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"snapshot" boolean,
  	"published_locale" "enum__win_cards_v_published_locale",
  	"latest" boolean
  );
  
  CREATE TABLE "popups" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"title" varchar,
  	"trigger" "enum_popups_trigger",
  	"segment_min_tier" "enum_popups_segment_min_tier" DEFAULT 'BASE',
  	"segment_requires_profiling_consent" boolean DEFAULT true,
  	"max_per_member_per_week" numeric DEFAULT 1,
  	"priority" numeric DEFAULT 100,
  	"body" jsonb,
  	"status" "enum_popups_status" DEFAULT 'draft',
  	"publish_at" timestamp(3) with time zone,
  	"unpublish_at" timestamp(3) with time zone,
  	"updated_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"created_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"_status" "enum_popups_status" DEFAULT 'draft'
  );
  
  CREATE TABLE "_popups_v" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"parent_id" integer,
  	"version_title" varchar,
  	"version_trigger" "enum__popups_v_version_trigger",
  	"version_segment_min_tier" "enum__popups_v_version_segment_min_tier" DEFAULT 'BASE',
  	"version_segment_requires_profiling_consent" boolean DEFAULT true,
  	"version_max_per_member_per_week" numeric DEFAULT 1,
  	"version_priority" numeric DEFAULT 100,
  	"version_body" jsonb,
  	"version_status" "enum__popups_v_version_status" DEFAULT 'draft',
  	"version_publish_at" timestamp(3) with time zone,
  	"version_unpublish_at" timestamp(3) with time zone,
  	"version_updated_at" timestamp(3) with time zone,
  	"version_created_at" timestamp(3) with time zone,
  	"version__status" "enum__popups_v_version_status" DEFAULT 'draft',
  	"created_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"updated_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"snapshot" boolean,
  	"published_locale" "enum__popups_v_published_locale",
  	"latest" boolean
  );
  
  CREATE TABLE "rewards" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"name" varchar,
  	"type" "enum_rewards_type",
  	"value_eur" numeric,
  	"points_cost" numeric,
  	"min_tier" "enum_rewards_min_tier" DEFAULT 'BASE',
  	"stock" numeric DEFAULT -1,
  	"supplier" varchar,
  	"category_id" integer,
  	"limit_per_member" numeric DEFAULT 0,
  	"limit_per_member_per_day" numeric DEFAULT 0,
  	"visible_from" timestamp(3) with time zone,
  	"visible_to" timestamp(3) with time zone,
  	"active_from" timestamp(3) with time zone,
  	"active_to" timestamp(3) with time zone,
  	"coupon_pool_id" integer,
  	"code_validity_days" numeric DEFAULT 0,
  	"discount_percent" numeric,
  	"cashback_eur" numeric,
  	"photo_id" integer,
  	"featured" boolean DEFAULT false,
  	"status" "enum_rewards_status" DEFAULT 'draft',
  	"updated_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"created_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"_status" "enum_rewards_status" DEFAULT 'draft'
  );
  
  CREATE TABLE "rewards_rels" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"order" integer,
  	"parent_id" integer NOT NULL,
  	"path" varchar NOT NULL,
  	"segments_id" integer
  );
  
  CREATE TABLE "_rewards_v" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"parent_id" integer,
  	"version_name" varchar,
  	"version_type" "enum__rewards_v_version_type",
  	"version_value_eur" numeric,
  	"version_points_cost" numeric,
  	"version_min_tier" "enum__rewards_v_version_min_tier" DEFAULT 'BASE',
  	"version_stock" numeric DEFAULT -1,
  	"version_supplier" varchar,
  	"version_category_id" integer,
  	"version_limit_per_member" numeric DEFAULT 0,
  	"version_limit_per_member_per_day" numeric DEFAULT 0,
  	"version_visible_from" timestamp(3) with time zone,
  	"version_visible_to" timestamp(3) with time zone,
  	"version_active_from" timestamp(3) with time zone,
  	"version_active_to" timestamp(3) with time zone,
  	"version_coupon_pool_id" integer,
  	"version_code_validity_days" numeric DEFAULT 0,
  	"version_discount_percent" numeric,
  	"version_cashback_eur" numeric,
  	"version_photo_id" integer,
  	"version_featured" boolean DEFAULT false,
  	"version_status" "enum__rewards_v_version_status" DEFAULT 'draft',
  	"version_updated_at" timestamp(3) with time zone,
  	"version_created_at" timestamp(3) with time zone,
  	"version__status" "enum__rewards_v_version_status" DEFAULT 'draft',
  	"created_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"updated_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"snapshot" boolean,
  	"published_locale" "enum__rewards_v_published_locale",
  	"latest" boolean
  );
  
  CREATE TABLE "_rewards_v_rels" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"order" integer,
  	"parent_id" integer NOT NULL,
  	"path" varchar NOT NULL,
  	"segments_id" integer
  );
  
  CREATE TABLE "reward_categories" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"name" varchar NOT NULL,
  	"order" numeric DEFAULT 0,
  	"photo_id" integer,
  	"updated_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"created_at" timestamp(3) with time zone DEFAULT now() NOT NULL
  );
  
  CREATE TABLE "coupon_pools" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"pool_id" varchar NOT NULL,
  	"supplier" varchar,
  	"codes_file_id" integer,
  	"alert_below" numeric DEFAULT 100,
  	"updated_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"created_at" timestamp(3) with time zone DEFAULT now() NOT NULL
  );
  
  CREATE TABLE "promo_codes" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"campaign" varchar,
  	"kind" "enum_promo_codes_kind",
  	"code" varchar,
  	"batch_size" numeric,
  	"valid_from" timestamp(3) with time zone,
  	"valid_to" timestamp(3) with time zone,
  	"max_uses" numeric DEFAULT 0,
  	"max_uses_per_member" numeric DEFAULT 1,
  	"qr_image_id" integer,
  	"status" "enum_promo_codes_status" DEFAULT 'draft',
  	"updated_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"created_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"_status" "enum_promo_codes_status" DEFAULT 'draft'
  );
  
  CREATE TABLE "_promo_codes_v" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"parent_id" integer,
  	"version_campaign" varchar,
  	"version_kind" "enum__promo_codes_v_version_kind",
  	"version_code" varchar,
  	"version_batch_size" numeric,
  	"version_valid_from" timestamp(3) with time zone,
  	"version_valid_to" timestamp(3) with time zone,
  	"version_max_uses" numeric DEFAULT 0,
  	"version_max_uses_per_member" numeric DEFAULT 1,
  	"version_qr_image_id" integer,
  	"version_status" "enum__promo_codes_v_version_status" DEFAULT 'draft',
  	"version_updated_at" timestamp(3) with time zone,
  	"version_created_at" timestamp(3) with time zone,
  	"version__status" "enum__promo_codes_v_version_status" DEFAULT 'draft',
  	"created_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"updated_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"snapshot" boolean,
  	"published_locale" "enum__promo_codes_v_published_locale",
  	"latest" boolean
  );
  
  CREATE TABLE "wallets" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"code" varchar NOT NULL,
  	"expiration" "enum_wallets_expiration" DEFAULT 'NONE',
  	"expiration_days" numeric,
  	"expiration_annual_date" varchar,
  	"pending_days" numeric DEFAULT 0,
  	"allow_negative" boolean DEFAULT false,
  	"global_limit" numeric DEFAULT 0,
  	"per_member_limit" numeric DEFAULT 0,
  	"spendable" boolean DEFAULT true,
  	"active" boolean DEFAULT true,
  	"updated_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"created_at" timestamp(3) with time zone DEFAULT now() NOT NULL
  );
  
  CREATE TABLE "wallets_locales" (
  	"name" varchar NOT NULL,
  	"unit_singular" varchar NOT NULL,
  	"unit_plural" varchar NOT NULL,
  	"id" serial PRIMARY KEY NOT NULL,
  	"_locale" "_locales" NOT NULL,
  	"_parent_id" integer NOT NULL
  );
  
  CREATE TABLE "tier_sets_conditions" (
  	"_order" integer NOT NULL,
  	"_parent_id" integer NOT NULL,
  	"id" varchar PRIMARY KEY NOT NULL,
  	"condition_id" varchar,
  	"metric" "enum_tier_sets_conditions_metric",
  	"reference" varchar,
  	"period_months" numeric DEFAULT 12
  );
  
  CREATE TABLE "tier_sets_tiers" (
  	"_order" integer NOT NULL,
  	"_parent_id" integer NOT NULL,
  	"id" varchar PRIMARY KEY NOT NULL,
  	"code" varchar,
  	"order" numeric,
  	"thresholds" jsonb,
  	"discount_percent" numeric,
  	"multiplier" numeric DEFAULT 1,
  	"photo_id" integer
  );
  
  CREATE TABLE "tier_sets_tiers_locales" (
  	"benefits_description" jsonb,
  	"id" serial PRIMARY KEY NOT NULL,
  	"_locale" "_locales" NOT NULL,
  	"_parent_id" varchar NOT NULL
  );
  
  CREATE TABLE "tier_sets" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"set_id" varchar,
  	"match" "enum_tier_sets_match" DEFAULT 'ALL',
  	"downgrade_mode" "enum_tier_sets_downgrade_mode" DEFAULT 'ANNUAL_ONE_LEVEL',
  	"downgrade_interval_months" numeric,
  	"status" "enum_tier_sets_status" DEFAULT 'draft',
  	"updated_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"created_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"_status" "enum_tier_sets_status" DEFAULT 'draft'
  );
  
  CREATE TABLE "tier_sets_locales" (
  	"name" varchar,
  	"id" serial PRIMARY KEY NOT NULL,
  	"_locale" "_locales" NOT NULL,
  	"_parent_id" integer NOT NULL
  );
  
  CREATE TABLE "tier_sets_texts" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"order" integer NOT NULL,
  	"parent_id" integer NOT NULL,
  	"path" varchar NOT NULL,
  	"text" varchar
  );
  
  CREATE TABLE "tier_sets_rels" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"order" integer,
  	"parent_id" integer NOT NULL,
  	"path" varchar NOT NULL,
  	"rewards_id" integer
  );
  
  CREATE TABLE "_tier_sets_v_version_conditions" (
  	"_order" integer NOT NULL,
  	"_parent_id" integer NOT NULL,
  	"id" serial PRIMARY KEY NOT NULL,
  	"condition_id" varchar,
  	"metric" "enum__tier_sets_v_version_conditions_metric",
  	"reference" varchar,
  	"period_months" numeric DEFAULT 12,
  	"_uuid" varchar
  );
  
  CREATE TABLE "_tier_sets_v_version_tiers" (
  	"_order" integer NOT NULL,
  	"_parent_id" integer NOT NULL,
  	"id" serial PRIMARY KEY NOT NULL,
  	"code" varchar,
  	"order" numeric,
  	"thresholds" jsonb,
  	"discount_percent" numeric,
  	"multiplier" numeric DEFAULT 1,
  	"photo_id" integer,
  	"_uuid" varchar
  );
  
  CREATE TABLE "_tier_sets_v_version_tiers_locales" (
  	"benefits_description" jsonb,
  	"id" serial PRIMARY KEY NOT NULL,
  	"_locale" "_locales" NOT NULL,
  	"_parent_id" integer NOT NULL
  );
  
  CREATE TABLE "_tier_sets_v" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"parent_id" integer,
  	"version_set_id" varchar,
  	"version_match" "enum__tier_sets_v_version_match" DEFAULT 'ALL',
  	"version_downgrade_mode" "enum__tier_sets_v_version_downgrade_mode" DEFAULT 'ANNUAL_ONE_LEVEL',
  	"version_downgrade_interval_months" numeric,
  	"version_status" "enum__tier_sets_v_version_status" DEFAULT 'draft',
  	"version_updated_at" timestamp(3) with time zone,
  	"version_created_at" timestamp(3) with time zone,
  	"version__status" "enum__tier_sets_v_version_status" DEFAULT 'draft',
  	"created_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"updated_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"snapshot" boolean,
  	"published_locale" "enum__tier_sets_v_published_locale",
  	"latest" boolean
  );
  
  CREATE TABLE "_tier_sets_v_locales" (
  	"version_name" varchar,
  	"id" serial PRIMARY KEY NOT NULL,
  	"_locale" "_locales" NOT NULL,
  	"_parent_id" integer NOT NULL
  );
  
  CREATE TABLE "_tier_sets_v_texts" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"order" integer NOT NULL,
  	"parent_id" integer NOT NULL,
  	"path" varchar NOT NULL,
  	"text" varchar
  );
  
  CREATE TABLE "_tier_sets_v_rels" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"order" integer,
  	"parent_id" integer NOT NULL,
  	"path" varchar NOT NULL,
  	"rewards_id" integer
  );
  
  CREATE TABLE "tiers" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"code" varchar NOT NULL,
  	"order" numeric NOT NULL,
  	"status_threshold" numeric NOT NULL,
  	"benefits" jsonb,
  	"discount_percent" numeric,
  	"photo_id" integer,
  	"status" "enum_tiers_status" DEFAULT 'draft' NOT NULL,
  	"updated_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"created_at" timestamp(3) with time zone DEFAULT now() NOT NULL
  );
  
  CREATE TABLE "tiers_rels" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"order" integer,
  	"parent_id" integer NOT NULL,
  	"path" varchar NOT NULL,
  	"rewards_id" integer
  );
  
  CREATE TABLE "segments_criteria" (
  	"_order" integer NOT NULL,
  	"_parent_id" integer NOT NULL,
  	"id" varchar PRIMARY KEY NOT NULL,
  	"type" "enum_segments_criteria_type",
  	"params" jsonb
  );
  
  CREATE TABLE "segments" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"segment_id" varchar,
  	"name" varchar,
  	"match" "enum_segments_match" DEFAULT 'ALL',
  	"static_list_id" integer,
  	"status" "enum_segments_status" DEFAULT 'draft',
  	"updated_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"created_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"_status" "enum_segments_status" DEFAULT 'draft'
  );
  
  CREATE TABLE "_segments_v_version_criteria" (
  	"_order" integer NOT NULL,
  	"_parent_id" integer NOT NULL,
  	"id" serial PRIMARY KEY NOT NULL,
  	"type" "enum__segments_v_version_criteria_type",
  	"params" jsonb,
  	"_uuid" varchar
  );
  
  CREATE TABLE "_segments_v" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"parent_id" integer,
  	"version_segment_id" varchar,
  	"version_name" varchar,
  	"version_match" "enum__segments_v_version_match" DEFAULT 'ALL',
  	"version_static_list_id" integer,
  	"version_status" "enum__segments_v_version_status" DEFAULT 'draft',
  	"version_updated_at" timestamp(3) with time zone,
  	"version_created_at" timestamp(3) with time zone,
  	"version__status" "enum__segments_v_version_status" DEFAULT 'draft',
  	"created_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"updated_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"snapshot" boolean,
  	"published_locale" "enum__segments_v_published_locale",
  	"latest" boolean
  );
  
  CREATE TABLE "collections" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"collection_id" varchar NOT NULL,
  	"description" varchar,
  	"csv_id" integer,
  	"updated_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"created_at" timestamp(3) with time zone DEFAULT now() NOT NULL
  );
  
  CREATE TABLE "event_schemas_attributes" (
  	"_order" integer NOT NULL,
  	"_parent_id" integer NOT NULL,
  	"id" varchar PRIMARY KEY NOT NULL,
  	"name" varchar,
  	"type" "enum_event_schemas_attributes_type",
  	"required" boolean DEFAULT false,
  	"description" varchar
  );
  
  CREATE TABLE "event_schemas" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"action_type" varchar,
  	"lenient" boolean DEFAULT true,
  	"status" "enum_event_schemas_status" DEFAULT 'draft',
  	"updated_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"created_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"_status" "enum_event_schemas_status" DEFAULT 'draft'
  );
  
  CREATE TABLE "event_schemas_locales" (
  	"name" varchar,
  	"id" serial PRIMARY KEY NOT NULL,
  	"_locale" "_locales" NOT NULL,
  	"_parent_id" integer NOT NULL
  );
  
  CREATE TABLE "_event_schemas_v_version_attributes" (
  	"_order" integer NOT NULL,
  	"_parent_id" integer NOT NULL,
  	"id" serial PRIMARY KEY NOT NULL,
  	"name" varchar,
  	"type" "enum__event_schemas_v_version_attributes_type",
  	"required" boolean DEFAULT false,
  	"description" varchar,
  	"_uuid" varchar
  );
  
  CREATE TABLE "_event_schemas_v" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"parent_id" integer,
  	"version_action_type" varchar,
  	"version_lenient" boolean DEFAULT true,
  	"version_status" "enum__event_schemas_v_version_status" DEFAULT 'draft',
  	"version_updated_at" timestamp(3) with time zone,
  	"version_created_at" timestamp(3) with time zone,
  	"version__status" "enum__event_schemas_v_version_status" DEFAULT 'draft',
  	"created_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"updated_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"snapshot" boolean,
  	"published_locale" "enum__event_schemas_v_published_locale",
  	"latest" boolean
  );
  
  CREATE TABLE "_event_schemas_v_locales" (
  	"version_name" varchar,
  	"id" serial PRIMARY KEY NOT NULL,
  	"_locale" "_locales" NOT NULL,
  	"_parent_id" integer NOT NULL
  );
  
  CREATE TABLE "custom_field_schemas_fields" (
  	"_order" integer NOT NULL,
  	"_parent_id" integer NOT NULL,
  	"id" varchar PRIMARY KEY NOT NULL,
  	"name" varchar NOT NULL,
  	"type" "enum_custom_field_schemas_fields_type" NOT NULL,
  	"required" boolean,
  	"max_length" numeric,
  	"regex" varchar,
  	"min" numeric,
  	"max" numeric,
  	"collection_id" integer
  );
  
  CREATE TABLE "custom_field_schemas" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"entity" "enum_custom_field_schemas_entity" NOT NULL,
  	"group" varchar NOT NULL,
  	"repeatable" boolean DEFAULT false,
  	"edit_role" "enum_custom_field_schemas_edit_role" DEFAULT 'customer_care',
  	"updated_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"created_at" timestamp(3) with time zone DEFAULT now() NOT NULL
  );
  
  CREATE TABLE "custom_field_schemas_locales" (
  	"name" varchar NOT NULL,
  	"id" serial PRIMARY KEY NOT NULL,
  	"_locale" "_locales" NOT NULL,
  	"_parent_id" integer NOT NULL
  );
  
  CREATE TABLE "channels" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"code" varchar NOT NULL,
  	"kind" "enum_channels_kind" DEFAULT 'ONLINE',
  	"address" varchar,
  	"lat" numeric,
  	"lon" numeric,
  	"active" boolean DEFAULT true,
  	"updated_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"created_at" timestamp(3) with time zone DEFAULT now() NOT NULL
  );
  
  CREATE TABLE "channels_locales" (
  	"name" varchar NOT NULL,
  	"id" serial PRIMARY KEY NOT NULL,
  	"_locale" "_locales" NOT NULL,
  	"_parent_id" integer NOT NULL
  );
  
  CREATE TABLE "programs_missions" (
  	"_order" integer NOT NULL,
  	"_parent_id" integer NOT NULL,
  	"id" varchar PRIMARY KEY NOT NULL,
  	"mission_id" varchar,
  	"name" varchar,
  	"ordered" boolean DEFAULT false,
  	"window_days" numeric DEFAULT 0,
  	"reward_id" integer,
  	"badge_id" integer
  );
  
  CREATE TABLE "programs" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"year" numeric,
  	"starts_at" timestamp(3) with time zone,
  	"ends_at" timestamp(3) with time zone,
  	"regulation_id" integer,
  	"bond_reference" varchar,
  	"status" "enum_programs_status" DEFAULT 'draft',
  	"updated_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"created_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"_status" "enum_programs_status" DEFAULT 'draft'
  );
  
  CREATE TABLE "programs_texts" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"order" integer NOT NULL,
  	"parent_id" integer NOT NULL,
  	"path" varchar NOT NULL,
  	"text" varchar
  );
  
  CREATE TABLE "_programs_v_version_missions" (
  	"_order" integer NOT NULL,
  	"_parent_id" integer NOT NULL,
  	"id" serial PRIMARY KEY NOT NULL,
  	"mission_id" varchar,
  	"name" varchar,
  	"ordered" boolean DEFAULT false,
  	"window_days" numeric DEFAULT 0,
  	"reward_id" integer,
  	"badge_id" integer,
  	"_uuid" varchar
  );
  
  CREATE TABLE "_programs_v" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"parent_id" integer,
  	"version_year" numeric,
  	"version_starts_at" timestamp(3) with time zone,
  	"version_ends_at" timestamp(3) with time zone,
  	"version_regulation_id" integer,
  	"version_bond_reference" varchar,
  	"version_status" "enum__programs_v_version_status" DEFAULT 'draft',
  	"version_updated_at" timestamp(3) with time zone,
  	"version_created_at" timestamp(3) with time zone,
  	"version__status" "enum__programs_v_version_status" DEFAULT 'draft',
  	"created_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"updated_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"snapshot" boolean,
  	"published_locale" "enum__programs_v_published_locale",
  	"latest" boolean
  );
  
  CREATE TABLE "_programs_v_texts" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"order" integer NOT NULL,
  	"parent_id" integer NOT NULL,
  	"path" varchar NOT NULL,
  	"text" varchar
  );
  
  CREATE TABLE "message_templates" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"event_type" varchar,
  	"channel" "enum_message_templates_channel",
  	"locale" "enum_message_templates_locale" DEFAULT 'it',
  	"subject" varchar,
  	"body" varchar,
  	"status" "enum_message_templates_status" DEFAULT 'draft',
  	"updated_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"created_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"_status" "enum_message_templates_status" DEFAULT 'draft'
  );
  
  CREATE TABLE "_message_templates_v" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"parent_id" integer,
  	"version_event_type" varchar,
  	"version_channel" "enum__message_templates_v_version_channel",
  	"version_locale" "enum__message_templates_v_version_locale" DEFAULT 'it',
  	"version_subject" varchar,
  	"version_body" varchar,
  	"version_status" "enum__message_templates_v_version_status" DEFAULT 'draft',
  	"version_updated_at" timestamp(3) with time zone,
  	"version_created_at" timestamp(3) with time zone,
  	"version__status" "enum__message_templates_v_version_status" DEFAULT 'draft',
  	"created_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"updated_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"snapshot" boolean,
  	"published_locale" "enum__message_templates_v_published_locale",
  	"latest" boolean
  );
  
  CREATE TABLE "webhooks" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"name" varchar NOT NULL,
  	"url" varchar NOT NULL,
  	"secret_ref" varchar NOT NULL,
  	"headers" jsonb,
  	"max_retries" numeric DEFAULT 5,
  	"active" boolean DEFAULT true,
  	"updated_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"created_at" timestamp(3) with time zone DEFAULT now() NOT NULL
  );
  
  CREATE TABLE "webhooks_texts" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"order" integer NOT NULL,
  	"parent_id" integer NOT NULL,
  	"path" varchar NOT NULL,
  	"text" varchar
  );
  
  CREATE TABLE "roles_permissions" (
  	"order" integer NOT NULL,
  	"parent_id" integer NOT NULL,
  	"value" "enum_roles_permissions",
  	"id" serial PRIMARY KEY NOT NULL
  );
  
  CREATE TABLE "roles" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"code" varchar NOT NULL,
  	"name" varchar NOT NULL,
  	"updated_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"created_at" timestamp(3) with time zone DEFAULT now() NOT NULL
  );
  
  CREATE TABLE "settings_locales" (
  	"order" integer NOT NULL,
  	"parent_id" integer NOT NULL,
  	"value" "enum_settings_locales",
  	"id" serial PRIMARY KEY NOT NULL
  );
  
  CREATE TABLE "settings_identification_priority" (
  	"order" integer NOT NULL,
  	"parent_id" integer NOT NULL,
  	"value" "enum_settings_identification_priority",
  	"id" serial PRIMARY KEY NOT NULL
  );
  
  CREATE TABLE "settings" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"program_name" varchar DEFAULT 'Loyalty Hub',
  	"points_name_singular" varchar DEFAULT 'punto',
  	"points_name_plural" varchar DEFAULT 'punti',
  	"timezone" varchar DEFAULT 'Europe/Rome',
  	"points_expiry" "enum_settings_points_expiry" DEFAULT 'END_OF_NEXT_PROGRAM_YEAR',
  	"points_expiry_days" numeric,
  	"tier_downgrade" "enum_settings_tier_downgrade" DEFAULT 'ANNUAL_ONE_LEVEL',
  	"redemption_cancel_hours" numeric DEFAULT 48,
  	"manual_posting_four_eyes_above" numeric DEFAULT 1000,
  	"referral_trigger" "enum_settings_referral_trigger" DEFAULT 'ON_FIRST_ACTION',
  	"referral_grace_days" numeric DEFAULT 30,
  	"referral_max_referrals_per_year" numeric DEFAULT 10,
  	"reject_unknown_action_types" boolean DEFAULT false,
  	"loyalty_card_enabled" boolean DEFAULT true,
  	"loyalty_card_format" "enum_settings_loyalty_card_format" DEFAULT 'ALPHANUMERIC',
  	"loyalty_card_length" numeric DEFAULT 8,
  	"loyalty_card_prefix" varchar DEFAULT 'LH',
  	"active_member_days" numeric DEFAULT 365,
  	"eur_per_unit" numeric DEFAULT 0.01,
  	"limits_active_leaderboards" numeric DEFAULT 3,
  	"limits_automation_campaigns" numeric DEFAULT 4,
  	"limits_automation_audience" numeric DEFAULT 200000,
  	"updated_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"created_at" timestamp(3) with time zone DEFAULT now() NOT NULL
  );
  
  CREATE TABLE "settings_texts" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"order" integer NOT NULL,
  	"parent_id" integer NOT NULL,
  	"path" varchar NOT NULL,
  	"text" varchar
  );
  
  CREATE TABLE "settings_rels" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"order" integer,
  	"parent_id" integer NOT NULL,
  	"path" varchar NOT NULL,
  	"event_schemas_id" integer
  );
  
  CREATE TABLE "media" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"alt" varchar,
  	"updated_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"created_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"url" varchar,
  	"thumbnail_u_r_l" varchar,
  	"filename" varchar,
  	"mime_type" varchar,
  	"filesize" numeric,
  	"width" numeric,
  	"height" numeric,
  	"focal_x" numeric,
  	"focal_y" numeric
  );
  
  CREATE TABLE "payload_kv" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"key" varchar NOT NULL,
  	"data" jsonb NOT NULL
  );
  
  CREATE TABLE "users_sessions" (
  	"_order" integer NOT NULL,
  	"_parent_id" integer NOT NULL,
  	"id" varchar PRIMARY KEY NOT NULL,
  	"created_at" timestamp(3) with time zone,
  	"expires_at" timestamp(3) with time zone NOT NULL
  );
  
  CREATE TABLE "users" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"updated_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"created_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"email" varchar NOT NULL,
  	"reset_password_token" varchar,
  	"reset_password_expiration" timestamp(3) with time zone,
  	"salt" varchar,
  	"hash" varchar,
  	"login_attempts" numeric DEFAULT 0,
  	"lock_until" timestamp(3) with time zone
  );
  
  CREATE TABLE "payload_locked_documents" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"global_slug" varchar,
  	"updated_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"created_at" timestamp(3) with time zone DEFAULT now() NOT NULL
  );
  
  CREATE TABLE "payload_locked_documents_rels" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"order" integer,
  	"parent_id" integer NOT NULL,
  	"path" varchar NOT NULL,
  	"campaigns_id" integer,
  	"decision_policies_id" integer,
  	"offers_id" integer,
  	"experiments_id" integer,
  	"prediction_providers_id" integer,
  	"fraud_rules_id" integer,
  	"delivery_routing_id" integer,
  	"consent_purposes_id" integer,
  	"point_rules_id" integer,
  	"achievements_id" integer,
  	"challenges_id" integer,
  	"badges_id" integer,
  	"leaderboards_id" integer,
  	"fortune_wheels_id" integer,
  	"contests_id" integer,
  	"contest_cards_id" integer,
  	"win_cards_id" integer,
  	"popups_id" integer,
  	"rewards_id" integer,
  	"reward_categories_id" integer,
  	"coupon_pools_id" integer,
  	"promo_codes_id" integer,
  	"wallets_id" integer,
  	"tier_sets_id" integer,
  	"tiers_id" integer,
  	"segments_id" integer,
  	"collections_id" integer,
  	"event_schemas_id" integer,
  	"custom_field_schemas_id" integer,
  	"channels_id" integer,
  	"programs_id" integer,
  	"message_templates_id" integer,
  	"webhooks_id" integer,
  	"roles_id" integer,
  	"settings_id" integer,
  	"media_id" integer,
  	"users_id" integer
  );
  
  CREATE TABLE "payload_preferences" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"key" varchar,
  	"value" jsonb,
  	"updated_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"created_at" timestamp(3) with time zone DEFAULT now() NOT NULL
  );
  
  CREATE TABLE "payload_preferences_rels" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"order" integer,
  	"parent_id" integer NOT NULL,
  	"path" varchar NOT NULL,
  	"users_id" integer
  );
  
  CREATE TABLE "payload_migrations" (
  	"id" serial PRIMARY KEY NOT NULL,
  	"name" varchar,
  	"batch" numeric,
  	"updated_at" timestamp(3) with time zone DEFAULT now() NOT NULL,
  	"created_at" timestamp(3) with time zone DEFAULT now() NOT NULL
  );
  
  ALTER TABLE "campaigns_visibility_tiers" ADD CONSTRAINT "campaigns_visibility_tiers_parent_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."campaigns"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "campaigns_rules_conditions" ADD CONSTRAINT "campaigns_rules_conditions_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."campaigns_rules"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "campaigns_rules_effects" ADD CONSTRAINT "campaigns_rules_effects_wallet_id_wallets_id_fk" FOREIGN KEY ("wallet_id") REFERENCES "public"."wallets"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "campaigns_rules_effects" ADD CONSTRAINT "campaigns_rules_effects_reward_id_rewards_id_fk" FOREIGN KEY ("reward_id") REFERENCES "public"."rewards"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "campaigns_rules_effects" ADD CONSTRAINT "campaigns_rules_effects_badge_id_badges_id_fk" FOREIGN KEY ("badge_id") REFERENCES "public"."badges"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "campaigns_rules_effects" ADD CONSTRAINT "campaigns_rules_effects_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."campaigns_rules"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "campaigns_rules" ADD CONSTRAINT "campaigns_rules_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."campaigns"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "campaigns" ADD CONSTRAINT "campaigns_trigger_action_type_id_event_schemas_id_fk" FOREIGN KEY ("trigger_action_type_id") REFERENCES "public"."event_schemas"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "campaigns" ADD CONSTRAINT "campaigns_trigger_line_filter_sku_collection_id_collections_id_fk" FOREIGN KEY ("trigger_line_filter_sku_collection_id") REFERENCES "public"."collections"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "campaigns" ADD CONSTRAINT "campaigns_photo_id_media_id_fk" FOREIGN KEY ("photo_id") REFERENCES "public"."media"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "campaigns_locales" ADD CONSTRAINT "campaigns_locales_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."campaigns"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "campaigns_texts" ADD CONSTRAINT "campaigns_texts_parent_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."campaigns"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "campaigns_rels" ADD CONSTRAINT "campaigns_rels_parent_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."campaigns"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "campaigns_rels" ADD CONSTRAINT "campaigns_rels_segments_fk" FOREIGN KEY ("segments_id") REFERENCES "public"."segments"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_campaigns_v_version_visibility_tiers" ADD CONSTRAINT "_campaigns_v_version_visibility_tiers_parent_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."_campaigns_v"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_campaigns_v_version_rules_conditions" ADD CONSTRAINT "_campaigns_v_version_rules_conditions_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."_campaigns_v_version_rules"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_campaigns_v_version_rules_effects" ADD CONSTRAINT "_campaigns_v_version_rules_effects_wallet_id_wallets_id_fk" FOREIGN KEY ("wallet_id") REFERENCES "public"."wallets"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "_campaigns_v_version_rules_effects" ADD CONSTRAINT "_campaigns_v_version_rules_effects_reward_id_rewards_id_fk" FOREIGN KEY ("reward_id") REFERENCES "public"."rewards"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "_campaigns_v_version_rules_effects" ADD CONSTRAINT "_campaigns_v_version_rules_effects_badge_id_badges_id_fk" FOREIGN KEY ("badge_id") REFERENCES "public"."badges"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "_campaigns_v_version_rules_effects" ADD CONSTRAINT "_campaigns_v_version_rules_effects_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."_campaigns_v_version_rules"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_campaigns_v_version_rules" ADD CONSTRAINT "_campaigns_v_version_rules_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."_campaigns_v"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_campaigns_v" ADD CONSTRAINT "_campaigns_v_parent_id_campaigns_id_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."campaigns"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "_campaigns_v" ADD CONSTRAINT "_campaigns_v_version_trigger_action_type_id_event_schemas_id_fk" FOREIGN KEY ("version_trigger_action_type_id") REFERENCES "public"."event_schemas"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "_campaigns_v" ADD CONSTRAINT "_campaigns_v_version_trigger_line_filter_sku_collection_id_collections_id_fk" FOREIGN KEY ("version_trigger_line_filter_sku_collection_id") REFERENCES "public"."collections"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "_campaigns_v" ADD CONSTRAINT "_campaigns_v_version_photo_id_media_id_fk" FOREIGN KEY ("version_photo_id") REFERENCES "public"."media"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "_campaigns_v_locales" ADD CONSTRAINT "_campaigns_v_locales_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."_campaigns_v"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_campaigns_v_texts" ADD CONSTRAINT "_campaigns_v_texts_parent_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."_campaigns_v"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_campaigns_v_rels" ADD CONSTRAINT "_campaigns_v_rels_parent_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."_campaigns_v"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_campaigns_v_rels" ADD CONSTRAINT "_campaigns_v_rels_segments_fk" FOREIGN KEY ("segments_id") REFERENCES "public"."segments"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "decision_policies_actions_channels" ADD CONSTRAINT "decision_policies_actions_channels_parent_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."decision_policies_actions"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "decision_policies_actions" ADD CONSTRAINT "decision_policies_actions_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."decision_policies"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "contact_cap_7d" ADD CONSTRAINT "contact_cap_7d_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."decision_policies"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "decision_policies_scoring_tier_boost" ADD CONSTRAINT "decision_policies_scoring_tier_boost_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."decision_policies"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "decision_policies_always_apply" ADD CONSTRAINT "decision_policies_always_apply_parent_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."decision_policies"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "channel_pref" ADD CONSTRAINT "channel_pref_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."decision_policies"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "decision_policies_locales" ADD CONSTRAINT "decision_policies_locales_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."decision_policies"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "decision_policies_texts" ADD CONSTRAINT "decision_policies_texts_parent_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."decision_policies"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_decision_policies_v_version_actions_channels" ADD CONSTRAINT "_decision_policies_v_version_actions_channels_parent_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."_decision_policies_v_version_actions"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_decision_policies_v_version_actions" ADD CONSTRAINT "_decision_policies_v_version_actions_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."_decision_policies_v"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_contact_cap_7d_v" ADD CONSTRAINT "_contact_cap_7d_v_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."_decision_policies_v"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_decision_policies_v_version_scoring_tier_boost" ADD CONSTRAINT "_decision_policies_v_version_scoring_tier_boost_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."_decision_policies_v"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_decision_policies_v_version_always_apply" ADD CONSTRAINT "_decision_policies_v_version_always_apply_parent_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."_decision_policies_v"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_channel_pref_v" ADD CONSTRAINT "_channel_pref_v_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."_decision_policies_v"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_decision_policies_v" ADD CONSTRAINT "_decision_policies_v_parent_id_decision_policies_id_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."decision_policies"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "_decision_policies_v_locales" ADD CONSTRAINT "_decision_policies_v_locales_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."_decision_policies_v"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_decision_policies_v_texts" ADD CONSTRAINT "_decision_policies_v_texts_parent_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."_decision_policies_v"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "offers_channels" ADD CONSTRAINT "offers_channels_parent_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."offers"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "offers" ADD CONSTRAINT "offers_photo_id_media_id_fk" FOREIGN KEY ("photo_id") REFERENCES "public"."media"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "offers_locales" ADD CONSTRAINT "offers_locales_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."offers"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_offers_v_version_channels" ADD CONSTRAINT "_offers_v_version_channels_parent_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."_offers_v"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_offers_v" ADD CONSTRAINT "_offers_v_parent_id_offers_id_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."offers"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "_offers_v" ADD CONSTRAINT "_offers_v_version_photo_id_media_id_fk" FOREIGN KEY ("version_photo_id") REFERENCES "public"."media"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "_offers_v_locales" ADD CONSTRAINT "_offers_v_locales_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."_offers_v"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "experiments_variants_overrides" ADD CONSTRAINT "experiments_variants_overrides_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."experiments_variants"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "experiments_variants" ADD CONSTRAINT "experiments_variants_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."experiments"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "experiments_texts" ADD CONSTRAINT "experiments_texts_parent_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."experiments"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_experiments_v_version_variants_overrides" ADD CONSTRAINT "_experiments_v_version_variants_overrides_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."_experiments_v_version_variants"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_experiments_v_version_variants" ADD CONSTRAINT "_experiments_v_version_variants_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."_experiments_v"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_experiments_v" ADD CONSTRAINT "_experiments_v_parent_id_experiments_id_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."experiments"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "_experiments_v_texts" ADD CONSTRAINT "_experiments_v_texts_parent_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."_experiments_v"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "prediction_providers_serves_keys" ADD CONSTRAINT "prediction_providers_serves_keys_parent_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."prediction_providers"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_prediction_providers_v_version_serves_keys" ADD CONSTRAINT "_prediction_providers_v_version_serves_keys_parent_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."_prediction_providers_v"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_prediction_providers_v" ADD CONSTRAINT "_prediction_providers_v_parent_id_prediction_providers_id_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."prediction_providers"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "fraud_rules_signals" ADD CONSTRAINT "fraud_rules_signals_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."fraud_rules"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_fraud_rules_v_version_signals" ADD CONSTRAINT "_fraud_rules_v_version_signals_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."_fraud_rules_v"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_fraud_rules_v" ADD CONSTRAINT "_fraud_rules_v_parent_id_fraud_rules_id_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."fraud_rules"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "delivery_routing_routes_channels" ADD CONSTRAINT "delivery_routing_routes_channels_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."delivery_routing_routes"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "delivery_routing_routes" ADD CONSTRAINT "delivery_routing_routes_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."delivery_routing"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "delivery_routing_enabled_channels" ADD CONSTRAINT "delivery_routing_enabled_channels_parent_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."delivery_routing"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "max_per_day" ADD CONSTRAINT "max_per_day_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."delivery_routing"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "delivery_routing_templates" ADD CONSTRAINT "delivery_routing_templates_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."delivery_routing"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_delivery_routing_v_version_routes_channels" ADD CONSTRAINT "_delivery_routing_v_version_routes_channels_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."_delivery_routing_v_version_routes"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_delivery_routing_v_version_routes" ADD CONSTRAINT "_delivery_routing_v_version_routes_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."_delivery_routing_v"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_delivery_routing_v_version_enabled_channels" ADD CONSTRAINT "_delivery_routing_v_version_enabled_channels_parent_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."_delivery_routing_v"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_max_per_day_v" ADD CONSTRAINT "_max_per_day_v_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."_delivery_routing_v"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_delivery_routing_v_version_templates" ADD CONSTRAINT "_delivery_routing_v_version_templates_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."_delivery_routing_v"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_delivery_routing_v" ADD CONSTRAINT "_delivery_routing_v_parent_id_delivery_routing_id_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."delivery_routing"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "consent_purposes_locales" ADD CONSTRAINT "consent_purposes_locales_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."consent_purposes"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "point_rules_conditions" ADD CONSTRAINT "point_rules_conditions_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."point_rules"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "point_rules_target_tiers" ADD CONSTRAINT "point_rules_target_tiers_parent_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."point_rules"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "point_rules_target_channels" ADD CONSTRAINT "point_rules_target_channels_parent_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."point_rules"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "point_rules" ADD CONSTRAINT "point_rules_earning_auto_reward_id_rewards_id_fk" FOREIGN KEY ("earning_auto_reward_id") REFERENCES "public"."rewards"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "point_rules" ADD CONSTRAINT "point_rules_photo_id_media_id_fk" FOREIGN KEY ("photo_id") REFERENCES "public"."media"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "point_rules_texts" ADD CONSTRAINT "point_rules_texts_parent_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."point_rules"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "point_rules_rels" ADD CONSTRAINT "point_rules_rels_parent_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."point_rules"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "point_rules_rels" ADD CONSTRAINT "point_rules_rels_segments_fk" FOREIGN KEY ("segments_id") REFERENCES "public"."segments"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_point_rules_v_version_conditions" ADD CONSTRAINT "_point_rules_v_version_conditions_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."_point_rules_v"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_point_rules_v_version_target_tiers" ADD CONSTRAINT "_point_rules_v_version_target_tiers_parent_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."_point_rules_v"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_point_rules_v_version_target_channels" ADD CONSTRAINT "_point_rules_v_version_target_channels_parent_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."_point_rules_v"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_point_rules_v" ADD CONSTRAINT "_point_rules_v_parent_id_point_rules_id_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."point_rules"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "_point_rules_v" ADD CONSTRAINT "_point_rules_v_version_earning_auto_reward_id_rewards_id_fk" FOREIGN KEY ("version_earning_auto_reward_id") REFERENCES "public"."rewards"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "_point_rules_v" ADD CONSTRAINT "_point_rules_v_version_photo_id_media_id_fk" FOREIGN KEY ("version_photo_id") REFERENCES "public"."media"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "_point_rules_v_texts" ADD CONSTRAINT "_point_rules_v_texts_parent_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."_point_rules_v"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_point_rules_v_rels" ADD CONSTRAINT "_point_rules_v_rels_parent_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."_point_rules_v"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_point_rules_v_rels" ADD CONSTRAINT "_point_rules_v_rels_segments_fk" FOREIGN KEY ("segments_id") REFERENCES "public"."segments"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "achievements_conditions" ADD CONSTRAINT "achievements_conditions_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."achievements"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "achievements" ADD CONSTRAINT "achievements_action_type_id_event_schemas_id_fk" FOREIGN KEY ("action_type_id") REFERENCES "public"."event_schemas"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "achievements" ADD CONSTRAINT "achievements_photo_id_media_id_fk" FOREIGN KEY ("photo_id") REFERENCES "public"."media"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "achievements_locales" ADD CONSTRAINT "achievements_locales_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."achievements"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_achievements_v_version_conditions" ADD CONSTRAINT "_achievements_v_version_conditions_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."_achievements_v"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_achievements_v" ADD CONSTRAINT "_achievements_v_parent_id_achievements_id_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."achievements"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "_achievements_v" ADD CONSTRAINT "_achievements_v_version_action_type_id_event_schemas_id_fk" FOREIGN KEY ("version_action_type_id") REFERENCES "public"."event_schemas"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "_achievements_v" ADD CONSTRAINT "_achievements_v_version_photo_id_media_id_fk" FOREIGN KEY ("version_photo_id") REFERENCES "public"."media"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "_achievements_v_locales" ADD CONSTRAINT "_achievements_v_locales_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."_achievements_v"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "challenges_availability_days_of_week" ADD CONSTRAINT "challenges_availability_days_of_week_parent_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."challenges"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "challenges_visibility_tiers" ADD CONSTRAINT "challenges_visibility_tiers_parent_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."challenges"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "challenges_milestones_conditions" ADD CONSTRAINT "challenges_milestones_conditions_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."challenges_milestones"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "challenges_milestones" ADD CONSTRAINT "challenges_milestones_action_type_id_event_schemas_id_fk" FOREIGN KEY ("action_type_id") REFERENCES "public"."event_schemas"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "challenges_milestones" ADD CONSTRAINT "challenges_milestones_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."challenges"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "challenges_milestones_locales" ADD CONSTRAINT "challenges_milestones_locales_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."challenges_milestones"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "challenges_rules_effects" ADD CONSTRAINT "challenges_rules_effects_wallet_id_wallets_id_fk" FOREIGN KEY ("wallet_id") REFERENCES "public"."wallets"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "challenges_rules_effects" ADD CONSTRAINT "challenges_rules_effects_reward_id_rewards_id_fk" FOREIGN KEY ("reward_id") REFERENCES "public"."rewards"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "challenges_rules_effects" ADD CONSTRAINT "challenges_rules_effects_badge_id_badges_id_fk" FOREIGN KEY ("badge_id") REFERENCES "public"."badges"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "challenges_rules_effects" ADD CONSTRAINT "challenges_rules_effects_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."challenges_rules"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "challenges_rules" ADD CONSTRAINT "challenges_rules_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."challenges"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "challenges" ADD CONSTRAINT "challenges_badge_id_badges_id_fk" FOREIGN KEY ("badge_id") REFERENCES "public"."badges"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "challenges" ADD CONSTRAINT "challenges_photo_id_media_id_fk" FOREIGN KEY ("photo_id") REFERENCES "public"."media"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "challenges_locales" ADD CONSTRAINT "challenges_locales_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."challenges"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "challenges_texts" ADD CONSTRAINT "challenges_texts_parent_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."challenges"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "challenges_rels" ADD CONSTRAINT "challenges_rels_parent_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."challenges"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "challenges_rels" ADD CONSTRAINT "challenges_rels_segments_fk" FOREIGN KEY ("segments_id") REFERENCES "public"."segments"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_challenges_v_version_availability_days_of_week" ADD CONSTRAINT "_challenges_v_version_availability_days_of_week_parent_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."_challenges_v"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_challenges_v_version_visibility_tiers" ADD CONSTRAINT "_challenges_v_version_visibility_tiers_parent_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."_challenges_v"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_challenges_v_version_milestones_conditions" ADD CONSTRAINT "_challenges_v_version_milestones_conditions_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."_challenges_v_version_milestones"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_challenges_v_version_milestones" ADD CONSTRAINT "_challenges_v_version_milestones_action_type_id_event_schemas_id_fk" FOREIGN KEY ("action_type_id") REFERENCES "public"."event_schemas"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "_challenges_v_version_milestones" ADD CONSTRAINT "_challenges_v_version_milestones_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."_challenges_v"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_challenges_v_version_milestones_locales" ADD CONSTRAINT "_challenges_v_version_milestones_locales_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."_challenges_v_version_milestones"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_challenges_v_version_rules_effects" ADD CONSTRAINT "_challenges_v_version_rules_effects_wallet_id_wallets_id_fk" FOREIGN KEY ("wallet_id") REFERENCES "public"."wallets"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "_challenges_v_version_rules_effects" ADD CONSTRAINT "_challenges_v_version_rules_effects_reward_id_rewards_id_fk" FOREIGN KEY ("reward_id") REFERENCES "public"."rewards"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "_challenges_v_version_rules_effects" ADD CONSTRAINT "_challenges_v_version_rules_effects_badge_id_badges_id_fk" FOREIGN KEY ("badge_id") REFERENCES "public"."badges"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "_challenges_v_version_rules_effects" ADD CONSTRAINT "_challenges_v_version_rules_effects_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."_challenges_v_version_rules"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_challenges_v_version_rules" ADD CONSTRAINT "_challenges_v_version_rules_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."_challenges_v"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_challenges_v" ADD CONSTRAINT "_challenges_v_parent_id_challenges_id_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."challenges"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "_challenges_v" ADD CONSTRAINT "_challenges_v_version_badge_id_badges_id_fk" FOREIGN KEY ("version_badge_id") REFERENCES "public"."badges"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "_challenges_v" ADD CONSTRAINT "_challenges_v_version_photo_id_media_id_fk" FOREIGN KEY ("version_photo_id") REFERENCES "public"."media"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "_challenges_v_locales" ADD CONSTRAINT "_challenges_v_locales_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."_challenges_v"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_challenges_v_texts" ADD CONSTRAINT "_challenges_v_texts_parent_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."_challenges_v"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_challenges_v_rels" ADD CONSTRAINT "_challenges_v_rels_parent_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."_challenges_v"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_challenges_v_rels" ADD CONSTRAINT "_challenges_v_rels_segments_fk" FOREIGN KEY ("segments_id") REFERENCES "public"."segments"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "badges" ADD CONSTRAINT "badges_image_id_media_id_fk" FOREIGN KEY ("image_id") REFERENCES "public"."media"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "badges_locales" ADD CONSTRAINT "badges_locales_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."badges"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "leaderboards_rewarding_cycle_rewards" ADD CONSTRAINT "leaderboards_rewarding_cycle_rewards_reward_id_rewards_id_fk" FOREIGN KEY ("reward_id") REFERENCES "public"."rewards"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "leaderboards_rewarding_cycle_rewards" ADD CONSTRAINT "leaderboards_rewarding_cycle_rewards_wallet_id_wallets_id_fk" FOREIGN KEY ("wallet_id") REFERENCES "public"."wallets"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "leaderboards_rewarding_cycle_rewards" ADD CONSTRAINT "leaderboards_rewarding_cycle_rewards_badge_id_badges_id_fk" FOREIGN KEY ("badge_id") REFERENCES "public"."badges"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "leaderboards_rewarding_cycle_rewards" ADD CONSTRAINT "leaderboards_rewarding_cycle_rewards_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."leaderboards"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "leaderboards_locales" ADD CONSTRAINT "leaderboards_locales_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."leaderboards"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_leaderboards_v_version_rewarding_cycle_rewards" ADD CONSTRAINT "_leaderboards_v_version_rewarding_cycle_rewards_reward_id_rewards_id_fk" FOREIGN KEY ("reward_id") REFERENCES "public"."rewards"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "_leaderboards_v_version_rewarding_cycle_rewards" ADD CONSTRAINT "_leaderboards_v_version_rewarding_cycle_rewards_wallet_id_wallets_id_fk" FOREIGN KEY ("wallet_id") REFERENCES "public"."wallets"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "_leaderboards_v_version_rewarding_cycle_rewards" ADD CONSTRAINT "_leaderboards_v_version_rewarding_cycle_rewards_badge_id_badges_id_fk" FOREIGN KEY ("badge_id") REFERENCES "public"."badges"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "_leaderboards_v_version_rewarding_cycle_rewards" ADD CONSTRAINT "_leaderboards_v_version_rewarding_cycle_rewards_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."_leaderboards_v"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_leaderboards_v" ADD CONSTRAINT "_leaderboards_v_parent_id_leaderboards_id_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."leaderboards"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "_leaderboards_v_locales" ADD CONSTRAINT "_leaderboards_v_locales_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."_leaderboards_v"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "fortune_wheels_slots" ADD CONSTRAINT "fortune_wheels_slots_reward_id_rewards_id_fk" FOREIGN KEY ("reward_id") REFERENCES "public"."rewards"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "fortune_wheels_slots" ADD CONSTRAINT "fortune_wheels_slots_wallet_id_wallets_id_fk" FOREIGN KEY ("wallet_id") REFERENCES "public"."wallets"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "fortune_wheels_slots" ADD CONSTRAINT "fortune_wheels_slots_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."fortune_wheels"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "fortune_wheels_slots_locales" ADD CONSTRAINT "fortune_wheels_slots_locales_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."fortune_wheels_slots"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "fortune_wheels" ADD CONSTRAINT "fortune_wheels_contest_id_contests_id_fk" FOREIGN KEY ("contest_id") REFERENCES "public"."contests"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "fortune_wheels" ADD CONSTRAINT "fortune_wheels_cost_wallet_id_wallets_id_fk" FOREIGN KEY ("cost_wallet_id") REFERENCES "public"."wallets"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "fortune_wheels_locales" ADD CONSTRAINT "fortune_wheels_locales_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."fortune_wheels"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_fortune_wheels_v_version_slots" ADD CONSTRAINT "_fortune_wheels_v_version_slots_reward_id_rewards_id_fk" FOREIGN KEY ("reward_id") REFERENCES "public"."rewards"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "_fortune_wheels_v_version_slots" ADD CONSTRAINT "_fortune_wheels_v_version_slots_wallet_id_wallets_id_fk" FOREIGN KEY ("wallet_id") REFERENCES "public"."wallets"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "_fortune_wheels_v_version_slots" ADD CONSTRAINT "_fortune_wheels_v_version_slots_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."_fortune_wheels_v"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_fortune_wheels_v_version_slots_locales" ADD CONSTRAINT "_fortune_wheels_v_version_slots_locales_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."_fortune_wheels_v_version_slots"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_fortune_wheels_v" ADD CONSTRAINT "_fortune_wheels_v_parent_id_fortune_wheels_id_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."fortune_wheels"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "_fortune_wheels_v" ADD CONSTRAINT "_fortune_wheels_v_version_contest_id_contests_id_fk" FOREIGN KEY ("version_contest_id") REFERENCES "public"."contests"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "_fortune_wheels_v" ADD CONSTRAINT "_fortune_wheels_v_version_cost_wallet_id_wallets_id_fk" FOREIGN KEY ("version_cost_wallet_id") REFERENCES "public"."wallets"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "_fortune_wheels_v_locales" ADD CONSTRAINT "_fortune_wheels_v_locales_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."_fortune_wheels_v"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "contests_prizes" ADD CONSTRAINT "contests_prizes_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."contests"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "contests_weighted_slots" ADD CONSTRAINT "contests_weighted_slots_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."contests"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "contests" ADD CONSTRAINT "contests_regulation_id_media_id_fk" FOREIGN KEY ("regulation_id") REFERENCES "public"."media"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "_contests_v_version_prizes" ADD CONSTRAINT "_contests_v_version_prizes_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."_contests_v"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_contests_v_version_weighted_slots" ADD CONSTRAINT "_contests_v_version_weighted_slots_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."_contests_v"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_contests_v" ADD CONSTRAINT "_contests_v_parent_id_contests_id_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."contests"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "_contests_v" ADD CONSTRAINT "_contests_v_version_regulation_id_media_id_fk" FOREIGN KEY ("version_regulation_id") REFERENCES "public"."media"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "contest_cards" ADD CONSTRAINT "contest_cards_image_id_media_id_fk" FOREIGN KEY ("image_id") REFERENCES "public"."media"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "contest_cards" ADD CONSTRAINT "contest_cards_regulation_id_media_id_fk" FOREIGN KEY ("regulation_id") REFERENCES "public"."media"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "_contest_cards_v" ADD CONSTRAINT "_contest_cards_v_parent_id_contest_cards_id_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."contest_cards"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "_contest_cards_v" ADD CONSTRAINT "_contest_cards_v_version_image_id_media_id_fk" FOREIGN KEY ("version_image_id") REFERENCES "public"."media"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "_contest_cards_v" ADD CONSTRAINT "_contest_cards_v_version_regulation_id_media_id_fk" FOREIGN KEY ("version_regulation_id") REFERENCES "public"."media"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "win_cards" ADD CONSTRAINT "win_cards_image_id_media_id_fk" FOREIGN KEY ("image_id") REFERENCES "public"."media"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "_win_cards_v" ADD CONSTRAINT "_win_cards_v_parent_id_win_cards_id_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."win_cards"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "_win_cards_v" ADD CONSTRAINT "_win_cards_v_version_image_id_media_id_fk" FOREIGN KEY ("version_image_id") REFERENCES "public"."media"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "_popups_v" ADD CONSTRAINT "_popups_v_parent_id_popups_id_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."popups"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "rewards" ADD CONSTRAINT "rewards_category_id_reward_categories_id_fk" FOREIGN KEY ("category_id") REFERENCES "public"."reward_categories"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "rewards" ADD CONSTRAINT "rewards_coupon_pool_id_coupon_pools_id_fk" FOREIGN KEY ("coupon_pool_id") REFERENCES "public"."coupon_pools"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "rewards" ADD CONSTRAINT "rewards_photo_id_media_id_fk" FOREIGN KEY ("photo_id") REFERENCES "public"."media"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "rewards_rels" ADD CONSTRAINT "rewards_rels_parent_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."rewards"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "rewards_rels" ADD CONSTRAINT "rewards_rels_segments_fk" FOREIGN KEY ("segments_id") REFERENCES "public"."segments"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_rewards_v" ADD CONSTRAINT "_rewards_v_parent_id_rewards_id_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."rewards"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "_rewards_v" ADD CONSTRAINT "_rewards_v_version_category_id_reward_categories_id_fk" FOREIGN KEY ("version_category_id") REFERENCES "public"."reward_categories"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "_rewards_v" ADD CONSTRAINT "_rewards_v_version_coupon_pool_id_coupon_pools_id_fk" FOREIGN KEY ("version_coupon_pool_id") REFERENCES "public"."coupon_pools"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "_rewards_v" ADD CONSTRAINT "_rewards_v_version_photo_id_media_id_fk" FOREIGN KEY ("version_photo_id") REFERENCES "public"."media"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "_rewards_v_rels" ADD CONSTRAINT "_rewards_v_rels_parent_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."_rewards_v"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_rewards_v_rels" ADD CONSTRAINT "_rewards_v_rels_segments_fk" FOREIGN KEY ("segments_id") REFERENCES "public"."segments"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "reward_categories" ADD CONSTRAINT "reward_categories_photo_id_media_id_fk" FOREIGN KEY ("photo_id") REFERENCES "public"."media"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "coupon_pools" ADD CONSTRAINT "coupon_pools_codes_file_id_media_id_fk" FOREIGN KEY ("codes_file_id") REFERENCES "public"."media"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "promo_codes" ADD CONSTRAINT "promo_codes_qr_image_id_media_id_fk" FOREIGN KEY ("qr_image_id") REFERENCES "public"."media"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "_promo_codes_v" ADD CONSTRAINT "_promo_codes_v_parent_id_promo_codes_id_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."promo_codes"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "_promo_codes_v" ADD CONSTRAINT "_promo_codes_v_version_qr_image_id_media_id_fk" FOREIGN KEY ("version_qr_image_id") REFERENCES "public"."media"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "wallets_locales" ADD CONSTRAINT "wallets_locales_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."wallets"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "tier_sets_conditions" ADD CONSTRAINT "tier_sets_conditions_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."tier_sets"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "tier_sets_tiers" ADD CONSTRAINT "tier_sets_tiers_photo_id_media_id_fk" FOREIGN KEY ("photo_id") REFERENCES "public"."media"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "tier_sets_tiers" ADD CONSTRAINT "tier_sets_tiers_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."tier_sets"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "tier_sets_tiers_locales" ADD CONSTRAINT "tier_sets_tiers_locales_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."tier_sets_tiers"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "tier_sets_locales" ADD CONSTRAINT "tier_sets_locales_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."tier_sets"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "tier_sets_texts" ADD CONSTRAINT "tier_sets_texts_parent_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."tier_sets"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "tier_sets_rels" ADD CONSTRAINT "tier_sets_rels_parent_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."tier_sets"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "tier_sets_rels" ADD CONSTRAINT "tier_sets_rels_rewards_fk" FOREIGN KEY ("rewards_id") REFERENCES "public"."rewards"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_tier_sets_v_version_conditions" ADD CONSTRAINT "_tier_sets_v_version_conditions_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."_tier_sets_v"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_tier_sets_v_version_tiers" ADD CONSTRAINT "_tier_sets_v_version_tiers_photo_id_media_id_fk" FOREIGN KEY ("photo_id") REFERENCES "public"."media"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "_tier_sets_v_version_tiers" ADD CONSTRAINT "_tier_sets_v_version_tiers_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."_tier_sets_v"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_tier_sets_v_version_tiers_locales" ADD CONSTRAINT "_tier_sets_v_version_tiers_locales_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."_tier_sets_v_version_tiers"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_tier_sets_v" ADD CONSTRAINT "_tier_sets_v_parent_id_tier_sets_id_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."tier_sets"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "_tier_sets_v_locales" ADD CONSTRAINT "_tier_sets_v_locales_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."_tier_sets_v"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_tier_sets_v_texts" ADD CONSTRAINT "_tier_sets_v_texts_parent_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."_tier_sets_v"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_tier_sets_v_rels" ADD CONSTRAINT "_tier_sets_v_rels_parent_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."_tier_sets_v"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_tier_sets_v_rels" ADD CONSTRAINT "_tier_sets_v_rels_rewards_fk" FOREIGN KEY ("rewards_id") REFERENCES "public"."rewards"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "tiers" ADD CONSTRAINT "tiers_photo_id_media_id_fk" FOREIGN KEY ("photo_id") REFERENCES "public"."media"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "tiers_rels" ADD CONSTRAINT "tiers_rels_parent_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."tiers"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "tiers_rels" ADD CONSTRAINT "tiers_rels_rewards_fk" FOREIGN KEY ("rewards_id") REFERENCES "public"."rewards"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "segments_criteria" ADD CONSTRAINT "segments_criteria_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."segments"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "segments" ADD CONSTRAINT "segments_static_list_id_media_id_fk" FOREIGN KEY ("static_list_id") REFERENCES "public"."media"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "_segments_v_version_criteria" ADD CONSTRAINT "_segments_v_version_criteria_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."_segments_v"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_segments_v" ADD CONSTRAINT "_segments_v_parent_id_segments_id_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."segments"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "_segments_v" ADD CONSTRAINT "_segments_v_version_static_list_id_media_id_fk" FOREIGN KEY ("version_static_list_id") REFERENCES "public"."media"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "collections" ADD CONSTRAINT "collections_csv_id_media_id_fk" FOREIGN KEY ("csv_id") REFERENCES "public"."media"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "event_schemas_attributes" ADD CONSTRAINT "event_schemas_attributes_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."event_schemas"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "event_schemas_locales" ADD CONSTRAINT "event_schemas_locales_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."event_schemas"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_event_schemas_v_version_attributes" ADD CONSTRAINT "_event_schemas_v_version_attributes_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."_event_schemas_v"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_event_schemas_v" ADD CONSTRAINT "_event_schemas_v_parent_id_event_schemas_id_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."event_schemas"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "_event_schemas_v_locales" ADD CONSTRAINT "_event_schemas_v_locales_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."_event_schemas_v"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "custom_field_schemas_fields" ADD CONSTRAINT "custom_field_schemas_fields_collection_id_collections_id_fk" FOREIGN KEY ("collection_id") REFERENCES "public"."collections"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "custom_field_schemas_fields" ADD CONSTRAINT "custom_field_schemas_fields_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."custom_field_schemas"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "custom_field_schemas_locales" ADD CONSTRAINT "custom_field_schemas_locales_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."custom_field_schemas"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "channels_locales" ADD CONSTRAINT "channels_locales_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."channels"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "programs_missions" ADD CONSTRAINT "programs_missions_reward_id_rewards_id_fk" FOREIGN KEY ("reward_id") REFERENCES "public"."rewards"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "programs_missions" ADD CONSTRAINT "programs_missions_badge_id_media_id_fk" FOREIGN KEY ("badge_id") REFERENCES "public"."media"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "programs_missions" ADD CONSTRAINT "programs_missions_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."programs"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "programs" ADD CONSTRAINT "programs_regulation_id_media_id_fk" FOREIGN KEY ("regulation_id") REFERENCES "public"."media"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "programs_texts" ADD CONSTRAINT "programs_texts_parent_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."programs"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_programs_v_version_missions" ADD CONSTRAINT "_programs_v_version_missions_reward_id_rewards_id_fk" FOREIGN KEY ("reward_id") REFERENCES "public"."rewards"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "_programs_v_version_missions" ADD CONSTRAINT "_programs_v_version_missions_badge_id_media_id_fk" FOREIGN KEY ("badge_id") REFERENCES "public"."media"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "_programs_v_version_missions" ADD CONSTRAINT "_programs_v_version_missions_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."_programs_v"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_programs_v" ADD CONSTRAINT "_programs_v_parent_id_programs_id_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."programs"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "_programs_v" ADD CONSTRAINT "_programs_v_version_regulation_id_media_id_fk" FOREIGN KEY ("version_regulation_id") REFERENCES "public"."media"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "_programs_v_texts" ADD CONSTRAINT "_programs_v_texts_parent_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."_programs_v"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "_message_templates_v" ADD CONSTRAINT "_message_templates_v_parent_id_message_templates_id_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."message_templates"("id") ON DELETE set null ON UPDATE no action;
  ALTER TABLE "webhooks_texts" ADD CONSTRAINT "webhooks_texts_parent_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."webhooks"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "roles_permissions" ADD CONSTRAINT "roles_permissions_parent_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."roles"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "settings_locales" ADD CONSTRAINT "settings_locales_parent_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."settings"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "settings_identification_priority" ADD CONSTRAINT "settings_identification_priority_parent_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."settings"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "settings_texts" ADD CONSTRAINT "settings_texts_parent_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."settings"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "settings_rels" ADD CONSTRAINT "settings_rels_parent_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."settings"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "settings_rels" ADD CONSTRAINT "settings_rels_event_schemas_fk" FOREIGN KEY ("event_schemas_id") REFERENCES "public"."event_schemas"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "users_sessions" ADD CONSTRAINT "users_sessions_parent_id_fk" FOREIGN KEY ("_parent_id") REFERENCES "public"."users"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "payload_locked_documents_rels" ADD CONSTRAINT "payload_locked_documents_rels_parent_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."payload_locked_documents"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "payload_locked_documents_rels" ADD CONSTRAINT "payload_locked_documents_rels_campaigns_fk" FOREIGN KEY ("campaigns_id") REFERENCES "public"."campaigns"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "payload_locked_documents_rels" ADD CONSTRAINT "payload_locked_documents_rels_decision_policies_fk" FOREIGN KEY ("decision_policies_id") REFERENCES "public"."decision_policies"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "payload_locked_documents_rels" ADD CONSTRAINT "payload_locked_documents_rels_offers_fk" FOREIGN KEY ("offers_id") REFERENCES "public"."offers"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "payload_locked_documents_rels" ADD CONSTRAINT "payload_locked_documents_rels_experiments_fk" FOREIGN KEY ("experiments_id") REFERENCES "public"."experiments"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "payload_locked_documents_rels" ADD CONSTRAINT "payload_locked_documents_rels_prediction_providers_fk" FOREIGN KEY ("prediction_providers_id") REFERENCES "public"."prediction_providers"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "payload_locked_documents_rels" ADD CONSTRAINT "payload_locked_documents_rels_fraud_rules_fk" FOREIGN KEY ("fraud_rules_id") REFERENCES "public"."fraud_rules"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "payload_locked_documents_rels" ADD CONSTRAINT "payload_locked_documents_rels_delivery_routing_fk" FOREIGN KEY ("delivery_routing_id") REFERENCES "public"."delivery_routing"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "payload_locked_documents_rels" ADD CONSTRAINT "payload_locked_documents_rels_consent_purposes_fk" FOREIGN KEY ("consent_purposes_id") REFERENCES "public"."consent_purposes"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "payload_locked_documents_rels" ADD CONSTRAINT "payload_locked_documents_rels_point_rules_fk" FOREIGN KEY ("point_rules_id") REFERENCES "public"."point_rules"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "payload_locked_documents_rels" ADD CONSTRAINT "payload_locked_documents_rels_achievements_fk" FOREIGN KEY ("achievements_id") REFERENCES "public"."achievements"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "payload_locked_documents_rels" ADD CONSTRAINT "payload_locked_documents_rels_challenges_fk" FOREIGN KEY ("challenges_id") REFERENCES "public"."challenges"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "payload_locked_documents_rels" ADD CONSTRAINT "payload_locked_documents_rels_badges_fk" FOREIGN KEY ("badges_id") REFERENCES "public"."badges"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "payload_locked_documents_rels" ADD CONSTRAINT "payload_locked_documents_rels_leaderboards_fk" FOREIGN KEY ("leaderboards_id") REFERENCES "public"."leaderboards"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "payload_locked_documents_rels" ADD CONSTRAINT "payload_locked_documents_rels_fortune_wheels_fk" FOREIGN KEY ("fortune_wheels_id") REFERENCES "public"."fortune_wheels"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "payload_locked_documents_rels" ADD CONSTRAINT "payload_locked_documents_rels_contests_fk" FOREIGN KEY ("contests_id") REFERENCES "public"."contests"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "payload_locked_documents_rels" ADD CONSTRAINT "payload_locked_documents_rels_contest_cards_fk" FOREIGN KEY ("contest_cards_id") REFERENCES "public"."contest_cards"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "payload_locked_documents_rels" ADD CONSTRAINT "payload_locked_documents_rels_win_cards_fk" FOREIGN KEY ("win_cards_id") REFERENCES "public"."win_cards"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "payload_locked_documents_rels" ADD CONSTRAINT "payload_locked_documents_rels_popups_fk" FOREIGN KEY ("popups_id") REFERENCES "public"."popups"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "payload_locked_documents_rels" ADD CONSTRAINT "payload_locked_documents_rels_rewards_fk" FOREIGN KEY ("rewards_id") REFERENCES "public"."rewards"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "payload_locked_documents_rels" ADD CONSTRAINT "payload_locked_documents_rels_reward_categories_fk" FOREIGN KEY ("reward_categories_id") REFERENCES "public"."reward_categories"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "payload_locked_documents_rels" ADD CONSTRAINT "payload_locked_documents_rels_coupon_pools_fk" FOREIGN KEY ("coupon_pools_id") REFERENCES "public"."coupon_pools"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "payload_locked_documents_rels" ADD CONSTRAINT "payload_locked_documents_rels_promo_codes_fk" FOREIGN KEY ("promo_codes_id") REFERENCES "public"."promo_codes"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "payload_locked_documents_rels" ADD CONSTRAINT "payload_locked_documents_rels_wallets_fk" FOREIGN KEY ("wallets_id") REFERENCES "public"."wallets"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "payload_locked_documents_rels" ADD CONSTRAINT "payload_locked_documents_rels_tier_sets_fk" FOREIGN KEY ("tier_sets_id") REFERENCES "public"."tier_sets"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "payload_locked_documents_rels" ADD CONSTRAINT "payload_locked_documents_rels_tiers_fk" FOREIGN KEY ("tiers_id") REFERENCES "public"."tiers"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "payload_locked_documents_rels" ADD CONSTRAINT "payload_locked_documents_rels_segments_fk" FOREIGN KEY ("segments_id") REFERENCES "public"."segments"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "payload_locked_documents_rels" ADD CONSTRAINT "payload_locked_documents_rels_collections_fk" FOREIGN KEY ("collections_id") REFERENCES "public"."collections"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "payload_locked_documents_rels" ADD CONSTRAINT "payload_locked_documents_rels_event_schemas_fk" FOREIGN KEY ("event_schemas_id") REFERENCES "public"."event_schemas"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "payload_locked_documents_rels" ADD CONSTRAINT "payload_locked_documents_rels_custom_field_schemas_fk" FOREIGN KEY ("custom_field_schemas_id") REFERENCES "public"."custom_field_schemas"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "payload_locked_documents_rels" ADD CONSTRAINT "payload_locked_documents_rels_channels_fk" FOREIGN KEY ("channels_id") REFERENCES "public"."channels"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "payload_locked_documents_rels" ADD CONSTRAINT "payload_locked_documents_rels_programs_fk" FOREIGN KEY ("programs_id") REFERENCES "public"."programs"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "payload_locked_documents_rels" ADD CONSTRAINT "payload_locked_documents_rels_message_templates_fk" FOREIGN KEY ("message_templates_id") REFERENCES "public"."message_templates"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "payload_locked_documents_rels" ADD CONSTRAINT "payload_locked_documents_rels_webhooks_fk" FOREIGN KEY ("webhooks_id") REFERENCES "public"."webhooks"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "payload_locked_documents_rels" ADD CONSTRAINT "payload_locked_documents_rels_roles_fk" FOREIGN KEY ("roles_id") REFERENCES "public"."roles"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "payload_locked_documents_rels" ADD CONSTRAINT "payload_locked_documents_rels_settings_fk" FOREIGN KEY ("settings_id") REFERENCES "public"."settings"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "payload_locked_documents_rels" ADD CONSTRAINT "payload_locked_documents_rels_media_fk" FOREIGN KEY ("media_id") REFERENCES "public"."media"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "payload_locked_documents_rels" ADD CONSTRAINT "payload_locked_documents_rels_users_fk" FOREIGN KEY ("users_id") REFERENCES "public"."users"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "payload_preferences_rels" ADD CONSTRAINT "payload_preferences_rels_parent_fk" FOREIGN KEY ("parent_id") REFERENCES "public"."payload_preferences"("id") ON DELETE cascade ON UPDATE no action;
  ALTER TABLE "payload_preferences_rels" ADD CONSTRAINT "payload_preferences_rels_users_fk" FOREIGN KEY ("users_id") REFERENCES "public"."users"("id") ON DELETE cascade ON UPDATE no action;
  CREATE INDEX "campaigns_visibility_tiers_order_idx" ON "campaigns_visibility_tiers" USING btree ("order");
  CREATE INDEX "campaigns_visibility_tiers_parent_idx" ON "campaigns_visibility_tiers" USING btree ("parent_id");
  CREATE INDEX "campaigns_rules_conditions_order_idx" ON "campaigns_rules_conditions" USING btree ("_order");
  CREATE INDEX "campaigns_rules_conditions_parent_id_idx" ON "campaigns_rules_conditions" USING btree ("_parent_id");
  CREATE INDEX "campaigns_rules_effects_order_idx" ON "campaigns_rules_effects" USING btree ("_order");
  CREATE INDEX "campaigns_rules_effects_parent_id_idx" ON "campaigns_rules_effects" USING btree ("_parent_id");
  CREATE INDEX "campaigns_rules_effects_wallet_idx" ON "campaigns_rules_effects" USING btree ("wallet_id");
  CREATE INDEX "campaigns_rules_effects_reward_idx" ON "campaigns_rules_effects" USING btree ("reward_id");
  CREATE INDEX "campaigns_rules_effects_badge_idx" ON "campaigns_rules_effects" USING btree ("badge_id");
  CREATE INDEX "campaigns_rules_order_idx" ON "campaigns_rules" USING btree ("_order");
  CREATE INDEX "campaigns_rules_parent_id_idx" ON "campaigns_rules" USING btree ("_parent_id");
  CREATE UNIQUE INDEX "campaigns_campaign_id_idx" ON "campaigns" USING btree ("campaign_id");
  CREATE INDEX "campaigns_trigger_trigger_action_type_idx" ON "campaigns" USING btree ("trigger_action_type_id");
  CREATE INDEX "campaigns_trigger_line_filter_trigger_line_filter_sku_co_idx" ON "campaigns" USING btree ("trigger_line_filter_sku_collection_id");
  CREATE INDEX "campaigns_photo_idx" ON "campaigns" USING btree ("photo_id");
  CREATE INDEX "campaigns_updated_at_idx" ON "campaigns" USING btree ("updated_at");
  CREATE INDEX "campaigns_created_at_idx" ON "campaigns" USING btree ("created_at");
  CREATE INDEX "campaigns__status_idx" ON "campaigns" USING btree ("_status");
  CREATE UNIQUE INDEX "campaigns_locales_locale_parent_id_unique" ON "campaigns_locales" USING btree ("_locale","_parent_id");
  CREATE INDEX "campaigns_texts_order_parent" ON "campaigns_texts" USING btree ("order","parent_id");
  CREATE INDEX "campaigns_rels_order_idx" ON "campaigns_rels" USING btree ("order");
  CREATE INDEX "campaigns_rels_parent_idx" ON "campaigns_rels" USING btree ("parent_id");
  CREATE INDEX "campaigns_rels_path_idx" ON "campaigns_rels" USING btree ("path");
  CREATE INDEX "campaigns_rels_segments_id_idx" ON "campaigns_rels" USING btree ("segments_id");
  CREATE INDEX "_campaigns_v_version_visibility_tiers_order_idx" ON "_campaigns_v_version_visibility_tiers" USING btree ("order");
  CREATE INDEX "_campaigns_v_version_visibility_tiers_parent_idx" ON "_campaigns_v_version_visibility_tiers" USING btree ("parent_id");
  CREATE INDEX "_campaigns_v_version_rules_conditions_order_idx" ON "_campaigns_v_version_rules_conditions" USING btree ("_order");
  CREATE INDEX "_campaigns_v_version_rules_conditions_parent_id_idx" ON "_campaigns_v_version_rules_conditions" USING btree ("_parent_id");
  CREATE INDEX "_campaigns_v_version_rules_effects_order_idx" ON "_campaigns_v_version_rules_effects" USING btree ("_order");
  CREATE INDEX "_campaigns_v_version_rules_effects_parent_id_idx" ON "_campaigns_v_version_rules_effects" USING btree ("_parent_id");
  CREATE INDEX "_campaigns_v_version_rules_effects_wallet_idx" ON "_campaigns_v_version_rules_effects" USING btree ("wallet_id");
  CREATE INDEX "_campaigns_v_version_rules_effects_reward_idx" ON "_campaigns_v_version_rules_effects" USING btree ("reward_id");
  CREATE INDEX "_campaigns_v_version_rules_effects_badge_idx" ON "_campaigns_v_version_rules_effects" USING btree ("badge_id");
  CREATE INDEX "_campaigns_v_version_rules_order_idx" ON "_campaigns_v_version_rules" USING btree ("_order");
  CREATE INDEX "_campaigns_v_version_rules_parent_id_idx" ON "_campaigns_v_version_rules" USING btree ("_parent_id");
  CREATE INDEX "_campaigns_v_parent_idx" ON "_campaigns_v" USING btree ("parent_id");
  CREATE INDEX "_campaigns_v_version_version_campaign_id_idx" ON "_campaigns_v" USING btree ("version_campaign_id");
  CREATE INDEX "_campaigns_v_version_trigger_version_trigger_action_type_idx" ON "_campaigns_v" USING btree ("version_trigger_action_type_id");
  CREATE INDEX "_campaigns_v_version_trigger_line_filter_version_trigger_idx" ON "_campaigns_v" USING btree ("version_trigger_line_filter_sku_collection_id");
  CREATE INDEX "_campaigns_v_version_version_photo_idx" ON "_campaigns_v" USING btree ("version_photo_id");
  CREATE INDEX "_campaigns_v_version_version_updated_at_idx" ON "_campaigns_v" USING btree ("version_updated_at");
  CREATE INDEX "_campaigns_v_version_version_created_at_idx" ON "_campaigns_v" USING btree ("version_created_at");
  CREATE INDEX "_campaigns_v_version_version__status_idx" ON "_campaigns_v" USING btree ("version__status");
  CREATE INDEX "_campaigns_v_created_at_idx" ON "_campaigns_v" USING btree ("created_at");
  CREATE INDEX "_campaigns_v_updated_at_idx" ON "_campaigns_v" USING btree ("updated_at");
  CREATE INDEX "_campaigns_v_snapshot_idx" ON "_campaigns_v" USING btree ("snapshot");
  CREATE INDEX "_campaigns_v_published_locale_idx" ON "_campaigns_v" USING btree ("published_locale");
  CREATE INDEX "_campaigns_v_latest_idx" ON "_campaigns_v" USING btree ("latest");
  CREATE UNIQUE INDEX "_campaigns_v_locales_locale_parent_id_unique" ON "_campaigns_v_locales" USING btree ("_locale","_parent_id");
  CREATE INDEX "_campaigns_v_texts_order_parent" ON "_campaigns_v_texts" USING btree ("order","parent_id");
  CREATE INDEX "_campaigns_v_rels_order_idx" ON "_campaigns_v_rels" USING btree ("order");
  CREATE INDEX "_campaigns_v_rels_parent_idx" ON "_campaigns_v_rels" USING btree ("parent_id");
  CREATE INDEX "_campaigns_v_rels_path_idx" ON "_campaigns_v_rels" USING btree ("path");
  CREATE INDEX "_campaigns_v_rels_segments_id_idx" ON "_campaigns_v_rels" USING btree ("segments_id");
  CREATE INDEX "decision_policies_actions_channels_order_idx" ON "decision_policies_actions_channels" USING btree ("order");
  CREATE INDEX "decision_policies_actions_channels_parent_idx" ON "decision_policies_actions_channels" USING btree ("parent_id");
  CREATE INDEX "decision_policies_actions_order_idx" ON "decision_policies_actions" USING btree ("_order");
  CREATE INDEX "decision_policies_actions_parent_id_idx" ON "decision_policies_actions" USING btree ("_parent_id");
  CREATE INDEX "contact_cap_7d_order_idx" ON "contact_cap_7d" USING btree ("_order");
  CREATE INDEX "contact_cap_7d_parent_id_idx" ON "contact_cap_7d" USING btree ("_parent_id");
  CREATE INDEX "decision_policies_scoring_tier_boost_order_idx" ON "decision_policies_scoring_tier_boost" USING btree ("_order");
  CREATE INDEX "decision_policies_scoring_tier_boost_parent_id_idx" ON "decision_policies_scoring_tier_boost" USING btree ("_parent_id");
  CREATE INDEX "decision_policies_always_apply_order_idx" ON "decision_policies_always_apply" USING btree ("order");
  CREATE INDEX "decision_policies_always_apply_parent_idx" ON "decision_policies_always_apply" USING btree ("parent_id");
  CREATE INDEX "channel_pref_order_idx" ON "channel_pref" USING btree ("_order");
  CREATE INDEX "channel_pref_parent_id_idx" ON "channel_pref" USING btree ("_parent_id");
  CREATE UNIQUE INDEX "decision_policies_code_idx" ON "decision_policies" USING btree ("code");
  CREATE INDEX "decision_policies_updated_at_idx" ON "decision_policies" USING btree ("updated_at");
  CREATE INDEX "decision_policies_created_at_idx" ON "decision_policies" USING btree ("created_at");
  CREATE INDEX "decision_policies__status_idx" ON "decision_policies" USING btree ("_status");
  CREATE UNIQUE INDEX "decision_policies_locales_locale_parent_id_unique" ON "decision_policies_locales" USING btree ("_locale","_parent_id");
  CREATE INDEX "decision_policies_texts_order_parent" ON "decision_policies_texts" USING btree ("order","parent_id");
  CREATE INDEX "_decision_policies_v_version_actions_channels_order_idx" ON "_decision_policies_v_version_actions_channels" USING btree ("order");
  CREATE INDEX "_decision_policies_v_version_actions_channels_parent_idx" ON "_decision_policies_v_version_actions_channels" USING btree ("parent_id");
  CREATE INDEX "_decision_policies_v_version_actions_order_idx" ON "_decision_policies_v_version_actions" USING btree ("_order");
  CREATE INDEX "_decision_policies_v_version_actions_parent_id_idx" ON "_decision_policies_v_version_actions" USING btree ("_parent_id");
  CREATE INDEX "_contact_cap_7d_v_order_idx" ON "_contact_cap_7d_v" USING btree ("_order");
  CREATE INDEX "_contact_cap_7d_v_parent_id_idx" ON "_contact_cap_7d_v" USING btree ("_parent_id");
  CREATE INDEX "_decision_policies_v_version_scoring_tier_boost_order_idx" ON "_decision_policies_v_version_scoring_tier_boost" USING btree ("_order");
  CREATE INDEX "_decision_policies_v_version_scoring_tier_boost_parent_id_idx" ON "_decision_policies_v_version_scoring_tier_boost" USING btree ("_parent_id");
  CREATE INDEX "_decision_policies_v_version_always_apply_order_idx" ON "_decision_policies_v_version_always_apply" USING btree ("order");
  CREATE INDEX "_decision_policies_v_version_always_apply_parent_idx" ON "_decision_policies_v_version_always_apply" USING btree ("parent_id");
  CREATE INDEX "_channel_pref_v_order_idx" ON "_channel_pref_v" USING btree ("_order");
  CREATE INDEX "_channel_pref_v_parent_id_idx" ON "_channel_pref_v" USING btree ("_parent_id");
  CREATE INDEX "_decision_policies_v_parent_idx" ON "_decision_policies_v" USING btree ("parent_id");
  CREATE INDEX "_decision_policies_v_version_version_code_idx" ON "_decision_policies_v" USING btree ("version_code");
  CREATE INDEX "_decision_policies_v_version_version_updated_at_idx" ON "_decision_policies_v" USING btree ("version_updated_at");
  CREATE INDEX "_decision_policies_v_version_version_created_at_idx" ON "_decision_policies_v" USING btree ("version_created_at");
  CREATE INDEX "_decision_policies_v_version_version__status_idx" ON "_decision_policies_v" USING btree ("version__status");
  CREATE INDEX "_decision_policies_v_created_at_idx" ON "_decision_policies_v" USING btree ("created_at");
  CREATE INDEX "_decision_policies_v_updated_at_idx" ON "_decision_policies_v" USING btree ("updated_at");
  CREATE INDEX "_decision_policies_v_snapshot_idx" ON "_decision_policies_v" USING btree ("snapshot");
  CREATE INDEX "_decision_policies_v_published_locale_idx" ON "_decision_policies_v" USING btree ("published_locale");
  CREATE INDEX "_decision_policies_v_latest_idx" ON "_decision_policies_v" USING btree ("latest");
  CREATE UNIQUE INDEX "_decision_policies_v_locales_locale_parent_id_unique" ON "_decision_policies_v_locales" USING btree ("_locale","_parent_id");
  CREATE INDEX "_decision_policies_v_texts_order_parent" ON "_decision_policies_v_texts" USING btree ("order","parent_id");
  CREATE INDEX "offers_channels_order_idx" ON "offers_channels" USING btree ("order");
  CREATE INDEX "offers_channels_parent_idx" ON "offers_channels" USING btree ("parent_id");
  CREATE UNIQUE INDEX "offers_code_idx" ON "offers" USING btree ("code");
  CREATE INDEX "offers_photo_idx" ON "offers" USING btree ("photo_id");
  CREATE INDEX "offers_updated_at_idx" ON "offers" USING btree ("updated_at");
  CREATE INDEX "offers_created_at_idx" ON "offers" USING btree ("created_at");
  CREATE INDEX "offers__status_idx" ON "offers" USING btree ("_status");
  CREATE UNIQUE INDEX "offers_locales_locale_parent_id_unique" ON "offers_locales" USING btree ("_locale","_parent_id");
  CREATE INDEX "_offers_v_version_channels_order_idx" ON "_offers_v_version_channels" USING btree ("order");
  CREATE INDEX "_offers_v_version_channels_parent_idx" ON "_offers_v_version_channels" USING btree ("parent_id");
  CREATE INDEX "_offers_v_parent_idx" ON "_offers_v" USING btree ("parent_id");
  CREATE INDEX "_offers_v_version_version_code_idx" ON "_offers_v" USING btree ("version_code");
  CREATE INDEX "_offers_v_version_version_photo_idx" ON "_offers_v" USING btree ("version_photo_id");
  CREATE INDEX "_offers_v_version_version_updated_at_idx" ON "_offers_v" USING btree ("version_updated_at");
  CREATE INDEX "_offers_v_version_version_created_at_idx" ON "_offers_v" USING btree ("version_created_at");
  CREATE INDEX "_offers_v_version_version__status_idx" ON "_offers_v" USING btree ("version__status");
  CREATE INDEX "_offers_v_created_at_idx" ON "_offers_v" USING btree ("created_at");
  CREATE INDEX "_offers_v_updated_at_idx" ON "_offers_v" USING btree ("updated_at");
  CREATE INDEX "_offers_v_snapshot_idx" ON "_offers_v" USING btree ("snapshot");
  CREATE INDEX "_offers_v_published_locale_idx" ON "_offers_v" USING btree ("published_locale");
  CREATE INDEX "_offers_v_latest_idx" ON "_offers_v" USING btree ("latest");
  CREATE UNIQUE INDEX "_offers_v_locales_locale_parent_id_unique" ON "_offers_v_locales" USING btree ("_locale","_parent_id");
  CREATE INDEX "experiments_variants_overrides_order_idx" ON "experiments_variants_overrides" USING btree ("_order");
  CREATE INDEX "experiments_variants_overrides_parent_id_idx" ON "experiments_variants_overrides" USING btree ("_parent_id");
  CREATE INDEX "experiments_variants_order_idx" ON "experiments_variants" USING btree ("_order");
  CREATE INDEX "experiments_variants_parent_id_idx" ON "experiments_variants" USING btree ("_parent_id");
  CREATE UNIQUE INDEX "experiments_code_idx" ON "experiments" USING btree ("code");
  CREATE INDEX "experiments_updated_at_idx" ON "experiments" USING btree ("updated_at");
  CREATE INDEX "experiments_created_at_idx" ON "experiments" USING btree ("created_at");
  CREATE INDEX "experiments__status_idx" ON "experiments" USING btree ("_status");
  CREATE INDEX "experiments_texts_order_parent" ON "experiments_texts" USING btree ("order","parent_id");
  CREATE INDEX "_experiments_v_version_variants_overrides_order_idx" ON "_experiments_v_version_variants_overrides" USING btree ("_order");
  CREATE INDEX "_experiments_v_version_variants_overrides_parent_id_idx" ON "_experiments_v_version_variants_overrides" USING btree ("_parent_id");
  CREATE INDEX "_experiments_v_version_variants_order_idx" ON "_experiments_v_version_variants" USING btree ("_order");
  CREATE INDEX "_experiments_v_version_variants_parent_id_idx" ON "_experiments_v_version_variants" USING btree ("_parent_id");
  CREATE INDEX "_experiments_v_parent_idx" ON "_experiments_v" USING btree ("parent_id");
  CREATE INDEX "_experiments_v_version_version_code_idx" ON "_experiments_v" USING btree ("version_code");
  CREATE INDEX "_experiments_v_version_version_updated_at_idx" ON "_experiments_v" USING btree ("version_updated_at");
  CREATE INDEX "_experiments_v_version_version_created_at_idx" ON "_experiments_v" USING btree ("version_created_at");
  CREATE INDEX "_experiments_v_version_version__status_idx" ON "_experiments_v" USING btree ("version__status");
  CREATE INDEX "_experiments_v_created_at_idx" ON "_experiments_v" USING btree ("created_at");
  CREATE INDEX "_experiments_v_updated_at_idx" ON "_experiments_v" USING btree ("updated_at");
  CREATE INDEX "_experiments_v_snapshot_idx" ON "_experiments_v" USING btree ("snapshot");
  CREATE INDEX "_experiments_v_published_locale_idx" ON "_experiments_v" USING btree ("published_locale");
  CREATE INDEX "_experiments_v_latest_idx" ON "_experiments_v" USING btree ("latest");
  CREATE INDEX "_experiments_v_texts_order_parent" ON "_experiments_v_texts" USING btree ("order","parent_id");
  CREATE INDEX "prediction_providers_serves_keys_order_idx" ON "prediction_providers_serves_keys" USING btree ("order");
  CREATE INDEX "prediction_providers_serves_keys_parent_idx" ON "prediction_providers_serves_keys" USING btree ("parent_id");
  CREATE UNIQUE INDEX "prediction_providers_code_idx" ON "prediction_providers" USING btree ("code");
  CREATE INDEX "prediction_providers_updated_at_idx" ON "prediction_providers" USING btree ("updated_at");
  CREATE INDEX "prediction_providers_created_at_idx" ON "prediction_providers" USING btree ("created_at");
  CREATE INDEX "prediction_providers__status_idx" ON "prediction_providers" USING btree ("_status");
  CREATE INDEX "_prediction_providers_v_version_serves_keys_order_idx" ON "_prediction_providers_v_version_serves_keys" USING btree ("order");
  CREATE INDEX "_prediction_providers_v_version_serves_keys_parent_idx" ON "_prediction_providers_v_version_serves_keys" USING btree ("parent_id");
  CREATE INDEX "_prediction_providers_v_parent_idx" ON "_prediction_providers_v" USING btree ("parent_id");
  CREATE INDEX "_prediction_providers_v_version_version_code_idx" ON "_prediction_providers_v" USING btree ("version_code");
  CREATE INDEX "_prediction_providers_v_version_version_updated_at_idx" ON "_prediction_providers_v" USING btree ("version_updated_at");
  CREATE INDEX "_prediction_providers_v_version_version_created_at_idx" ON "_prediction_providers_v" USING btree ("version_created_at");
  CREATE INDEX "_prediction_providers_v_version_version__status_idx" ON "_prediction_providers_v" USING btree ("version__status");
  CREATE INDEX "_prediction_providers_v_created_at_idx" ON "_prediction_providers_v" USING btree ("created_at");
  CREATE INDEX "_prediction_providers_v_updated_at_idx" ON "_prediction_providers_v" USING btree ("updated_at");
  CREATE INDEX "_prediction_providers_v_snapshot_idx" ON "_prediction_providers_v" USING btree ("snapshot");
  CREATE INDEX "_prediction_providers_v_published_locale_idx" ON "_prediction_providers_v" USING btree ("published_locale");
  CREATE INDEX "_prediction_providers_v_latest_idx" ON "_prediction_providers_v" USING btree ("latest");
  CREATE INDEX "fraud_rules_signals_order_idx" ON "fraud_rules_signals" USING btree ("_order");
  CREATE INDEX "fraud_rules_signals_parent_id_idx" ON "fraud_rules_signals" USING btree ("_parent_id");
  CREATE UNIQUE INDEX "fraud_rules_code_idx" ON "fraud_rules" USING btree ("code");
  CREATE INDEX "fraud_rules_updated_at_idx" ON "fraud_rules" USING btree ("updated_at");
  CREATE INDEX "fraud_rules_created_at_idx" ON "fraud_rules" USING btree ("created_at");
  CREATE INDEX "fraud_rules__status_idx" ON "fraud_rules" USING btree ("_status");
  CREATE INDEX "_fraud_rules_v_version_signals_order_idx" ON "_fraud_rules_v_version_signals" USING btree ("_order");
  CREATE INDEX "_fraud_rules_v_version_signals_parent_id_idx" ON "_fraud_rules_v_version_signals" USING btree ("_parent_id");
  CREATE INDEX "_fraud_rules_v_parent_idx" ON "_fraud_rules_v" USING btree ("parent_id");
  CREATE INDEX "_fraud_rules_v_version_version_code_idx" ON "_fraud_rules_v" USING btree ("version_code");
  CREATE INDEX "_fraud_rules_v_version_version_updated_at_idx" ON "_fraud_rules_v" USING btree ("version_updated_at");
  CREATE INDEX "_fraud_rules_v_version_version_created_at_idx" ON "_fraud_rules_v" USING btree ("version_created_at");
  CREATE INDEX "_fraud_rules_v_version_version__status_idx" ON "_fraud_rules_v" USING btree ("version__status");
  CREATE INDEX "_fraud_rules_v_created_at_idx" ON "_fraud_rules_v" USING btree ("created_at");
  CREATE INDEX "_fraud_rules_v_updated_at_idx" ON "_fraud_rules_v" USING btree ("updated_at");
  CREATE INDEX "_fraud_rules_v_snapshot_idx" ON "_fraud_rules_v" USING btree ("snapshot");
  CREATE INDEX "_fraud_rules_v_published_locale_idx" ON "_fraud_rules_v" USING btree ("published_locale");
  CREATE INDEX "_fraud_rules_v_latest_idx" ON "_fraud_rules_v" USING btree ("latest");
  CREATE INDEX "delivery_routing_routes_channels_order_idx" ON "delivery_routing_routes_channels" USING btree ("_order");
  CREATE INDEX "delivery_routing_routes_channels_parent_id_idx" ON "delivery_routing_routes_channels" USING btree ("_parent_id");
  CREATE INDEX "delivery_routing_routes_order_idx" ON "delivery_routing_routes" USING btree ("_order");
  CREATE INDEX "delivery_routing_routes_parent_id_idx" ON "delivery_routing_routes" USING btree ("_parent_id");
  CREATE INDEX "delivery_routing_enabled_channels_order_idx" ON "delivery_routing_enabled_channels" USING btree ("order");
  CREATE INDEX "delivery_routing_enabled_channels_parent_idx" ON "delivery_routing_enabled_channels" USING btree ("parent_id");
  CREATE INDEX "max_per_day_order_idx" ON "max_per_day" USING btree ("_order");
  CREATE INDEX "max_per_day_parent_id_idx" ON "max_per_day" USING btree ("_parent_id");
  CREATE INDEX "delivery_routing_templates_order_idx" ON "delivery_routing_templates" USING btree ("_order");
  CREATE INDEX "delivery_routing_templates_parent_id_idx" ON "delivery_routing_templates" USING btree ("_parent_id");
  CREATE UNIQUE INDEX "delivery_routing_code_idx" ON "delivery_routing" USING btree ("code");
  CREATE INDEX "delivery_routing_updated_at_idx" ON "delivery_routing" USING btree ("updated_at");
  CREATE INDEX "delivery_routing_created_at_idx" ON "delivery_routing" USING btree ("created_at");
  CREATE INDEX "delivery_routing__status_idx" ON "delivery_routing" USING btree ("_status");
  CREATE INDEX "_delivery_routing_v_version_routes_channels_order_idx" ON "_delivery_routing_v_version_routes_channels" USING btree ("_order");
  CREATE INDEX "_delivery_routing_v_version_routes_channels_parent_id_idx" ON "_delivery_routing_v_version_routes_channels" USING btree ("_parent_id");
  CREATE INDEX "_delivery_routing_v_version_routes_order_idx" ON "_delivery_routing_v_version_routes" USING btree ("_order");
  CREATE INDEX "_delivery_routing_v_version_routes_parent_id_idx" ON "_delivery_routing_v_version_routes" USING btree ("_parent_id");
  CREATE INDEX "_delivery_routing_v_version_enabled_channels_order_idx" ON "_delivery_routing_v_version_enabled_channels" USING btree ("order");
  CREATE INDEX "_delivery_routing_v_version_enabled_channels_parent_idx" ON "_delivery_routing_v_version_enabled_channels" USING btree ("parent_id");
  CREATE INDEX "_max_per_day_v_order_idx" ON "_max_per_day_v" USING btree ("_order");
  CREATE INDEX "_max_per_day_v_parent_id_idx" ON "_max_per_day_v" USING btree ("_parent_id");
  CREATE INDEX "_delivery_routing_v_version_templates_order_idx" ON "_delivery_routing_v_version_templates" USING btree ("_order");
  CREATE INDEX "_delivery_routing_v_version_templates_parent_id_idx" ON "_delivery_routing_v_version_templates" USING btree ("_parent_id");
  CREATE INDEX "_delivery_routing_v_parent_idx" ON "_delivery_routing_v" USING btree ("parent_id");
  CREATE INDEX "_delivery_routing_v_version_version_code_idx" ON "_delivery_routing_v" USING btree ("version_code");
  CREATE INDEX "_delivery_routing_v_version_version_updated_at_idx" ON "_delivery_routing_v" USING btree ("version_updated_at");
  CREATE INDEX "_delivery_routing_v_version_version_created_at_idx" ON "_delivery_routing_v" USING btree ("version_created_at");
  CREATE INDEX "_delivery_routing_v_version_version__status_idx" ON "_delivery_routing_v" USING btree ("version__status");
  CREATE INDEX "_delivery_routing_v_created_at_idx" ON "_delivery_routing_v" USING btree ("created_at");
  CREATE INDEX "_delivery_routing_v_updated_at_idx" ON "_delivery_routing_v" USING btree ("updated_at");
  CREATE INDEX "_delivery_routing_v_snapshot_idx" ON "_delivery_routing_v" USING btree ("snapshot");
  CREATE INDEX "_delivery_routing_v_published_locale_idx" ON "_delivery_routing_v" USING btree ("published_locale");
  CREATE INDEX "_delivery_routing_v_latest_idx" ON "_delivery_routing_v" USING btree ("latest");
  CREATE UNIQUE INDEX "consent_purposes_code_idx" ON "consent_purposes" USING btree ("code");
  CREATE INDEX "consent_purposes_updated_at_idx" ON "consent_purposes" USING btree ("updated_at");
  CREATE INDEX "consent_purposes_created_at_idx" ON "consent_purposes" USING btree ("created_at");
  CREATE UNIQUE INDEX "consent_purposes_locales_locale_parent_id_unique" ON "consent_purposes_locales" USING btree ("_locale","_parent_id");
  CREATE INDEX "point_rules_conditions_order_idx" ON "point_rules_conditions" USING btree ("_order");
  CREATE INDEX "point_rules_conditions_parent_id_idx" ON "point_rules_conditions" USING btree ("_parent_id");
  CREATE INDEX "point_rules_target_tiers_order_idx" ON "point_rules_target_tiers" USING btree ("order");
  CREATE INDEX "point_rules_target_tiers_parent_idx" ON "point_rules_target_tiers" USING btree ("parent_id");
  CREATE INDEX "point_rules_target_channels_order_idx" ON "point_rules_target_channels" USING btree ("order");
  CREATE INDEX "point_rules_target_channels_parent_idx" ON "point_rules_target_channels" USING btree ("parent_id");
  CREATE INDEX "point_rules_earning_earning_auto_reward_idx" ON "point_rules" USING btree ("earning_auto_reward_id");
  CREATE INDEX "point_rules_photo_idx" ON "point_rules" USING btree ("photo_id");
  CREATE INDEX "point_rules_updated_at_idx" ON "point_rules" USING btree ("updated_at");
  CREATE INDEX "point_rules_created_at_idx" ON "point_rules" USING btree ("created_at");
  CREATE INDEX "point_rules__status_idx" ON "point_rules" USING btree ("_status");
  CREATE INDEX "point_rules_texts_order_parent" ON "point_rules_texts" USING btree ("order","parent_id");
  CREATE INDEX "point_rules_rels_order_idx" ON "point_rules_rels" USING btree ("order");
  CREATE INDEX "point_rules_rels_parent_idx" ON "point_rules_rels" USING btree ("parent_id");
  CREATE INDEX "point_rules_rels_path_idx" ON "point_rules_rels" USING btree ("path");
  CREATE INDEX "point_rules_rels_segments_id_idx" ON "point_rules_rels" USING btree ("segments_id");
  CREATE INDEX "_point_rules_v_version_conditions_order_idx" ON "_point_rules_v_version_conditions" USING btree ("_order");
  CREATE INDEX "_point_rules_v_version_conditions_parent_id_idx" ON "_point_rules_v_version_conditions" USING btree ("_parent_id");
  CREATE INDEX "_point_rules_v_version_target_tiers_order_idx" ON "_point_rules_v_version_target_tiers" USING btree ("order");
  CREATE INDEX "_point_rules_v_version_target_tiers_parent_idx" ON "_point_rules_v_version_target_tiers" USING btree ("parent_id");
  CREATE INDEX "_point_rules_v_version_target_channels_order_idx" ON "_point_rules_v_version_target_channels" USING btree ("order");
  CREATE INDEX "_point_rules_v_version_target_channels_parent_idx" ON "_point_rules_v_version_target_channels" USING btree ("parent_id");
  CREATE INDEX "_point_rules_v_parent_idx" ON "_point_rules_v" USING btree ("parent_id");
  CREATE INDEX "_point_rules_v_version_earning_version_earning_auto_rewa_idx" ON "_point_rules_v" USING btree ("version_earning_auto_reward_id");
  CREATE INDEX "_point_rules_v_version_version_photo_idx" ON "_point_rules_v" USING btree ("version_photo_id");
  CREATE INDEX "_point_rules_v_version_version_updated_at_idx" ON "_point_rules_v" USING btree ("version_updated_at");
  CREATE INDEX "_point_rules_v_version_version_created_at_idx" ON "_point_rules_v" USING btree ("version_created_at");
  CREATE INDEX "_point_rules_v_version_version__status_idx" ON "_point_rules_v" USING btree ("version__status");
  CREATE INDEX "_point_rules_v_created_at_idx" ON "_point_rules_v" USING btree ("created_at");
  CREATE INDEX "_point_rules_v_updated_at_idx" ON "_point_rules_v" USING btree ("updated_at");
  CREATE INDEX "_point_rules_v_snapshot_idx" ON "_point_rules_v" USING btree ("snapshot");
  CREATE INDEX "_point_rules_v_published_locale_idx" ON "_point_rules_v" USING btree ("published_locale");
  CREATE INDEX "_point_rules_v_latest_idx" ON "_point_rules_v" USING btree ("latest");
  CREATE INDEX "_point_rules_v_texts_order_parent" ON "_point_rules_v_texts" USING btree ("order","parent_id");
  CREATE INDEX "_point_rules_v_rels_order_idx" ON "_point_rules_v_rels" USING btree ("order");
  CREATE INDEX "_point_rules_v_rels_parent_idx" ON "_point_rules_v_rels" USING btree ("parent_id");
  CREATE INDEX "_point_rules_v_rels_path_idx" ON "_point_rules_v_rels" USING btree ("path");
  CREATE INDEX "_point_rules_v_rels_segments_id_idx" ON "_point_rules_v_rels" USING btree ("segments_id");
  CREATE INDEX "achievements_conditions_order_idx" ON "achievements_conditions" USING btree ("_order");
  CREATE INDEX "achievements_conditions_parent_id_idx" ON "achievements_conditions" USING btree ("_parent_id");
  CREATE UNIQUE INDEX "achievements_achievement_id_idx" ON "achievements" USING btree ("achievement_id");
  CREATE INDEX "achievements_action_type_idx" ON "achievements" USING btree ("action_type_id");
  CREATE INDEX "achievements_photo_idx" ON "achievements" USING btree ("photo_id");
  CREATE INDEX "achievements_updated_at_idx" ON "achievements" USING btree ("updated_at");
  CREATE INDEX "achievements_created_at_idx" ON "achievements" USING btree ("created_at");
  CREATE INDEX "achievements__status_idx" ON "achievements" USING btree ("_status");
  CREATE UNIQUE INDEX "achievements_locales_locale_parent_id_unique" ON "achievements_locales" USING btree ("_locale","_parent_id");
  CREATE INDEX "_achievements_v_version_conditions_order_idx" ON "_achievements_v_version_conditions" USING btree ("_order");
  CREATE INDEX "_achievements_v_version_conditions_parent_id_idx" ON "_achievements_v_version_conditions" USING btree ("_parent_id");
  CREATE INDEX "_achievements_v_parent_idx" ON "_achievements_v" USING btree ("parent_id");
  CREATE INDEX "_achievements_v_version_version_achievement_id_idx" ON "_achievements_v" USING btree ("version_achievement_id");
  CREATE INDEX "_achievements_v_version_version_action_type_idx" ON "_achievements_v" USING btree ("version_action_type_id");
  CREATE INDEX "_achievements_v_version_version_photo_idx" ON "_achievements_v" USING btree ("version_photo_id");
  CREATE INDEX "_achievements_v_version_version_updated_at_idx" ON "_achievements_v" USING btree ("version_updated_at");
  CREATE INDEX "_achievements_v_version_version_created_at_idx" ON "_achievements_v" USING btree ("version_created_at");
  CREATE INDEX "_achievements_v_version_version__status_idx" ON "_achievements_v" USING btree ("version__status");
  CREATE INDEX "_achievements_v_created_at_idx" ON "_achievements_v" USING btree ("created_at");
  CREATE INDEX "_achievements_v_updated_at_idx" ON "_achievements_v" USING btree ("updated_at");
  CREATE INDEX "_achievements_v_snapshot_idx" ON "_achievements_v" USING btree ("snapshot");
  CREATE INDEX "_achievements_v_published_locale_idx" ON "_achievements_v" USING btree ("published_locale");
  CREATE INDEX "_achievements_v_latest_idx" ON "_achievements_v" USING btree ("latest");
  CREATE UNIQUE INDEX "_achievements_v_locales_locale_parent_id_unique" ON "_achievements_v_locales" USING btree ("_locale","_parent_id");
  CREATE INDEX "challenges_availability_days_of_week_order_idx" ON "challenges_availability_days_of_week" USING btree ("order");
  CREATE INDEX "challenges_availability_days_of_week_parent_idx" ON "challenges_availability_days_of_week" USING btree ("parent_id");
  CREATE INDEX "challenges_visibility_tiers_order_idx" ON "challenges_visibility_tiers" USING btree ("order");
  CREATE INDEX "challenges_visibility_tiers_parent_idx" ON "challenges_visibility_tiers" USING btree ("parent_id");
  CREATE INDEX "challenges_milestones_conditions_order_idx" ON "challenges_milestones_conditions" USING btree ("_order");
  CREATE INDEX "challenges_milestones_conditions_parent_id_idx" ON "challenges_milestones_conditions" USING btree ("_parent_id");
  CREATE INDEX "challenges_milestones_order_idx" ON "challenges_milestones" USING btree ("_order");
  CREATE INDEX "challenges_milestones_parent_id_idx" ON "challenges_milestones" USING btree ("_parent_id");
  CREATE INDEX "challenges_milestones_action_type_idx" ON "challenges_milestones" USING btree ("action_type_id");
  CREATE UNIQUE INDEX "challenges_milestones_locales_locale_parent_id_unique" ON "challenges_milestones_locales" USING btree ("_locale","_parent_id");
  CREATE INDEX "challenges_rules_effects_order_idx" ON "challenges_rules_effects" USING btree ("_order");
  CREATE INDEX "challenges_rules_effects_parent_id_idx" ON "challenges_rules_effects" USING btree ("_parent_id");
  CREATE INDEX "challenges_rules_effects_wallet_idx" ON "challenges_rules_effects" USING btree ("wallet_id");
  CREATE INDEX "challenges_rules_effects_reward_idx" ON "challenges_rules_effects" USING btree ("reward_id");
  CREATE INDEX "challenges_rules_effects_badge_idx" ON "challenges_rules_effects" USING btree ("badge_id");
  CREATE INDEX "challenges_rules_order_idx" ON "challenges_rules" USING btree ("_order");
  CREATE INDEX "challenges_rules_parent_id_idx" ON "challenges_rules" USING btree ("_parent_id");
  CREATE UNIQUE INDEX "challenges_challenge_id_idx" ON "challenges" USING btree ("challenge_id");
  CREATE INDEX "challenges_badge_idx" ON "challenges" USING btree ("badge_id");
  CREATE INDEX "challenges_photo_idx" ON "challenges" USING btree ("photo_id");
  CREATE INDEX "challenges_updated_at_idx" ON "challenges" USING btree ("updated_at");
  CREATE INDEX "challenges_created_at_idx" ON "challenges" USING btree ("created_at");
  CREATE INDEX "challenges__status_idx" ON "challenges" USING btree ("_status");
  CREATE UNIQUE INDEX "challenges_locales_locale_parent_id_unique" ON "challenges_locales" USING btree ("_locale","_parent_id");
  CREATE INDEX "challenges_texts_order_parent" ON "challenges_texts" USING btree ("order","parent_id");
  CREATE INDEX "challenges_rels_order_idx" ON "challenges_rels" USING btree ("order");
  CREATE INDEX "challenges_rels_parent_idx" ON "challenges_rels" USING btree ("parent_id");
  CREATE INDEX "challenges_rels_path_idx" ON "challenges_rels" USING btree ("path");
  CREATE INDEX "challenges_rels_segments_id_idx" ON "challenges_rels" USING btree ("segments_id");
  CREATE INDEX "_challenges_v_version_availability_days_of_week_order_idx" ON "_challenges_v_version_availability_days_of_week" USING btree ("order");
  CREATE INDEX "_challenges_v_version_availability_days_of_week_parent_idx" ON "_challenges_v_version_availability_days_of_week" USING btree ("parent_id");
  CREATE INDEX "_challenges_v_version_visibility_tiers_order_idx" ON "_challenges_v_version_visibility_tiers" USING btree ("order");
  CREATE INDEX "_challenges_v_version_visibility_tiers_parent_idx" ON "_challenges_v_version_visibility_tiers" USING btree ("parent_id");
  CREATE INDEX "_challenges_v_version_milestones_conditions_order_idx" ON "_challenges_v_version_milestones_conditions" USING btree ("_order");
  CREATE INDEX "_challenges_v_version_milestones_conditions_parent_id_idx" ON "_challenges_v_version_milestones_conditions" USING btree ("_parent_id");
  CREATE INDEX "_challenges_v_version_milestones_order_idx" ON "_challenges_v_version_milestones" USING btree ("_order");
  CREATE INDEX "_challenges_v_version_milestones_parent_id_idx" ON "_challenges_v_version_milestones" USING btree ("_parent_id");
  CREATE INDEX "_challenges_v_version_milestones_action_type_idx" ON "_challenges_v_version_milestones" USING btree ("action_type_id");
  CREATE UNIQUE INDEX "_challenges_v_version_milestones_locales_locale_parent_id_un" ON "_challenges_v_version_milestones_locales" USING btree ("_locale","_parent_id");
  CREATE INDEX "_challenges_v_version_rules_effects_order_idx" ON "_challenges_v_version_rules_effects" USING btree ("_order");
  CREATE INDEX "_challenges_v_version_rules_effects_parent_id_idx" ON "_challenges_v_version_rules_effects" USING btree ("_parent_id");
  CREATE INDEX "_challenges_v_version_rules_effects_wallet_idx" ON "_challenges_v_version_rules_effects" USING btree ("wallet_id");
  CREATE INDEX "_challenges_v_version_rules_effects_reward_idx" ON "_challenges_v_version_rules_effects" USING btree ("reward_id");
  CREATE INDEX "_challenges_v_version_rules_effects_badge_idx" ON "_challenges_v_version_rules_effects" USING btree ("badge_id");
  CREATE INDEX "_challenges_v_version_rules_order_idx" ON "_challenges_v_version_rules" USING btree ("_order");
  CREATE INDEX "_challenges_v_version_rules_parent_id_idx" ON "_challenges_v_version_rules" USING btree ("_parent_id");
  CREATE INDEX "_challenges_v_parent_idx" ON "_challenges_v" USING btree ("parent_id");
  CREATE INDEX "_challenges_v_version_version_challenge_id_idx" ON "_challenges_v" USING btree ("version_challenge_id");
  CREATE INDEX "_challenges_v_version_version_badge_idx" ON "_challenges_v" USING btree ("version_badge_id");
  CREATE INDEX "_challenges_v_version_version_photo_idx" ON "_challenges_v" USING btree ("version_photo_id");
  CREATE INDEX "_challenges_v_version_version_updated_at_idx" ON "_challenges_v" USING btree ("version_updated_at");
  CREATE INDEX "_challenges_v_version_version_created_at_idx" ON "_challenges_v" USING btree ("version_created_at");
  CREATE INDEX "_challenges_v_version_version__status_idx" ON "_challenges_v" USING btree ("version__status");
  CREATE INDEX "_challenges_v_created_at_idx" ON "_challenges_v" USING btree ("created_at");
  CREATE INDEX "_challenges_v_updated_at_idx" ON "_challenges_v" USING btree ("updated_at");
  CREATE INDEX "_challenges_v_snapshot_idx" ON "_challenges_v" USING btree ("snapshot");
  CREATE INDEX "_challenges_v_published_locale_idx" ON "_challenges_v" USING btree ("published_locale");
  CREATE INDEX "_challenges_v_latest_idx" ON "_challenges_v" USING btree ("latest");
  CREATE UNIQUE INDEX "_challenges_v_locales_locale_parent_id_unique" ON "_challenges_v_locales" USING btree ("_locale","_parent_id");
  CREATE INDEX "_challenges_v_texts_order_parent" ON "_challenges_v_texts" USING btree ("order","parent_id");
  CREATE INDEX "_challenges_v_rels_order_idx" ON "_challenges_v_rels" USING btree ("order");
  CREATE INDEX "_challenges_v_rels_parent_idx" ON "_challenges_v_rels" USING btree ("parent_id");
  CREATE INDEX "_challenges_v_rels_path_idx" ON "_challenges_v_rels" USING btree ("path");
  CREATE INDEX "_challenges_v_rels_segments_id_idx" ON "_challenges_v_rels" USING btree ("segments_id");
  CREATE UNIQUE INDEX "badges_code_idx" ON "badges" USING btree ("code");
  CREATE INDEX "badges_image_idx" ON "badges" USING btree ("image_id");
  CREATE INDEX "badges_updated_at_idx" ON "badges" USING btree ("updated_at");
  CREATE INDEX "badges_created_at_idx" ON "badges" USING btree ("created_at");
  CREATE UNIQUE INDEX "badges_locales_locale_parent_id_unique" ON "badges_locales" USING btree ("_locale","_parent_id");
  CREATE INDEX "leaderboards_rewarding_cycle_rewards_order_idx" ON "leaderboards_rewarding_cycle_rewards" USING btree ("_order");
  CREATE INDEX "leaderboards_rewarding_cycle_rewards_parent_id_idx" ON "leaderboards_rewarding_cycle_rewards" USING btree ("_parent_id");
  CREATE INDEX "leaderboards_rewarding_cycle_rewards_reward_idx" ON "leaderboards_rewarding_cycle_rewards" USING btree ("reward_id");
  CREATE INDEX "leaderboards_rewarding_cycle_rewards_wallet_idx" ON "leaderboards_rewarding_cycle_rewards" USING btree ("wallet_id");
  CREATE INDEX "leaderboards_rewarding_cycle_rewards_badge_idx" ON "leaderboards_rewarding_cycle_rewards" USING btree ("badge_id");
  CREATE UNIQUE INDEX "leaderboards_leaderboard_id_idx" ON "leaderboards" USING btree ("leaderboard_id");
  CREATE INDEX "leaderboards_updated_at_idx" ON "leaderboards" USING btree ("updated_at");
  CREATE INDEX "leaderboards_created_at_idx" ON "leaderboards" USING btree ("created_at");
  CREATE INDEX "leaderboards__status_idx" ON "leaderboards" USING btree ("_status");
  CREATE UNIQUE INDEX "leaderboards_locales_locale_parent_id_unique" ON "leaderboards_locales" USING btree ("_locale","_parent_id");
  CREATE INDEX "_leaderboards_v_version_rewarding_cycle_rewards_order_idx" ON "_leaderboards_v_version_rewarding_cycle_rewards" USING btree ("_order");
  CREATE INDEX "_leaderboards_v_version_rewarding_cycle_rewards_parent_id_idx" ON "_leaderboards_v_version_rewarding_cycle_rewards" USING btree ("_parent_id");
  CREATE INDEX "_leaderboards_v_version_rewarding_cycle_rewards_reward_idx" ON "_leaderboards_v_version_rewarding_cycle_rewards" USING btree ("reward_id");
  CREATE INDEX "_leaderboards_v_version_rewarding_cycle_rewards_wallet_idx" ON "_leaderboards_v_version_rewarding_cycle_rewards" USING btree ("wallet_id");
  CREATE INDEX "_leaderboards_v_version_rewarding_cycle_rewards_badge_idx" ON "_leaderboards_v_version_rewarding_cycle_rewards" USING btree ("badge_id");
  CREATE INDEX "_leaderboards_v_parent_idx" ON "_leaderboards_v" USING btree ("parent_id");
  CREATE INDEX "_leaderboards_v_version_version_leaderboard_id_idx" ON "_leaderboards_v" USING btree ("version_leaderboard_id");
  CREATE INDEX "_leaderboards_v_version_version_updated_at_idx" ON "_leaderboards_v" USING btree ("version_updated_at");
  CREATE INDEX "_leaderboards_v_version_version_created_at_idx" ON "_leaderboards_v" USING btree ("version_created_at");
  CREATE INDEX "_leaderboards_v_version_version__status_idx" ON "_leaderboards_v" USING btree ("version__status");
  CREATE INDEX "_leaderboards_v_created_at_idx" ON "_leaderboards_v" USING btree ("created_at");
  CREATE INDEX "_leaderboards_v_updated_at_idx" ON "_leaderboards_v" USING btree ("updated_at");
  CREATE INDEX "_leaderboards_v_snapshot_idx" ON "_leaderboards_v" USING btree ("snapshot");
  CREATE INDEX "_leaderboards_v_published_locale_idx" ON "_leaderboards_v" USING btree ("published_locale");
  CREATE INDEX "_leaderboards_v_latest_idx" ON "_leaderboards_v" USING btree ("latest");
  CREATE UNIQUE INDEX "_leaderboards_v_locales_locale_parent_id_unique" ON "_leaderboards_v_locales" USING btree ("_locale","_parent_id");
  CREATE INDEX "fortune_wheels_slots_order_idx" ON "fortune_wheels_slots" USING btree ("_order");
  CREATE INDEX "fortune_wheels_slots_parent_id_idx" ON "fortune_wheels_slots" USING btree ("_parent_id");
  CREATE INDEX "fortune_wheels_slots_reward_idx" ON "fortune_wheels_slots" USING btree ("reward_id");
  CREATE INDEX "fortune_wheels_slots_wallet_idx" ON "fortune_wheels_slots" USING btree ("wallet_id");
  CREATE UNIQUE INDEX "fortune_wheels_slots_locales_locale_parent_id_unique" ON "fortune_wheels_slots_locales" USING btree ("_locale","_parent_id");
  CREATE UNIQUE INDEX "fortune_wheels_wheel_id_idx" ON "fortune_wheels" USING btree ("wheel_id");
  CREATE INDEX "fortune_wheels_contest_idx" ON "fortune_wheels" USING btree ("contest_id");
  CREATE INDEX "fortune_wheels_cost_wallet_idx" ON "fortune_wheels" USING btree ("cost_wallet_id");
  CREATE INDEX "fortune_wheels_updated_at_idx" ON "fortune_wheels" USING btree ("updated_at");
  CREATE INDEX "fortune_wheels_created_at_idx" ON "fortune_wheels" USING btree ("created_at");
  CREATE INDEX "fortune_wheels__status_idx" ON "fortune_wheels" USING btree ("_status");
  CREATE UNIQUE INDEX "fortune_wheels_locales_locale_parent_id_unique" ON "fortune_wheels_locales" USING btree ("_locale","_parent_id");
  CREATE INDEX "_fortune_wheels_v_version_slots_order_idx" ON "_fortune_wheels_v_version_slots" USING btree ("_order");
  CREATE INDEX "_fortune_wheels_v_version_slots_parent_id_idx" ON "_fortune_wheels_v_version_slots" USING btree ("_parent_id");
  CREATE INDEX "_fortune_wheels_v_version_slots_reward_idx" ON "_fortune_wheels_v_version_slots" USING btree ("reward_id");
  CREATE INDEX "_fortune_wheels_v_version_slots_wallet_idx" ON "_fortune_wheels_v_version_slots" USING btree ("wallet_id");
  CREATE UNIQUE INDEX "_fortune_wheels_v_version_slots_locales_locale_parent_id_uni" ON "_fortune_wheels_v_version_slots_locales" USING btree ("_locale","_parent_id");
  CREATE INDEX "_fortune_wheels_v_parent_idx" ON "_fortune_wheels_v" USING btree ("parent_id");
  CREATE INDEX "_fortune_wheels_v_version_version_wheel_id_idx" ON "_fortune_wheels_v" USING btree ("version_wheel_id");
  CREATE INDEX "_fortune_wheels_v_version_version_contest_idx" ON "_fortune_wheels_v" USING btree ("version_contest_id");
  CREATE INDEX "_fortune_wheels_v_version_version_cost_wallet_idx" ON "_fortune_wheels_v" USING btree ("version_cost_wallet_id");
  CREATE INDEX "_fortune_wheels_v_version_version_updated_at_idx" ON "_fortune_wheels_v" USING btree ("version_updated_at");
  CREATE INDEX "_fortune_wheels_v_version_version_created_at_idx" ON "_fortune_wheels_v" USING btree ("version_created_at");
  CREATE INDEX "_fortune_wheels_v_version_version__status_idx" ON "_fortune_wheels_v" USING btree ("version__status");
  CREATE INDEX "_fortune_wheels_v_created_at_idx" ON "_fortune_wheels_v" USING btree ("created_at");
  CREATE INDEX "_fortune_wheels_v_updated_at_idx" ON "_fortune_wheels_v" USING btree ("updated_at");
  CREATE INDEX "_fortune_wheels_v_snapshot_idx" ON "_fortune_wheels_v" USING btree ("snapshot");
  CREATE INDEX "_fortune_wheels_v_published_locale_idx" ON "_fortune_wheels_v" USING btree ("published_locale");
  CREATE INDEX "_fortune_wheels_v_latest_idx" ON "_fortune_wheels_v" USING btree ("latest");
  CREATE UNIQUE INDEX "_fortune_wheels_v_locales_locale_parent_id_unique" ON "_fortune_wheels_v_locales" USING btree ("_locale","_parent_id");
  CREATE INDEX "contests_prizes_order_idx" ON "contests_prizes" USING btree ("_order");
  CREATE INDEX "contests_prizes_parent_id_idx" ON "contests_prizes" USING btree ("_parent_id");
  CREATE INDEX "contests_weighted_slots_order_idx" ON "contests_weighted_slots" USING btree ("_order");
  CREATE INDEX "contests_weighted_slots_parent_id_idx" ON "contests_weighted_slots" USING btree ("_parent_id");
  CREATE INDEX "contests_regulation_idx" ON "contests" USING btree ("regulation_id");
  CREATE INDEX "contests_updated_at_idx" ON "contests" USING btree ("updated_at");
  CREATE INDEX "contests_created_at_idx" ON "contests" USING btree ("created_at");
  CREATE INDEX "contests__status_idx" ON "contests" USING btree ("_status");
  CREATE INDEX "_contests_v_version_prizes_order_idx" ON "_contests_v_version_prizes" USING btree ("_order");
  CREATE INDEX "_contests_v_version_prizes_parent_id_idx" ON "_contests_v_version_prizes" USING btree ("_parent_id");
  CREATE INDEX "_contests_v_version_weighted_slots_order_idx" ON "_contests_v_version_weighted_slots" USING btree ("_order");
  CREATE INDEX "_contests_v_version_weighted_slots_parent_id_idx" ON "_contests_v_version_weighted_slots" USING btree ("_parent_id");
  CREATE INDEX "_contests_v_parent_idx" ON "_contests_v" USING btree ("parent_id");
  CREATE INDEX "_contests_v_version_version_regulation_idx" ON "_contests_v" USING btree ("version_regulation_id");
  CREATE INDEX "_contests_v_version_version_updated_at_idx" ON "_contests_v" USING btree ("version_updated_at");
  CREATE INDEX "_contests_v_version_version_created_at_idx" ON "_contests_v" USING btree ("version_created_at");
  CREATE INDEX "_contests_v_version_version__status_idx" ON "_contests_v" USING btree ("version__status");
  CREATE INDEX "_contests_v_created_at_idx" ON "_contests_v" USING btree ("created_at");
  CREATE INDEX "_contests_v_updated_at_idx" ON "_contests_v" USING btree ("updated_at");
  CREATE INDEX "_contests_v_snapshot_idx" ON "_contests_v" USING btree ("snapshot");
  CREATE INDEX "_contests_v_published_locale_idx" ON "_contests_v" USING btree ("published_locale");
  CREATE INDEX "_contests_v_latest_idx" ON "_contests_v" USING btree ("latest");
  CREATE INDEX "contest_cards_image_idx" ON "contest_cards" USING btree ("image_id");
  CREATE INDEX "contest_cards_regulation_idx" ON "contest_cards" USING btree ("regulation_id");
  CREATE INDEX "contest_cards_updated_at_idx" ON "contest_cards" USING btree ("updated_at");
  CREATE INDEX "contest_cards_created_at_idx" ON "contest_cards" USING btree ("created_at");
  CREATE INDEX "contest_cards__status_idx" ON "contest_cards" USING btree ("_status");
  CREATE INDEX "_contest_cards_v_parent_idx" ON "_contest_cards_v" USING btree ("parent_id");
  CREATE INDEX "_contest_cards_v_version_version_image_idx" ON "_contest_cards_v" USING btree ("version_image_id");
  CREATE INDEX "_contest_cards_v_version_version_regulation_idx" ON "_contest_cards_v" USING btree ("version_regulation_id");
  CREATE INDEX "_contest_cards_v_version_version_updated_at_idx" ON "_contest_cards_v" USING btree ("version_updated_at");
  CREATE INDEX "_contest_cards_v_version_version_created_at_idx" ON "_contest_cards_v" USING btree ("version_created_at");
  CREATE INDEX "_contest_cards_v_version_version__status_idx" ON "_contest_cards_v" USING btree ("version__status");
  CREATE INDEX "_contest_cards_v_created_at_idx" ON "_contest_cards_v" USING btree ("created_at");
  CREATE INDEX "_contest_cards_v_updated_at_idx" ON "_contest_cards_v" USING btree ("updated_at");
  CREATE INDEX "_contest_cards_v_snapshot_idx" ON "_contest_cards_v" USING btree ("snapshot");
  CREATE INDEX "_contest_cards_v_published_locale_idx" ON "_contest_cards_v" USING btree ("published_locale");
  CREATE INDEX "_contest_cards_v_latest_idx" ON "_contest_cards_v" USING btree ("latest");
  CREATE INDEX "win_cards_image_idx" ON "win_cards" USING btree ("image_id");
  CREATE INDEX "win_cards_updated_at_idx" ON "win_cards" USING btree ("updated_at");
  CREATE INDEX "win_cards_created_at_idx" ON "win_cards" USING btree ("created_at");
  CREATE INDEX "win_cards__status_idx" ON "win_cards" USING btree ("_status");
  CREATE INDEX "_win_cards_v_parent_idx" ON "_win_cards_v" USING btree ("parent_id");
  CREATE INDEX "_win_cards_v_version_version_image_idx" ON "_win_cards_v" USING btree ("version_image_id");
  CREATE INDEX "_win_cards_v_version_version_updated_at_idx" ON "_win_cards_v" USING btree ("version_updated_at");
  CREATE INDEX "_win_cards_v_version_version_created_at_idx" ON "_win_cards_v" USING btree ("version_created_at");
  CREATE INDEX "_win_cards_v_version_version__status_idx" ON "_win_cards_v" USING btree ("version__status");
  CREATE INDEX "_win_cards_v_created_at_idx" ON "_win_cards_v" USING btree ("created_at");
  CREATE INDEX "_win_cards_v_updated_at_idx" ON "_win_cards_v" USING btree ("updated_at");
  CREATE INDEX "_win_cards_v_snapshot_idx" ON "_win_cards_v" USING btree ("snapshot");
  CREATE INDEX "_win_cards_v_published_locale_idx" ON "_win_cards_v" USING btree ("published_locale");
  CREATE INDEX "_win_cards_v_latest_idx" ON "_win_cards_v" USING btree ("latest");
  CREATE INDEX "popups_updated_at_idx" ON "popups" USING btree ("updated_at");
  CREATE INDEX "popups_created_at_idx" ON "popups" USING btree ("created_at");
  CREATE INDEX "popups__status_idx" ON "popups" USING btree ("_status");
  CREATE INDEX "_popups_v_parent_idx" ON "_popups_v" USING btree ("parent_id");
  CREATE INDEX "_popups_v_version_version_updated_at_idx" ON "_popups_v" USING btree ("version_updated_at");
  CREATE INDEX "_popups_v_version_version_created_at_idx" ON "_popups_v" USING btree ("version_created_at");
  CREATE INDEX "_popups_v_version_version__status_idx" ON "_popups_v" USING btree ("version__status");
  CREATE INDEX "_popups_v_created_at_idx" ON "_popups_v" USING btree ("created_at");
  CREATE INDEX "_popups_v_updated_at_idx" ON "_popups_v" USING btree ("updated_at");
  CREATE INDEX "_popups_v_snapshot_idx" ON "_popups_v" USING btree ("snapshot");
  CREATE INDEX "_popups_v_published_locale_idx" ON "_popups_v" USING btree ("published_locale");
  CREATE INDEX "_popups_v_latest_idx" ON "_popups_v" USING btree ("latest");
  CREATE INDEX "rewards_category_idx" ON "rewards" USING btree ("category_id");
  CREATE INDEX "rewards_coupon_pool_idx" ON "rewards" USING btree ("coupon_pool_id");
  CREATE INDEX "rewards_photo_idx" ON "rewards" USING btree ("photo_id");
  CREATE INDEX "rewards_updated_at_idx" ON "rewards" USING btree ("updated_at");
  CREATE INDEX "rewards_created_at_idx" ON "rewards" USING btree ("created_at");
  CREATE INDEX "rewards__status_idx" ON "rewards" USING btree ("_status");
  CREATE INDEX "rewards_rels_order_idx" ON "rewards_rels" USING btree ("order");
  CREATE INDEX "rewards_rels_parent_idx" ON "rewards_rels" USING btree ("parent_id");
  CREATE INDEX "rewards_rels_path_idx" ON "rewards_rels" USING btree ("path");
  CREATE INDEX "rewards_rels_segments_id_idx" ON "rewards_rels" USING btree ("segments_id");
  CREATE INDEX "_rewards_v_parent_idx" ON "_rewards_v" USING btree ("parent_id");
  CREATE INDEX "_rewards_v_version_version_category_idx" ON "_rewards_v" USING btree ("version_category_id");
  CREATE INDEX "_rewards_v_version_version_coupon_pool_idx" ON "_rewards_v" USING btree ("version_coupon_pool_id");
  CREATE INDEX "_rewards_v_version_version_photo_idx" ON "_rewards_v" USING btree ("version_photo_id");
  CREATE INDEX "_rewards_v_version_version_updated_at_idx" ON "_rewards_v" USING btree ("version_updated_at");
  CREATE INDEX "_rewards_v_version_version_created_at_idx" ON "_rewards_v" USING btree ("version_created_at");
  CREATE INDEX "_rewards_v_version_version__status_idx" ON "_rewards_v" USING btree ("version__status");
  CREATE INDEX "_rewards_v_created_at_idx" ON "_rewards_v" USING btree ("created_at");
  CREATE INDEX "_rewards_v_updated_at_idx" ON "_rewards_v" USING btree ("updated_at");
  CREATE INDEX "_rewards_v_snapshot_idx" ON "_rewards_v" USING btree ("snapshot");
  CREATE INDEX "_rewards_v_published_locale_idx" ON "_rewards_v" USING btree ("published_locale");
  CREATE INDEX "_rewards_v_latest_idx" ON "_rewards_v" USING btree ("latest");
  CREATE INDEX "_rewards_v_rels_order_idx" ON "_rewards_v_rels" USING btree ("order");
  CREATE INDEX "_rewards_v_rels_parent_idx" ON "_rewards_v_rels" USING btree ("parent_id");
  CREATE INDEX "_rewards_v_rels_path_idx" ON "_rewards_v_rels" USING btree ("path");
  CREATE INDEX "_rewards_v_rels_segments_id_idx" ON "_rewards_v_rels" USING btree ("segments_id");
  CREATE INDEX "reward_categories_photo_idx" ON "reward_categories" USING btree ("photo_id");
  CREATE INDEX "reward_categories_updated_at_idx" ON "reward_categories" USING btree ("updated_at");
  CREATE INDEX "reward_categories_created_at_idx" ON "reward_categories" USING btree ("created_at");
  CREATE UNIQUE INDEX "coupon_pools_pool_id_idx" ON "coupon_pools" USING btree ("pool_id");
  CREATE INDEX "coupon_pools_codes_file_idx" ON "coupon_pools" USING btree ("codes_file_id");
  CREATE INDEX "coupon_pools_updated_at_idx" ON "coupon_pools" USING btree ("updated_at");
  CREATE INDEX "coupon_pools_created_at_idx" ON "coupon_pools" USING btree ("created_at");
  CREATE INDEX "promo_codes_qr_image_idx" ON "promo_codes" USING btree ("qr_image_id");
  CREATE INDEX "promo_codes_updated_at_idx" ON "promo_codes" USING btree ("updated_at");
  CREATE INDEX "promo_codes_created_at_idx" ON "promo_codes" USING btree ("created_at");
  CREATE INDEX "promo_codes__status_idx" ON "promo_codes" USING btree ("_status");
  CREATE INDEX "_promo_codes_v_parent_idx" ON "_promo_codes_v" USING btree ("parent_id");
  CREATE INDEX "_promo_codes_v_version_version_qr_image_idx" ON "_promo_codes_v" USING btree ("version_qr_image_id");
  CREATE INDEX "_promo_codes_v_version_version_updated_at_idx" ON "_promo_codes_v" USING btree ("version_updated_at");
  CREATE INDEX "_promo_codes_v_version_version_created_at_idx" ON "_promo_codes_v" USING btree ("version_created_at");
  CREATE INDEX "_promo_codes_v_version_version__status_idx" ON "_promo_codes_v" USING btree ("version__status");
  CREATE INDEX "_promo_codes_v_created_at_idx" ON "_promo_codes_v" USING btree ("created_at");
  CREATE INDEX "_promo_codes_v_updated_at_idx" ON "_promo_codes_v" USING btree ("updated_at");
  CREATE INDEX "_promo_codes_v_snapshot_idx" ON "_promo_codes_v" USING btree ("snapshot");
  CREATE INDEX "_promo_codes_v_published_locale_idx" ON "_promo_codes_v" USING btree ("published_locale");
  CREATE INDEX "_promo_codes_v_latest_idx" ON "_promo_codes_v" USING btree ("latest");
  CREATE UNIQUE INDEX "wallets_code_idx" ON "wallets" USING btree ("code");
  CREATE INDEX "wallets_updated_at_idx" ON "wallets" USING btree ("updated_at");
  CREATE INDEX "wallets_created_at_idx" ON "wallets" USING btree ("created_at");
  CREATE UNIQUE INDEX "wallets_locales_locale_parent_id_unique" ON "wallets_locales" USING btree ("_locale","_parent_id");
  CREATE INDEX "tier_sets_conditions_order_idx" ON "tier_sets_conditions" USING btree ("_order");
  CREATE INDEX "tier_sets_conditions_parent_id_idx" ON "tier_sets_conditions" USING btree ("_parent_id");
  CREATE INDEX "tier_sets_tiers_order_idx" ON "tier_sets_tiers" USING btree ("_order");
  CREATE INDEX "tier_sets_tiers_parent_id_idx" ON "tier_sets_tiers" USING btree ("_parent_id");
  CREATE INDEX "tier_sets_tiers_photo_idx" ON "tier_sets_tiers" USING btree ("photo_id");
  CREATE UNIQUE INDEX "tier_sets_tiers_locales_locale_parent_id_unique" ON "tier_sets_tiers_locales" USING btree ("_locale","_parent_id");
  CREATE UNIQUE INDEX "tier_sets_set_id_idx" ON "tier_sets" USING btree ("set_id");
  CREATE INDEX "tier_sets_updated_at_idx" ON "tier_sets" USING btree ("updated_at");
  CREATE INDEX "tier_sets_created_at_idx" ON "tier_sets" USING btree ("created_at");
  CREATE INDEX "tier_sets__status_idx" ON "tier_sets" USING btree ("_status");
  CREATE UNIQUE INDEX "tier_sets_locales_locale_parent_id_unique" ON "tier_sets_locales" USING btree ("_locale","_parent_id");
  CREATE INDEX "tier_sets_texts_order_parent" ON "tier_sets_texts" USING btree ("order","parent_id");
  CREATE INDEX "tier_sets_rels_order_idx" ON "tier_sets_rels" USING btree ("order");
  CREATE INDEX "tier_sets_rels_parent_idx" ON "tier_sets_rels" USING btree ("parent_id");
  CREATE INDEX "tier_sets_rels_path_idx" ON "tier_sets_rels" USING btree ("path");
  CREATE INDEX "tier_sets_rels_rewards_id_idx" ON "tier_sets_rels" USING btree ("rewards_id");
  CREATE INDEX "_tier_sets_v_version_conditions_order_idx" ON "_tier_sets_v_version_conditions" USING btree ("_order");
  CREATE INDEX "_tier_sets_v_version_conditions_parent_id_idx" ON "_tier_sets_v_version_conditions" USING btree ("_parent_id");
  CREATE INDEX "_tier_sets_v_version_tiers_order_idx" ON "_tier_sets_v_version_tiers" USING btree ("_order");
  CREATE INDEX "_tier_sets_v_version_tiers_parent_id_idx" ON "_tier_sets_v_version_tiers" USING btree ("_parent_id");
  CREATE INDEX "_tier_sets_v_version_tiers_photo_idx" ON "_tier_sets_v_version_tiers" USING btree ("photo_id");
  CREATE UNIQUE INDEX "_tier_sets_v_version_tiers_locales_locale_parent_id_unique" ON "_tier_sets_v_version_tiers_locales" USING btree ("_locale","_parent_id");
  CREATE INDEX "_tier_sets_v_parent_idx" ON "_tier_sets_v" USING btree ("parent_id");
  CREATE INDEX "_tier_sets_v_version_version_set_id_idx" ON "_tier_sets_v" USING btree ("version_set_id");
  CREATE INDEX "_tier_sets_v_version_version_updated_at_idx" ON "_tier_sets_v" USING btree ("version_updated_at");
  CREATE INDEX "_tier_sets_v_version_version_created_at_idx" ON "_tier_sets_v" USING btree ("version_created_at");
  CREATE INDEX "_tier_sets_v_version_version__status_idx" ON "_tier_sets_v" USING btree ("version__status");
  CREATE INDEX "_tier_sets_v_created_at_idx" ON "_tier_sets_v" USING btree ("created_at");
  CREATE INDEX "_tier_sets_v_updated_at_idx" ON "_tier_sets_v" USING btree ("updated_at");
  CREATE INDEX "_tier_sets_v_snapshot_idx" ON "_tier_sets_v" USING btree ("snapshot");
  CREATE INDEX "_tier_sets_v_published_locale_idx" ON "_tier_sets_v" USING btree ("published_locale");
  CREATE INDEX "_tier_sets_v_latest_idx" ON "_tier_sets_v" USING btree ("latest");
  CREATE UNIQUE INDEX "_tier_sets_v_locales_locale_parent_id_unique" ON "_tier_sets_v_locales" USING btree ("_locale","_parent_id");
  CREATE INDEX "_tier_sets_v_texts_order_parent" ON "_tier_sets_v_texts" USING btree ("order","parent_id");
  CREATE INDEX "_tier_sets_v_rels_order_idx" ON "_tier_sets_v_rels" USING btree ("order");
  CREATE INDEX "_tier_sets_v_rels_parent_idx" ON "_tier_sets_v_rels" USING btree ("parent_id");
  CREATE INDEX "_tier_sets_v_rels_path_idx" ON "_tier_sets_v_rels" USING btree ("path");
  CREATE INDEX "_tier_sets_v_rels_rewards_id_idx" ON "_tier_sets_v_rels" USING btree ("rewards_id");
  CREATE INDEX "tiers_photo_idx" ON "tiers" USING btree ("photo_id");
  CREATE INDEX "tiers_updated_at_idx" ON "tiers" USING btree ("updated_at");
  CREATE INDEX "tiers_created_at_idx" ON "tiers" USING btree ("created_at");
  CREATE INDEX "tiers_rels_order_idx" ON "tiers_rels" USING btree ("order");
  CREATE INDEX "tiers_rels_parent_idx" ON "tiers_rels" USING btree ("parent_id");
  CREATE INDEX "tiers_rels_path_idx" ON "tiers_rels" USING btree ("path");
  CREATE INDEX "tiers_rels_rewards_id_idx" ON "tiers_rels" USING btree ("rewards_id");
  CREATE INDEX "segments_criteria_order_idx" ON "segments_criteria" USING btree ("_order");
  CREATE INDEX "segments_criteria_parent_id_idx" ON "segments_criteria" USING btree ("_parent_id");
  CREATE UNIQUE INDEX "segments_segment_id_idx" ON "segments" USING btree ("segment_id");
  CREATE INDEX "segments_static_list_idx" ON "segments" USING btree ("static_list_id");
  CREATE INDEX "segments_updated_at_idx" ON "segments" USING btree ("updated_at");
  CREATE INDEX "segments_created_at_idx" ON "segments" USING btree ("created_at");
  CREATE INDEX "segments__status_idx" ON "segments" USING btree ("_status");
  CREATE INDEX "_segments_v_version_criteria_order_idx" ON "_segments_v_version_criteria" USING btree ("_order");
  CREATE INDEX "_segments_v_version_criteria_parent_id_idx" ON "_segments_v_version_criteria" USING btree ("_parent_id");
  CREATE INDEX "_segments_v_parent_idx" ON "_segments_v" USING btree ("parent_id");
  CREATE INDEX "_segments_v_version_version_segment_id_idx" ON "_segments_v" USING btree ("version_segment_id");
  CREATE INDEX "_segments_v_version_version_static_list_idx" ON "_segments_v" USING btree ("version_static_list_id");
  CREATE INDEX "_segments_v_version_version_updated_at_idx" ON "_segments_v" USING btree ("version_updated_at");
  CREATE INDEX "_segments_v_version_version_created_at_idx" ON "_segments_v" USING btree ("version_created_at");
  CREATE INDEX "_segments_v_version_version__status_idx" ON "_segments_v" USING btree ("version__status");
  CREATE INDEX "_segments_v_created_at_idx" ON "_segments_v" USING btree ("created_at");
  CREATE INDEX "_segments_v_updated_at_idx" ON "_segments_v" USING btree ("updated_at");
  CREATE INDEX "_segments_v_snapshot_idx" ON "_segments_v" USING btree ("snapshot");
  CREATE INDEX "_segments_v_published_locale_idx" ON "_segments_v" USING btree ("published_locale");
  CREATE INDEX "_segments_v_latest_idx" ON "_segments_v" USING btree ("latest");
  CREATE UNIQUE INDEX "collections_collection_id_idx" ON "collections" USING btree ("collection_id");
  CREATE INDEX "collections_csv_idx" ON "collections" USING btree ("csv_id");
  CREATE INDEX "collections_updated_at_idx" ON "collections" USING btree ("updated_at");
  CREATE INDEX "collections_created_at_idx" ON "collections" USING btree ("created_at");
  CREATE INDEX "event_schemas_attributes_order_idx" ON "event_schemas_attributes" USING btree ("_order");
  CREATE INDEX "event_schemas_attributes_parent_id_idx" ON "event_schemas_attributes" USING btree ("_parent_id");
  CREATE UNIQUE INDEX "event_schemas_action_type_idx" ON "event_schemas" USING btree ("action_type");
  CREATE INDEX "event_schemas_updated_at_idx" ON "event_schemas" USING btree ("updated_at");
  CREATE INDEX "event_schemas_created_at_idx" ON "event_schemas" USING btree ("created_at");
  CREATE INDEX "event_schemas__status_idx" ON "event_schemas" USING btree ("_status");
  CREATE UNIQUE INDEX "event_schemas_locales_locale_parent_id_unique" ON "event_schemas_locales" USING btree ("_locale","_parent_id");
  CREATE INDEX "_event_schemas_v_version_attributes_order_idx" ON "_event_schemas_v_version_attributes" USING btree ("_order");
  CREATE INDEX "_event_schemas_v_version_attributes_parent_id_idx" ON "_event_schemas_v_version_attributes" USING btree ("_parent_id");
  CREATE INDEX "_event_schemas_v_parent_idx" ON "_event_schemas_v" USING btree ("parent_id");
  CREATE INDEX "_event_schemas_v_version_version_action_type_idx" ON "_event_schemas_v" USING btree ("version_action_type");
  CREATE INDEX "_event_schemas_v_version_version_updated_at_idx" ON "_event_schemas_v" USING btree ("version_updated_at");
  CREATE INDEX "_event_schemas_v_version_version_created_at_idx" ON "_event_schemas_v" USING btree ("version_created_at");
  CREATE INDEX "_event_schemas_v_version_version__status_idx" ON "_event_schemas_v" USING btree ("version__status");
  CREATE INDEX "_event_schemas_v_created_at_idx" ON "_event_schemas_v" USING btree ("created_at");
  CREATE INDEX "_event_schemas_v_updated_at_idx" ON "_event_schemas_v" USING btree ("updated_at");
  CREATE INDEX "_event_schemas_v_snapshot_idx" ON "_event_schemas_v" USING btree ("snapshot");
  CREATE INDEX "_event_schemas_v_published_locale_idx" ON "_event_schemas_v" USING btree ("published_locale");
  CREATE INDEX "_event_schemas_v_latest_idx" ON "_event_schemas_v" USING btree ("latest");
  CREATE UNIQUE INDEX "_event_schemas_v_locales_locale_parent_id_unique" ON "_event_schemas_v_locales" USING btree ("_locale","_parent_id");
  CREATE INDEX "custom_field_schemas_fields_order_idx" ON "custom_field_schemas_fields" USING btree ("_order");
  CREATE INDEX "custom_field_schemas_fields_parent_id_idx" ON "custom_field_schemas_fields" USING btree ("_parent_id");
  CREATE INDEX "custom_field_schemas_fields_collection_idx" ON "custom_field_schemas_fields" USING btree ("collection_id");
  CREATE INDEX "custom_field_schemas_updated_at_idx" ON "custom_field_schemas" USING btree ("updated_at");
  CREATE INDEX "custom_field_schemas_created_at_idx" ON "custom_field_schemas" USING btree ("created_at");
  CREATE UNIQUE INDEX "custom_field_schemas_locales_locale_parent_id_unique" ON "custom_field_schemas_locales" USING btree ("_locale","_parent_id");
  CREATE UNIQUE INDEX "channels_code_idx" ON "channels" USING btree ("code");
  CREATE INDEX "channels_updated_at_idx" ON "channels" USING btree ("updated_at");
  CREATE INDEX "channels_created_at_idx" ON "channels" USING btree ("created_at");
  CREATE UNIQUE INDEX "channels_locales_locale_parent_id_unique" ON "channels_locales" USING btree ("_locale","_parent_id");
  CREATE INDEX "programs_missions_order_idx" ON "programs_missions" USING btree ("_order");
  CREATE INDEX "programs_missions_parent_id_idx" ON "programs_missions" USING btree ("_parent_id");
  CREATE INDEX "programs_missions_reward_idx" ON "programs_missions" USING btree ("reward_id");
  CREATE INDEX "programs_missions_badge_idx" ON "programs_missions" USING btree ("badge_id");
  CREATE UNIQUE INDEX "programs_year_idx" ON "programs" USING btree ("year");
  CREATE INDEX "programs_regulation_idx" ON "programs" USING btree ("regulation_id");
  CREATE INDEX "programs_updated_at_idx" ON "programs" USING btree ("updated_at");
  CREATE INDEX "programs_created_at_idx" ON "programs" USING btree ("created_at");
  CREATE INDEX "programs__status_idx" ON "programs" USING btree ("_status");
  CREATE INDEX "programs_texts_order_parent" ON "programs_texts" USING btree ("order","parent_id");
  CREATE INDEX "_programs_v_version_missions_order_idx" ON "_programs_v_version_missions" USING btree ("_order");
  CREATE INDEX "_programs_v_version_missions_parent_id_idx" ON "_programs_v_version_missions" USING btree ("_parent_id");
  CREATE INDEX "_programs_v_version_missions_reward_idx" ON "_programs_v_version_missions" USING btree ("reward_id");
  CREATE INDEX "_programs_v_version_missions_badge_idx" ON "_programs_v_version_missions" USING btree ("badge_id");
  CREATE INDEX "_programs_v_parent_idx" ON "_programs_v" USING btree ("parent_id");
  CREATE INDEX "_programs_v_version_version_year_idx" ON "_programs_v" USING btree ("version_year");
  CREATE INDEX "_programs_v_version_version_regulation_idx" ON "_programs_v" USING btree ("version_regulation_id");
  CREATE INDEX "_programs_v_version_version_updated_at_idx" ON "_programs_v" USING btree ("version_updated_at");
  CREATE INDEX "_programs_v_version_version_created_at_idx" ON "_programs_v" USING btree ("version_created_at");
  CREATE INDEX "_programs_v_version_version__status_idx" ON "_programs_v" USING btree ("version__status");
  CREATE INDEX "_programs_v_created_at_idx" ON "_programs_v" USING btree ("created_at");
  CREATE INDEX "_programs_v_updated_at_idx" ON "_programs_v" USING btree ("updated_at");
  CREATE INDEX "_programs_v_snapshot_idx" ON "_programs_v" USING btree ("snapshot");
  CREATE INDEX "_programs_v_published_locale_idx" ON "_programs_v" USING btree ("published_locale");
  CREATE INDEX "_programs_v_latest_idx" ON "_programs_v" USING btree ("latest");
  CREATE INDEX "_programs_v_texts_order_parent" ON "_programs_v_texts" USING btree ("order","parent_id");
  CREATE INDEX "message_templates_updated_at_idx" ON "message_templates" USING btree ("updated_at");
  CREATE INDEX "message_templates_created_at_idx" ON "message_templates" USING btree ("created_at");
  CREATE INDEX "message_templates__status_idx" ON "message_templates" USING btree ("_status");
  CREATE INDEX "_message_templates_v_parent_idx" ON "_message_templates_v" USING btree ("parent_id");
  CREATE INDEX "_message_templates_v_version_version_updated_at_idx" ON "_message_templates_v" USING btree ("version_updated_at");
  CREATE INDEX "_message_templates_v_version_version_created_at_idx" ON "_message_templates_v" USING btree ("version_created_at");
  CREATE INDEX "_message_templates_v_version_version__status_idx" ON "_message_templates_v" USING btree ("version__status");
  CREATE INDEX "_message_templates_v_created_at_idx" ON "_message_templates_v" USING btree ("created_at");
  CREATE INDEX "_message_templates_v_updated_at_idx" ON "_message_templates_v" USING btree ("updated_at");
  CREATE INDEX "_message_templates_v_snapshot_idx" ON "_message_templates_v" USING btree ("snapshot");
  CREATE INDEX "_message_templates_v_published_locale_idx" ON "_message_templates_v" USING btree ("published_locale");
  CREATE INDEX "_message_templates_v_latest_idx" ON "_message_templates_v" USING btree ("latest");
  CREATE INDEX "webhooks_updated_at_idx" ON "webhooks" USING btree ("updated_at");
  CREATE INDEX "webhooks_created_at_idx" ON "webhooks" USING btree ("created_at");
  CREATE INDEX "webhooks_texts_order_parent" ON "webhooks_texts" USING btree ("order","parent_id");
  CREATE INDEX "roles_permissions_order_idx" ON "roles_permissions" USING btree ("order");
  CREATE INDEX "roles_permissions_parent_idx" ON "roles_permissions" USING btree ("parent_id");
  CREATE UNIQUE INDEX "roles_code_idx" ON "roles" USING btree ("code");
  CREATE INDEX "roles_updated_at_idx" ON "roles" USING btree ("updated_at");
  CREATE INDEX "roles_created_at_idx" ON "roles" USING btree ("created_at");
  CREATE INDEX "settings_locales_order_idx" ON "settings_locales" USING btree ("order");
  CREATE INDEX "settings_locales_parent_idx" ON "settings_locales" USING btree ("parent_id");
  CREATE INDEX "settings_identification_priority_order_idx" ON "settings_identification_priority" USING btree ("order");
  CREATE INDEX "settings_identification_priority_parent_idx" ON "settings_identification_priority" USING btree ("parent_id");
  CREATE INDEX "settings_updated_at_idx" ON "settings" USING btree ("updated_at");
  CREATE INDEX "settings_created_at_idx" ON "settings" USING btree ("created_at");
  CREATE INDEX "settings_texts_order_parent" ON "settings_texts" USING btree ("order","parent_id");
  CREATE INDEX "settings_rels_order_idx" ON "settings_rels" USING btree ("order");
  CREATE INDEX "settings_rels_parent_idx" ON "settings_rels" USING btree ("parent_id");
  CREATE INDEX "settings_rels_path_idx" ON "settings_rels" USING btree ("path");
  CREATE INDEX "settings_rels_event_schemas_id_idx" ON "settings_rels" USING btree ("event_schemas_id");
  CREATE INDEX "media_updated_at_idx" ON "media" USING btree ("updated_at");
  CREATE INDEX "media_created_at_idx" ON "media" USING btree ("created_at");
  CREATE UNIQUE INDEX "media_filename_idx" ON "media" USING btree ("filename");
  CREATE UNIQUE INDEX "payload_kv_key_idx" ON "payload_kv" USING btree ("key");
  CREATE INDEX "users_sessions_order_idx" ON "users_sessions" USING btree ("_order");
  CREATE INDEX "users_sessions_parent_id_idx" ON "users_sessions" USING btree ("_parent_id");
  CREATE INDEX "users_updated_at_idx" ON "users" USING btree ("updated_at");
  CREATE INDEX "users_created_at_idx" ON "users" USING btree ("created_at");
  CREATE UNIQUE INDEX "users_email_idx" ON "users" USING btree ("email");
  CREATE INDEX "payload_locked_documents_global_slug_idx" ON "payload_locked_documents" USING btree ("global_slug");
  CREATE INDEX "payload_locked_documents_updated_at_idx" ON "payload_locked_documents" USING btree ("updated_at");
  CREATE INDEX "payload_locked_documents_created_at_idx" ON "payload_locked_documents" USING btree ("created_at");
  CREATE INDEX "payload_locked_documents_rels_order_idx" ON "payload_locked_documents_rels" USING btree ("order");
  CREATE INDEX "payload_locked_documents_rels_parent_idx" ON "payload_locked_documents_rels" USING btree ("parent_id");
  CREATE INDEX "payload_locked_documents_rels_path_idx" ON "payload_locked_documents_rels" USING btree ("path");
  CREATE INDEX "payload_locked_documents_rels_campaigns_id_idx" ON "payload_locked_documents_rels" USING btree ("campaigns_id");
  CREATE INDEX "payload_locked_documents_rels_decision_policies_id_idx" ON "payload_locked_documents_rels" USING btree ("decision_policies_id");
  CREATE INDEX "payload_locked_documents_rels_offers_id_idx" ON "payload_locked_documents_rels" USING btree ("offers_id");
  CREATE INDEX "payload_locked_documents_rels_experiments_id_idx" ON "payload_locked_documents_rels" USING btree ("experiments_id");
  CREATE INDEX "payload_locked_documents_rels_prediction_providers_id_idx" ON "payload_locked_documents_rels" USING btree ("prediction_providers_id");
  CREATE INDEX "payload_locked_documents_rels_fraud_rules_id_idx" ON "payload_locked_documents_rels" USING btree ("fraud_rules_id");
  CREATE INDEX "payload_locked_documents_rels_delivery_routing_id_idx" ON "payload_locked_documents_rels" USING btree ("delivery_routing_id");
  CREATE INDEX "payload_locked_documents_rels_consent_purposes_id_idx" ON "payload_locked_documents_rels" USING btree ("consent_purposes_id");
  CREATE INDEX "payload_locked_documents_rels_point_rules_id_idx" ON "payload_locked_documents_rels" USING btree ("point_rules_id");
  CREATE INDEX "payload_locked_documents_rels_achievements_id_idx" ON "payload_locked_documents_rels" USING btree ("achievements_id");
  CREATE INDEX "payload_locked_documents_rels_challenges_id_idx" ON "payload_locked_documents_rels" USING btree ("challenges_id");
  CREATE INDEX "payload_locked_documents_rels_badges_id_idx" ON "payload_locked_documents_rels" USING btree ("badges_id");
  CREATE INDEX "payload_locked_documents_rels_leaderboards_id_idx" ON "payload_locked_documents_rels" USING btree ("leaderboards_id");
  CREATE INDEX "payload_locked_documents_rels_fortune_wheels_id_idx" ON "payload_locked_documents_rels" USING btree ("fortune_wheels_id");
  CREATE INDEX "payload_locked_documents_rels_contests_id_idx" ON "payload_locked_documents_rels" USING btree ("contests_id");
  CREATE INDEX "payload_locked_documents_rels_contest_cards_id_idx" ON "payload_locked_documents_rels" USING btree ("contest_cards_id");
  CREATE INDEX "payload_locked_documents_rels_win_cards_id_idx" ON "payload_locked_documents_rels" USING btree ("win_cards_id");
  CREATE INDEX "payload_locked_documents_rels_popups_id_idx" ON "payload_locked_documents_rels" USING btree ("popups_id");
  CREATE INDEX "payload_locked_documents_rels_rewards_id_idx" ON "payload_locked_documents_rels" USING btree ("rewards_id");
  CREATE INDEX "payload_locked_documents_rels_reward_categories_id_idx" ON "payload_locked_documents_rels" USING btree ("reward_categories_id");
  CREATE INDEX "payload_locked_documents_rels_coupon_pools_id_idx" ON "payload_locked_documents_rels" USING btree ("coupon_pools_id");
  CREATE INDEX "payload_locked_documents_rels_promo_codes_id_idx" ON "payload_locked_documents_rels" USING btree ("promo_codes_id");
  CREATE INDEX "payload_locked_documents_rels_wallets_id_idx" ON "payload_locked_documents_rels" USING btree ("wallets_id");
  CREATE INDEX "payload_locked_documents_rels_tier_sets_id_idx" ON "payload_locked_documents_rels" USING btree ("tier_sets_id");
  CREATE INDEX "payload_locked_documents_rels_tiers_id_idx" ON "payload_locked_documents_rels" USING btree ("tiers_id");
  CREATE INDEX "payload_locked_documents_rels_segments_id_idx" ON "payload_locked_documents_rels" USING btree ("segments_id");
  CREATE INDEX "payload_locked_documents_rels_collections_id_idx" ON "payload_locked_documents_rels" USING btree ("collections_id");
  CREATE INDEX "payload_locked_documents_rels_event_schemas_id_idx" ON "payload_locked_documents_rels" USING btree ("event_schemas_id");
  CREATE INDEX "payload_locked_documents_rels_custom_field_schemas_id_idx" ON "payload_locked_documents_rels" USING btree ("custom_field_schemas_id");
  CREATE INDEX "payload_locked_documents_rels_channels_id_idx" ON "payload_locked_documents_rels" USING btree ("channels_id");
  CREATE INDEX "payload_locked_documents_rels_programs_id_idx" ON "payload_locked_documents_rels" USING btree ("programs_id");
  CREATE INDEX "payload_locked_documents_rels_message_templates_id_idx" ON "payload_locked_documents_rels" USING btree ("message_templates_id");
  CREATE INDEX "payload_locked_documents_rels_webhooks_id_idx" ON "payload_locked_documents_rels" USING btree ("webhooks_id");
  CREATE INDEX "payload_locked_documents_rels_roles_id_idx" ON "payload_locked_documents_rels" USING btree ("roles_id");
  CREATE INDEX "payload_locked_documents_rels_settings_id_idx" ON "payload_locked_documents_rels" USING btree ("settings_id");
  CREATE INDEX "payload_locked_documents_rels_media_id_idx" ON "payload_locked_documents_rels" USING btree ("media_id");
  CREATE INDEX "payload_locked_documents_rels_users_id_idx" ON "payload_locked_documents_rels" USING btree ("users_id");
  CREATE INDEX "payload_preferences_key_idx" ON "payload_preferences" USING btree ("key");
  CREATE INDEX "payload_preferences_updated_at_idx" ON "payload_preferences" USING btree ("updated_at");
  CREATE INDEX "payload_preferences_created_at_idx" ON "payload_preferences" USING btree ("created_at");
  CREATE INDEX "payload_preferences_rels_order_idx" ON "payload_preferences_rels" USING btree ("order");
  CREATE INDEX "payload_preferences_rels_parent_idx" ON "payload_preferences_rels" USING btree ("parent_id");
  CREATE INDEX "payload_preferences_rels_path_idx" ON "payload_preferences_rels" USING btree ("path");
  CREATE INDEX "payload_preferences_rels_users_id_idx" ON "payload_preferences_rels" USING btree ("users_id");
  CREATE INDEX "payload_migrations_updated_at_idx" ON "payload_migrations" USING btree ("updated_at");
  CREATE INDEX "payload_migrations_created_at_idx" ON "payload_migrations" USING btree ("created_at");`)
}

export async function down({ db, payload, req }: MigrateDownArgs): Promise<void> {
  await db.execute(sql`
   DROP TABLE "campaigns_visibility_tiers" CASCADE;
  DROP TABLE "campaigns_rules_conditions" CASCADE;
  DROP TABLE "campaigns_rules_effects" CASCADE;
  DROP TABLE "campaigns_rules" CASCADE;
  DROP TABLE "campaigns" CASCADE;
  DROP TABLE "campaigns_locales" CASCADE;
  DROP TABLE "campaigns_texts" CASCADE;
  DROP TABLE "campaigns_rels" CASCADE;
  DROP TABLE "_campaigns_v_version_visibility_tiers" CASCADE;
  DROP TABLE "_campaigns_v_version_rules_conditions" CASCADE;
  DROP TABLE "_campaigns_v_version_rules_effects" CASCADE;
  DROP TABLE "_campaigns_v_version_rules" CASCADE;
  DROP TABLE "_campaigns_v" CASCADE;
  DROP TABLE "_campaigns_v_locales" CASCADE;
  DROP TABLE "_campaigns_v_texts" CASCADE;
  DROP TABLE "_campaigns_v_rels" CASCADE;
  DROP TABLE "decision_policies_actions_channels" CASCADE;
  DROP TABLE "decision_policies_actions" CASCADE;
  DROP TABLE "contact_cap_7d" CASCADE;
  DROP TABLE "decision_policies_scoring_tier_boost" CASCADE;
  DROP TABLE "decision_policies_always_apply" CASCADE;
  DROP TABLE "channel_pref" CASCADE;
  DROP TABLE "decision_policies" CASCADE;
  DROP TABLE "decision_policies_locales" CASCADE;
  DROP TABLE "decision_policies_texts" CASCADE;
  DROP TABLE "_decision_policies_v_version_actions_channels" CASCADE;
  DROP TABLE "_decision_policies_v_version_actions" CASCADE;
  DROP TABLE "_contact_cap_7d_v" CASCADE;
  DROP TABLE "_decision_policies_v_version_scoring_tier_boost" CASCADE;
  DROP TABLE "_decision_policies_v_version_always_apply" CASCADE;
  DROP TABLE "_channel_pref_v" CASCADE;
  DROP TABLE "_decision_policies_v" CASCADE;
  DROP TABLE "_decision_policies_v_locales" CASCADE;
  DROP TABLE "_decision_policies_v_texts" CASCADE;
  DROP TABLE "offers_channels" CASCADE;
  DROP TABLE "offers" CASCADE;
  DROP TABLE "offers_locales" CASCADE;
  DROP TABLE "_offers_v_version_channels" CASCADE;
  DROP TABLE "_offers_v" CASCADE;
  DROP TABLE "_offers_v_locales" CASCADE;
  DROP TABLE "experiments_variants_overrides" CASCADE;
  DROP TABLE "experiments_variants" CASCADE;
  DROP TABLE "experiments" CASCADE;
  DROP TABLE "experiments_texts" CASCADE;
  DROP TABLE "_experiments_v_version_variants_overrides" CASCADE;
  DROP TABLE "_experiments_v_version_variants" CASCADE;
  DROP TABLE "_experiments_v" CASCADE;
  DROP TABLE "_experiments_v_texts" CASCADE;
  DROP TABLE "prediction_providers_serves_keys" CASCADE;
  DROP TABLE "prediction_providers" CASCADE;
  DROP TABLE "_prediction_providers_v_version_serves_keys" CASCADE;
  DROP TABLE "_prediction_providers_v" CASCADE;
  DROP TABLE "fraud_rules_signals" CASCADE;
  DROP TABLE "fraud_rules" CASCADE;
  DROP TABLE "_fraud_rules_v_version_signals" CASCADE;
  DROP TABLE "_fraud_rules_v" CASCADE;
  DROP TABLE "delivery_routing_routes_channels" CASCADE;
  DROP TABLE "delivery_routing_routes" CASCADE;
  DROP TABLE "delivery_routing_enabled_channels" CASCADE;
  DROP TABLE "max_per_day" CASCADE;
  DROP TABLE "delivery_routing_templates" CASCADE;
  DROP TABLE "delivery_routing" CASCADE;
  DROP TABLE "_delivery_routing_v_version_routes_channels" CASCADE;
  DROP TABLE "_delivery_routing_v_version_routes" CASCADE;
  DROP TABLE "_delivery_routing_v_version_enabled_channels" CASCADE;
  DROP TABLE "_max_per_day_v" CASCADE;
  DROP TABLE "_delivery_routing_v_version_templates" CASCADE;
  DROP TABLE "_delivery_routing_v" CASCADE;
  DROP TABLE "consent_purposes" CASCADE;
  DROP TABLE "consent_purposes_locales" CASCADE;
  DROP TABLE "point_rules_conditions" CASCADE;
  DROP TABLE "point_rules_target_tiers" CASCADE;
  DROP TABLE "point_rules_target_channels" CASCADE;
  DROP TABLE "point_rules" CASCADE;
  DROP TABLE "point_rules_texts" CASCADE;
  DROP TABLE "point_rules_rels" CASCADE;
  DROP TABLE "_point_rules_v_version_conditions" CASCADE;
  DROP TABLE "_point_rules_v_version_target_tiers" CASCADE;
  DROP TABLE "_point_rules_v_version_target_channels" CASCADE;
  DROP TABLE "_point_rules_v" CASCADE;
  DROP TABLE "_point_rules_v_texts" CASCADE;
  DROP TABLE "_point_rules_v_rels" CASCADE;
  DROP TABLE "achievements_conditions" CASCADE;
  DROP TABLE "achievements" CASCADE;
  DROP TABLE "achievements_locales" CASCADE;
  DROP TABLE "_achievements_v_version_conditions" CASCADE;
  DROP TABLE "_achievements_v" CASCADE;
  DROP TABLE "_achievements_v_locales" CASCADE;
  DROP TABLE "challenges_availability_days_of_week" CASCADE;
  DROP TABLE "challenges_visibility_tiers" CASCADE;
  DROP TABLE "challenges_milestones_conditions" CASCADE;
  DROP TABLE "challenges_milestones" CASCADE;
  DROP TABLE "challenges_milestones_locales" CASCADE;
  DROP TABLE "challenges_rules_effects" CASCADE;
  DROP TABLE "challenges_rules" CASCADE;
  DROP TABLE "challenges" CASCADE;
  DROP TABLE "challenges_locales" CASCADE;
  DROP TABLE "challenges_texts" CASCADE;
  DROP TABLE "challenges_rels" CASCADE;
  DROP TABLE "_challenges_v_version_availability_days_of_week" CASCADE;
  DROP TABLE "_challenges_v_version_visibility_tiers" CASCADE;
  DROP TABLE "_challenges_v_version_milestones_conditions" CASCADE;
  DROP TABLE "_challenges_v_version_milestones" CASCADE;
  DROP TABLE "_challenges_v_version_milestones_locales" CASCADE;
  DROP TABLE "_challenges_v_version_rules_effects" CASCADE;
  DROP TABLE "_challenges_v_version_rules" CASCADE;
  DROP TABLE "_challenges_v" CASCADE;
  DROP TABLE "_challenges_v_locales" CASCADE;
  DROP TABLE "_challenges_v_texts" CASCADE;
  DROP TABLE "_challenges_v_rels" CASCADE;
  DROP TABLE "badges" CASCADE;
  DROP TABLE "badges_locales" CASCADE;
  DROP TABLE "leaderboards_rewarding_cycle_rewards" CASCADE;
  DROP TABLE "leaderboards" CASCADE;
  DROP TABLE "leaderboards_locales" CASCADE;
  DROP TABLE "_leaderboards_v_version_rewarding_cycle_rewards" CASCADE;
  DROP TABLE "_leaderboards_v" CASCADE;
  DROP TABLE "_leaderboards_v_locales" CASCADE;
  DROP TABLE "fortune_wheels_slots" CASCADE;
  DROP TABLE "fortune_wheels_slots_locales" CASCADE;
  DROP TABLE "fortune_wheels" CASCADE;
  DROP TABLE "fortune_wheels_locales" CASCADE;
  DROP TABLE "_fortune_wheels_v_version_slots" CASCADE;
  DROP TABLE "_fortune_wheels_v_version_slots_locales" CASCADE;
  DROP TABLE "_fortune_wheels_v" CASCADE;
  DROP TABLE "_fortune_wheels_v_locales" CASCADE;
  DROP TABLE "contests_prizes" CASCADE;
  DROP TABLE "contests_weighted_slots" CASCADE;
  DROP TABLE "contests" CASCADE;
  DROP TABLE "_contests_v_version_prizes" CASCADE;
  DROP TABLE "_contests_v_version_weighted_slots" CASCADE;
  DROP TABLE "_contests_v" CASCADE;
  DROP TABLE "contest_cards" CASCADE;
  DROP TABLE "_contest_cards_v" CASCADE;
  DROP TABLE "win_cards" CASCADE;
  DROP TABLE "_win_cards_v" CASCADE;
  DROP TABLE "popups" CASCADE;
  DROP TABLE "_popups_v" CASCADE;
  DROP TABLE "rewards" CASCADE;
  DROP TABLE "rewards_rels" CASCADE;
  DROP TABLE "_rewards_v" CASCADE;
  DROP TABLE "_rewards_v_rels" CASCADE;
  DROP TABLE "reward_categories" CASCADE;
  DROP TABLE "coupon_pools" CASCADE;
  DROP TABLE "promo_codes" CASCADE;
  DROP TABLE "_promo_codes_v" CASCADE;
  DROP TABLE "wallets" CASCADE;
  DROP TABLE "wallets_locales" CASCADE;
  DROP TABLE "tier_sets_conditions" CASCADE;
  DROP TABLE "tier_sets_tiers" CASCADE;
  DROP TABLE "tier_sets_tiers_locales" CASCADE;
  DROP TABLE "tier_sets" CASCADE;
  DROP TABLE "tier_sets_locales" CASCADE;
  DROP TABLE "tier_sets_texts" CASCADE;
  DROP TABLE "tier_sets_rels" CASCADE;
  DROP TABLE "_tier_sets_v_version_conditions" CASCADE;
  DROP TABLE "_tier_sets_v_version_tiers" CASCADE;
  DROP TABLE "_tier_sets_v_version_tiers_locales" CASCADE;
  DROP TABLE "_tier_sets_v" CASCADE;
  DROP TABLE "_tier_sets_v_locales" CASCADE;
  DROP TABLE "_tier_sets_v_texts" CASCADE;
  DROP TABLE "_tier_sets_v_rels" CASCADE;
  DROP TABLE "tiers" CASCADE;
  DROP TABLE "tiers_rels" CASCADE;
  DROP TABLE "segments_criteria" CASCADE;
  DROP TABLE "segments" CASCADE;
  DROP TABLE "_segments_v_version_criteria" CASCADE;
  DROP TABLE "_segments_v" CASCADE;
  DROP TABLE "collections" CASCADE;
  DROP TABLE "event_schemas_attributes" CASCADE;
  DROP TABLE "event_schemas" CASCADE;
  DROP TABLE "event_schemas_locales" CASCADE;
  DROP TABLE "_event_schemas_v_version_attributes" CASCADE;
  DROP TABLE "_event_schemas_v" CASCADE;
  DROP TABLE "_event_schemas_v_locales" CASCADE;
  DROP TABLE "custom_field_schemas_fields" CASCADE;
  DROP TABLE "custom_field_schemas" CASCADE;
  DROP TABLE "custom_field_schemas_locales" CASCADE;
  DROP TABLE "channels" CASCADE;
  DROP TABLE "channels_locales" CASCADE;
  DROP TABLE "programs_missions" CASCADE;
  DROP TABLE "programs" CASCADE;
  DROP TABLE "programs_texts" CASCADE;
  DROP TABLE "_programs_v_version_missions" CASCADE;
  DROP TABLE "_programs_v" CASCADE;
  DROP TABLE "_programs_v_texts" CASCADE;
  DROP TABLE "message_templates" CASCADE;
  DROP TABLE "_message_templates_v" CASCADE;
  DROP TABLE "webhooks" CASCADE;
  DROP TABLE "webhooks_texts" CASCADE;
  DROP TABLE "roles_permissions" CASCADE;
  DROP TABLE "roles" CASCADE;
  DROP TABLE "settings_locales" CASCADE;
  DROP TABLE "settings_identification_priority" CASCADE;
  DROP TABLE "settings" CASCADE;
  DROP TABLE "settings_texts" CASCADE;
  DROP TABLE "settings_rels" CASCADE;
  DROP TABLE "media" CASCADE;
  DROP TABLE "payload_kv" CASCADE;
  DROP TABLE "users_sessions" CASCADE;
  DROP TABLE "users" CASCADE;
  DROP TABLE "payload_locked_documents" CASCADE;
  DROP TABLE "payload_locked_documents_rels" CASCADE;
  DROP TABLE "payload_preferences" CASCADE;
  DROP TABLE "payload_preferences_rels" CASCADE;
  DROP TABLE "payload_migrations" CASCADE;
  DROP TYPE "public"."_locales";
  DROP TYPE "public"."enum_campaigns_visibility_tiers";
  DROP TYPE "public"."enum_campaigns_rules_conditions_op";
  DROP TYPE "public"."enum_campaigns_rules_effects_type";
  DROP TYPE "public"."enum_campaigns_kind";
  DROP TYPE "public"."enum_campaigns_trigger_type";
  DROP TYPE "public"."enum_campaigns_visibility_mode";
  DROP TYPE "public"."enum_campaigns_limits_per_member_triggers_period";
  DROP TYPE "public"."enum_campaigns_limits_per_member_units_period";
  DROP TYPE "public"."enum_campaigns_status";
  DROP TYPE "public"."enum__campaigns_v_version_visibility_tiers";
  DROP TYPE "public"."enum__campaigns_v_version_rules_conditions_op";
  DROP TYPE "public"."enum__campaigns_v_version_rules_effects_type";
  DROP TYPE "public"."enum__campaigns_v_version_kind";
  DROP TYPE "public"."enum__campaigns_v_version_trigger_type";
  DROP TYPE "public"."enum__campaigns_v_version_visibility_mode";
  DROP TYPE "public"."enum__campaigns_v_version_limits_per_member_triggers_period";
  DROP TYPE "public"."enum__campaigns_v_version_limits_per_member_units_period";
  DROP TYPE "public"."enum__campaigns_v_version_status";
  DROP TYPE "public"."enum__campaigns_v_published_locale";
  DROP TYPE "public"."enum_decision_policies_actions_channels";
  DROP TYPE "public"."enum_decision_policies_actions_type";
  DROP TYPE "public"."enum_decision_policies_actions_max_risk_level";
  DROP TYPE "public"."enum_decision_policies_actions_period";
  DROP TYPE "public"."enum_policy_contact_cap_channel";
  DROP TYPE "public"."enum_decision_policies_always_apply";
  DROP TYPE "public"."enum_policy_channel_pref";
  DROP TYPE "public"."enum_decision_policies_constraints_block_risk_level";
  DROP TYPE "public"."enum_decision_policies_scoring_strategy";
  DROP TYPE "public"."enum_decision_policies_status";
  DROP TYPE "public"."enum__decision_policies_v_version_actions_channels";
  DROP TYPE "public"."enum__decision_policies_v_version_actions_type";
  DROP TYPE "public"."enum__decision_policies_v_version_actions_max_risk_level";
  DROP TYPE "public"."enum__decision_policies_v_version_actions_period";
  DROP TYPE "public"."enum__decision_policies_v_version_always_apply";
  DROP TYPE "public"."enum__decision_policies_v_version_constraints_block_risk_level";
  DROP TYPE "public"."enum__decision_policies_v_version_scoring_strategy";
  DROP TYPE "public"."enum__decision_policies_v_version_status";
  DROP TYPE "public"."enum__decision_policies_v_published_locale";
  DROP TYPE "public"."enum_offers_channels";
  DROP TYPE "public"."enum_offers_action";
  DROP TYPE "public"."enum_offers_status";
  DROP TYPE "public"."enum__offers_v_version_channels";
  DROP TYPE "public"."enum__offers_v_version_action";
  DROP TYPE "public"."enum__offers_v_version_status";
  DROP TYPE "public"."enum__offers_v_published_locale";
  DROP TYPE "public"."enum_experiments_variants_overrides_key";
  DROP TYPE "public"."enum_experiments_status";
  DROP TYPE "public"."enum__experiments_v_version_variants_overrides_key";
  DROP TYPE "public"."enum__experiments_v_version_status";
  DROP TYPE "public"."enum__experiments_v_published_locale";
  DROP TYPE "public"."enum_prediction_providers_serves_keys";
  DROP TYPE "public"."enum_prediction_providers_kind";
  DROP TYPE "public"."enum_prediction_providers_status";
  DROP TYPE "public"."enum__prediction_providers_v_version_serves_keys";
  DROP TYPE "public"."enum__prediction_providers_v_version_kind";
  DROP TYPE "public"."enum__prediction_providers_v_version_status";
  DROP TYPE "public"."enum__prediction_providers_v_published_locale";
  DROP TYPE "public"."enum_fraud_rules_signals_signal";
  DROP TYPE "public"."enum_fraud_rules_auto_block_level";
  DROP TYPE "public"."enum_fraud_rules_status";
  DROP TYPE "public"."enum__fraud_rules_v_version_signals_signal";
  DROP TYPE "public"."enum__fraud_rules_v_version_auto_block_level";
  DROP TYPE "public"."enum__fraud_rules_v_version_status";
  DROP TYPE "public"."enum__fraud_rules_v_published_locale";
  DROP TYPE "public"."enum_delivery_routing_routes_channels_channel";
  DROP TYPE "public"."enum_delivery_routing_routes_action";
  DROP TYPE "public"."enum_delivery_routing_enabled_channels";
  DROP TYPE "public"."enum_routing_max_per_day_channel";
  DROP TYPE "public"."enum_delivery_routing_templates_action";
  DROP TYPE "public"."enum_delivery_routing_quiet_hours_fallback";
  DROP TYPE "public"."enum_delivery_routing_status";
  DROP TYPE "public"."enum__delivery_routing_v_version_routes_channels_channel";
  DROP TYPE "public"."enum__delivery_routing_v_version_routes_action";
  DROP TYPE "public"."enum__delivery_routing_v_version_enabled_channels";
  DROP TYPE "public"."enum__delivery_routing_v_version_templates_action";
  DROP TYPE "public"."enum__delivery_routing_v_version_quiet_hours_fallback";
  DROP TYPE "public"."enum__delivery_routing_v_version_status";
  DROP TYPE "public"."enum__delivery_routing_v_published_locale";
  DROP TYPE "public"."enum_consent_purposes_legal_basis";
  DROP TYPE "public"."enum_point_rules_conditions_op";
  DROP TYPE "public"."enum_point_rules_target_tiers";
  DROP TYPE "public"."enum_point_rules_target_channels";
  DROP TYPE "public"."enum_point_rules_status";
  DROP TYPE "public"."enum__point_rules_v_version_conditions_op";
  DROP TYPE "public"."enum__point_rules_v_version_target_tiers";
  DROP TYPE "public"."enum__point_rules_v_version_target_channels";
  DROP TYPE "public"."enum__point_rules_v_version_status";
  DROP TYPE "public"."enum__point_rules_v_published_locale";
  DROP TYPE "public"."enum_achievements_conditions_op";
  DROP TYPE "public"."enum_achievements_metric";
  DROP TYPE "public"."enum_achievements_goal_type";
  DROP TYPE "public"."enum_achievements_goal_period";
  DROP TYPE "public"."enum_achievements_event_limit_period";
  DROP TYPE "public"."enum_achievements_completion_limit_period";
  DROP TYPE "public"."enum_achievements_status";
  DROP TYPE "public"."enum__achievements_v_version_conditions_op";
  DROP TYPE "public"."enum__achievements_v_version_metric";
  DROP TYPE "public"."enum__achievements_v_version_goal_type";
  DROP TYPE "public"."enum__achievements_v_version_goal_period";
  DROP TYPE "public"."enum__achievements_v_version_event_limit_period";
  DROP TYPE "public"."enum__achievements_v_version_completion_limit_period";
  DROP TYPE "public"."enum__achievements_v_version_status";
  DROP TYPE "public"."enum__achievements_v_published_locale";
  DROP TYPE "public"."enum_challenges_availability_days_of_week";
  DROP TYPE "public"."enum_challenges_visibility_tiers";
  DROP TYPE "public"."enum_challenges_milestones_conditions_op";
  DROP TYPE "public"."enum_challenges_milestones_kind";
  DROP TYPE "public"."enum_challenges_milestones_metric";
  DROP TYPE "public"."enum_challenges_milestones_goal_type";
  DROP TYPE "public"."enum_challenges_milestones_goal_period";
  DROP TYPE "public"."enum_challenges_milestones_event_limit_period";
  DROP TYPE "public"."enum_challenges_milestones_completion_limit_period";
  DROP TYPE "public"."enum_challenges_rules_effects_type";
  DROP TYPE "public"."enum_challenges_rules_trigger";
  DROP TYPE "public"."enum_challenges_visibility_mode";
  DROP TYPE "public"."enum_challenges_completion_limit_period";
  DROP TYPE "public"."enum_challenges_status";
  DROP TYPE "public"."enum__challenges_v_version_availability_days_of_week";
  DROP TYPE "public"."enum__challenges_v_version_visibility_tiers";
  DROP TYPE "public"."enum__challenges_v_version_milestones_conditions_op";
  DROP TYPE "public"."enum__challenges_v_version_milestones_kind";
  DROP TYPE "public"."enum__challenges_v_version_milestones_metric";
  DROP TYPE "public"."enum__challenges_v_version_milestones_goal_type";
  DROP TYPE "public"."enum__challenges_v_version_milestones_goal_period";
  DROP TYPE "public"."enum__challenges_v_version_milestones_event_limit_period";
  DROP TYPE "public"."enum__challenges_v_version_milestones_completion_limit_period";
  DROP TYPE "public"."enum__challenges_v_version_rules_effects_type";
  DROP TYPE "public"."enum__challenges_v_version_rules_trigger";
  DROP TYPE "public"."enum__challenges_v_version_visibility_mode";
  DROP TYPE "public"."enum__challenges_v_version_completion_limit_period";
  DROP TYPE "public"."enum__challenges_v_version_status";
  DROP TYPE "public"."enum__challenges_v_published_locale";
  DROP TYPE "public"."enum_leaderboards_metric";
  DROP TYPE "public"."enum_leaderboards_rewarding_cycle_period";
  DROP TYPE "public"."enum_leaderboards_visibility";
  DROP TYPE "public"."enum_leaderboards_status";
  DROP TYPE "public"."enum__leaderboards_v_version_metric";
  DROP TYPE "public"."enum__leaderboards_v_version_rewarding_cycle_period";
  DROP TYPE "public"."enum__leaderboards_v_version_visibility";
  DROP TYPE "public"."enum__leaderboards_v_version_status";
  DROP TYPE "public"."enum__leaderboards_v_published_locale";
  DROP TYPE "public"."enum_fortune_wheels_mode";
  DROP TYPE "public"."enum_fortune_wheels_spins_period";
  DROP TYPE "public"."enum_fortune_wheels_visibility";
  DROP TYPE "public"."enum_fortune_wheels_status";
  DROP TYPE "public"."enum__fortune_wheels_v_version_mode";
  DROP TYPE "public"."enum__fortune_wheels_v_version_spins_period";
  DROP TYPE "public"."enum__fortune_wheels_v_version_visibility";
  DROP TYPE "public"."enum__fortune_wheels_v_version_status";
  DROP TYPE "public"."enum__fortune_wheels_v_published_locale";
  DROP TYPE "public"."enum_contests_status";
  DROP TYPE "public"."enum__contests_v_version_status";
  DROP TYPE "public"."enum__contests_v_published_locale";
  DROP TYPE "public"."enum_contest_cards_min_tier";
  DROP TYPE "public"."enum_contest_cards_status";
  DROP TYPE "public"."enum__contest_cards_v_version_min_tier";
  DROP TYPE "public"."enum__contest_cards_v_version_status";
  DROP TYPE "public"."enum__contest_cards_v_published_locale";
  DROP TYPE "public"."enum_win_cards_status";
  DROP TYPE "public"."enum__win_cards_v_version_status";
  DROP TYPE "public"."enum__win_cards_v_published_locale";
  DROP TYPE "public"."enum_popups_trigger";
  DROP TYPE "public"."enum_popups_segment_min_tier";
  DROP TYPE "public"."enum_popups_status";
  DROP TYPE "public"."enum__popups_v_version_trigger";
  DROP TYPE "public"."enum__popups_v_version_segment_min_tier";
  DROP TYPE "public"."enum__popups_v_version_status";
  DROP TYPE "public"."enum__popups_v_published_locale";
  DROP TYPE "public"."enum_rewards_type";
  DROP TYPE "public"."enum_rewards_min_tier";
  DROP TYPE "public"."enum_rewards_status";
  DROP TYPE "public"."enum__rewards_v_version_type";
  DROP TYPE "public"."enum__rewards_v_version_min_tier";
  DROP TYPE "public"."enum__rewards_v_version_status";
  DROP TYPE "public"."enum__rewards_v_published_locale";
  DROP TYPE "public"."enum_promo_codes_kind";
  DROP TYPE "public"."enum_promo_codes_status";
  DROP TYPE "public"."enum__promo_codes_v_version_kind";
  DROP TYPE "public"."enum__promo_codes_v_version_status";
  DROP TYPE "public"."enum__promo_codes_v_published_locale";
  DROP TYPE "public"."enum_wallets_expiration";
  DROP TYPE "public"."enum_tier_sets_conditions_metric";
  DROP TYPE "public"."enum_tier_sets_match";
  DROP TYPE "public"."enum_tier_sets_downgrade_mode";
  DROP TYPE "public"."enum_tier_sets_status";
  DROP TYPE "public"."enum__tier_sets_v_version_conditions_metric";
  DROP TYPE "public"."enum__tier_sets_v_version_match";
  DROP TYPE "public"."enum__tier_sets_v_version_downgrade_mode";
  DROP TYPE "public"."enum__tier_sets_v_version_status";
  DROP TYPE "public"."enum__tier_sets_v_published_locale";
  DROP TYPE "public"."enum_tiers_status";
  DROP TYPE "public"."enum_segments_criteria_type";
  DROP TYPE "public"."enum_segments_match";
  DROP TYPE "public"."enum_segments_status";
  DROP TYPE "public"."enum__segments_v_version_criteria_type";
  DROP TYPE "public"."enum__segments_v_version_match";
  DROP TYPE "public"."enum__segments_v_version_status";
  DROP TYPE "public"."enum__segments_v_published_locale";
  DROP TYPE "public"."enum_event_schemas_attributes_type";
  DROP TYPE "public"."enum_event_schemas_status";
  DROP TYPE "public"."enum__event_schemas_v_version_attributes_type";
  DROP TYPE "public"."enum__event_schemas_v_version_status";
  DROP TYPE "public"."enum__event_schemas_v_published_locale";
  DROP TYPE "public"."enum_custom_field_schemas_fields_type";
  DROP TYPE "public"."enum_custom_field_schemas_entity";
  DROP TYPE "public"."enum_custom_field_schemas_edit_role";
  DROP TYPE "public"."enum_channels_kind";
  DROP TYPE "public"."enum_programs_status";
  DROP TYPE "public"."enum__programs_v_version_status";
  DROP TYPE "public"."enum__programs_v_published_locale";
  DROP TYPE "public"."enum_message_templates_channel";
  DROP TYPE "public"."enum_message_templates_locale";
  DROP TYPE "public"."enum_message_templates_status";
  DROP TYPE "public"."enum__message_templates_v_version_channel";
  DROP TYPE "public"."enum__message_templates_v_version_locale";
  DROP TYPE "public"."enum__message_templates_v_version_status";
  DROP TYPE "public"."enum__message_templates_v_published_locale";
  DROP TYPE "public"."enum_roles_permissions";
  DROP TYPE "public"."enum_settings_locales";
  DROP TYPE "public"."enum_settings_identification_priority";
  DROP TYPE "public"."enum_settings_points_expiry";
  DROP TYPE "public"."enum_settings_tier_downgrade";
  DROP TYPE "public"."enum_settings_referral_trigger";
  DROP TYPE "public"."enum_settings_loyalty_card_format";`)
}
