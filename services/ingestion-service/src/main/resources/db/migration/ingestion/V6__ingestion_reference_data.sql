-- V6 — dati di riferimento di sistema di ingestion in ogni profilo (Q-629 opzione B, F-ING-06, F-ING-08, ADR-049;
-- regola 14 expand/contract: solo INSERT ... ON CONFLICT DO NOTHING, nessuna modifica di schema).
--
-- Cosa inserisce (ingestion è il proprietario di entrambe le entità, docs/04):
--   * 19 tipi azione origin='SYSTEM' con JSON Schema di data e dati di esempio: gli schemi coincidono con
--     contracts/events/action/*.schema.json (precedenza 2 su seed/, verificato da scripts/check-seed.mjs);
--   * 9 mappature del ponte fatti -> azioni interne (F-ING-08, docs/05 §7).
-- Non inserisce fonti (le crea lo script di programma via POST /v1/sources), scenari, membri, storico né tipi CUSTOM.
--
-- Perché una migrazione e non un seeder. Nel profilo enterprise nessun seeder e nessuna API creano i tipi SYSTEM
-- (POST /v1/event-types crea solo CUSTOM, le PUT agiscono su righe esistenti) e POST /v1/sources respinge i tipi
-- inesistenti: senza queste righe nessun evento potrebbe diventare un'azione. Sono dati di riferimento del prodotto,
-- non dati fittizi né membri: docs/18 §3.15 («il profilo demo rifiuta di avviarsi su un database che contiene membri
-- non di seed») e ADR-049 escludono i membri di seed e il seed di vetrina in enterprise, non il catalogo di sistema
-- del prodotto, che è lo stesso in ogni profilo.
--
-- Convivenza con la demo (DemoSeeder.resetToSeed): all'avvio e a ogni POST /v1/demo/reset il seeder cancella e
-- reinserisce le stesse righe con un upsert (in transazione nel reset via API), quindi non ci sono violazioni di chiave e dopo
-- il reset i dati ci sono ancora. DO NOTHING qui preserva le modifiche fatte da un operatore (name, description,
-- enabled, icon dei SYSTEM; abilitazione delle mappature) su un database che ha già queste righe.
--
-- Fonte dei valori: seed/event-types.json e seed/internal-mappings.json; ReferenceDataMigrationIT fallisce se la
-- migrazione diverge da seed/ o da contracts/events/action/. Testo SQL costante (regola 19).

INSERT INTO event_type (code, name, description, origin, category, data_schema, sample_data, enabled, icon) VALUES
  ('purchase.completed', 'Acquisto completato', 'Un membro ha concluso un acquisto', 'SYSTEM', 'TRANSACTION',
   $lh$
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "required": [
    "orderId",
    "amount",
    "currency"
  ],
  "properties": {
    "orderId": {
      "type": "string",
      "minLength": 1,
      "x-lh-pii": false
    },
    "amount": {
      "type": "number",
      "minimum": 0,
      "x-lh-pii": false
    },
    "currency": {
      "type": "string",
      "minLength": 1,
      "x-lh-pii": false
    },
    "channel": {
      "enum": [
        "ONLINE",
        "STORE",
        "APP"
      ],
      "x-lh-pii": false
    },
    "items": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "sku": {
            "type": "string",
            "x-lh-pii": false
          },
          "category": {
            "type": "string",
            "x-lh-pii": false
          },
          "quantity": {
            "type": "integer",
            "minimum": 0,
            "x-lh-pii": false
          },
          "unitPrice": {
            "type": "number",
            "minimum": 0,
            "x-lh-pii": false
          }
        },
        "additionalProperties": true
      },
      "x-lh-pii": false
    }
  },
  "additionalProperties": true
}
$lh$::jsonb,
   $lh$
{
  "orderId": "ORD-48213",
  "amount": 64.9,
  "currency": "EUR",
  "channel": "ONLINE",
  "items": [
    {
      "sku": "SKU-100",
      "category": "casa",
      "quantity": 1,
      "unitPrice": 39.9
    },
    {
      "sku": "SKU-220",
      "category": "energia",
      "quantity": 1,
      "unitPrice": 25
    }
  ]
}
$lh$::jsonb,
   true, 'shopping-cart'),
  ('purchase.returned', 'Reso di un acquisto', 'Un membro ha restituito un acquisto', 'SYSTEM', 'TRANSACTION',
   $lh$
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "required": [
    "orderId",
    "amount"
  ],
  "properties": {
    "orderId": {
      "type": "string",
      "minLength": 1,
      "x-lh-pii": false
    },
    "amount": {
      "type": "number",
      "exclusiveMinimum": 0,
      "x-lh-pii": false
    }
  }
}
$lh$::jsonb,
   $lh$
{
  "orderId": "ORD-48120",
  "amount": 24.9
}
$lh$::jsonb,
   true, 'rotate-ccw'),
  ('ebill.activated', 'Bolletta digitale attivata', 'Attivazione della bolletta elettronica', 'SYSTEM', 'SERVICE',
   $lh$
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "required": [
    "contractId"
  ],
  "properties": {
    "contractId": {
      "type": "string",
      "minLength": 1,
      "x-lh-pii": false
    }
  }
}
$lh$::jsonb,
   $lh$
{
  "contractId": "CTR-77120"
}
$lh$::jsonb,
   true, 'file-text'),
  ('directdebit.activated', 'Domiciliazione attivata', 'Attivazione dell''addebito diretto', 'SYSTEM', 'SERVICE',
   $lh$
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "required": [
    "contractId"
  ],
  "properties": {
    "contractId": {
      "type": "string",
      "minLength": 1,
      "x-lh-pii": false
    }
  }
}
$lh$::jsonb,
   $lh$
{
  "contractId": "CTR-77120"
}
$lh$::jsonb,
   true, 'credit-card'),
  ('selfreading.submitted', 'Autolettura inviata', 'Un membro ha inviato l''autolettura del contatore', 'SYSTEM', 'SERVICE',
   $lh$
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "required": [
    "meterId",
    "reading"
  ],
  "properties": {
    "meterId": {
      "type": "string",
      "minLength": 1,
      "x-lh-pii": false
    },
    "reading": {
      "type": "number",
      "minimum": 0,
      "x-lh-pii": false
    }
  }
}
$lh$::jsonb,
   $lh$
{
  "meterId": "MTR-9931",
  "reading": 14820
}
$lh$::jsonb,
   true, 'gauge'),
  ('app.login.daily', 'Accesso giornaliero all''app', 'Primo accesso della giornata all''app', 'SYSTEM', 'ENGAGEMENT',
   $lh$
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "platform": {
      "enum": [
        "IOS",
        "ANDROID",
        "WEB"
      ],
      "x-lh-pii": false
    }
  },
  "additionalProperties": true
}
$lh$::jsonb,
   $lh$
{
  "platform": "IOS"
}
$lh$::jsonb,
   true, 'smartphone'),
  ('survey.completed', 'Survey completata', 'Un membro ha completato una survey di un partner', 'SYSTEM', 'ENGAGEMENT',
   $lh$
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "required": [
    "surveyId"
  ],
  "properties": {
    "surveyId": {
      "type": "string",
      "minLength": 1,
      "x-lh-pii": false
    },
    "score": {
      "type": "integer",
      "minimum": 0,
      "maximum": 100,
      "x-lh-pii": false
    }
  }
}
$lh$::jsonb,
   $lh$
{
  "surveyId": "SRV-2025-09",
  "score": 80
}
$lh$::jsonb,
   true, 'clipboard-list'),
  ('quiz.completed', 'Quiz completato', 'Un membro ha completato un quiz di un partner', 'SYSTEM', 'ENGAGEMENT',
   $lh$
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "required": [
    "quizId",
    "correctAnswers",
    "totalQuestions"
  ],
  "properties": {
    "quizId": {
      "type": "string",
      "minLength": 1,
      "x-lh-pii": false
    },
    "correctAnswers": {
      "type": "integer",
      "minimum": 0,
      "x-lh-pii": false
    },
    "totalQuestions": {
      "type": "integer",
      "minimum": 1,
      "x-lh-pii": false
    }
  }
}
$lh$::jsonb,
   $lh$
{
  "quizId": "QZ-AUTUNNO",
  "correctAnswers": 8,
  "totalQuestions": 10
}
$lh$::jsonb,
   true, 'help-circle'),
  ('review.submitted', 'Recensione inviata', 'Un membro ha recensito un prodotto', 'SYSTEM', 'ENGAGEMENT',
   $lh$
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "required": [
    "productId",
    "rating"
  ],
  "properties": {
    "productId": {
      "type": "string",
      "minLength": 1,
      "x-lh-pii": false
    },
    "rating": {
      "type": "integer",
      "minimum": 1,
      "maximum": 5,
      "x-lh-pii": false
    }
  }
}
$lh$::jsonb,
   $lh$
{
  "productId": "SKU-100",
  "rating": 5
}
$lh$::jsonb,
   true, 'star'),
  ('newsletter.subscribed', 'Iscrizione newsletter', 'Un membro si è iscritto alla newsletter', 'SYSTEM', 'ENGAGEMENT',
   $lh$
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {}
}
$lh$::jsonb,
   $lh$
{}
$lh$::jsonb,
   true, 'mail'),
  ('member.registered', 'Registrazione membro', 'Azione interna: un nuovo membro si è registrato', 'SYSTEM', 'INTERNAL',
   $lh$
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "channel": {
      "type": "string",
      "x-lh-pii": false
    },
    "referred": {
      "type": "boolean",
      "x-lh-pii": false
    }
  }
}
$lh$::jsonb,
   $lh$
{
  "channel": "WEB",
  "referred": false
}
$lh$::jsonb,
   true, 'user-plus'),
  ('member.profile.completed', 'Profilo completato', 'Azione interna: il membro ha completato il profilo', 'SYSTEM', 'INTERNAL',
   $lh$
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "memberId": {
      "type": "string",
      "pattern": "^MBR-[0-9]{6}$",
      "x-lh-pii": false
    }
  },
  "additionalProperties": true
}
$lh$::jsonb,
   $lh$
{}
$lh$::jsonb,
   true, 'user-check'),
  ('member.birthday', 'Compleanno membro', 'Azione interna: ricorrenza di compleanno', 'SYSTEM', 'INTERNAL',
   $lh$
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "age": {
      "type": "integer",
      "minimum": 0,
      "x-lh-pii": false
    }
  }
}
$lh$::jsonb,
   $lh$
{
  "age": 34
}
$lh$::jsonb,
   true, 'cake'),
  ('tier.upgraded', 'Passaggio di livello', 'Azione interna: il membro è salito di livello', 'SYSTEM', 'INTERNAL',
   $lh$
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "required": [
    "newTier"
  ],
  "properties": {
    "previousTier": {
      "type": "string",
      "x-lh-pii": false
    },
    "newTier": {
      "type": "string",
      "x-lh-pii": false
    }
  }
}
$lh$::jsonb,
   $lh$
{
  "previousTier": "SILVER",
  "newTier": "GOLD"
}
$lh$::jsonb,
   true, 'trending-up'),
  ('instantwin.won', 'Vincita instant win', 'Azione interna: vincita a un concorso instant win', 'SYSTEM', 'INTERNAL',
   $lh$
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "required": [
    "contestCode",
    "prizeCode",
    "prizeType"
  ],
  "properties": {
    "contestCode": {
      "type": "string",
      "pattern": "^[A-Z][A-Z0-9-]{2,39}$",
      "x-lh-pii": false
    },
    "playId": {
      "type": "string",
      "minLength": 1,
      "x-lh-pii": false
    },
    "prizeCode": {
      "type": "string",
      "pattern": "^[A-Z][A-Z0-9-]{2,39}$",
      "x-lh-pii": false
    },
    "prizeName": {
      "type": "string",
      "x-lh-pii": false
    },
    "prizeType": {
      "enum": [
        "POINTS",
        "COUPON",
        "PHYSICAL"
      ],
      "x-lh-pii": false
    },
    "points": {
      "type": "integer",
      "minimum": 1,
      "x-lh-pii": false
    },
    "rewardCode": {
      "type": "string",
      "pattern": "^[A-Z][A-Z0-9-]{2,39}$",
      "x-lh-pii": false
    }
  },
  "additionalProperties": true
}
$lh$::jsonb,
   $lh$
{
  "contestCode": "IW-AUTUNNO",
  "prizeCode": "PZ-500",
  "prizeType": "POINTS",
  "points": 500
}
$lh$::jsonb,
   true, 'gift'),
  ('achievement.completed', 'Obiettivo completato', 'Azione interna: obiettivo di gamification raggiunto', 'SYSTEM', 'INTERNAL',
   $lh$
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "required": [
    "achievementCode",
    "periodKey"
  ],
  "properties": {
    "achievementCode": {
      "type": "string",
      "pattern": "^[A-Z][A-Z0-9-]{2,39}$",
      "x-lh-pii": false
    },
    "achievementName": {
      "type": "string",
      "minLength": 1,
      "x-lh-pii": false
    },
    "periodKey": {
      "type": "string",
      "minLength": 1,
      "x-lh-pii": false
    }
  },
  "additionalProperties": true
}
$lh$::jsonb,
   $lh$
{
  "achievementCode": "ACH-3-PURCHASES",
  "periodKey": "2026-Q3"
}
$lh$::jsonb,
   true, 'target'),
  ('badge.awarded', 'Badge assegnato', 'Azione interna: assegnato un badge', 'SYSTEM', 'INTERNAL',
   $lh$
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "required": [
    "badgeCode"
  ],
  "properties": {
    "badgeCode": {
      "type": "string",
      "pattern": "^[A-Z][A-Z0-9-]{2,39}$",
      "x-lh-pii": false
    },
    "badgeName": {
      "type": "string",
      "minLength": 1,
      "x-lh-pii": false
    },
    "origin": {
      "enum": [
        "ACHIEVEMENT",
        "CAMPAIGN"
      ],
      "x-lh-pii": false
    }
  },
  "additionalProperties": true
}
$lh$::jsonb,
   $lh$
{
  "badgeCode": "BDG-EXPLORER"
}
$lh$::jsonb,
   true, 'award'),
  ('referral.completed', 'Referral completato', 'Azione interna: referral andato a buon fine', 'SYSTEM', 'INTERNAL',
   $lh$
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "required": [
    "role",
    "counterpartMemberId"
  ],
  "properties": {
    "role": {
      "enum": [
        "REFERRER",
        "REFEREE"
      ],
      "x-lh-pii": false
    },
    "counterpartMemberId": {
      "type": "string",
      "pattern": "^MBR-[0-9]{6}$",
      "x-lh-pii": false
    },
    "qualifyingActionId": {
      "type": "string",
      "minLength": 1,
      "x-lh-pii": false
    }
  },
  "additionalProperties": true
}
$lh$::jsonb,
   $lh$
{
  "role": "REFERRER",
  "counterpartMemberId": "MBR-000009"
}
$lh$::jsonb,
   true, 'users'),
  ('reward.redeemed', 'Premio riscattato', 'Azione interna: conferma del riscatto di un premio', 'SYSTEM', 'INTERNAL',
   $lh$
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "required": [
    "rewardCode",
    "pointsCost"
  ],
  "properties": {
    "rewardCode": {
      "type": "string",
      "pattern": "^[A-Z][A-Z0-9-]{2,39}$",
      "x-lh-pii": false
    },
    "pointsCost": {
      "type": "integer",
      "minimum": 1,
      "x-lh-pii": false
    },
    "redemptionId": {
      "type": "string",
      "x-lh-pii": false
    },
    "rewardName": {
      "type": "string",
      "x-lh-pii": false
    }
  },
  "additionalProperties": true
}
$lh$::jsonb,
   $lh$
{
  "rewardCode": "RWD-COFFEE-5",
  "pointsCost": 500
}
$lh$::jsonb,
   true, 'package-check')
ON CONFLICT (code) DO NOTHING;

INSERT INTO internal_mapping (fact_type, action_type, enabled) VALUES
  ('fact.member.registered', 'member.registered', true),
  ('fact.member.profile.completed', 'member.profile.completed', true),
  ('fact.member.birthday', 'member.birthday', true),
  ('fact.tier.upgraded', 'tier.upgraded', true),
  ('fact.contest.won', 'instantwin.won', true),
  ('fact.achievement.completed', 'achievement.completed', true),
  ('fact.badge.awarded', 'badge.awarded', true),
  ('fact.referral.completed', 'referral.completed', true),
  ('fact.reward.redemption.confirmed', 'reward.redeemed', true)
ON CONFLICT (fact_type) DO NOTHING;
