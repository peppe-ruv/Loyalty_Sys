# member-service

**Porta** 8082 · **Schema** `member` · **Feature** `F-MBR-*`, `F-SEG-*`, `F-REF-*` · **Milestone** M1 (anagrafica), M5 (registrazione, profilo, referral), M6 (segmenti, attributi), M7 (anonimizzazione)

## 1. Scopo e confini
Anagrafica dei membri, attributi, etichette, segmenti, referral. Mantiene una **proiezione** di saldi/tier e le **statistiche di attività** per elenchi e segmenti.
Non possiede saldi né tier (sono del wallet): li riflette.

## 2. Modello dati
| Tabella | Colonne principali |
|---|---|
| `member` | `id` PK (`MBR-######`), `external_id` UQ, `first_name`, `last_name`, `nickname`, `email` UQ, `phone`, `birth_date`, `gender`, `city`, `status`, `channel` (`PORTAL, APP, STORE, IMPORT`), `registered_at`, `referral_code` UQ, `referred_by`, `referral_completed_at`, `consents jsonb` (`{marketing, profiling}`), `attributes jsonb`, `labels text[]`, `avatar_seed`, `profile_completed_at`, `version` |
| `member_projection` | `member_id` PK, `tier_code`, `period_sts`, `balance_pts`, `pending_pts`, `lifetime_earned_pts`, `updated_at` |
| `member_stats` | `member_id` PK, `last_activity_at`, `actions_total`, `actions_by_type jsonb` (`{type: {count30d, total, lastAt}}`), `purchases_count`, `purchases_amount_90d`, `purchases_amount_total` |
| `segment` | `id`, `code` UQ, `name`, `description`, `type` (`STATIC`/`DYNAMIC`), `criteria jsonb`, `status` (`ACTIVE`/`ARCHIVED`), `member_count`, `refreshed_at` |
| `segment_member` | (`segment_id`, `member_id`) PK, `entered_at` |
| `attribute_definition` | `key` PK, `label`, `type` (`STRING, NUMBER, BOOLEAN, DATE`), `options text[]` |

## 3. API
### Gestione
| Metodo | Path | Note |
|---|---|---|
| GET | `/v1/members` | filtri `q` (nome, e-mail, id, externalId), `status, tier, label, segment, registeredFrom/To`; colonne da `member` + proiezione |
| POST | `/v1/members` | crea (usato anche dal portale per la registrazione); `referralCode` opzionale → valida e lega |
| GET/PATCH | `/v1/members/{id}` | PATCH parziale con `version` |
| POST | `/v1/members/{id}/status` | `{status, reason}` — `BLOCKED/INACTIVE/ACTIVE` |
| POST | `/v1/members/{id}/anonymize` | irreversibile; conferma `{confirm: "MBR-…"}` |
| GET | `/v1/members/{id}/segments` · `/referrals` | segmenti di appartenenza; invitati con stato |
| GET/POST/PUT | `/v1/segments`, `/v1/segments/{id}` | |
| POST | `/v1/segments/preview` | `{criteria}` → `{count, sample[10]}` senza salvare |
| POST | `/v1/segments/{id}/refresh` | ricalcolo immediato → `{entered, left, total}` |
| PUT | `/v1/segments/{id}/members` | solo `STATIC`: sostituisce l'elenco |
| GET/PUT | `/v1/attribute-definitions` | |
| GET | `/v1/referral/overview` | totali: inviti, completati, tasso, top presentatori |

### Portale
| Metodo | Path | Note |
|---|---|---|
| GET | `/v1/portal/members/{id}` | profilo + completezza (`{completed, missingFields[]}`) |
| PATCH | `/v1/portal/members/{id}` | solo campi di profilo e consensi |
| GET | `/v1/portal/members/{id}/referral` | `{code, shareUrl, invited[], completedCount}` |

### Demo
| GET | `/v1/demo/personas` | i membri in evidenza per il selettore: `{memberId, name, tier, story, avatarSeed, balancePts}` (saldo dalla proiezione, per le schede del Demo Hub, docs/07 §8) |
| POST | `/v1/demo/jobs/refresh-segments` · `/v1/demo/jobs/birthdays?asOf=` | |

