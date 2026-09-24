# Matrice di Tracciabilità - Loyalty Hub

Questo documento fornisce un controllo indipendente dello stato riportato in `docs/14-STATO-AVANZAMENTO.md` confrontandolo con le evidenze nel codice (implementazione e test).

## Riepilogo
- **Coperto**: 42
- **Implementato senza test**: 67
- **Parziale**: 0
- **Mancante**: 3
- **Ticked ma non trovato**: 45

### I Gap Più Importanti (Top 10)
- `BO-03` (M1): implementato senza test
- `BO-05` (M1): implementato senza test
- `BO-09` (M1): implementato senza test
- `BO-26` (M1): implementato senza test
- `BO-28` (M1): implementato senza test
- `BO-30` (M1): implementato senza test
- `F-CMP-01` (M1): implementato senza test
- `F-CMP-02` (M1): ticked ma non trovato
- `F-CMP-03` (M1): ticked ma non trovato
- `F-CMP-04` (M1): ticked ma non trovato

---

## M1

| ID | Descrizione | Stato in docs/14 | Evidenza Implementazione | Evidenza Test | Verdetto |
|---|---|---|---|---|---|
| `BO-02` | Membri | spuntato | web/app/backoffice/members/page.tsx | nav.test::BO-04 Segmenti vive nel gruppo Clienti, nav.test::la dashboard è attiva solo sulla radice del backoffice, nav.test::BO-27 DLQ è in Osservabilità da M7, nav.test::sceglie la voce più specifica, nav.test::nessuna voce fuori dal backoffice | coperto |
| `BO-03` | Scheda 360° | spuntato | web/components/bo/members/MemberAttributesCard.tsx | nessun test | implementato senza test |
| `BO-05` | Campagne | spuntato | web/components/bo/campaigns/CampaignStatsPanel.tsx | nessun test | implementato senza test |
| `BO-06` | Editor campagna | spuntato | web/components/bo/GeneratedSentence.tsx | ConditionBuilder.test::mostra i tipi abilitati per categoria, con la pill custom, e li seleziona, conditions.test::enum su entrambi → unione; tipi incompatibili → string, conditions.test::limita i gruppi a 3 livelli, conditions.test::attributi non segnalati se il catalogo dei membri non è disponibile, conditions.test::offre solo i comparatori coerenti col tipo, ConditionBuilder.test::degraded: con ingestion addormentato torna al campo di testo, conditions.test::cambio di comparatore: scalare ⇄ elenco ⇄ intervallo senza perdere il dato, conditions.test::un percorso fuori catalogo resta accettato come campo libero, ConditionBuilder.test::modifica il valore mantenendo i numeri come numeri, conditions.test::segnala valori mancanti o del tipo sbagliato, conditions.test::etichette in italiano, con le forme per le date, conditions.test::aggiunge, aggiorna e toglie nodi in modo immutabile, conditions.test::ignora i trigger non ancora caricati; nessun trigger → nessun campo, conditions.test::andata e ritorno senza perdite, numeri come numeri, conditions.test::cmp assente → eq, ConditionBuilder.test::con più trigger avvisa sui campi non comuni senza toglierli, conditions.test::tier di riserva quando wallet non risponde, conditions.test::contiene i quattro spazi nell, conditions.test::ricerca per etichetta o percorso, conditions.test::converte testo in numeri, conditions.test::mappa i tipi dello schema sui tipi del costruttore, conditions.test::attributi custom → member.attributes.<key>, opzioni ⇒ enum, conditions.test::errori di struttura con messaggio, albero null, conditions.test::contesto e storico, ConditionBuilder.test::aggiunge gruppi fino a 3 livelli, conditions.test::una foglia alla radice viene avvolta in TUTTE, conditions.test::un solo trigger: tutti i suoi campi, conditions.test::campo non comune a tutti i trigger: avviso col trigger mancante, campo mantenuto, ConditionBuilder.test::combobox raggruppata per spazio con attributi custom e percorso libero, conditions.test::enum solo da una parte → niente enum; format solo se uguale, ConditionBuilder.test::?, conditions.test::vuoto/null → gruppo TUTTE vuoto, e ritorno a null, conditions.test::MAX_DEPTH è 3, conditions.test::più trigger: solo i percorsi comuni, number+integer → number, required solo se ovunque, conditions.test::cambio di campo: tiene comparatore e valore solo se compatibili, conditions.test::toglie i gruppi vuoti e il valore per exists/nexists, conditions.test::rifiuta più di 3 livelli di gruppi, conditions.test::validateTree mappa id → problema su tutto l, conditions.test::membro: tier dai codici, stato, segmenti con nome, etichette, numeri, conditions.test::foglie valide, conditions.test::nessun avviso sui data.* finché i campi non sono arrivati, conditions.test::forma del valore per comparatore, conditions.test::la frase generata rilegge lo stesso JSON, conditions.test::elenchi separati da virgola, senza vuoti né doppioni, conditions.test::campi data.* col tipo giusto e l, conditions.test::nuova foglia: comparatore e valore di default del tipo | coperto |
| `BO-09` | Azioni e fonti | spuntato | web/components/bo/campaigns/TriggerPicker.tsx | nessun test | implementato senza test |
| `BO-26` | Monitor ingressi | spuntato | web/app/backoffice/observe/inbound/page.tsx | nessun test | implementato senza test |
| `BO-28` | Simulatore eventi | spuntato | web/components/portal/InboxBell.tsx | nessun test | implementato senza test |
| `BO-30` | Console demo | spuntato | web/app/backoffice/demo/console/page.tsx | nessun test | implementato senza test |
| `F-CMP-01` | CRUD campagne | spuntato | .../api/CampaignsController.java | nessun test | implementato senza test |
| `F-CMP-02` | Ciclo di vita | spuntato | non trovato | nessun test | ticked ma non trovato |
| `F-CMP-03` | Costruttore condizioni | spuntato | non trovato | nessun test | ticked ma non trovato |
| `F-CMP-04` | Effetti | spuntato | non trovato | nessun test | ticked ma non trovato |
| `F-CMP-05` | Limiti | spuntato | non trovato | nessun test | ticked ma non trovato |
| `F-CMP-06` | Pubblico | spuntato | .../messaging/MemberSnapshotHandler.java | CampaignServiceIT::surveyGrantsPointsAndAPlay, CampaignServiceIT::listReturnsSeededCampaigns, CampaignServiceIT::duplicateActionProducesEffectsOnce, CampaignServiceIT::campaignAboveBudgetNeedsLegalApproval, CampaignServiceIT::segmentFactsDriveTheSegmentAudience, CampaignServiceIT::portalListsEarnRulesForMember, CampaignServiceIT::statsReflectMatchesAndDailySeries, CampaignServiceIT::liveCampaignAcceptsOnlySafeFieldsOtherwise409, CampaignServiceIT::purchaseWeekdaySilverGrantsBaseAndSts, CampaignServiceIT::purchaseWeekendAppliesCampaignMultiplierToPts, CampaignServiceIT::birthdayGrantsPointsAndSendsMessageEffect, CampaignServiceIT::appLoginDailyLimitSkipsSecondSameDay, CampaignServiceIT::simulateTierUpgradedWithLookupCampaign, CampaignServiceIT::portalByCodesIncludesHiddenReferralCampaigns, CampaignServiceIT::simulateWeekendPurchaseWithoutWriting, CampaignServiceIT::duplicateCopiesIntoDraftAndStaleVersionIs409, CampaignServiceIT::validateRequiresTemplateCodeForSendMessage | coperto |
| `F-CMP-08` | Simulazione | spuntato | non trovato | nessun test | ticked ma non trovato |
| `F-CMP-09` | Registro valutazioni | spuntato | non trovato | nessun test | ticked ma non trovato |
| `F-CMP-11` | "Come guadagnare" | spuntato | non trovato | nessun test | ticked ma non trovato |
| `F-DEMO-01` | Demo Hub | non spuntato | non trovato | nessun test | mancante |
| `F-DEMO-03` | Simulatore eventi | spuntato | .../api/SimulatorController.java | nessun test | implementato senza test |
| `F-DEMO-05` | Reset dati | spuntato | non trovato | nessun test | ticked ma non trovato |
| `F-DEMO-07` | Keep-alive gentile | non spuntato | non trovato | nessun test | mancante |
| `F-ING-01` | Ricezione CloudEvents | spuntato | non trovato | nessun test | ticked ma non trovato |
| `F-ING-02` | Deduplica | spuntato | non trovato | nessun test | ticked ma non trovato |
| `F-ING-03` | Risoluzione membro | spuntato | non trovato | nessun test | ticked ma non trovato |
| `F-ING-05` | Registro fonti | spuntato | non trovato | nessun test | ticked ma non trovato |
| `F-ING-06` | Tipi azione e schemi | spuntato | .../messaging/CampaignEvaluationHandler.java | EventTypesIT::systemTypesOnlyChangeLabelsAndOnlyByAdmin, EventTypesIT::fieldsAreFlattenedFromTheSchema, EventTypesIT::customTypeIsUsableRightAwayAndItsSchemaChangesAtRuntime, EventTypesIT::invalidCustomTypesAreRejectedWithFieldErrors | coperto |
| `F-ING-09` | Monitor ingressi | spuntato | .../api/InboundEventsController.java | InboundResolutionTest::autoMatchKeysSkipMissingReferences, UnmatchedResolutionIT::memberRegisteredAutoMatchesParkedEmailEventsOnceEvenUnderRedelivery, InboundResolutionTest::matchOnlyForUnmatched, InboundResolutionTest::retryOnlyForRejectedAndUnmatched, InboundResolutionTest::unknownStatusParsesToNull, UnmatchedResolutionIT::retryOfAnUnmatchedWithoutNewMemberStaysUnmatched, UnmatchedResolutionIT::manualMatchPublishesOneActionFlipsStatusAndIsAudited, InboundResolutionTest::autoMatchKeysFollowThePipelineResolution, UnmatchedResolutionIT::matchRequiresAnExistingActiveMember, UnmatchedResolutionIT::retryOfSourceDisabledRejectionPublishesOnceAfterEnablingTheSource, UnmatchedResolutionIT::autoMatchIgnoresMembersThatAreNotActive | coperto |
| `F-MBR-01` | Anagrafica membro | spuntato | non trovato | nessun test | ticked ma non trovato |
| `F-MBR-02` | Scheda 360° | spuntato | non trovato | nessun test | ticked ma non trovato |
| `F-MBR-04` | Stati del membro | spuntato | non trovato | nessun test | ticked ma non trovato |
| `F-WAL-01` | Doppia valuta | spuntato | non trovato | nessun test | ticked ma non trovato |
| `F-WAL-02` | Libro mastro | spuntato | non trovato | nessun test | ticked ma non trovato |
| `PT-01` | Schermata PT-01 | spuntato | web/components/portal/PopupHost.tsx | nessun test | implementato senza test |
| `PT-02` | Schermata PT-02 | spuntato | web/app/portal/earn/page.tsx | nessun test | implementato senza test |
| `PT-07` | Schermata PT-07 | spuntato | web/app/portal/activity/page.tsx | nessun test | implementato senza test |
| `PT-14` | Schermata PT-14 | spuntato | web/components/portal/PortalShell.tsx | nessun test | implementato senza test |

