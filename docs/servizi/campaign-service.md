# campaign-service

**Porta** 8083 · **Schema** `campaign` · **Feature** `F-CMP-*` · **Milestone** M1 (motore, punti, limiti, simulazione), M2 (statistiche), M3 (moltiplicatori, esclusività), M5 (altri effetti, campagne di sistema), M6 (segmenti), M7 (approvazione)

## 1. Scopo e confini
Il **motore regole**: valuta ogni azione contro le campagne attive e decide gli effetti. Unico produttore di `lh.effects.v1`. Tiene contatori dei limiti, budget e il registro delle valutazioni (spiegabilità).
Non applica gli effetti e non conosce i saldi.

## 2. Modello dati
| Tabella | Colonne principali |
|---|---|
| `campaign` | `id`, `code` UQ, `name`, `description`, `member_description` (testo per il portale), `icon`, `trigger_action_types text[]`, `audience jsonb`, `conditions jsonb`, `effects jsonb`, `limits jsonb`, `schedule jsonb`, `priority int`, `exclusive_group`, `visible_in_portal bool`, `system bool`, `requires_legal bool`, `labels text[]`, `status`, `version`, audit cols |
| `campaign_counter` | (`campaign_id`, `member_id`, `period`, `period_key`) PK, `matches int`, `points bigint`, `last_match_at` (`time` di business dell'ultimo match, per `cooldownMinutes`). Una riga per periodo dichiarato nei limiti più la riga `ALWAYS`/`ALWAYS`, sempre presente: accumula i punti del membro (`perMemberPoints`) |
| `campaign_totals` | `campaign_id` PK, `matches`, `unique_members`, `points_decided bigint`, `points_granted bigint` (da fatti wallet), `last_match_at` |
| `member_action_counter` | (`member_id`, `action_type`) PK, `count`, `first_at`, `last_at` |
| `member_snapshot` | `member_id` PK, `status`, `tier_code`, `segments text[]`, `labels text[]`, `attributes jsonb`, `registered_at`, `birth_date` |
| `evaluation_log` | `action_id` PK, `member_id`, `action_type`, `action_time`, `evaluated_at`, `correlation_id`, `outcome` (`MATCHED, NO_MATCH, NO_MEMBER`), `results jsonb` |

`results` = `[{campaignCode, campaignName, matched, reason?, failedConditions?[{field,cmp,value,actual}], effects?[…]}]`. Pulizia `evaluation_log` > 30 giorni.

## 3. API
### Gestione
| Metodo | Path | Note |
|---|---|---|
| GET | `/v1/campaigns` | filtri `status, actionType, q, label, system`; include `totals` |
| POST/GET/PUT | `/v1/campaigns`, `/v1/campaigns/{id}` | `{id}` = id o `code` (vale per tutti i path `/v1/campaigns/{id}/…`, docs/06 §2). POST: `code` nel formato `^[A-Z][A-Z0-9-]{2,39}$` (`422 INVALID_CODE`), `requiresLegal` opzionale (default `false`; solo alla creazione, SPEC-GAP Q-254). PUT su `LIVE` → solo campi sicuri, altrimenti `409 CAMPAIGN_LIVE_LOCKED`; `code` mai modificabile (`409 CODE_IMMUTABLE`, Q-51) |
| POST | `/v1/campaigns/{id}/transitions` | `docs/06 §7`; le `system` non ammettono `ARCHIVE` |
| POST | `/v1/campaigns/{id}/duplicate` | nuovo `code` = `<code>-COPY-n` (primo `n` libero; base accorciata se servisse a restare entro 40 caratteri, SPEC-GAP Q-253), stato `DRAFT` |
| POST | `/v1/campaigns/validate` | valida struttura di condizioni/effetti/limiti senza salvare → `{valid, errors[]}` |
| POST | `/v1/campaigns/simulate` | `{action: {type, time?, source?, data}, memberId? , memberOverride?: {tier, segments, attributes}, campaignIds?: []}` → stessa forma di `evaluation_log.results` + totali per valuta. Con `campaignIds` include anche bozze |
| GET | `/v1/campaigns/{id}/stats` | totali + serie giornaliera 30 giorni (da `evaluation_log`) |
| GET | `/v1/evaluations` | filtri `memberId, actionId, outcome, from, to` |
| GET | `/v1/evaluations/{actionId}` | dettaglio completo |
| GET | `/v1/approvals` | formato comune |
| GET | `/v1/meta/condition-fields` | campi `member.*`, `context.*`, `history.*` con tipo e valori ammessi (i `data.*` arrivano da ingestion) |

### Portale
| GET | `/v1/portal/campaigns?memberId=` | campagne `LIVE`, `visible_in_portal`, con pubblico soddisfatto: `{code, name, memberDescription, icon, rewardSummary ("+300 punti"), limitProgress?: {used, max, period}, endsAt?}` |

## 4. Eventi
| Direzione | Topic | Tipi |
|---|---|---|
| Consuma | `lh.actions.v1` | tutte |
| Consuma | `lh.facts.v1` | `member.*`, `member.segment.*`, `tier.*` → `member_snapshot`; `wallet.points.earned` → `campaign_totals.points_granted` |
| Produce | `lh.effects.v1` | tutti gli `effect.*` |
| Produce | `lh.facts.v1` | `campaign.evaluated`, `campaign.status.changed` |
| Produce | `lh.audit.v1` | scritture da backoffice |

## 5. Regole
- Algoritmo: `docs/03 §3.5`, senza deviazioni. Il motore è una classe pura `CampaignEngine.evaluate(action, memberSnapshot, campaigns, counters, clock)` testabile senza Spring.
- Le campagne `LIVE` sono tenute in cache in memoria, invalidata a ogni scrittura/transizione (e comunque ogni 30 s).
- Fine automatica: job ogni minuto porta a `ENDED` le `LIVE`/`PAUSED` con `schedule.endAt` superato.
- `rewardSummary` per il portale è generato dagli effetti: `FIXED` → "+300 punti"; `PER_AMOUNT` → "1 punto ogni 1 €"; `MULTIPLIER` → "Punti ×2"; `GRANT_PLAYS` → "+1 giocata".
- Validazioni di salvataggio (`422`): almeno un trigger; almeno un effetto; `MULTIPLIER.factor` tra 1.1 e 5; `GRANT_PLAYS.contestCode`, `ISSUE_COUPON.rewardCode` (o `rewardCodeField`), `AWARD_BADGE.badgeCode` e `SEND_MESSAGE.templateCode` non vuoti (l'esistenza non è verificabile senza chiamate sincrone: l'errore emergerà a valle in DLQ e nel tracciato); `endAt > startAt`.
- Limiti per membro oltre ai periodi (F-CMP-05, docs/03 §3.2; SPEC-GAP Q-165): `limits.perMemberPoints` — punti già decisi dalla campagna per il membro da sempre ≥ tetto → `LIMIT` (l'ultimo accredito sotto il tetto non si riduce, come il budget); `limits.cooldownMinutes` — meno di N minuti tra il `time` dell'ultimo match del membro sulla campagna e quello dell'azione → `LIMIT`. Letti anche dalla simulazione, consumati solo dalla valutazione reale.
- Azione per membro assente dallo snapshot: ritenta 3 volte (il fatto `member.registered` può essere in arrivo), poi `NO_MEMBER`.

## 6. Seed
`seed/campaigns.json` (19 campagne, `docs/10 §4`), `seed/members.json` + `seed/wallets.json` (snapshot), `seed/activity-history.json` (contatori e 60 valutazioni storiche).

## 7. Accettazione minima
- Azione `purchase.completed` da 130 € per un membro `SILVER` di martedì → due effetti `points.grant` (`PTS` 130 con `tierMultiplierApplies`, `STS` 130), log `MATCHED`.
- Stessa azione di sabato → `PTS` 260 (`campaignMultiplier` 2.0), `STS` 130.
- `app.login.daily` due volte nello stesso giorno → la seconda `skipped` con `LIMIT`.
- Doppia consegna della stessa azione → un solo insieme di effetti in outbox, contatori incrementati una volta.
- Simulazione di `ebill.activated` per un membro che l'ha già ottenuta → `matched=false`, `reason=LIMIT`, nessuna scrittura.
- Condizione su campo assente → foglia falsa, nessuna eccezione.
