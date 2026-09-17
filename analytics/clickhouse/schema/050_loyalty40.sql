-- Loyalty 4.0 (RF-127..RF-135): decisioni, consegne, rischio nel warehouse per spiegabilità, esperimenti e BI.
CREATE TABLE IF NOT EXISTS loyalty.kafka_decisions ON CLUSTER '{cluster}' (raw String) ENGINE = Kafka
SETTINGS kafka_broker_list = '{kafka_bootstrap}', kafka_topic_list = 'loyalty.decisions.v1', kafka_group_name = 'clickhouse-decisions', kafka_format = 'JSONAsString';
CREATE TABLE IF NOT EXISTS loyalty.kafka_deliveries ON CLUSTER '{cluster}' (raw String) ENGINE = Kafka
SETTINGS kafka_broker_list = '{kafka_bootstrap}', kafka_topic_list = 'loyalty.deliveries.v1', kafka_group_name = 'clickhouse-deliveries', kafka_format = 'JSONAsString';
CREATE TABLE IF NOT EXISTS loyalty.kafka_risk ON CLUSTER '{cluster}' (raw String) ENGINE = Kafka
SETTINGS kafka_broker_list = '{kafka_bootstrap}', kafka_topic_list = 'loyalty.risk.v1', kafka_group_name = 'clickhouse-risk', kafka_format = 'JSONAsString';

CREATE TABLE IF NOT EXISTS loyalty.fact_decision ON CLUSTER '{cluster}' (
    decision_id String, member_id String, event_type LowCardinality(String), correlation_id String,
    policy_version LowCardinality(String), experiment_id LowCardinality(String), variant LowCardinality(String),
    action LowCardinality(String), reference String, channel LowCardinality(String), reason String,
    n_actions UInt8, n_rejected UInt8, risk_level LowCardinality(String),
    churn_risk Float32, purchase_propensity Float32, offer_propensity Float32,
    decided_at DateTime64(3, 'Europe/Rome')
) ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/fact_decision', '{replica}')
PARTITION BY toYYYYMM(decided_at) ORDER BY (decided_at, decision_id) TTL toDateTime(decided_at) + INTERVAL 5 YEAR;

CREATE TABLE IF NOT EXISTS loyalty.fact_delivery ON CLUSTER '{cluster}' (
    delivery_id String, member_id String, decision_id String, action LowCardinality(String), reference String,
    channel LowCardinality(String), status LowCardinality(String), occurred_at DateTime64(3, 'Europe/Rome')
) ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/fact_delivery', '{replica}')
PARTITION BY toYYYYMM(occurred_at) ORDER BY (occurred_at, delivery_id) TTL toDateTime(occurred_at) + INTERVAL 5 YEAR;

CREATE TABLE IF NOT EXISTS loyalty.fact_risk ON CLUSTER '{cluster}' (
    member_id String, score UInt8, level LowCardinality(String), previous_level LowCardinality(String),
    top_reason LowCardinality(String), policy_version LowCardinality(String), assessed_at DateTime64(3, 'Europe/Rome')
) ENGINE = ReplicatedMergeTree('/clickhouse/tables/{shard}/fact_risk', '{replica}')
PARTITION BY toYYYYMM(assessed_at) ORDER BY (assessed_at, member_id) TTL toDateTime(assessed_at) + INTERVAL 5 YEAR;

CREATE MATERIALIZED VIEW IF NOT EXISTS loyalty.mv_decisions ON CLUSTER '{cluster}' TO loyalty.fact_decision AS
SELECT JSONExtractString(raw, 'data', 'decisionId') AS decision_id, replaceOne(JSONExtractString(raw, 'subject'), 'member:', '') AS member_id,
       JSONExtractString(raw, 'data', 'eventType') AS event_type, JSONExtractString(raw, 'correlationid') AS correlation_id,
       JSONExtractString(raw, 'data', 'policyVersion') AS policy_version, JSONExtractString(raw, 'data', 'experimentId') AS experiment_id,
       JSONExtractString(raw, 'data', 'variant') AS variant, JSONExtractString(raw, 'data', 'action') AS action,
       JSONExtractString(raw, 'data', 'reference') AS reference, JSONExtractString(raw, 'data', 'channel') AS channel, JSONExtractString(raw, 'data', 'reason') AS reason,
       toUInt8(length(JSONExtractArrayRaw(raw, 'data', 'actions'))) AS n_actions, toUInt8(length(JSONExtractArrayRaw(raw, 'data', 'rejected'))) AS n_rejected,
       JSONExtractString(raw, 'data', 'riskLevel') AS risk_level,
       toFloat32(JSONExtractFloat(raw, 'data', 'predictions', 'churnRisk')) AS churn_risk,
       toFloat32(JSONExtractFloat(raw, 'data', 'predictions', 'purchasePropensity')) AS purchase_propensity,
       toFloat32(JSONExtractFloat(raw, 'data', 'predictions', 'offerPropensity')) AS offer_propensity,
       parseDateTime64BestEffort(JSONExtractString(raw, 'data', 'decidedAt'), 3, 'Europe/Rome') AS decided_at