## M2

| ID | Descrizione | Stato in docs/14 | Evidenza Implementazione | Evidenza Test | Verdetto |
|---|---|---|---|---|---|
| `BO-01` | Dashboard | spuntato | web/components/bo/dashboard/SourceBars.tsx | nessun test | implementato senza test |
| `BO-05` | Campagne | spuntato | web/components/bo/campaigns/CampaignStatsPanel.tsx | nessun test | implementato senza test |
| `BO-06` | Editor campagna | spuntato | web/components/bo/GeneratedSentence.tsx | ConditionBuilder.test::mostra i tipi abilitati per categoria, con la pill custom, e li seleziona, conditions.test::enum su entrambi → unione; tipi incompatibili → string, conditions.test::limita i gruppi a 3 livelli, conditions.test::attributi non segnalati se il catalogo dei membri non è disponibile, conditions.test::offre solo i comparatori coerenti col tipo, ConditionBuilder.test::degraded: con ingestion addormentato torna al campo di testo, conditions.test::cambio di comparatore: scalare ⇄ elenco ⇄ intervallo senza perdere il dato, conditions.test::un percorso fuori catalogo resta accettato come campo libero, ConditionBuilder.test::modifica il valore mantenendo i numeri come numeri, conditions.test::segnala valori mancanti o del tipo sbagliato, conditions.test::etichette in italiano, con le forme per le date, conditions.test::aggiunge, aggiorna e toglie nodi in modo immutabile, conditions.test::ignora i trigger non ancora caricati; nessun trigger → nessun campo, conditions.test::andata e ritorno senza perdite, numeri come numeri, conditions.test::cmp assente → eq, ConditionBuilder.test::con più trigger avvisa sui campi non comuni senza toglierli, conditions.test::tier di riserva quando wallet non risponde, conditions.test::contiene i quattro spazi nell, conditions.test::ricerca per etichetta o percorso, conditions.test::converte testo in numeri, conditions.test::mappa i tipi dello schema sui tipi del costruttore, conditions.test::attributi custom → member.attributes.<key>, opzioni ⇒ enum, conditions.test::errori di struttura con messaggio, albero null, conditions.test::contesto e storico, ConditionBuilder.test::aggiunge gruppi fino a 3 livelli, conditions.test::una foglia alla radice viene avvolta in TUTTE, conditions.test::un solo trigger: tutti i suoi campi, conditions.test::campo non comune a tutti i trigger: avviso col trigger mancante, campo mantenuto, ConditionBuilder.test::combobox raggruppata per spazio con attributi custom e percorso libero, conditions.test::enum solo da una parte → niente enum; format solo se uguale, ConditionBuilder.test::?, conditions.test::vuoto/null → gruppo TUTTE vuoto, e ritorno a null, conditions.test::MAX_DEPTH è 3, conditions.test::più trigger: solo i percorsi comuni, number+integer → number, required solo se ovunque, conditions.test::cambio di campo: tiene comparatore e valore solo se compatibili, conditions.test::toglie i gruppi vuoti e il valore per exists/nexists, conditions.test::rifiuta più di 3 livelli di gruppi, conditions.test::validateTree mappa id → problema su tutto l, conditions.test::membro: tier dai codici, stato, segmenti con nome, etichette, numeri, conditions.test::foglie valide, conditions.test::nessun avviso sui data.* finché i campi non sono arrivati, conditions.test::forma del valore per comparatore, conditions.test::la frase generata rilegge lo stesso JSON, conditions.test::elenchi separati da virgola, senza vuoti né doppioni, conditions.test::campi data.* col tipo giusto e l, conditions.test::nuova foglia: comparatore e valore di default del tipo | coperto |
| `BO-22` | Audit | spuntato | web/components/bo/audit/DiffView.tsx | nessun test | implementato senza test |
| `BO-24` | Flusso live | spuntato | web/components/observe/TopicDot.tsx | nessun test | implementato senza test |
| `BO-25` | Tracciati | spuntato | web/components/observe/TraceWaterfall.tsx | nessun test | implementato senza test |
| `BO-26` | Monitor ingressi | spuntato | web/app/backoffice/observe/inbound/page.tsx | nessun test | implementato senza test |
| `BO-29` | Scenari | spuntato | web/app/backoffice/observe/inbound/page.tsx | nessun test | implementato senza test |
| `F-AUD-01` | Audit log | spuntato | .../application/InboundResolutionService.java | nessun test | implementato senza test |
| `F-CMP-10` | Statistiche campagna | spuntato | .../api/CampaignSummary.java | CampaignServiceIT::surveyGrantsPointsAndAPlay, CampaignServiceIT::listReturnsSeededCampaigns, CampaignServiceIT::duplicateActionProducesEffectsOnce, CampaignServiceIT::campaignAboveBudgetNeedsLegalApproval, CampaignServiceIT::segmentFactsDriveTheSegmentAudience, CampaignServiceIT::portalListsEarnRulesForMember, CampaignServiceIT::statsReflectMatchesAndDailySeries, CampaignServiceIT::liveCampaignAcceptsOnlySafeFieldsOtherwise409, CampaignServiceIT::purchaseWeekdaySilverGrantsBaseAndSts, CampaignServiceIT::purchaseWeekendAppliesCampaignMultiplierToPts, CampaignServiceIT::birthdayGrantsPointsAndSendsMessageEffect, CampaignServiceIT::appLoginDailyLimitSkipsSecondSameDay, CampaignServiceIT::simulateTierUpgradedWithLookupCampaign, CampaignServiceIT::portalByCodesIncludesHiddenReferralCampaigns, CampaignServiceIT::simulateWeekendPurchaseWithoutWriting, CampaignServiceIT::duplicateCopiesIntoDraftAndStaleVersionIs409, CampaignServiceIT::validateRequiresTemplateCodeForSendMessage | coperto |
| `F-DEMO-04` | Scenari guidati | spuntato | .../api/ScenariosController.java | nessun test | implementato senza test |
| `F-INS-01` | Flusso eventi live | spuntato | non trovato | nessun test | ticked ma non trovato |
| `F-INS-02` | Tracciato | spuntato | non trovato | nessun test | ticked ma non trovato |
| `F-INS-03` | KPI e serie storiche | spuntato | non trovato | nessun test | ticked ma non trovato |
| `F-INS-04` | Storico sintetico | spuntato | .../demo/InsightSyntheticSeeder.java | InsightServiceIT::MBR-000007, InsightServiceIT::traceOutcomeReadsTierChangeFromTheFactContract, InsightServiceIT::kpiOverviewHasBaselineAndDeltas, InsightServiceIT::buildsTraceTreeWithOutcome, InsightServiceIT::recordsAuditEntryFromAuditTopic, InsightServiceIT::demoResetClearsTheStore, InsightServiceIT::syntheticHistoryFillsNinetyDaysOfKpis, InsightServiceIT::streamDeliversLiveEventOverSse, InsightServiceIT::storesEventsFromAllTopicsWithFamilyAndIsIdempotent, InsightServiceIT::kpiBreakdownSplitsActionsBySource | coperto |
| `F-INS-06` | Stato pipeline | spuntato | non trovato | nessun test | ticked ma non trovato |

