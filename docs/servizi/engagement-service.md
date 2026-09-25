# engagement-service

**Porta** 8087 · **Schema** `engagement` · **Feature** `F-CNT-*`, `F-MSG-*`, `F-THM-01`, `F-WBH-01` · **Milestone** M6 (inbox, template, regole, contenuti, pop-up, tema, anteprima), M7 (webhook)

## 1. Scopo e confini
È il **CMS del programma**: card, pop-up, banner, card di vincita; **inbox** in-app generata dai fatti; **tema** del portale; **webhook** in uscita.
Non invia email/SMS/push reali: il canale `EMAIL_FAKE` produce solo un'anteprima consultabile da BO-19.

## 2. Modello dati
| Tabella | Colonne principali |
|---|---|
| `content_item` | `id`, `code` UQ, `kind` (`CARD, POPUP, BANNER`), `placement` (`HOME_HERO, HOME_GRID, CATALOG_TOP, CONTEST, WIN`), `title`, `body`, `image_url`, `cta_label`, `cta_target` (path del portale o URL), `link_type` (`NONE, CONTEST, CAMPAIGN, REWARD, PRIZE`), `link_code`, `audience jsonb` (`{tiers[], segments[], statuses[]}`; vuoto = tutti), `start_at`, `end_at`, `priority`, `frequency` (`ONCE, ONCE_PER_DAY, ALWAYS`; solo pop-up), `dismissible`, `style jsonb` (`{tone: PRIMARY/SECONDARY/COIN/NIGHT, layout}`), `status` (`DRAFT, LIVE, PAUSED, ENDED, ARCHIVED`), `version` |
| `popup_view` | (`content_id`,`member_id`,`view_date`) PK, `dismissed_at` |
| `message_template` | `code` PK, `name`, `channel` (`INAPP, EMAIL_FAKE`), `title_tpl`, `body_tpl`, `icon`, `link_target`, `category` (`POINTS, TIER, REWARD, GAME, PROGRAM`) |
| `notification_rule` | `id`, `fact_type`, `condition jsonb` null (stesso formato condizioni, spazio `data.*`), `template_code`, `enabled` |
| `inbox_message` | `id` (ULID), `member_id`, `template_code`, `channel`, `title`, `body`, `icon`, `link_target`, `category`, `source_event_id`, `correlation_id`, `created_at`, `read_at` · UQ (`member_id`,`source_event_id`,`template_code`) |
| `theme` | `id` = `default`, `program_name`, `tagline`, `logo_url`, `colors jsonb` (`{primary, secondary, coin, night, bg}`), `hero_title`, `hero_subtitle`, `font_display`, `updated_at` |
| `webhook` | `id`, `name`, `url`, `secret`, `fact_types text[]`, `enabled`, `created_by` |
| `webhook_delivery` | `id`, `webhook_id`, `event_id`, `fact_type`, `attempt`, `status` (`PENDING, OK, FAILED, GAVE_UP`), `http_status`, `response_excerpt`, `next_attempt_at`, `created_at` |
| `member_snapshot` | `member_id` PK, `first_name`, `status`, `tier_code`, `segments text[]` |

## 3. API
### Gestione
| Metodo | Path | Note |
|---|---|---|
| GET/POST/PUT | `/v1/contents`, `/v1/contents/{id}` | filtri `kind, placement, status, q`; PUT su `LIVE` → solo campi sicuri (titolo, testo, immagine, priorità, `endAt`; docs/03 §3.6), altrimenti `409 CONTENT_LIVE_LOCKED` |
| POST | `/v1/contents/{id}/transitions` | `CONTENT` non richiede approvazione (`DRAFT → LIVE` diretto) |
| GET | `/v1/contents/{id}/approval-history` | storico delle transizioni (`approval_history`, `entity_type = CONTENT`: chi, quando, da/verso; docs/03 §3.6, docs/06 §7), dal più recente |
| POST | `/v1/contents/{id}/duplicate` | |
| GET | `/v1/contents/preview?memberId=&placement=` | ciò che vedrebbe quel membro adesso, con il motivo di esclusione degli altri (`NOT_IN_AUDIENCE, OUT_OF_SCHEDULE, NOT_LIVE, FREQUENCY`) |
| GET/POST/PUT | `/v1/message-templates`, `/v1/notification-rules` | |
| POST | `/v1/message-templates/{code}/render` | `{sampleEvent}` → anteprima con segnaposto risolti |
| GET | `/v1/messages?memberId=&category=&channel=` | registro messaggi per BO-19 e Scheda 360° |
| GET/PUT | `/v1/theme` | colori validati come esadecimali; contrasto testo/primario ≥ 4.5 altrimenti `422 THEME_CONTRAST_TOO_LOW` |
| GET/POST/PUT/DELETE | `/v1/webhooks` · GET `/v1/webhooks/{id}/deliveries` · POST `/v1/webhooks/{id}/test` · POST `/v1/webhook-deliveries/{id}/retry` | il `secret` si legge solo alla creazione |

