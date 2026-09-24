# Audit UI - web app

## Pages

| Page | Data queries (service + path) | States (Load/Empty/Error/Degrad) | Write actions & gating (capability) | Irreversible confirmed? | IT text? | Runtime bugs? | Cross-area imports | Severity | Suggested fix |
|------|-------------------------------|----------------------------------|-------------------------------------|-------------------------|----------|---------------|--------------------|----------|---------------|
| `backoffice/page.tsx` | /v1/kpi/timeseries, /v1/kpi/breakdown, /v1/kpi/overview, /v1/liability | Empty, Degraded(QueryState) | None | No | Yes (No EN found) | Possible undefined: points.map | None | Medium | Fix points.map |
| `backoffice/program/actions/page.tsx` | /v1/internal-mappings/, /v1/internal-mappings, /v1/event-types, /v1/sources, /v1/inbound-events, /v1/campaigns, queryKey: ["ingestion"] | Empty, Error, Degraded(QueryState) | Gated(actiontype.custom), Gated(program.config) | No | Yes (No EN found) | None found | None | Low | None |
| `backoffice/program/currencies/page.tsx` | /v1/liability, /v1/editions/, queryKey: ["wallet"], /v1/editions, /v1/currencies, /v1/currencies/ | Empty, Error, Degraded(QueryState) | Gated(program.config), Gated(program.config), Gated(program.config) | Typed | Yes (No EN found) | Possible undefined: sorted.map, Possible undefined: currencies.map, Possible undefined: filteredMembers.map | None | Medium | Fix sorted.map; Fix currencies.map; Fix filteredMembers.map |
| `backoffice/program/tiers/page.tsx` | queryKey: ["wallet"], /v1/tiers/, /v1/tiers/distribution, /v1/tiers | None handled | Gated(program.config) | No | Yes (No EN found) | Possible undefined: scale.map | None | Medium | Add QueryState or custom state handlers; Fix scale.map |
| `backoffice/members/page.tsx` | /v1/segments, /v1/members | Empty, Degraded(QueryState) | None | No | Yes (No EN found) | Possible undefined: TIERS.map, Possible undefined: STATUSES.map, Possible undefined: items.map | None | Medium | Fix TIERS.map; Fix STATUSES.map; Fix items.map |
| `backoffice/members/[id]/page.tsx` | /v1/members/, /v1/evaluations, /v1/wallets/ | Empty, Degraded(QueryState) | Gated(points.adjust) | No | Yes (No EN found) | None found | None | Low | None |
| `backoffice/governance/approvals/page.tsx` | /v1/approvals | Loading, Empty, Error, Degraded(QueryState) | None | No | Yes (No EN found) | Possible undefined: failed.map, Possible undefined: ENTITY_TYPES.map, Possible undefined: rows.map | None | Medium | Fix failed.map; Fix ENTITY_TYPES.map; Fix rows.map |
| `backoffice/governance/audit/page.tsx` | /v1/audit | Empty, Degraded(QueryState) | None | No | Yes (No EN found) | Possible undefined: options.map, Possible undefined: items.map | None | Medium | Fix options.map; Fix items.map |
| `backoffice/governance/webhooks/page.tsx` | /v1/webhooks/, /v1/webhooks | Loading, Empty, Error, Degraded(QueryState) | Gated(webhook.write), Gated(webhook.write) | No | Yes (No EN found) | None found | None | Low | None |
| `backoffice/game/achievements/page.tsx` | /v1/achievements/, /v1/achievements, /v1/badges/, /v1/badges | Loading, Empty, Error, Degraded(QueryState) | Gated(object.edit), Gated(object.edit) | No | Yes (No EN found) | Possible undefined: ICON_CHOICES.map, Possible undefined: d.map, Possible undefined: unlockedBy.map, Possible undefined: badges.map, Possible undefined: actionTypes.map | None | Medium | Fix ICON_CHOICES.map; Fix d.map; Fix unlockedBy.map; Fix badges.map; Fix actionTypes.map |
| `backoffice/game/leaderboards/page.tsx` | /v1/leaderboards, /v1/members, /v1/leaderboards/ | Loading, Empty, Error, Degraded(QueryState) | Gated(object.edit) | No | Yes (No EN found) | Possible undefined: d.map, Possible undefined: actionTypes.map, Possible undefined: items.map, Possible undefined: periods.map | None | Medium | Fix d.map; Fix actionTypes.map; Fix items.map; Fix periods.map |
| `backoffice/game/contests/page.tsx` | /v1/contests | Empty, Degraded(QueryState) | Gated(object.edit) | No | Yes (No EN found) | Possible undefined: STATUSES.map | None | Medium | Fix STATUSES.map |
| `backoffice/game/contests/[id]/page.tsx` | /v1/plays/, /v1/demo/contests/, /v1/contests, /api/lh/gamification/v1/contests/, /v1/contests/ | Loading, Empty, Error, Degraded(QueryState) | Gated(demo.admin), Gated(object.edit), Gated(delivery.handle) | No | Yes (No EN found) | Possible undefined: INSTANT_STATUSES.map, Possible undefined: prizes.map, Possible undefined: daily.map | None | Medium | Fix INSTANT_STATUSES.map; Fix prizes.map; Fix daily.map |
| `backoffice/game/referral/page.tsx` | /v1/referral/overview, /v1/members/{id}/referrals, /v1/campaigns, /v1/members/, /v1/portal/campaigns | Empty, Error, Degraded(QueryState) | None | No | Yes (No EN found) | Possible undefined: topReferrers.map, Possible undefined: d.map, Possible undefined: steps.map, Possible undefined: REFERRAL_CAMPAIGNS.map | None | Medium | Fix topReferrers.map; Fix d.map; Fix steps.map; Fix REFERRAL_CAMPAIGNS.map |
| `backoffice/campaigns/page.tsx` | /v1/campaigns/, /v1/campaigns | Empty, Degraded(QueryState) | None | No | Yes (No EN found) | Possible undefined: STATUSES.map | None | Medium | Fix STATUSES.map |
| `backoffice/campaigns/new/page.tsx` | /v1/campaigns, /v1/event-types | Empty, Error | Gated(object.edit) | No | Yes (No EN found) | None found | None | Low | None |
| `backoffice/campaigns/[id]/page.tsx` | /v1/campaigns/ | Degraded(QueryState) | None | No | Yes (No EN found) | None found | None | Low | None |
| `backoffice/observe/live/page.tsx` | /v1/pipeline/status, /v1/stream/events | None handled | None | No | Yes (No EN found) | None found | None | Medium | Add QueryState or custom state handlers |
| `backoffice/observe/traces/page.tsx` | /v1/traces, /v1/traces/ | Empty, Degraded(QueryState) | None | No | Yes (No EN found) | Possible undefined: items.map | None | Medium | Fix items.map |
| `backoffice/observe/inbound/page.tsx` | /v1/inbound-events, /v1/members, /v1/inbound-events/ | Loading, Empty, Error, Degraded(QueryState) | Gated(inbound.handle) | No | Yes (No EN found) | Possible undefined: OUTCOMES.map, Possible undefined: errors.map | None | Medium | Fix OUTCOMES.map; Fix errors.map |
| `backoffice/observe/dlq/page.tsx` | /v1/dlq/, /v1/dlq | Loading, Empty, Error, Degraded(QueryState) | Gated(dlq.handle) | Typed | Yes (No EN found) | Possible undefined: DLQ_STATUS_TABS.map, Possible undefined: DLQ_CONSUMERS.map | None | Medium | Fix DLQ_STATUS_TABS.map; Fix DLQ_CONSUMERS.map |
| `backoffice/content/page.tsx` | /v1/contents, /v1/contents/preview, /v1/members | Empty, Degraded(QueryState) | Gated(content.write) | No | Yes (No EN found) | Possible undefined: excluded.map, Possible undefined: shown.map, Possible undefined: PLACEMENTS.map, Possible undefined: items.map | None | Medium | Fix excluded.map; Fix shown.map; Fix PLACEMENTS.map; Fix items.map |
| `backoffice/content/[id]/page.tsx` | /v1/rewards, /v1/campaigns, /v1/contests, /v1/contents, /v1/contents/ | Loading, Error, Degraded(QueryState) | Gated(content.write), Gated(content.write) | No | Yes (No EN found) | Possible undefined: PLACEMENTS.map, Possible undefined: prizes.map, Possible undefined: TIERS.map, Possible undefined: PORTAL_PAGES.map, Possible undefined: options.map, Possible undefined: TONES.map | None | Medium | Fix PLACEMENTS.map; Fix prizes.map; Fix TIERS.map; Fix PORTAL_PAGES.map; Fix options.map; Fix TONES.map |
| `backoffice/content/messages/page.tsx` | None | None handled | None | No | Yes (No EN found) | None found | None | Medium | Add QueryState or custom state handlers |
| `backoffice/content/theme/page.tsx` | /v1/theme | Loading, Error, Degraded(QueryState) | Gated(content.write), Gated(content.write) | No | Yes (No EN found) | Possible undefined: checks.map, Possible undefined: COLOR_KEYS.map | None | Medium | Fix checks.map; Fix COLOR_KEYS.map |
| `backoffice/segments/page.tsx` | /v1/segments, /v1/rewards, /v1/campaigns, /v1/contents | Loading, Empty, Error, Degraded(QueryState) | Gated(segment.write) | No | Yes (No EN found) | None found | None | Low | None |
| `backoffice/segments/[id]/page.tsx` | /v1/rewards, queryKey: ["member", "segment-preview", debounced], /v1/segments/, /v1/segments, /v1/campaigns, /v1/segments/preview, /v1/contents | Loading, Empty, Error, Degraded(QueryState) | Gated(segment.write), Gated(segment.write), Gated(segment.write) | window.confirm | Yes (No EN found) | Possible undefined: groups.map, Possible undefined: errors.map, Possible undefined: items.map | None | Medium | Fix groups.map; Fix errors.map; Fix items.map |
| `backoffice/rewards/page.tsx` | /v1/rewards/stats, /v1/rewards, /v1/reward-bands, /v1/reward-categories | Empty, Degraded(QueryState) | Gated(object.edit) | No | Yes (No EN found) | Possible undefined: TYPES.map, Possible undefined: options.map, Possible undefined: STATUSES.map, Possible undefined: d.map | None | Medium | Fix TYPES.map; Fix options.map; Fix STATUSES.map; Fix d.map |
| `backoffice/rewards/coupons/page.tsx` | /v1/coupon-pools, /v1/coupon-pools/ | Loading, Empty, Error, Degraded(QueryState) | Gated(object.edit), Gated(object.edit) | No | Yes (No EN found) | Possible undefined: COUPON_STATUSES.map, Possible undefined: ps.map, Possible undefined: rewards.map | None | Medium | Fix COUPON_STATUSES.map; Fix ps.map; Fix rewards.map |
| `backoffice/rewards/[id]/page.tsx` | /v1/rewards/, /v1/rewards, /v1/reward-categories, /v1/coupon-pools, /v1/reward-bands, /v1/tiers | Loading, Error, Degraded(QueryState) | None | No | Yes (No EN found) | None found | None | Low | None |
| `backoffice/rewards/bands/page.tsx` | /v1/rewards, /v1/reward-bands, /v1/reward-bands/ | Loading, Empty, Error, Degraded(QueryState) | Gated(object.edit) | No | Yes (No EN found) | Possible undefined: sorted.map, Possible undefined: bs.map, Possible undefined: byStatus.map | None | Medium | Fix sorted.map; Fix bs.map; Fix byStatus.map |
| `backoffice/rewards/redemptions/page.tsx` | /v1/traces/, /v1/redemptions/, /v1/redemptions | Loading, Empty, Error, Degraded(QueryState) | Gated(redemption.handle) | Typed | Yes (No EN found) | Possible undefined: REDEMPTION_TABS.map | None | Medium | Fix REDEMPTION_TABS.map |
| `backoffice/demo/simulator/page.tsx` | /v1/demo/simulator/fire, /v1/event-types | Error | Gated(demo.simulate) | No | Yes (No EN found) | Possible undefined: SHORTCUTS.map, Possible undefined: results.map | None | Medium | Fix SHORTCUTS.map; Fix results.map |
| `backoffice/demo/scenarios/page.tsx` | /v1/demo/scenario-runs/, /v1/demo/scenario-runs/{id}, /v1/demo/scenarios, /v1/demo/scenarios/ | Loading, Empty, Error, Degraded(QueryState) | Gated(demo.simulate) | No | Yes (No EN found) | Possible undefined: steps.map, Possible undefined: list.map | None | Medium | Fix steps.map; Fix list.map |
| `backoffice/demo/console/page.tsx` | queryKey: ["demo-status"], /v1/demo/jobs/, /v1/demo/reset | None handled | Gated(demo.admin), Gated(demo.admin) | No | Yes (No EN found) | Possible undefined: services.map, Possible undefined: RESETTABLE.map, Possible undefined: jobLog.map, Possible undefined: log.map, Possible undefined: JOBS.map | None | Medium | Add QueryState or custom state handlers; Fix services.map; Fix RESETTABLE.map; Fix jobLog.map; Fix log.map; Fix JOBS.map |
| `portal/page.tsx` | /v1/members/, /v1/portal/wallets/ | Empty, Degraded(QueryState) | None | No | Yes (No EN found) | Possible undefined: d.map | bo in portal | High | Remove cross-area import; Fix d.map |
| `portal/invite/page.tsx` | /v1/portal/campaigns, /v1/portal/members/ | Empty, Degraded(QueryState) | None | No | Yes (No EN found) | Possible undefined: invited.map | bo in portal | High | Remove cross-area import; Fix invited.map |
| `portal/profile/page.tsx` | /v1/members/, /v1/portal/wallets/, /v1/portal/tiers | Degraded(QueryState) | None | No | Yes (No EN found) | Possible undefined: scale.map, Possible undefined: benefits.map | bo in portal | High | Remove cross-area import; Fix scale.map; Fix benefits.map |
| `portal/achievements/page.tsx` | /v1/portal/achievements, /v1/portal/badges | Empty, Degraded(QueryState) | None | No | Yes (No EN found) | Possible undefined: d.map | bo in portal | High | Remove cross-area import; Fix d.map |
| `portal/earn/page.tsx` | /v1/portal/campaigns | Empty, Degraded(QueryState) | None | No | Yes (No EN found) | Possible undefined: d.map | bo in portal | High | Remove cross-area import; Fix d.map |
| `portal/inbox/page.tsx` | /v1/portal/inbox, queryKey: ["engagement", UNREAD_PATH] | Loading, Empty, Error | None | No | Yes (No EN found) | None found | None | Low | None |
| `portal/play/page.tsx` | /v1/portal/contests | Empty, Degraded(QueryState) | None | No | Yes (No EN found) | Possible undefined: d.map, Possible undefined: prizes.map | bo in portal | High | Remove cross-area import; Fix d.map; Fix prizes.map |
| `portal/play/[code]/page.tsx` | queryKey: ["gamification"], /v1/portal/contests/, /v1/portal/contests, /v1/portal/coupons, /v1/portal/content | Loading, Empty, Error, Degraded(QueryState) | None | No | Yes (No EN found) | None found | bo in portal | High | Remove cross-area import |
| `portal/leaderboard/page.tsx` | /v1/portal/leaderboards | Empty, Degraded(QueryState) | None | No | Yes (No EN found) | Possible undefined: d.map, Possible undefined: order.map, Possible undefined: rest.map | bo in portal | High | Remove cross-area import; Fix d.map; Fix order.map; Fix rest.map |
| `portal/activity/page.tsx` | /v1/portal/wallets/ | Empty, Degraded(QueryState) | None | No | Yes (No EN found) | Possible undefined: FILTERS.map, Possible undefined: list.map | bo in portal | High | Remove cross-area import; Fix FILTERS.map; Fix list.map |
| `portal/my-rewards/page.tsx` | /v1/portal/coupons, /v1/portal/redemptions/, /v1/portal/redemptions | Loading, Empty, Error, Degraded(QueryState) | None | Typed | Yes (No EN found) | Possible undefined: d.map, Possible undefined: STEPS.map | bo in portal | High | Remove cross-area import; Fix d.map; Fix STEPS.map |
| `portal/rewards/page.tsx` | /v1/portal/catalog, /v1/portal/wallets/, /v1/reward-categories | Empty, Degraded(QueryState) | None | No | Yes (No EN found) | Possible undefined: bands.map, Possible undefined: rewards.map | bo in portal | High | Remove cross-area import; Fix bands.map; Fix rewards.map |
| `portal/rewards/[code]/page.tsx` | /v1/portal/rewards/, queryKey: ["reward"], /v1/portal/redemptions, /v1/reward-categories, queryKey: ["wallet"], /v1/portal/wallets/, /v1/portal/coupons, /v1/portal/redemptions/ | Error, Degraded(QueryState) | None | Typed | Yes (No EN found) | None found | bo in portal | High | Remove cross-area import |
| `portal/join/page.tsx` | /v1/portal/wallets/, /v1/members | Error | None | No | Yes (No EN found) | None found | None | Low | None |