## M3

| ID | Descrizione | Stato in docs/14 | Evidenza Implementazione | Evidenza Test | Verdetto |
|---|---|---|---|---|---|
| `BO-03` | Scheda 360° | spuntato | web/components/bo/members/MemberAttributesCard.tsx | nessun test | implementato senza test |
| `BO-07` | Livelli | spuntato | web/app/backoffice/program/tiers/page.tsx | nessun test | implementato senza test |
| `BO-08` | Valute ed edizioni | spuntato | web/components/bo/LiabilityColumns.tsx | nessun test | implementato senza test |
| `BO-09` | Azioni e fonti | spuntato | web/components/bo/campaigns/TriggerPicker.tsx | nessun test | implementato senza test |
| `BO-30` | Console demo | spuntato | web/app/backoffice/demo/console/page.tsx | nessun test | implementato senza test |
| `F-CMP-07` | Cumulabilità | spuntato | non trovato | nessun test | ticked ma non trovato |
| `F-DEMO-06` | Job su richiesta | spuntato | non trovato | nessun test | ticked ma non trovato |
| `F-ING-07` | Transazioni d'acquisto | spuntato | .../api/TransactionsController.java | IngestionPipelineIT::unknownSourceIsRejected, IngestionPipelineIT::unknownMemberIsUnmatched, IngestionPipelineIT::transactionWithMissingFieldsIs400AndUnknownMemberIsUnmatched, IngestionPipelineIT::memberResolvedByExternalId, IngestionPipelineIT::transactionBecomesPurchaseCompletedWithTxnIdAndIsDeduplicated, IngestionPipelineIT::sameSourceAndIdTwiceIsDuplicateWithSingleRecord, IngestionPipelineIT::demoResetIsExposedAndReloadsSeed, IngestionPipelineIT::transactionReturnBecomesPurchaseReturned, IngestionPipelineIT::futureTimeIsRejected, IngestionPipelineIT::blockedMemberIsRejected, IngestionPipelineIT::validEventIsAcceptedAndPublishedWithMemberKey, IngestionPipelineIT::reprocessHeaderRepublishesTheAcceptedActionWithTheSameIdOnlyForAdmin, IngestionPipelineIT::malformedEventIsBadRequest, IngestionPipelineIT::typeNotAllowedForSourceIsRejected, IngestionPipelineIT::unknownTypeIsRejected, IngestionPipelineIT::scenarioRunsThroughPipelineWithExpectedOutcomes, IngestionPipelineIT::invalidDataIsRejectedWithSchemaDetail | coperto |
| `F-ING-08` | Ponte azioni interne | spuntato | non trovato | nessun test | ticked ma non trovato |
| `F-TIER-01` | Definizione livelli | spuntato | .../api/TiersController.java | nessun test | implementato senza test |
| `F-TIER-02` | Salita immediata | spuntato | .../application/WalletService.java | nessun test | implementato senza test |
| `F-TIER-03` | Moltiplicatore di livello | spuntato | non trovato | nessun test | ticked ma non trovato |
| `F-TIER-04` | Chiusura edizione con discesa morbida | spuntato | .../application/EditionCloseBatchService.java | EditionCloseConcurrencyIT::concurrentClosesAreSerializedAndSeeInFlightStsGrant | coperto |
| `F-TIER-05` | Anteprima chiusura | spuntato | non trovato | nessun test | ticked ma non trovato |
| `F-TIER-06` | Storico livelli | spuntato | .../infra/TierHistoryRepository.java | nessun test | implementato senza test |
| `F-WAL-03` | Lotti e scadenza | spuntato | non trovato | nessun test | ticked ma non trovato |
| `F-WAL-05` | Punti in attesa | spuntato | .../application/WalletService.java | nessun test | implementato senza test |
| `F-WAL-06` | Scadenza | spuntato | .../application/WalletService.java | nessun test | implementato senza test |
| `F-WAL-07` | Rettifiche manuali | spuntato | non trovato | nessun test | ticked ma non trovato |
| `F-WAL-09` | Passività | spuntato | .../api/LiabilityController.java | nessun test | implementato senza test |
| `PT-08` | Schermata PT-08 | spuntato | web/components/portal/profile/ProfileForm.tsx | nessun test | implementato senza test |

