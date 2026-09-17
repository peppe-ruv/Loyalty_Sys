-- KPI pronti per Superset (RF-111): metriche standard del programma loyalty.
CREATE VIEW IF NOT EXISTS loyalty.v_kpi_daily ON CLUSTER '{cluster}' AS
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
CREATE VIEW IF NOT EXISTS loyalty.v_active_members ON CLUSTER '{cluster}' AS
SELECT toDate(now()) AS as_of, uniqExact(member_id) AS active_members FROM loyalty.fact_action WHERE occurred_at >= now() - INTERVAL 365 DAY;

-- Membri per tier (ultimo cambio per membro)
CREATE VIEW IF NOT EXISTS loyalty.v_members_by_tier ON CLUSTER '{cluster}' AS
SELECT to_tier AS tier, count() AS members FROM (SELECT member_id, argMax(to_tier, changed_at) AS to_tier FROM loyalty.fact_tier_change GROUP BY member_id) GROUP BY tier;

-- Passività punti: saldo disponibile aggregato (unità non ancora spese né scadute) e in sospeso
CREATE VIEW IF NOT EXISTS loyalty.v_points_liability ON CLUSTER '{cluster}' AS
SELECT wallet, sum(balance_after) AS outstanding_units, sum(pending_after) AS pending_units
FROM (SELECT member_id, wallet, argMax(balance_after, occurred_at) AS balance_after, argMax(pending_after, occurred_at) AS pending_after FROM loyalty.fact_movement GROUP BY member_id, wallet)
GROUP BY wallet;

-- Coorti di adesione per canale e retention a 30/90 giorni
CREATE VIEW IF NOT EXISTS loyalty.v_cohorts ON CLUSTER '{cluster}' AS
SELECT toStartOfMonth(m.enrolled_at) AS cohort, m.channel, count() AS members,
       countIf(a30.member_id != '') AS active_30d, countIf(a90.member_id != '') AS active_90d
FROM loyalty.dim_member m FINAL
LEFT JOIN (SELECT DISTINCT member_id FROM loyalty.fact_action WHERE occurred_at >= now() - INTERVAL 30 DAY) a30 ON a30.member_id = m.member_id
LEFT JOIN (SELECT DISTINCT member_id FROM loyalty.fact_action WHERE occurred_at >= now() - INTERVAL 90 DAY) a90 ON a90.member_id = m.member_id
GROUP BY cohort, m.channel;

-- Referral: presentatori con almeno un presentato attivo
CREATE VIEW IF NOT EXISTS loyalty.v_referrals ON CLUSTER '{cluster}' AS
SELECT referred_by AS referrer, count() AS referred, countIf(status = 'ACTIVE') AS referred_active FROM loyalty.dim_member FINAL WHERE referred_by != '' GROUP BY referrer;

-- Concorsi: giocate, vincite, tasso e giocatori unici
CREATE VIEW IF NOT EXISTS loyalty.v_contests ON CLUSTER '{cluster}' AS
SELECT contest_id, count() AS plays, countIf(won) AS wins, uniqExact(member_id) AS players, round(countIf(won) / count(), 4) AS win_rate FROM loyalty.fact_contest_play GROUP BY contest_id;