FROM loyalty.kafka_decisions;

CREATE MATERIALIZED VIEW IF NOT EXISTS loyalty.mv_deliveries ON CLUSTER '{cluster}' TO loyalty.fact_delivery AS
SELECT JSONExtractString(raw, 'data', 'deliveryId') AS delivery_id, replaceOne(JSONExtractString(raw, 'subject'), 'member:', '') AS member_id,
       JSONExtractString(raw, 'data', 'decisionId') AS decision_id, JSONExtractString(raw, 'data', 'action') AS action, JSONExtractString(raw, 'data', 'reference') AS reference,
       JSONExtractString(raw, 'data', 'channel') AS channel, JSONExtractString(raw, 'data', 'status') AS status,
       parseDateTime64BestEffort(JSONExtractString(raw, 'time'), 3, 'Europe/Rome') AS occurred_at
FROM loyalty.kafka_deliveries;

CREATE MATERIALIZED VIEW IF NOT EXISTS loyalty.mv_risk ON CLUSTER '{cluster}' TO loyalty.fact_risk AS
SELECT replaceOne(JSONExtractString(raw, 'subject'), 'member:', '') AS member_id, toUInt8(JSONExtractInt(raw, 'data', 'score')) AS score,
       JSONExtractString(raw, 'data', 'level') AS level, JSONExtractString(raw, 'data', 'previousLevel') AS previous_level,
       JSONExtractString(JSONExtractArrayRaw(raw, 'data', 'reasonCodes')[1]) AS top_reason, JSONExtractString(raw, 'data', 'policyVersion') AS policy_version,
       parseDateTime64BestEffort(JSONExtractString(raw, 'data', 'assessedAt'), 3, 'Europe/Rome') AS assessed_at
FROM loyalty.kafka_risk;

-- KPI: decisioni per azione e variante; metriche incrementali per esperimento (RF-134): offerte presentate → accettate,
-- riscatti nei 7 giorni successivi, per variante vs controllo
CREATE VIEW IF NOT EXISTS loyalty.kpi_decisions_daily ON CLUSTER '{cluster}' AS
SELECT toDate(decided_at) AS day, experiment_id, variant, action, count() AS decisions, avg(churn_risk) AS avg_churn_risk
FROM loyalty.fact_decision GROUP BY day, experiment_id, variant, action;

CREATE VIEW IF NOT EXISTS loyalty.kpi_experiment_uplift ON CLUSTER '{cluster}' AS
WITH presented AS (
    SELECT d.experiment_id, d.variant, d.member_id, d.decision_id, d.decided_at
    FROM loyalty.fact_decision d WHERE d.experiment_id != '' AND d.action IN ('SHOW_OFFER', 'ISSUE_COUPON', 'ISSUE_REWARD', 'SEND_MESSAGE')
), accepted AS (
    SELECT member_id, JSONExtractString(attributes, 'decisionId') AS decision_id FROM loyalty.fact_action WHERE action_type = 'OFFER_ACCEPTED'
), redeemed AS (
    SELECT member_id, occurred_at FROM loyalty.fact_redemption WHERE status = 'CONFIRMED'
)
SELECT p.experiment_id, p.variant,
       uniqExact(p.member_id) AS members, count() AS offers,
       countIf(a.decision_id != '') AS accepted, round(countIf(a.decision_id != '') / count(), 4) AS acceptance_rate,
       uniqExactIf(p.member_id, r.member_id != '') AS members_redeemed_7d, round(uniqExactIf(p.member_id, r.member_id != '') / uniqExact(p.member_id), 4) AS redemption_rate_7d
FROM presented p
LEFT JOIN accepted a ON a.decision_id = p.decision_id
LEFT JOIN redeemed r ON r.member_id = p.member_id AND r.occurred_at BETWEEN p.decided_at AND p.decided_at + INTERVAL 7 DAY
GROUP BY p.experiment_id, p.variant;

CREATE VIEW IF NOT EXISTS loyalty.kpi_rejections_daily ON CLUSTER '{cluster}' AS
SELECT toDate(decided_at) AS day, action, reason, count() AS n FROM loyalty.fact_decision WHERE action = 'NO_ACTION' GROUP BY day, action, reason;

CREATE VIEW IF NOT EXISTS loyalty.kpi_deliveries_daily ON CLUSTER '{cluster}' AS
SELECT toDate(occurred_at) AS day, channel, action, status, count() AS n FROM loyalty.fact_delivery GROUP BY day, channel, action, status;

CREATE VIEW IF NOT EXISTS loyalty.kpi_risk_daily ON CLUSTER '{cluster}' AS
SELECT toDate(assessed_at) AS day, level, top_reason, count() AS assessments, uniqExact(member_id) AS members FROM loyalty.fact_risk GROUP BY day, level, top_reason;