## Findings Summary

- **High**: `portal/page.tsx` - Remove cross-area import; Fix d.map
- **High**: `portal/invite/page.tsx` - Remove cross-area import; Fix invited.map
- **High**: `portal/profile/page.tsx` - Remove cross-area import; Fix scale.map; Fix benefits.map
- **High**: `portal/achievements/page.tsx` - Remove cross-area import; Fix d.map
- **High**: `portal/earn/page.tsx` - Remove cross-area import; Fix d.map
- **High**: `portal/play/page.tsx` - Remove cross-area import; Fix d.map; Fix prizes.map
- **High**: `portal/play/[code]/page.tsx` - Remove cross-area import
- **High**: `portal/leaderboard/page.tsx` - Remove cross-area import; Fix d.map; Fix order.map; Fix rest.map
- **High**: `portal/activity/page.tsx` - Remove cross-area import; Fix FILTERS.map; Fix list.map
- **High**: `portal/my-rewards/page.tsx` - Remove cross-area import; Fix d.map; Fix STEPS.map
- **High**: `portal/rewards/page.tsx` - Remove cross-area import; Fix bands.map; Fix rewards.map
- **High**: `portal/rewards/[code]/page.tsx` - Remove cross-area import
- **Medium**: `backoffice/page.tsx` - Fix points.map
- **Medium**: `backoffice/program/currencies/page.tsx` - Fix sorted.map; Fix currencies.map; Fix filteredMembers.map
- **Medium**: `backoffice/program/tiers/page.tsx` - Add QueryState or custom state handlers; Fix scale.map
- **Medium**: `backoffice/members/page.tsx` - Fix TIERS.map; Fix STATUSES.map; Fix items.map
- **Medium**: `backoffice/governance/approvals/page.tsx` - Fix failed.map; Fix ENTITY_TYPES.map; Fix rows.map
- **Medium**: `backoffice/governance/audit/page.tsx` - Fix options.map; Fix items.map
- **Medium**: `backoffice/game/achievements/page.tsx` - Fix ICON_CHOICES.map; Fix d.map; Fix unlockedBy.map; Fix badges.map; Fix actionTypes.map
- **Medium**: `backoffice/game/leaderboards/page.tsx` - Fix d.map; Fix actionTypes.map; Fix items.map; Fix periods.map
- **Medium**: `backoffice/game/contests/page.tsx` - Fix STATUSES.map
- **Medium**: `backoffice/game/contests/[id]/page.tsx` - Fix INSTANT_STATUSES.map; Fix prizes.map; Fix daily.map
- **Medium**: `backoffice/game/referral/page.tsx` - Fix topReferrers.map; Fix d.map; Fix steps.map; Fix REFERRAL_CAMPAIGNS.map
- **Medium**: `backoffice/campaigns/page.tsx` - Fix STATUSES.map
- **Medium**: `backoffice/observe/live/page.tsx` - Add QueryState or custom state handlers
- **Medium**: `backoffice/observe/traces/page.tsx` - Fix items.map
- **Medium**: `backoffice/observe/inbound/page.tsx` - Fix OUTCOMES.map; Fix errors.map
- **Medium**: `backoffice/observe/dlq/page.tsx` - Fix DLQ_STATUS_TABS.map; Fix DLQ_CONSUMERS.map
- **Medium**: `backoffice/content/page.tsx` - Fix excluded.map; Fix shown.map; Fix PLACEMENTS.map; Fix items.map
- **Medium**: `backoffice/content/[id]/page.tsx` - Fix PLACEMENTS.map; Fix prizes.map; Fix TIERS.map; Fix PORTAL_PAGES.map; Fix options.map; Fix TONES.map
- **Medium**: `backoffice/content/messages/page.tsx` - Add QueryState or custom state handlers
- **Medium**: `backoffice/content/theme/page.tsx` - Fix checks.map; Fix COLOR_KEYS.map
- **Medium**: `backoffice/segments/[id]/page.tsx` - Fix groups.map; Fix errors.map; Fix items.map
- **Medium**: `backoffice/rewards/page.tsx` - Fix TYPES.map; Fix options.map; Fix STATUSES.map; Fix d.map
- **Medium**: `backoffice/rewards/coupons/page.tsx` - Fix COUPON_STATUSES.map; Fix ps.map; Fix rewards.map
- **Medium**: `backoffice/rewards/bands/page.tsx` - Fix sorted.map; Fix bs.map; Fix byStatus.map
- **Medium**: `backoffice/rewards/redemptions/page.tsx` - Fix REDEMPTION_TABS.map
- **Medium**: `backoffice/demo/simulator/page.tsx` - Fix SHORTCUTS.map; Fix results.map
- **Medium**: `backoffice/demo/scenarios/page.tsx` - Fix steps.map; Fix list.map
- **Medium**: `backoffice/demo/console/page.tsx` - Add QueryState or custom state handlers; Fix services.map; Fix RESETTABLE.map; Fix jobLog.map; Fix log.map; Fix JOBS.map