## M4

| ID | Descrizione | Stato in docs/14 | Evidenza Implementazione | Evidenza Test | Verdetto |
|---|---|---|---|---|---|
| `BO-10` | Catalogo | spuntato | web/components/bo/rewards/RewardForm.tsx | nessun test | implementato senza test |
| `BO-12` | Coupon | spuntato | web/components/bo/primitives.tsx | nessun test | implementato senza test |
| `BO-13` | Richieste premio | spuntato | web/components/bo/primitives.tsx | nessun test | implementato senza test |
| `BO-25` | Tracciati | spuntato | web/components/observe/TraceWaterfall.tsx | nessun test | implementato senza test |
| `F-CPN-01` | Pool di coupon | spuntato | .../api/CouponController.java | CouponIT::seededPoolsAreDeterministicAndLinkedToTheirRewards, CouponIT::emptyPoolSendsTheEffectToTheDlqWithoutRetries, CouponIT::availableCodesCannotBeUsedButCanBeVoidedAndIssuedOnesExpire, CouponIT::couponIssueEffectIsIdempotentAndTheTillUsesItOnce, CouponIT::generateAndImportRespectLimitsAndRoles | coperto |
| `F-CPN-02` | Emissione | spuntato | non trovato | nessun test | ticked ma non trovato |
| `F-CPN-03` | Utilizzo | spuntato | .../application/CouponService.java | nessun test | implementato senza test |
| `F-RWD-01` | Catalogo premi | spuntato | non trovato | nessun test | ticked ma non trovato |
| `F-RWD-02` | Fasce premi | spuntato | non trovato | nessun test | ticked ma non trovato |
| `F-RWD-03` | Disponibilità | spuntato | non trovato | nessun test | ticked ma non trovato |
| `F-RWD-04` | Visibilità | spuntato | .../messaging/MemberSnapshotHandler.java | RewardServiceIT::segmentFactsShowAndHideSegmentRewards, RewardServiceIT::bandsKeepUniqueIncreasingThresholdsAndCannotBeDeletedInUse, RewardServiceIT::seededCatalogHasBandsAndRewards, RewardServiceIT::portalCatalogAppliesTierSegmentStatusAndStockRules, RewardServiceIT::tierFactUpdatesTheSnapshotAndUnlocksRewards, RewardServiceIT::rewardLifecycleAndLiveLock | coperto |
| `F-RWD-05` | Richiesta premio | spuntato | .../domain/Redemption.java | RedemptionIT::lastUnitGoesToExactlyOneOfTwoConcurrentRequests, RedemptionIT::memberCancelsWhilePendingAndManualOrInstantRewardsBehave, RedemptionIT::seededHistoryHasTheDemoStories, RedemptionIT::cancelWithRefundRestoresStockAndAsksTheWalletToRefund, RedemptionIT::careFulfilsAManualRequestWithNoteAndTracking, RedemptionIT::timeoutRejectsAndALateSpendIsCompensatedWithARefund, RedemptionIT::walletRejectionRejectsAndRestoresStock, RedemptionIT::immediateValidationsAnswer422WithoutEvents, RedemptionIT::retryFulfilmentIssuesTheCouponOfARequestNeedingAttention, RedemptionIT::couponRewardIsConfirmedAndFulfilledWhenTheWalletSpends | coperto |
| `F-RWD-06` | Evasione | spuntato | .../application/RedemptionService.java | nessun test | implementato senza test |
| `F-RWD-07` | Annullamento con rimborso | spuntato | .../application/RedemptionService.java | nessun test | implementato senza test |
| `F-RWD-08` | Ciclo di vita premio | spuntato | non trovato | nessun test | ticked ma non trovato |
| `F-WAL-04` | Spesa FIFO | spuntato | .../infra/PointsLotRepository.java | WalletRedemptionIT::spendConsumesLotsByExpiryAndRefundRestoresThem, WalletRedemptionIT::cancellationWithoutRefundOrWithoutSpendChangesNothing, WalletRedemptionIT::insufficientBalanceOrInactiveMemberIsRejectedWithoutMovements | coperto |
| `F-WAL-08` | Saga di spesa | spuntato | .../application/RedemptionPayments.java | WalletRedemptionIT::spendConsumesLotsByExpiryAndRefundRestoresThem, WalletRedemptionIT::cancellationWithoutRefundOrWithoutSpendChangesNothing, WalletRedemptionIT::insufficientBalanceOrInactiveMemberIsRejectedWithoutMovements | coperto |
| `PT-03` | Schermata PT-03 | spuntato | web/components/portal/ContentSlot.tsx | nessun test | implementato senza test |
| `PT-04` | Schermata PT-04 | spuntato | web/app/portal/rewards/[code]/page.tsx | nessun test | implementato senza test |
| `PT-13` | Schermata PT-13 | spuntato | web/app/portal/play/[code]/page.tsx | nessun test | implementato senza test |

## M5

