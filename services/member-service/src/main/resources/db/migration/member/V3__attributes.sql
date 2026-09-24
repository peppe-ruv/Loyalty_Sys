-- V3 — definizioni degli attributi personalizzati (docs/servizi/member-service.md §2, F-MBR-03, M6.7).
-- I valori vivono in member.attributes (jsonb, già da V1); qui solo chiave, etichetta, tipo e opzioni.

CREATE TABLE IF NOT EXISTS attribute_definition (
  key       text PRIMARY KEY,                -- householdSize: usato come member.attributes.<key> nelle condizioni
  label     text NOT NULL,
  type      text NOT NULL,                   -- STRING | NUMBER | BOOLEAN | DATE
  options   text[],                          -- solo STRING: valori ammessi (vuoto = testo libero)
  position  integer NOT NULL DEFAULT 0       -- ordine di BO-03 e del costruttore di condizioni
);
