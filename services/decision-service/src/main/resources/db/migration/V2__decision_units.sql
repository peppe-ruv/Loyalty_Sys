-- Budget giornaliero di unità (RF-128): per applicarlo serve sapere quante unità le azioni arbitrate hanno già
-- concesso oggi. Il decision log lo sa per decisione ma non lo teneva in colonna: senza, il tetto restava un campo
-- di configurazione che nessuno leggeva.
ALTER TABLE decisionservice.decision_action
    ADD COLUMN wallet VARCHAR(32),
    ADD COLUMN units  BIGINT;

-- Somma delle unità concesse nella giornata: indice sulle sole righe che contano (azioni arbitrate con unità).
CREATE INDEX decision_action_units_idx ON decisionservice.decision_action (decided_at)
    WHERE units IS NOT NULL AND action NOT IN ('AWARD_POINTS', 'UPGRADE_TIER', 'GRANT_BADGE', 'SET_ATTRIBUTE', 'EMIT_EVENT');