| ID | Descrizione | Stato in docs/14 | Evidenza Implementazione | Evidenza Test | Verdetto |
|---|---|---|---|---|---|
| `BO-14` | Concorsi | spuntato | web/components/bo/game/PrizeEditor.tsx | page, contests::-, page.tsx, ContestSetupForm | coperto |
| `BO-15` | Obiettivi e badge | spuntato | web/app/backoffice/game/achievements/page.tsx | nessun test | implementato senza test |
| `BO-16` | Classifiche | spuntato | web/app/backoffice/game/leaderboards/page.tsx | nessun test | implementato senza test |
| `BO-17` | Referral | spuntato | web/app/backoffice/game/referral/page.tsx | nessun test | implementato senza test |
| `F-ACH-01` | Obiettivi | spuntato | .../domain/Achievement.java | AchievementIT::badgeEffectIsIdempotentAndBlockedMembersAreIgnored, AchievementIT::seededProgressAndBadges, LeaderboardIT::/v1/portal/leaderboards/LDB-MONTH-PTS?memberId=MBR-000009, LeaderboardIT::seededRankingExcludesInactiveMembersAndShowsOnlyNicknames, LeaderboardIT::/v1/portal/leaderboards/LDB-EDITION-STS?memberId=MBR-000009, LeaderboardIT::/v1/portal/leaderboards/LDB-IT-QUIZ?memberId=MBR-000003, AchievementIT::digitalNeedsBothTypesAndAwardsItsBadge, LeaderboardIT::actionCountBoardsAndManagementRules, LeaderboardIT::/v1/portal/leaderboards/LDB-MONTH-PTS?memberId=MBR-000001, AchievementIT::managementNeedsObjectEdit, LeaderboardIT::pointsEarnedFeedTheRightBoardAndTiesGoToWhoArrivedFirst, AchievementIT::threePurchasesInAMonthCompleteOnceAndTheFourthDoesNotReemit, AchievementIT::streakGrowsOncePerDay | coperto |
| `F-ACH-02` | Progresso | spuntato | .../application/AchievementService.java | nessun test | implementato senza test |
| `F-ACH-03` | Badge | spuntato | .../infra/BadgeRepository.java | nessun test | implementato senza test |
| `F-CMP-04` | Effetti | spuntato | non trovato | nessun test | ticked ma non trovato |
| `F-CMP-12` | Campagne di sistema | spuntato | non trovato | nessun test | ticked ma non trovato |
| `F-CPN-02` | Emissione | spuntato | non trovato | nessun test | ticked ma non trovato |
| `F-IW-01` | Concorso | spuntato | .../domain/Contest.java | ContestIT::IW-AUTUNNO, ContestIT::IW-NATALE, ContestIT::staleVersionIs409AndDuplicateStartsAsDraftWithoutInstants, ContestIT::publishRequiresInstantsAndLiveLocksPrizes, ContestIT::seededContestsHaveTheirInstants, ContestIT::sameSeedGivesTheSameInstants, ContestIT::winnersStatsAndDelivery, ContestIT::instantsTableIsReservedToAdminAndLegal, ContestIT::IW-ESTATE, ContestIT::marketingSubmitsLegalDecidesAndHistoryIsVisible, ContestIT::approvalIsForLegalAndAdmin | coperto |
| `F-IW-02` | Montepremi | spuntato | .../domain/Prize.java | nessun test | implementato senza test |
| `F-IW-03` | Istanti vincenti pre-generati | spuntato | .../domain/InstantGenerator.java | nessun test | implementato senza test |
| `F-IW-04` | Giocata | spuntato | .../application/PlayService.java | PlayIT::grantIsIdempotentAndDailyLimitHolds, PlayIT::fiftyConcurrentPlaysOneExpiredInstantOneWin, PlayIT::IW-IT-RACE, PlayIT::IW-ESTATE, PlayIT::freePlayOnceADayThenNoPlays, PlayIT::seededHistoryIsConsistent | coperto |
| `F-IW-05` | Crediti di gioco | spuntato | .../messaging/PlaysGrantHandler.java | PlayIT::grantIsIdempotentAndDailyLimitHolds, PlayIT::fiftyConcurrentPlaysOneExpiredInstantOneWin, PlayIT::IW-IT-RACE, PlayIT::IW-ESTATE, PlayIT::freePlayOnceADayThenNoPlays, PlayIT::seededHistoryIsConsistent | coperto |
| `F-IW-06` | Vincita come azione interna | spuntato | non trovato | nessun test | ticked ma non trovato |
| `F-IW-07` | Vincitori e report | spuntato | .../application/ContestAdminService.java | ContestIT::IW-AUTUNNO, ContestIT::IW-NATALE, ContestIT::staleVersionIs409AndDuplicateStartsAsDraftWithoutInstants, ContestIT::publishRequiresInstantsAndLiveLocksPrizes, ContestIT::seededContestsHaveTheirInstants, ContestIT::sameSeedGivesTheSameInstants, ContestIT::winnersStatsAndDelivery, ContestIT::instantsTableIsReservedToAdminAndLegal, ContestIT::IW-ESTATE, ContestIT::marketingSubmitsLegalDecidesAndHistoryIsVisible, ContestIT::approvalIsForLegalAndAdmin | coperto |
| `F-IW-08` | Aiuto demo | spuntato | .../api/GamificationDemoController.java | DemoToolsIT::IW-AUTUNNO, DemoToolsIT::plantedInstantMakesTheNextPlayWin, DemoToolsIT::closeContestsEndsLiveContestsAndVoidsOpenInstants | coperto |
| `F-LDB-01` | Classifiche | spuntato | .../domain/Leaderboard.java | LeaderboardIT::/v1/portal/leaderboards/LDB-IT-QUIZ?memberId=MBR-000003, LeaderboardIT::/v1/portal/leaderboards/LDB-EDITION-STS?memberId=MBR-000009, LeaderboardIT::seededRankingExcludesInactiveMembersAndShowsOnlyNicknames, LeaderboardIT::actionCountBoardsAndManagementRules, LeaderboardIT::/v1/portal/leaderboards/LDB-MONTH-PTS?memberId=MBR-000001, LeaderboardIT::pointsEarnedFeedTheRightBoardAndTiesGoToWhoArrivedFirst, LeaderboardIT::/v1/portal/leaderboards/LDB-MONTH-PTS?memberId=MBR-000009 | coperto |
| `F-MBR-06` | Registrazione dal portale | spuntato | .../api/CreateMemberRequest.java | ReferralIT::registrationWithFriendCodeCreatesLinkAndInvalidCodeIs422, ReferralIT::firstQualifyingPurchaseCompletesReferralOnce, ReferralIT::completingProfileEmitsFactOnce, ReferralIT::overviewCountsLinksAndTopReferrers | coperto |
| `F-MBR-07` | Completamento profilo | spuntato | .../api/PortalMembersController.java | ReferralIT::registrationWithFriendCodeCreatesLinkAndInvalidCodeIs422, ReferralIT::firstQualifyingPurchaseCompletesReferralOnce, ReferralIT::completingProfileEmitsFactOnce, ReferralIT::overviewCountsLinksAndTopReferrers | coperto |
| `F-REF-01` | Codice amico | spuntato | .../api/ReferralController.java | ReferralIT::registrationWithFriendCodeCreatesLinkAndInvalidCodeIs422, ReferralIT::firstQualifyingPurchaseCompletesReferralOnce, ReferralIT::completingProfileEmitsFactOnce, ReferralIT::overviewCountsLinksAndTopReferrers | coperto |
| `F-REF-02` | Completamento referral | spuntato | non trovato | nessun test | ticked ma non trovato |
| `PT-05` | Schermata PT-05 | spuntato | web/components/portal/ContentSlot.tsx | nessun test | implementato senza test |
| `PT-09` | Schermata PT-09 | spuntato | web/app/portal/profile/page.tsx | nessun test | implementato senza test |
| `PT-10` | Schermata PT-10 | spuntato | web/app/portal/leaderboard/page.tsx | nessun test | implementato senza test |
| `PT-11` | Schermata PT-11 | spuntato | web/app/portal/invite/page.tsx | nessun test | implementato senza test |

## M6

