# member-service — porta 8082, schema `member` (docs/04 §3, docs/servizi/member-service.md).

M1.2: anagrafica (`member`), stati, ricerca, proiezione saldi/tier (`member_projection`),
statistiche di attività (`member_stats`); produce i fatti `member.registered/updated/status.changed`,
consuma `lh.actions.v1` (→ stats) e `lh.facts.v1` (`wallet.points.*`, `tier.*` → proiezione).
Segmenti, attributi, referral e anonimizzazione arrivano con M5/M6/M7.