## 4. Eventi
| Direzione | Topic | Tipi |
|---|---|---|
| Produce | `lh.facts.v1` | `member.registered`, `member.updated`, `member.status.changed`, `member.profile.completed`, `member.birthday`, `member.segment.entered/left`, `referral.completed` |
| Produce | `lh.audit.v1` | ogni scrittura da backoffice |
| Consuma | `lh.actions.v1` | tutte → `member_stats`; verifica qualifica referral |
| Consuma | `lh.facts.v1` | `tier.*`, `wallet.points.*` → `member_projection` |

## 5. Regole
- `member.registered` e `member.updated` portano sempre lo **snapshot completo** (gli altri servizi sovrascrivono il proprio snapshot, nessun merge).
- `nickname` default = nome + iniziale cognome ("Giulia F.").
- Profilo completo: alla prima PATCH che valorizza tutti i campi richiesti (`docs/03 §2`) → `profile_completed_at` + fatto (una sola volta).
- Referral: codice inesistente → `422 REFERRAL_CODE_INVALID`; proprio codice → `422 REFERRAL_SELF`. Alla prima azione di tipo `referral.qualifyingActionType` di un membro con `referred_by` e `referral_completed_at` nullo → due fatti `referral.completed` (chiavi = i due `memberId`).
- **Criteri dei segmenti** (docs/03 §3.3, §10): cast tipizzato comune di lh-common (`io.loyaltyhub.common.condition.TypedCast`, Q-215/Q-216 decise), identico in campaign, member, gamification ed engagement: il **tipo bersaglio è quello del dato** e si converte il valore della regola, mai il contrario; numero ← numero JSON o testo `^-?\d+(\.\d+)?$` esatto (confronto `BigDecimal`), booleano ← `true`/`false` JSON o testo esatto, data ← `AAAA-MM-GG` valida, istante ← data e ora ISO-8601 con fuso (stessa granularità: una data non si confronta con un istante), testo ← solo testo; cast fallito → foglia falsa per ogni comparatore, negazioni comprese; `contains/ncontains/startsWith` solo su testo (una data non è testo); campo assente o `null` → falsa tranne `nexists`; comparatore sconosciuto → falsa. Una lista vuota (`labels`) vale come assente; su lista `in` = intersezione non vuota, `nin` = intersezione vuota. Gruppo con operatore sconosciuto o mancante, `any` vuoto e foglia senza `field`/`cmp` → falsi (Q-222, Q-223, Q-224, Q-219). Validazione (salvataggio e anteprima): forma non valida → `422 INVALID_CRITERIA`; forma valida ma valore non convertibile nel tipo del campo (contatori e saldi numerici, `tier/status/city` testo, `attributes.<k>` col tipo della sua definizione `STRING/NUMBER/BOOLEAN/DATE`) → `422 CONDITION_INVALID`, percorso in `errors[].field` (es. `criteria.rules[1].value`) (Q-215).
- Segmenti dinamici: ricalcolo su richiesta, dopo il reset e ogni 15 minuti (`@Scheduled`, solo se ci sono state variazioni in `member_stats`/`member_projection`). Si emettono solo le differenze.
- Le azioni interne (`source internal`) non aggiornano `last_activity_at` né i conteggi acquisti.

## 6. Seed
`seed/members.json` (12 membri, `docs/10 §2`), `seed/wallets.json` (per la proiezione), `seed/segments.json`, `seed/attribute-definitions.json`. `member_stats` calcolate dal seeder da `seed/activity-history.json`.

## 7. Accettazione minima
- Creando un membro, allora esiste il fatto `member.registered` con snapshot completo e `referralCode` di 8 caratteri.
- Ricevendo `wallet.points.earned` per un membro, allora `member_projection.balance_pts` = `balanceAfter`.
- Dato Elisa (`MBR-000009`, invitata da Marco) al primo `purchase.completed`, allora due fatti `referral.completed` con ruoli opposti e nessun altro ai successivi acquisti.
- L'anteprima di un segmento `member.tier in [GOLD, PLATINUM]` restituisce il conteggio atteso dai seed (4).