| ID | Descrizione | Stato in docs/14 | Evidenza Implementazione | Evidenza Test | Verdetto |
|---|---|---|---|---|---|
| `BO-04` | Segmenti | non spuntato | web/components/bo/segments/CriteriaBuilder.tsx | nav.test::BO-04 Segmenti vive nel gruppo Clienti, usage.test::trova campagne, premi e contenuti che citano il codice, criteria.test::validazione delle righe prima del salvataggio, usage.test::esito del ricalcolo, criteria.test::gruppi annidati, not o campi sconosciuti → vista JSON, criteria.test::campi con parametro, criteria.test::JSON → righe e ritorno, anche senza prefisso member. e con una foglia sola, nav.test::la dashboard è attiva solo sulla radice del backoffice, criteria.test::una riga nuova usa il primo comparatore ammesso, nav.test::BO-27 DLQ è in Osservabilità da M7, criteria.test::righe → JSON nel formato delle condizioni, criteria.test::frase italiana dei criteri, usage.test::elenco manuale dei membri, nav.test::sceglie la voce più specifica, criteria.test::JSON scritto a mano, usage.test::fonti assenti, nav.test::nessuna voce fuori dal backoffice | coperto |
| `BO-06` | Editor campagna | non spuntato | web/components/bo/GeneratedSentence.tsx | ConditionBuilder.test::mostra i tipi abilitati per categoria, con la pill custom, e li seleziona, conditions.test::enum su entrambi → unione; tipi incompatibili → string, conditions.test::limita i gruppi a 3 livelli, conditions.test::attributi non segnalati se il catalogo dei membri non è disponibile, conditions.test::offre solo i comparatori coerenti col tipo, ConditionBuilder.test::degraded: con ingestion addormentato torna al campo di testo, conditions.test::cambio di comparatore: scalare ⇄ elenco ⇄ intervallo senza perdere il dato, conditions.test::un percorso fuori catalogo resta accettato come campo libero, ConditionBuilder.test::modifica il valore mantenendo i numeri come numeri, conditions.test::segnala valori mancanti o del tipo sbagliato, conditions.test::etichette in italiano, con le forme per le date, conditions.test::aggiunge, aggiorna e toglie nodi in modo immutabile, conditions.test::ignora i trigger non ancora caricati; nessun trigger → nessun campo, conditions.test::andata e ritorno senza perdite, numeri come numeri, conditions.test::cmp assente → eq, ConditionBuilder.test::con più trigger avvisa sui campi non comuni senza toglierli, conditions.test::tier di riserva quando wallet non risponde, conditions.test::contiene i quattro spazi nell, conditions.test::ricerca per etichetta o percorso, conditions.test::converte testo in numeri, conditions.test::mappa i tipi dello schema sui tipi del costruttore, conditions.test::attributi custom → member.attributes.<key>, opzioni ⇒ enum, conditions.test::errori di struttura con messaggio, albero null, conditions.test::contesto e storico, ConditionBuilder.test::aggiunge gruppi fino a 3 livelli, conditions.test::una foglia alla radice viene avvolta in TUTTE, conditions.test::un solo trigger: tutti i suoi campi, conditions.test::campo non comune a tutti i trigger: avviso col trigger mancante, campo mantenuto, ConditionBuilder.test::combobox raggruppata per spazio con attributi custom e percorso libero, conditions.test::enum solo da una parte → niente enum; format solo se uguale, ConditionBuilder.test::?, conditions.test::vuoto/null → gruppo TUTTE vuoto, e ritorno a null, conditions.test::MAX_DEPTH è 3, conditions.test::più trigger: solo i percorsi comuni, number+integer → number, required solo se ovunque, conditions.test::cambio di campo: tiene comparatore e valore solo se compatibili, conditions.test::toglie i gruppi vuoti e il valore per exists/nexists, conditions.test::rifiuta più di 3 livelli di gruppi, conditions.test::validateTree mappa id → problema su tutto l, conditions.test::membro: tier dai codici, stato, segmenti con nome, etichette, numeri, conditions.test::foglie valide, conditions.test::nessun avviso sui data.* finché i campi non sono arrivati, conditions.test::forma del valore per comparatore, conditions.test::la frase generata rilegge lo stesso JSON, conditions.test::elenchi separati da virgola, senza vuoti né doppioni, conditions.test::campi data.* col tipo giusto e l, conditions.test::nuova foglia: comparatore e valore di default del tipo | coperto |
| `BO-09` | Azioni e fonti | non spuntato | web/components/bo/campaigns/TriggerPicker.tsx | nessun test | implementato senza test |
| `BO-18` | Card e pop-up | non spuntato | web/components/bo/PhoneFrame.tsx | nav.test::BO-04 Segmenti vive nel gruppo Clienti, nav.test::la dashboard è attiva solo sulla radice del backoffice, nav.test::BO-27 DLQ è in Osservabilità da M7, nav.test::sceglie la voce più specifica, nav.test::nessuna voce fuori dal backoffice | coperto |
| `BO-19` | Messaggi | non spuntato | web/components/bo/messages/RulesTab.tsx | facts.test::coincide con i tipi ammessi da engagement, nav.test::BO-04 Segmenti vive nel gruppo Clienti, nav.test::la dashboard è attiva solo sulla radice del backoffice, facts.test::ha un, facts.test::riconosce numeri e date, nav.test::BO-27 DLQ è in Osservabilità da M7, facts.test::i campioni rispettano i contratti: campi obbligatori presenti, nessun campo inventato, facts.test::per una campagna con SEND_MESSAGE includono i campi dell, facts.test::è un fatto sul membro scelto col data di esempio, nav.test::sceglie la voce più specifica, facts.test::per una campagna è un effetto message.send con i params fusi in data, facts.test::senza sorgenti restano solo quelli comuni, facts.test::propongono i campi data.* delle sorgenti con il formattatore adatto, senza duplicati, nav.test::nessuna voce fuori dal backoffice | coperto |
| `BO-20` | Tema e brand | non spuntato | web/components/bo/PhoneFrame.tsx | nessun test | implementato senza test |
| `BO-28` | Simulatore eventi | non spuntato | web/components/portal/InboxBell.tsx | nessun test | implementato senza test |
| `F-CMP-06` | Pubblico | spuntato | .../messaging/MemberSnapshotHandler.java | CampaignServiceIT::surveyGrantsPointsAndAPlay, CampaignServiceIT::listReturnsSeededCampaigns, CampaignServiceIT::duplicateActionProducesEffectsOnce, CampaignServiceIT::campaignAboveBudgetNeedsLegalApproval, CampaignServiceIT::segmentFactsDriveTheSegmentAudience, CampaignServiceIT::portalListsEarnRulesForMember, CampaignServiceIT::statsReflectMatchesAndDailySeries, CampaignServiceIT::liveCampaignAcceptsOnlySafeFieldsOtherwise409, CampaignServiceIT::purchaseWeekdaySilverGrantsBaseAndSts, CampaignServiceIT::purchaseWeekendAppliesCampaignMultiplierToPts, CampaignServiceIT::birthdayGrantsPointsAndSendsMessageEffect, CampaignServiceIT::appLoginDailyLimitSkipsSecondSameDay, CampaignServiceIT::simulateTierUpgradedWithLookupCampaign, CampaignServiceIT::portalByCodesIncludesHiddenReferralCampaigns, CampaignServiceIT::simulateWeekendPurchaseWithoutWriting, CampaignServiceIT::duplicateCopiesIntoDraftAndStaleVersionIs409, CampaignServiceIT::validateRequiresTemplateCodeForSendMessage | coperto |
| `F-CNT-01` | Card | spuntato | .../api/ContentsController.java | ContentIT::seedFillsThePortalPlacements, ContentIT::lifecycleEmitsTheStatusFactAndOnlyValidTransitions, ContentIT::tierAudienceHidesTheCardFromAnnaAndPreviewExplainsWhy, ContentSelectionTest::ordersByPriorityThenCodeAndCutsAtTheLimit, ContentSelectionTest::explainsEveryExclusion, ContentIT::updateReplacesTheContentWithOptimisticLock, ContentIT::validationAndRoles, ContentIT::welcomePopupShowsOnceToNewMembers, ContentIT::popupEndpointsValidateTheirInput, ContentIT::dailyPopupComesBackTheNextDay, ContentIT::expiredContentsEndAutomatically, ContentSelectionTest::unknownMemberSeesOnlyContentForEveryone, ContentSelectionTest::popupFrequencies, ContentSelectionTest::popupAudienceByRegistrationAndWeekday, ContentIT::themeChangesAtRuntimeWithContrastCheck | coperto |
| `F-CNT-02` | Pop-up | spuntato | .../api/PortalPopupsController.java | nessun test | implementato senza test |
| `F-CNT-03` | Card vincita | spuntato | non trovato | nessun test | ticked ma non trovato |
| `F-CNT-04` | Anteprima | spuntato | .../application/ContentService.java | nessun test | implementato senza test |
| `F-ING-06` | Tipi azione e schemi | spuntato | .../messaging/CampaignEvaluationHandler.java | EventTypesIT::systemTypesOnlyChangeLabelsAndOnlyByAdmin, EventTypesIT::fieldsAreFlattenedFromTheSchema, EventTypesIT::customTypeIsUsableRightAwayAndItsSchemaChangesAtRuntime, EventTypesIT::invalidCustomTypesAreRejectedWithFieldErrors | coperto |
| `F-MBR-02` | Scheda 360° | spuntato | non trovato | nessun test | ticked ma non trovato |
| `F-MBR-03` | Attributi custom ed etichette | spuntato | .../api/AttributeDefinitionsController.java | nessun test | implementato senza test |
| `F-MSG-01` | Inbox in-app | spuntato | .../api/PortalInboxController.java | EngagementIT::unreadCountReadAndReadAll, EngagementIT::messageSendEffectDeliversAndEmitsDelivered, EngagementIT::messageDeliveredNeverTriggersAnything, EngagementIT::renderPreviewResolvesPlaceholders, EngagementIT::seededInboxTellsTheStories, EngagementIT::referralRuleMatchesOnlyTheReferrer, EngagementIT::pointsEarnedBecomesOneMessageEvenWhenReprocessed, EngagementIT::managementRolesValidationAuditAndRuntimeRules | coperto |
| `F-MSG-02` | Template | spuntato | .../api/MessageTemplatesController.java | EngagementIT::unreadCountReadAndReadAll, EngagementIT::pointsEarnedBecomesOneMessageEvenWhenReprocessed, EngagementIT::referralRuleMatchesOnlyTheReferrer, EngagementIT::messageSendEffectDeliversAndEmitsDelivered, TemplateEngineTest::replacesPathsOverDataMemberAndEvent, TemplateEngineTest::syntaxProblemsForTheEditor, TemplateEngineTest::noLogicIsEvaluated, EngagementIT::messageDeliveredNeverTriggersAnything, EngagementIT::renderPreviewResolvesPlaceholders, EngagementIT::seededInboxTellsTheStories, TemplateEngineTest::missingPathBecomesEmptyAndIsReported, TemplateEngineTest::numberFormatterUsesItalianGrouping, EngagementIT::managementRolesValidationAuditAndRuntimeRules, TemplateEngineTest::dateFormatterUsesRomeCivilDay | coperto |
| `F-RWD-04` | Visibilità | spuntato | .../messaging/MemberSnapshotHandler.java | RewardServiceIT::segmentFactsShowAndHideSegmentRewards, RewardServiceIT::bandsKeepUniqueIncreasingThresholdsAndCannotBeDeletedInUse, RewardServiceIT::seededCatalogHasBandsAndRewards, RewardServiceIT::portalCatalogAppliesTierSegmentStatusAndStockRules, RewardServiceIT::tierFactUpdatesTheSnapshotAndUnlocksRewards, RewardServiceIT::rewardLifecycleAndLiveLock | coperto |
| `F-SEG-01` | Segmenti statici | spuntato | .../domain/Segment.java | SegmentIT | coperto |
| `F-SEG-02` | Segmenti dinamici | spuntato | non trovato | nessun test | ticked ma non trovato |
| `F-SEG-03` | Ricalcolo e fatti | spuntato | non trovato | nessun test | ticked ma non trovato |
| `F-THM-01` | Tema del portale | spuntato | .../api/ThemeController.java | nessun test | implementato senza test |
| `PT-06` | Schermata PT-06 | non spuntato | web/components/portal/game/GiftBoxes.tsx | nessun test | implementato senza test |
| `PT-12` | Schermata PT-12 | non spuntato | web/components/portal/InboxBell.tsx | nessun test | implementato senza test |

