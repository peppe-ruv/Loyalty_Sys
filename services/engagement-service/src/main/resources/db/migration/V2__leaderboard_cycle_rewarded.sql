-- I premi di fine ciclo venivano assegnati dopo aver già segnato il ciclo come chiuso: un errore a
-- metà elenco lasciava i vincitori successivi senza premio e il ciclo chiuso per sempre. La riga ora
-- è una *prenotazione* (closed_at) e la premiazione si conferma a parte (rewarded_at): i cicli
-- prenotati e non confermati vengono ripresi al giro successivo, e le chiavi di idempotenza dei
-- premi rendono il secondo tentativo innocuo (RF-94).
ALTER TABLE engagementservice.leaderboard_cycle ADD COLUMN rewarded_at TIMESTAMPTZ;
-- I cicli già presenti valgono come premiati: nessuno li riaprirebbe con cognizione.
UPDATE engagementservice.leaderboard_cycle SET rewarded_at = closed_at WHERE rewarded_at IS NULL;
