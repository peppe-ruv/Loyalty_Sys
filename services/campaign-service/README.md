# campaign-service — porta 8083, schema `campaign` (docs/04 §3, docs/servizi/campaign-service.md).

M1.3: motore regole (`CampaignEngine`, classe pura, algoritmo docs/03 §3.5), condizioni
(`data`/`member`/`context`/`history`), effetti `GRANT_POINTS` (`FIXED`, `PER_AMOUNT`) e `MULTIPLIER`,
limiti per membro, calendario, pubblico per tier, `campaign.evaluated` + `evaluation_log`, simulazione
(`POST /v1/campaigns/simulate`), elenco "come guadagnare" del portale. Le campagne con effetti non ancora
supportati (`GRANT_PLAYS`, `ISSUE_COUPON`, `SEND_MESSAGE`, `LOOKUP`, `FROM_FIELD`) restano caricate ma non
valutate (motivo `EFFECT_NOT_SUPPORTED_YET`). Statistiche (M2), moltiplicatori tier a valle nel wallet (M1.4),
esclusività (M3), segmenti (M6), approvazione (M7).