## M7

| ID | Descrizione | Stato in docs/14 | Evidenza Implementazione | Evidenza Test | Verdetto |
|---|---|---|---|---|---|
| `BO-21` | Approvazioni | non spuntato | web/components/bo/LifecycleBar.tsx | nessun test | implementato senza test |
| `BO-23` | Webhook | non spuntato | web/components/bo/webhooks/DeliveryLog.tsx | nessun test | implementato senza test |
| `BO-26` | Monitor ingressi | non spuntato | web/app/backoffice/observe/inbound/page.tsx | nessun test | implementato senza test |
| `BO-27` | DLQ | non spuntato | web/components/observe/TraceWaterfall.tsx | nav.test::BO-04 Segmenti vive nel gruppo Clienti, nav.test::la dashboard è attiva solo sulla radice del backoffice, nav.test::BO-27 DLQ è in Osservabilità da M7, nav.test::sceglie la voce più specifica, nav.test::nessuna voce fuori dal backoffice | coperto |
| `F-APR-01` | Workflow di approvazione | spuntato | non trovato | nessun test | ticked ma non trovato |
| `F-APR-02` | Policy | spuntato | non trovato | nessun test | ticked ma non trovato |
| `F-APR-03` | Casella approvazioni | spuntato | .../application/ContestAdminService.java | nessun test | implementato senza test |
| `F-CMP-13` | Duplica campagna | non spuntato | .../application/CampaignAdminService.java | CampaignServiceIT::surveyGrantsPointsAndAPlay, CampaignServiceIT::listReturnsSeededCampaigns, CampaignServiceIT::duplicateActionProducesEffectsOnce, CampaignServiceIT::campaignAboveBudgetNeedsLegalApproval, CampaignServiceIT::segmentFactsDriveTheSegmentAudience, CampaignServiceIT::portalListsEarnRulesForMember, CampaignServiceIT::statsReflectMatchesAndDailySeries, CampaignServiceIT::liveCampaignAcceptsOnlySafeFieldsOtherwise409, CampaignServiceIT::purchaseWeekdaySilverGrantsBaseAndSts, CampaignServiceIT::purchaseWeekendAppliesCampaignMultiplierToPts, CampaignServiceIT::birthdayGrantsPointsAndSendsMessageEffect, CampaignServiceIT::appLoginDailyLimitSkipsSecondSameDay, CampaignServiceIT::simulateTierUpgradedWithLookupCampaign, CampaignServiceIT::portalByCodesIncludesHiddenReferralCampaigns, CampaignServiceIT::simulateWeekendPurchaseWithoutWriting, CampaignServiceIT::duplicateCopiesIntoDraftAndStaleVersionIs409, CampaignServiceIT::validateRequiresTemplateCodeForSendMessage | coperto |
| `F-ING-04` | Eventi non abbinati | non spuntato | .../api/InboundEventsController.java | InboundResolutionTest::autoMatchKeysSkipMissingReferences, UnmatchedResolutionIT::memberRegisteredAutoMatchesParkedEmailEventsOnceEvenUnderRedelivery, InboundResolutionTest::matchOnlyForUnmatched, InboundResolutionTest::retryOnlyForRejectedAndUnmatched, InboundResolutionTest::unknownStatusParsesToNull, UnmatchedResolutionIT::retryOfAnUnmatchedWithoutNewMemberStaysUnmatched, UnmatchedResolutionIT::manualMatchPublishesOneActionFlipsStatusAndIsAudited, InboundResolutionTest::autoMatchKeysFollowThePipelineResolution, UnmatchedResolutionIT::matchRequiresAnExistingActiveMember, UnmatchedResolutionIT::retryOfSourceDisabledRejectionPublishesOnceAfterEnablingTheSource, UnmatchedResolutionIT::autoMatchIgnoresMembersThatAreNotActive | coperto |
| `F-INS-05` | DLQ | spuntato | .../api/DlqController.java | DlqIT::effectsAndFactsAreNotReprocessable, DlqIT::reprocessRefusedByIngestionLeavesTheEntryOpen, DlqIT::reprocessOfAnActionResendsItToIngestionWithTheSameId, DlqIT::poisonedActionBecomesAnEntryWithHeaderFieldsAndTheTraceFails, DlqIT::discardNeedsAdminAndANoteAndIsAudited, DlqIT::nonJsonDlqValueIsKeptAsRaw, DlqIT::listFiltersByStatusConsumerAndErrorCode | coperto |
| `F-MBR-05` | Anonimizzazione | non spuntato | non trovato | nessun test | mancante |
| `F-RWD-08` | Ciclo di vita premio | spuntato | non trovato | nessun test | ticked ma non trovato |
| `F-WBH-01` | Webhook in uscita | spuntato | .../api/WebhooksController.java | WebhookIT::factBecomesASignedDeliveryVerifiableByTheReceiver, WebhookSignatureTest::sharedVectorMatchesTheNodeReceiver, WebhookIT::failingEndpointIsRetriedThreeTimesThenGivesUpAndManualRetryWorks, WebhookIT::writesAreAdminOnlyAndValidated, WebhookSignatureTest::newSecretsAreRandomAndPrefixed, WebhookIT::WEBHOOK:WH-IT-FACT, WebhookIT::updateToggleAndDelete, WebhookSignatureTest::signatureIsLowercaseHexWithPrefixAndDependsOnSecretAndBody, WebhookIT::seedHasOneDisabledWebhookWithItsDeliveryLog | coperto |

