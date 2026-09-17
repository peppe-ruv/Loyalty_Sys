-- Fatti e dimensioni (schema a stella). ReplicatedMergeTree su 3 repliche (ClickHouse Keeper); partizioni mensili; TTL 5 anni.
CREATE TABLE IF NOT EXISTS loyalty.fact_action ON CLUSTER '{cluster}' (
    event_id String, member_id String, action_type LowCardinality(String), source LowCardinality(String), channel LowCardinality(String),
    occurred_at DateTime64(3, 'Europe/Rome'), amount_eur Decimal(12, 2), is_reversal UInt8, idempotency_key String, attributes String
) ENGINE = ReplicatedMergeTree('/clickhouse/tables/{shard}/fact_action', '{replica}')
PARTITION BY toYYYYMM(occurred_at) ORDER BY (action_type, occurred_at, member_id) TTL toDateTime(occurred_at) + INTERVAL 5 YEAR;

CREATE TABLE IF NOT EXISTS loyalty.fact_movement ON CLUSTER '{cluster}' (
    movement_id String, member_id String, wallet LowCardinality(String), kind LowCardinality(String), amount Int64, reason String,
    action_key String, balance_after Int64, pending_after Int64, occurred_at DateTime64(3, 'Europe/Rome')
) ENGINE = ReplicatedMergeTree('/clickhouse/tables/{shard}/fact_movement', '{replica}')
PARTITION BY toYYYYMM(occurred_at) ORDER BY (wallet, kind, occurred_at, member_id) TTL toDateTime(occurred_at) + INTERVAL 5 YEAR;

CREATE TABLE IF NOT EXISTS loyalty.fact_redemption ON CLUSTER '{cluster}' (
    redemption_id String, member_id String, reward_id String, reward_type LowCardinality(String), status LowCardinality(String), points Int64, occurred_at DateTime64(3, 'Europe/Rome')
) ENGINE = ReplicatedMergeTree('/clickhouse/tables/{shard}/fact_redemption', '{replica}')
PARTITION BY toYYYYMM(occurred_at) ORDER BY (reward_id, occurred_at) TTL toDateTime(occurred_at) + INTERVAL 5 YEAR;

CREATE TABLE IF NOT EXISTS loyalty.fact_contest_play ON CLUSTER '{cluster}' (
    play_id String, member_id String, contest_id String, won UInt8, prize_code String, played_at DateTime64(3, 'Europe/Rome')
) ENGINE = ReplicatedMergeTree('/clickhouse/tables/{shard}/fact_contest_play', '{replica}')
PARTITION BY toYYYYMM(played_at) ORDER BY (contest_id, played_at) TTL toDateTime(played_at) + INTERVAL 5 YEAR;

CREATE TABLE IF NOT EXISTS loyalty.fact_tier_change ON CLUSTER '{cluster}' (
    member_id String, from_tier LowCardinality(String), to_tier LowCardinality(String), reason LowCardinality(String), changed_at DateTime64(3, 'Europe/Rome')
) ENGINE = ReplicatedMergeTree('/clickhouse/tables/{shard}/fact_tier_change', '{replica}')
PARTITION BY toYYYYMM(changed_at) ORDER BY (changed_at, member_id);

-- Dimensione membro: ultima versione per id (ReplacingMergeTree), senza dati personali
CREATE TABLE IF NOT EXISTS loyalty.dim_member ON CLUSTER '{cluster}' (
    member_id String, status LowCardinality(String), enrolled_at DateTime64(3, 'Europe/Rome'), channel LowCardinality(String),
    referred_by String, labels String, updated_at DateTime64(3, 'Europe/Rome')
) ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/dim_member', '{replica}', updated_at) ORDER BY member_id;

CREATE TABLE IF NOT EXISTS loyalty.fact_segment_membership ON CLUSTER '{cluster}' (
    member_id String, segment_id LowCardinality(String), entered UInt8, at DateTime64(3, 'Europe/Rome')
) ENGINE = ReplicatedMergeTree('/clickhouse/tables/{shard}/fact_segment_membership', '{replica}') PARTITION BY toYYYYMM(at) ORDER BY (segment_id, at);
