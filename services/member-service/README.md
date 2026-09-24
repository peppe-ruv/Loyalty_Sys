# member-service — porta 8082, schema `member` (docs/04 §3, docs/servizi/member-service.md).

M1.2: anagrafica (`member`), stati, ricerca, proiezione saldi/tier (`member_projection`),
statistiche di attività (`member_stats`); produce i fatti `member.registered/updated/status.changed`,
consuma `lh.actions.v1` (→ stats) e `lh.facts.v1` (`wallet.points.*`, `tier.*` → proiezione).
M5: referral. M6.6: segmenti statici e dinamici (`segment`, `segment_member`, V2), criteri sullo spazio `member.*`
esteso (docs/03 §10), anteprima, ricalcolo su richiesta / dopo il reset / ogni 15 minuti con `loyaltyhub.jobs.enabled`
(job demo `POST /v1/demo/jobs/refresh-segments`), fatti `member.segment.entered/left` solo per le differenze;
statistiche dei seed da `seed/activity-history.json`. Attributi personalizzati (M6.7) e anonimizzazione (M7) dopo.