## Criteri di Accettazione (docs/12)

| Milestone | Criterio | Test Evidence |
|---|---|---|
| M0 | *Dato* il compose attivo, *quando* invio un CloudEvent valido a `POST /v1/events`, *allora* ricevo `202` e il messaggio è sul topic con chiave = `subject` entro 2 s. | nessun test |
| M0 | *Dato* lo stesso `id` inviato due volte, *allora* la seconda risposta è `202` con `status=DUPLICATE` e sul topic c'è un solo messaggio. | nessun test |
| M0 | *Dato* un consumer che lancia sempre eccezione, *allora* dopo 3 tentativi il messaggio è su `lh.dlq.v1` con `errorCode` e il consumer prosegue. | nessun test |
| M0 | *Dato* Kafka irraggiungibile durante una scrittura, *allora* la riga resta in `outbox` e viene pubblicata al ritorno di Kafka (nessuna perdita). | nessun test |
| M0 | *Dato* il Demo Hub aperto con servizi spenti, *allora* ogni tessera mostra `DOWN/SLEEPING` senza errori in pagina. | nessun test |
| M0 | Immagine Docker dell'archetipo: parte con `-m 512m` e RSS ≤ 450 MB dopo 2 minuti. | nessun test |
| M1 | *Dato* Marco (SILVER), *quando* dal simulatore invio `purchase.completed` di 130 € in un giorno feriale, *allora* entro 8 s (p95, servizi svegli) il ledger ha `EARN 162 PTS` e `EARN 130 STS` con `campaignCode=CMP-PURCHASE-BASE` e lo stesso `correlationId` dell'azione. | nessun test |
| M1 | *Stesso acquisto di sabato* → `campaignMultiplier=2`, 325 PTS (130 × 2 × 1,25 arrotondato per difetto). | nessun test |
| M1 | *Quarto acquisto nello stesso giorno* → `campaign.evaluated.skipped[]` con `reason=MEMBER_LIMIT_REACHED`, nessun movimento. | nessun test |
| M1 | *Membro `BLOCKED`* (Roberto) → ingestion `REJECTED (MEMBER_NOT_ACTIVE)`, niente sul topic; la riga compare in BO-26. | nessun test |
| M1 | *Simulazione* di `CMP-WEEKEND-X2` con un evento di martedì → `NO_MATCH`, con la condizione di calendario marcata ✗; nessun evento emesso. | nessun test |
| M1 | *Portale*: dopo l'invio da PT-14 compare "in arrivo…", poi il saldo sale con count-up; nessun aggiornamento ottimistico. | nessun test |
| M1 | *Servizio wallet fermo* → PT-01 mostra la sezione saldo *degraded*, il resto funziona; al riavvio l'arretrato viene elaborato e il saldo è corretto (RNF-06). | nessun test |
| M1 | *Reset* → i 12 membri tornano allo stato di `docs/10`; `check-seed` verde. | nessun test |
| M1 | **Online**: dal Demo Hub pubblico, "Accendi la demo" porta 6/10 verdi (4 servizi + Kafka + DB) e `smoke.sh` passa. | nessun test |
| M2 | Un acquisto produce ≥ 4 righe nel rail entro 3 s, colorate per topic, con lo stesso `correlationId`. | nessun test |
| M2 | BO-25 mostra l'albero azione → valutazione → 2 effetti → 2 fatti, con tempi e l'esito "+162 PTS, +130 STS" (i messaggi si aggiungono in M6). | nessun test |
| M2 | `SCN-WEEKEND-BURST` eseguito da BO-29: 12 azioni, avanzamento visibile, nessuna perdita (12 valutazioni nell'event store). | nessun test |
| M2 | `SCN-DUPLICATE` → BO-26 mostra `DUPLICATE`; un solo movimento. | nessun test |
| M2 | `SCN-POISON` → voce DLQ, tracciato `FAILED`. | DemoPoisonTest::poisonedActionFailsWithANonRetryableErrorInDemo |
| M2 | Modifica di una campagna da BO-06 → riga in BO-22 con diff prima/dopo e attore `MARKETING:luca.marketing`. | nessun test |
| M2 | BO-01 dopo un reset: nessun grafico vuoto. | nessun test |
| M2 | SSE interrotto → dopo 3 tentativi l'UI passa a polling e lo dichiara. | nessun test |
| M3 | `SCN-TIER-UP`: Giulia passa a GOLD; il fatto `tier.upgraded` rientra come azione (`lhhop=1`, `source=internal`) e `CMP-TIER-UP-BONUS` accredita 500 PTS; nel tracciato è **un solo albero**. | nessun test |
| M3 | Catena artificiale con `lhhop` > 3 → DLQ `LOOP_GUARD`. | nessun test |
| M3 | Job scadenze con `asOf` = +31 giorni → Chiara perde 1 900 PTS (`EXPIRE`), fatto `wallet.points.expired`, saldo aggiornato nel portale. | nessun test |
| M3 | Chiusura edizione in `dryRun` come da `wallet-service.md §7` (Stefano → SILVER). | nessun test |
| M3 | Due campagne nello stesso `exclusiveGroup` → scatta solo quella a priorità più alta; l'altra è in `skipped[]` con `reason=EXCLUSIVE_GROUP`. | nessun test |
| M3 | Campagna `LIVE`: i campi bloccati non sono modificabili né da UI né da API (`409`). | nessun test |
| M7 | `luca.marketing` non può portare un concorso a `LIVE`: solo *Invia in revisione*; `elena.legal` approva con commento; storico visibile; tutto in audit. | nessun test |
| M7 | Rifiuto senza commento → `422`. | nessun test |
| M7 | Webhook verso endpoint che fallisce → 3 ritenti, `GAVE_UP`, *Riprova* manuale funziona; firma verificata da uno script d'esempio in `deploy/webhook-receiver/`. | nessun test |
| M7 | Anonimizzazione di un membro di prova: nessun servizio espone più nome/e-mail (test che interroga tutte le API di gestione); i movimenti restano. | nessun test |
