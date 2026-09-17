# Runbook

| Procedura | Quando | Passi |
| --- | --- | --- |
| Riprocessare la coda di scarto | alert `loyalty.actions.dlq.v1` sopra soglia | 1. Leggere il motivo dagli eventi DLQ. 2. Correggere la fonte o la mappatura identità. 3. Ripubblicare gli eventi corretti su `loyalty.actions.v1` (stessa idempotencyKey: il ledger non duplica). |
| Lag Kafka del rules-engine | lag > 60 s durante un lancio | 1. `kubectl scale deploy/rules-engine --replicas=<n>` (max = partizioni). 2. Verificare i tempi del ledger (p95). 3. Se il ledger è il collo, scalare `ledger` e controllare le connessioni Postgres. |
| Kill switch giocate e riscatti | errori sulle giocate, incidente | `helm upgrade ... --set services.contest-service.replicas=0` ferma le giocate; il BFF risponde 503 e il sito disabilita i pulsanti (RF-54). |
| Ripristino in seconda region (DR) | perdita della region primaria | 1. Ripristinare RDS dal backup continuo (RPO 15 min). 2. `make infra ENV=prod REGION=<dr>` con `dr_region` valorizzata. 3. `make install`. 4. Aggiornare il DNS. Obiettivo RTO 4 h; prova trimestrale. |
| Chiusura concorso e export per il notaio | fine periodo del concorso | 1. Stato `CLOSED` dal backoffice. 2. Export registro giocate + istanti vincenti + premi non assegnati (RF-37). 3. Verifica degli hash con lo script di controllo. 4. Estrazione di recupero alla presenza del funzionario (RF-36). |
| Batch di fine anno programma | 31 dicembre | 1. Prova in staging almeno 30 giorni prima (RF-23). 2. Eseguire verifica tier, scadenza punti, chiusura missioni. 3. Report per Legal. |

- [Osservabilità e BI](osservabilita.md): alert SLO, cosa fare, manutenzione dei cruscotti, escalation.
