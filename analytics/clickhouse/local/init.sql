-- Warehouse analitico del programma (RF-121): ClickHouse alimentato direttamente dai topic Kafka (CloudEvents JSON).
-- Nessun dato anagrafico: solo id loyalty (ADR-012); l'id membro è mantenuto per i conteggi distinti, mai esposto nei cruscotti.
CREATE DATABASE IF NOT EXISTS loyalty;

-- Tabelle sorgente sul motore Kafka: una per topic, formato JSONAsString (l'evento CloudEvents intero in una colonna).
CREATE TABLE IF NOT EXISTS loyalty.kafka_actions (raw String) ENGINE = Kafka
SETTINGS kafka_broker_list = 'kafka:9092', kafka_topic_list = 'loyalty.actions.v1', kafka_group_name = 'clickhouse-actions', kafka_format = 'JSONAsString', kafka_num_consumers = 2, kafka_thread_per_consumer = 1;
CREATE TABLE IF NOT EXISTS loyalty.kafka_movements (raw String) ENGINE = Kafka
SETTINGS kafka_broker_list = 'kafka:9092', kafka_topic_list = 'loyalty.movements.v1', kafka_group_name = 'clickhouse-movements', kafka_format = 'JSONAsString', kafka_num_consumers = 2;
CREATE TABLE IF NOT EXISTS loyalty.kafka_redemptions (raw String) ENGINE = Kafka
SETTINGS kafka_broker_list = 'kafka:9092', kafka_topic_list = 'loyalty.redemptions.v1', kafka_group_name = 'clickhouse-redemptions', kafka_format = 'JSONAsString';
CREATE TABLE IF NOT EXISTS loyalty.kafka_contests (raw String) ENGINE = Kafka
SETTINGS kafka_broker_list = 'kafka:9092', kafka_topic_list = 'loyalty.contests.v1', kafka_group_name = 'clickhouse-contests', kafka_format = 'JSONAsString';
CREATE TABLE IF NOT EXISTS loyalty.kafka_tiers (raw String) ENGINE = Kafka
SETTINGS kafka_broker_list = 'kafka:9092', kafka_topic_list = 'loyalty.tiers.v1', kafka_group_name = 'clickhouse-tiers', kafka_format = 'JSONAsString';
CREATE TABLE IF NOT EXISTS loyalty.kafka_members (raw String) ENGINE = Kafka
SETTINGS kafka_broker_list = 'kafka:9092', kafka_topic_list = 'loyalty.members.v1', kafka_group_name = 'clickhouse-members', kafka_format = 'JSONAsString';
CREATE TABLE IF NOT EXISTS loyalty.kafka_segments (raw String) ENGINE = Kafka
SETTINGS kafka_broker_list = 'kafka:9092', kafka_topic_list = 'loyalty.segments.v1', kafka_group_name = 'clickhouse-segments', kafka_format = 'JSONAsString';

-- Fatti e dimensioni (schema a stella). ReplicatedMergeTree su 3 repliche (ClickHouse Keeper); partizioni mensili; TTL 5 anni.
CREATE TABLE IF NOT EXISTS loyalty.fact_action (
    event_id String, member_id String, action_type LowCardinality(String), source LowCardinality(String), channel LowCardinality(String),
    occurred_at DateTime64(3, 'Europe/Rome'), amount_eur Decimal(12, 2), is_reversal UInt8, idempotency_key String, attributes String
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(occurred_at) ORDER BY (action_type, occurred_at, member_id) TTL toDateTime(occurred_at) + INTERVAL 5 YEAR;

CREATE TABLE IF NOT EXISTS loyalty.fact_movement (
    movement_id String, member_id String, wallet LowCardinality(String), kind LowCardinality(String), amount Int64, reason String,
    action_key String, balance_after Int64, pending_after Int64, occurred_at DateTime64(3, 'Europe/Rome')
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(occurred_at) ORDER BY (wallet, kind, occurred_at, member_id) TTL toDateTime(occurred_at) + INTERVAL 5 YEAR;

CREATE TABLE IF NOT EXISTS loyalty.fact_redemption (
    redemption_id String, member_id String, reward_id String, reward_type LowCardinality(String), status LowCardinality(String), points Int64, occurred_at DateTime64(3, 'Europe/Rome')
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(occurred_at) ORDER BY (reward_id, occurred_at) TTL toDateTime(occurred_at) + INTERVAL 5 YEAR;

CREATE TABLE IF NOT EXISTS loyalty.fact_contest_play (
    play_id String, member_id String, contest_id String, won UInt8, prize_code String, played_at DateTime64(3, 'Europe/Rome')
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(played_at) ORDER BY (contest_id, played_at) TTL toDateTime(played_at) + INTERVAL 5 YEAR;

CREATE TABLE IF NOT EXISTS loyalty.fact_tier_change (
    member_id String, from_tier LowCardinality(String), to_tier LowCardinality(String), reason LowCardinality(String), changed_at DateTime64(3, 'Europe/Rome')
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(changed_at) ORDER BY (changed_at, member_id);

-- Dimensione membro: ultima versione per id (ReplacingMergeTree), senza dati personali
CREATE TABLE IF NOT EXISTS loyalty.dim_member (
    member_id String, status LowCardinality(String), enrolled_at DateTime64(3, 'Europe/Rome'), channel LowCardinality(String),
    referred_by String, labels String, updated_at DateTime64(3, 'Europe/Rome')
) ENGINE = ReplacingMergeTree(updated_at) ORDER BY member_id;

CREATE TABLE IF NOT EXISTS loyalty.fact_segment_membership (
    member_id String, segment_id LowCardinality(String), entered UInt8, at DateTime64(3, 'Europe/Rome')
) ENGINE = MergeTree() PARTITION BY toYYYYMM(at) ORDER BY (segment_id, at);

-- Viste materializzate: da Kafka ai fatti (parsing del CloudEvent: subject = "member:<id>", data = payload).
CREATE MATERIALIZED VIEW IF NOT EXISTS loyalty.mv_actions TO loyalty.fact_action AS
SELECT JSONExtractString(raw, 'id') AS event_id,
       replaceOne(JSONExtractString(raw, 'subject'), 'member:', '') AS member_id,
       JSONExtractString(raw, 'data', 'actionType') AS action_type,
       replaceOne(JSONExtractString(raw, 'source'), 'urn:loyaltyhub:source:', '') AS source,
       JSONExtractString(raw, 'data', 'attributes', 'channel') AS channel,
       parseDateTime64BestEffort(JSONExtractString(raw, 'data', 'occurredAt'), 3, 'Europe/Rome') AS occurred_at,
       toDecimal64OrZero(JSONExtractRaw(raw, 'data', 'attributes', 'amountEur'), 2) AS amount_eur,
       JSONExtractString(raw, 'data', 'reversalOf') != '' AS is_reversal,
       JSONExtractString(raw, 'data', 'idempotencyKey') AS idempotency_key,
       JSONExtractRaw(raw, 'data', 'attributes') AS attributes
FROM loyalty.kafka_actions WHERE JSONExtractString(raw, 'type') = 'io.loyaltyhub.action.v1';

CREATE MATERIALIZED VIEW IF NOT EXISTS loyalty.mv_movements TO loyalty.fact_movement AS
SELECT JSONExtractString(raw, 'data', 'movementId') AS movement_id,
       replaceOne(JSONExtractString(raw, 'subject'), 'member:', '') AS member_id,
       JSONExtractString(raw, 'data', 'currency') AS wallet, JSONExtractString(raw, 'data', 'kind') AS kind,
       JSONExtractInt(raw, 'data', 'amount') AS amount, JSONExtractString(raw, 'data', 'reason') AS reason,
       JSONExtractString(raw, 'data', 'actionKey') AS action_key, JSONExtractInt(raw, 'data', 'balance') AS balance_after,
       JSONExtractInt(raw, 'data', 'pending') AS pending_after,
       parseDateTime64BestEffort(JSONExtractString(raw, 'time'), 3, 'Europe/Rome') AS occurred_at
FROM loyalty.kafka_movements;

CREATE MATERIALIZED VIEW IF NOT EXISTS loyalty.mv_redemptions TO loyalty.fact_redemption AS
SELECT JSONExtractString(raw, 'data', 'redemptionId') AS redemption_id, replaceOne(JSONExtractString(raw, 'subject'), 'member:', '') AS member_id,
       JSONExtractString(raw, 'data', 'rewardId') AS reward_id, JSONExtractString(raw, 'data', 'rewardType') AS reward_type,
       JSONExtractString(raw, 'data', 'status') AS status, JSONExtractInt(raw, 'data', 'points') AS points,
       parseDateTime64BestEffort(JSONExtractString(raw, 'time'), 3, 'Europe/Rome') AS occurred_at
FROM loyalty.kafka_redemptions;

CREATE MATERIALIZED VIEW IF NOT EXISTS loyalty.mv_contests TO loyalty.fact_contest_play AS
SELECT JSONExtractString(raw, 'data', 'playId') AS play_id, replaceOne(JSONExtractString(raw, 'subject'), 'member:', '') AS member_id,
       JSONExtractString(raw, 'data', 'contestId') AS contest_id, JSONExtractBool(raw, 'data', 'won') AS won,
       JSONExtractString(raw, 'data', 'prizeCode') AS prize_code,
       parseDateTime64BestEffort(JSONExtractString(raw, 'time'), 3, 'Europe/Rome') AS played_at
FROM loyalty.kafka_contests;

CREATE MATERIALIZED VIEW IF NOT EXISTS loyalty.mv_tiers TO loyalty.fact_tier_change AS
SELECT replaceOne(JSONExtractString(raw, 'subject'), 'member:', '') AS member_id, JSONExtractString(raw, 'data', 'fromTier') AS from_tier,
       JSONExtractString(raw, 'data', 'toTier') AS to_tier, JSONExtractString(raw, 'data', 'reason') AS reason,
       parseDateTime64BestEffort(JSONExtractString(raw, 'time'), 3, 'Europe/Rome') AS changed_at
FROM loyalty.kafka_tiers;

CREATE MATERIALIZED VIEW IF NOT EXISTS loyalty.mv_members TO loyalty.dim_member AS
SELECT JSONExtractString(raw, 'data', 'id') AS member_id, JSONExtractString(raw, 'data', 'status') AS status,
       parseDateTime64BestEffort(JSONExtractString(raw, 'data', 'enrolledAt'), 3, 'Europe/Rome') AS enrolled_at,
       JSONExtractString(raw, 'data', 'enrollmentChannel') AS channel, JSONExtractString(raw, 'data', 'referredBy') AS referred_by,
       JSONExtractRaw(raw, 'data', 'labels') AS labels, parseDateTime64BestEffort(JSONExtractString(raw, 'time'), 3, 'Europe/Rome') AS updated_at
FROM loyalty.kafka_members;

CREATE MATERIALIZED VIEW IF NOT EXISTS loyalty.mv_segments TO loyalty.fact_segment_membership AS
SELECT replaceOne(JSONExtractString(raw, 'subject'), 'member:', '') AS member_id, JSONExtractString(raw, 'data', 'segmentId') AS segment_id,
       JSONExtractBool(raw, 'data', 'entered') AS entered, parseDateTime64BestEffort(JSONExtractString(raw, 'data', 'at'), 3, 'Europe/Rome') AS at
FROM loyalty.kafka_segments;

-- KPI pronti per Superset (RF-111): metriche standard del programma loyalty.
CREATE VIEW IF NOT EXISTS loyalty.v_kpi_daily AS
SELECT day,
       sumIf(n, metric = 'registered') AS registered_members,
       sumIf(n, metric = 'actions') AS actions,
       sumIf(n, metric = 'transactions') AS transactions,
       sumIf(v, metric = 'transaction_value') AS transaction_value_eur,
       sumIf(n, metric = 'earned') AS units_earned, sumIf(n, metric = 'spent') AS units_spent, sumIf(n, metric = 'expired') AS units_expired,
       sumIf(n, metric = 'redemptions') AS redemptions, sumIf(n, metric = 'plays') AS contest_plays, sumIf(n, metric = 'wins') AS contest_wins
FROM (
    SELECT toDate(enrolled_at) AS day, 'registered' AS metric, toInt64(count()) AS n, toFloat64(0) AS v FROM loyalty.dim_member FINAL WHERE status != 'ANONYMIZED' GROUP BY day
    UNION ALL SELECT toDate(occurred_at), 'actions', toInt64(count()), toFloat64(0) FROM loyalty.fact_action WHERE NOT is_reversal GROUP BY 1
    UNION ALL SELECT toDate(occurred_at), 'transactions', toInt64(count()), toFloat64(sum(amount_eur)) FROM loyalty.fact_action WHERE action_type = 'TRANSACTION' AND NOT is_reversal GROUP BY 1
    UNION ALL SELECT toDate(occurred_at), 'transaction_value', toInt64(0), toFloat64(sum(amount_eur)) FROM loyalty.fact_action WHERE action_type = 'TRANSACTION' AND NOT is_reversal GROUP BY 1
    UNION ALL SELECT toDate(occurred_at), 'earned', toInt64(sum(amount)), toFloat64(0) FROM loyalty.fact_movement WHERE kind = 'EARN' AND wallet = 'PREMIO' GROUP BY 1
    UNION ALL SELECT toDate(occurred_at), 'spent', toInt64(-sum(amount)), toFloat64(0) FROM loyalty.fact_movement WHERE kind = 'SPEND' AND wallet = 'PREMIO' GROUP BY 1
    UNION ALL SELECT toDate(occurred_at), 'expired', toInt64(-sum(amount)), toFloat64(0) FROM loyalty.fact_movement WHERE kind = 'EXPIRY' AND wallet = 'PREMIO' GROUP BY 1
    UNION ALL SELECT toDate(occurred_at), 'redemptions', toInt64(count()), toFloat64(0) FROM loyalty.fact_redemption WHERE status IN ('CONFIRMED', 'DELIVERED') GROUP BY 1
    UNION ALL SELECT toDate(played_at), 'plays', toInt64(count()), toFloat64(0) FROM loyalty.fact_contest_play GROUP BY 1
    UNION ALL SELECT toDate(played_at), 'wins', toInt64(countIf(won)), toFloat64(0) FROM loyalty.fact_contest_play GROUP BY 1
) GROUP BY day;

-- Membri attivi: almeno un'azione negli ultimi N giorni (default 365, RF-108)
CREATE VIEW IF NOT EXISTS loyalty.v_active_members AS
SELECT toDate(now()) AS as_of, uniqExact(member_id) AS active_members FROM loyalty.fact_action WHERE occurred_at >= now() - INTERVAL 365 DAY;

-- Membri per tier (ultimo cambio per membro)
CREATE VIEW IF NOT EXISTS loyalty.v_members_by_tier AS
SELECT to_tier AS tier, count() AS members FROM (SELECT member_id, argMax(to_tier, changed_at) AS to_tier FROM loyalty.fact_tier_change GROUP BY member_id) GROUP BY tier;

-- Passività punti: saldo disponibile aggregato (unità non ancora spese né scadute) e in sospeso
CREATE VIEW IF NOT EXISTS loyalty.v_points_liability AS
SELECT wallet, sum(balance_after) AS outstanding_units, sum(pending_after) AS pending_units
FROM (SELECT member_id, wallet, argMax(balance_after, occurred_at) AS balance_after, argMax(pending_after, occurred_at) AS pending_after FROM loyalty.fact_movement GROUP BY member_id, wallet)
GROUP BY wallet;

-- Coorti di adesione per canale e retention a 30/90 giorni
CREATE VIEW IF NOT EXISTS loyalty.v_cohorts AS
SELECT toStartOfMonth(m.enrolled_at) AS cohort, m.channel, count() AS members,
       countIf(a30.member_id != '') AS active_30d, countIf(a90.member_id != '') AS active_90d
FROM loyalty.dim_member m FINAL
LEFT JOIN (SELECT DISTINCT member_id FROM loyalty.fact_action WHERE occurred_at >= now() - INTERVAL 30 DAY) a30 ON a30.member_id = m.member_id
LEFT JOIN (SELECT DISTINCT member_id FROM loyalty.fact_action WHERE occurred_at >= now() - INTERVAL 90 DAY) a90 ON a90.member_id = m.member_id
GROUP BY cohort, m.channel;

-- Referral: presentatori con almeno un presentato attivo
CREATE VIEW IF NOT EXISTS loyalty.v_referrals AS
SELECT referred_by AS referrer, count() AS referred, countIf(status = 'ACTIVE') AS referred_active FROM loyalty.dim_member FINAL WHERE referred_by != '' GROUP BY referrer;

-- Concorsi: giocate, vincite, tasso e giocatori unici
CREATE VIEW IF NOT EXISTS loyalty.v_contests AS
SELECT contest_id, count() AS plays, countIf(won) AS wins, uniqExact(member_id) AS players, round(countIf(won) / count(), 4) AS win_rate FROM loyalty.fact_contest_play GROUP BY contest_id;
