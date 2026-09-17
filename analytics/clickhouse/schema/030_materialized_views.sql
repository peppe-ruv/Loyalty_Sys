-- Viste materializzate: da Kafka ai fatti (parsing del CloudEvent: subject = "member:<id>", data = payload).
CREATE MATERIALIZED VIEW IF NOT EXISTS loyalty.mv_actions ON CLUSTER '{cluster}' TO loyalty.fact_action AS
SELECT JSONExtractString(raw, 'id') AS event_id,
       replaceOne(JSONExtractString(raw, 'subject'), 'member:', '') AS member_id,
       JSONExtractString(raw, 'data', 'actionType') AS action_type,
       replaceOne(JSONExtractString(raw, 'source'), 'urn:iren:loyalty:source:', '') AS source,
       JSONExtractString(raw, 'data', 'attributes', 'channel') AS channel,
       parseDateTime64BestEffort(JSONExtractString(raw, 'data', 'occurredAt'), 3, 'Europe/Rome') AS occurred_at,
       toDecimal64OrZero(JSONExtractRaw(raw, 'data', 'attributes', 'amountEur'), 2) AS amount_eur,
       JSONExtractString(raw, 'data', 'reversalOf') != '' AS is_reversal,
       JSONExtractString(raw, 'data', 'idempotencyKey') AS idempotency_key,
       JSONExtractRaw(raw, 'data', 'attributes') AS attributes
FROM loyalty.kafka_actions WHERE JSONExtractString(raw, 'type') = 'it.iren.loyalty.action.v1';

CREATE MATERIALIZED VIEW IF NOT EXISTS loyalty.mv_movements ON CLUSTER '{cluster}' TO loyalty.fact_movement AS
SELECT JSONExtractString(raw, 'data', 'movementId') AS movement_id,
       replaceOne(JSONExtractString(raw, 'subject'), 'member:', '') AS member_id,
       JSONExtractString(raw, 'data', 'currency') AS wallet, JSONExtractString(raw, 'data', 'kind') AS kind,
       JSONExtractInt(raw, 'data', 'amount') AS amount, JSONExtractString(raw, 'data', 'reason') AS reason,
       JSONExtractString(raw, 'data', 'actionKey') AS action_key, JSONExtractInt(raw, 'data', 'balance') AS balance_after,
       JSONExtractInt(raw, 'data', 'pending') AS pending_after,
       parseDateTime64BestEffort(JSONExtractString(raw, 'time'), 3, 'Europe/Rome') AS occurred_at
FROM loyalty.kafka_movements;

CREATE MATERIALIZED VIEW IF NOT EXISTS loyalty.mv_redemptions ON CLUSTER '{cluster}' TO loyalty.fact_redemption AS
SELECT JSONExtractString(raw, 'data', 'redemptionId') AS redemption_id, replaceOne(JSONExtractString(raw, 'subject'), 'member:', '') AS member_id,
       JSONExtractString(raw, 'data', 'rewardId') AS reward_id, JSONExtractString(raw, 'data', 'rewardType') AS reward_type,
       JSONExtractString(raw, 'data', 'status') AS status, JSONExtractInt(raw, 'data', 'points') AS points,
       parseDateTime64BestEffort(JSONExtractString(raw, 'time'), 3, 'Europe/Rome') AS occurred_at
FROM loyalty.kafka_redemptions;

CREATE MATERIALIZED VIEW IF NOT EXISTS loyalty.mv_contests ON CLUSTER '{cluster}' TO loyalty.fact_contest_play AS
SELECT JSONExtractString(raw, 'data', 'playId') AS play_id, replaceOne(JSONExtractString(raw, 'subject'), 'member:', '') AS member_id,
       JSONExtractString(raw, 'data', 'contestId') AS contest_id, JSONExtractBool(raw, 'data', 'won') AS won,
       JSONExtractString(raw, 'data', 'prizeCode') AS prize_code,
       parseDateTime64BestEffort(JSONExtractString(raw, 'time'), 3, 'Europe/Rome') AS played_at
FROM loyalty.kafka_contests;

CREATE MATERIALIZED VIEW IF NOT EXISTS loyalty.mv_tiers ON CLUSTER '{cluster}' TO loyalty.fact_tier_change AS
SELECT replaceOne(JSONExtractString(raw, 'subject'), 'member:', '') AS member_id, JSONExtractString(raw, 'data', 'fromTier') AS from_tier,
       JSONExtractString(raw, 'data', 'toTier') AS to_tier, JSONExtractString(raw, 'data', 'reason') AS reason,
       parseDateTime64BestEffort(JSONExtractString(raw, 'time'), 3, 'Europe/Rome') AS changed_at
FROM loyalty.kafka_tiers;

CREATE MATERIALIZED VIEW IF NOT EXISTS loyalty.mv_members ON CLUSTER '{cluster}' TO loyalty.dim_member AS
SELECT JSONExtractString(raw, 'data', 'id') AS member_id, JSONExtractString(raw, 'data', 'status') AS status,
       parseDateTime64BestEffort(JSONExtractString(raw, 'data', 'enrolledAt'), 3, 'Europe/Rome') AS enrolled_at,
       JSONExtractString(raw, 'data', 'enrollmentChannel') AS channel, JSONExtractString(raw, 'data', 'referredBy') AS referred_by,
       JSONExtractRaw(raw, 'data', 'labels') AS labels, parseDateTime64BestEffort(JSONExtractString(raw, 'time'), 3, 'Europe/Rome') AS updated_at
FROM loyalty.kafka_members;

CREATE MATERIALIZED VIEW IF NOT EXISTS loyalty.mv_segments ON CLUSTER '{cluster}' TO loyalty.fact_segment_membership AS
SELECT replaceOne(JSONExtractString(raw, 'subject'), 'member:', '') AS member_id, JSONExtractString(raw, 'data', 'segmentId') AS segment_id,
       JSONExtractBool(raw, 'data', 'entered') AS entered, parseDateTime64BestEffort(JSONExtractString(raw, 'data', 'at'), 3, 'Europe/Rome') AS at
FROM loyalty.kafka_segments;