### Portale
| Metodo | Path | Note |
|---|---|---|
| GET | `/v1/portal/content?memberId=&placement=` | elenco ordinato; per `WIN` aggiungere `&prizeCode=` |
| GET | `/v1/portal/popups/next?memberId=` | `204` se nessuno |
| POST | `/v1/portal/popups/{id}/seen` | `{memberId, dismissed}` |
| GET | `/v1/portal/inbox?memberId=&page=` · GET `/v1/portal/inbox/unread-count?memberId=` | |
| POST | `/v1/portal/inbox/{id}/read` · POST `/v1/portal/inbox/read-all` | |
| GET | `/v1/portal/theme` | cache 60 s |

## 4. Eventi
| Direzione | Topic | Tipi |
|---|---|---|
| Consuma | `lh.effects.v1` | `message.send` |
| Consuma | `lh.facts.v1` | tutti (regole di notifica, webhook); `member.*`, `tier.*`, `member.segment.*` anche per lo snapshot |
| Produce | `lh.facts.v1` | `message.delivered`, `content.status.changed` |
| Produce | `lh.audit.v1` | scritture su contenuti, template, regole, tema, webhook |

## 5. Regole
Dominio in `docs/03 §9`. Note implementative:
- **Motore template**: sostituzione `{{percorso}}` su contesto `{data, member, event}`; percorso assente → stringa vuota + log `WARN`; nessuna logica nei template. Formattatori: `{{data.amount|number}}`, `{{data.expiresAt|date}}`.
- `message.delivered` **non** è mai oggetto di regole né di webhook (evita cicli).
- **Webhook**: corpo = CloudEvent originale; header `X-LH-Signature: sha256=<HMAC(secret, body)>`, `X-LH-Event-Id`, `X-LH-Delivery-Id`; timeout 5 s; ritenti a 1, 5, 15 min poi `GAVE_UP`; scheduler ogni 30 s; solo URL `https://` (in `local` anche `http://localhost`); blocca indirizzi privati/loopback nel profilo `free` (anti-SSRF).
- `popups/next`: primo per priorità che passa pubblico, calendario e frequenza; la registrazione della vista avviene con `seen` (non alla lettura) così un errore di rendering non consuma il pop-up.
- `ENDED` automatico dei contenuti con `end_at` passato (job ogni 10 min).
- Pulizia: `inbox_message` > 180 giorni, `webhook_delivery` > 14 giorni, `popup_view` > 90 giorni.

## 6. Seed
`seed/contents.json`, `seed/message-templates.json`, `seed/notification-rules.json`, `seed/theme.json` (tema **Aurora**), `seed/webhooks.json` (uno, disabilitato, verso `https://example.org/hook`), `seed/inbox.json` (3–8 messaggi storici per membro, alcuni non letti). Immagini: file statici in `web/public/demo/` referenziati per percorso relativo. Dettaglio in `docs/10 §7`.

Regole seed minime: `wallet.points.earned → MSG-POINTS-EARNED`; `wallet.points.expiring → MSG-POINTS-EXPIRING`; `tier.upgraded → MSG-TIER-UP`; `tier.downgraded → MSG-TIER-DOWN`; `reward.redemption.fulfilled → MSG-REWARD-READY`; `reward.redemption.rejected → MSG-REWARD-REJECTED`; `coupon.issued (origin=CAMPAIGN) → MSG-COUPON-GIFT`; `contest.won → MSG-CONTEST-WON`; `badge.awarded → MSG-BADGE`; `referral.completed (role=REFERRER) → MSG-REFERRAL-DONE`; `member.registered → MSG-WELCOME`.

## 7. Accettazione minima
- `wallet.points.earned` di 162 PTS → messaggio "Hai guadagnato 162 punti" nell'inbox; stesso evento rielaborato → nessun duplicato.
- Pop-up `ONCE` visto e chiuso → non ricompare; `ONCE_PER_DAY` ricompare il giorno dopo (test con orologio iniettato).
- Contenuto con `audience.tiers=[GOLD,PLATINUM]` → assente per Anna, presente per Davide; `preview` ne spiega il motivo.
- `PUT /v1/theme` con nuovo `primary` → `GET /v1/portal/theme` lo restituisce; il portale cambia colore senza rebuild.
- Webhook di test verso un endpoint che risponde 500 → 3 ritenti pianificati, stato finale `GAVE_UP`, firma verificabile.
