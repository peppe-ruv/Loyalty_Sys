# Addendum alla Specifica Loyalty Hub — Linee guida UX del backoffice (0.6.0)

Da inserire nella sezione «Backoffice e CMS» di [`SPECIFICATION.md`](SPECIFICATION.md), dopo i requisiti trasversali RF-40..RF-46. Riferimenti: ADR-026, `docs/LINEE-GUIDA-UX-BACKOFFICE.md`.

## Linee guida UX (LG-01..LG-48)

Dal 17 settembre 2026 il backoffice segue le 48 linee guida derivate dall'analisi della baseline di mercato: navigazione a gruppi (Amministrazione, Generale, Moduli loyalty, Concorsi e programma, Contenuti, Decisioni), tabella standard con filtri a chip per ogni lista, form a sezioni fisse, condizioni scritte come frasi, effetti con formula guidata, profilo membro a due colonne con timeline, KPI come tab di un solo grafico con confronto al periodo precedente. Quindici pattern UI formano il design system (`web/backoffice-design-system/`), da costruire prima dei moduli.

**Requisiti UX (RF-137 – RF-142)**

- RF-137 Ogni oggetto configurabile espone lo stato di workflow di ADR-014 (Bozza, In revisione, Approvato, Verifica Legal, Programmato, Pubblicato, Bloccato) come ultima sezione del form, con testo che spiega l'effetto dello stato e transizioni filtrate per ruolo; non esiste un toggle Attivo/Inattivo separato dal workflow.
- RF-138 Tutti i costruttori di condizioni (campagne, segmenti, tier, achievement, policy decisionali, regole frode) usano la stessa semantica — AND tra le condizioni di una regola, OR tra regole — e mostrano sempre l'operatore logico tra ogni coppia di righe.
- RF-139 Le frasi, le chip e le tabelle mostrano solo nomi leggibili degli oggetti referenziati; gli identificativi tecnici compaiono unicamente nella scheda identità con icona di copia.
- RF-140 Ogni operazione manuale su un membro (accredito, storno, correzione achievement, tier forzato, merge identità) richiede un commento obbligatorio, mostra «richiede seconda approvazione» sopra la soglia quattro occhi (RF-18) e compare nella timeline con autore e motivo (RF-41).
- RF-141 Segmenti, campagne, achievement, collection e premi mostrano un riquadro «Usato da / Usa» con le dipendenze navigabili; l'eliminazione è bloccata finché l'oggetto è referenziato.
- RF-142 Il modulo concorsi (programma annuale, missioni, instant win) usa lo stesso scheletro di form dei moduli loyalty (tipo → base → logica → limiti → stato), lo stesso cruscotto KPI e le stesse card CMS collegate; per gli istanti vincenti mostra solo il conteggio residuo (RF-31).

## Punti aperti aggiunti

- [ ] Prodotto: contratto del simulatore di campagne (input membro/evento di prova, output regole scattate e unità) — riuso di `POST /v1/evaluations`
- [ ] Marketing: estendere la galleria modelli (LG-31) alle campagne tipiche di una utility (autolettura, bolletta digitale, domiciliazione)
- [ ] Prodotto: un effetto muove un solo wallet (proposta) o entrambi
- [ ] Dati: quali provider di previsione alimentano le metriche predittive del profilo (RF-129)

---

## CHANGELOG — 0.6.0 (2026-09-17)

### Aggiunto
- `docs/LINEE-GUIDA-UX-BACKOFFICE.md`: 48 linee guida UX con mappa video → LG → RF
- `docs/adr/ADR-026.md`, `docs/adr/ADR-027.md`
- `web/backoffice-design-system/`: contratti dei 15 pattern (`patterns.ts`), token sulle fondamenta Design Tokens Italia (`tokens.css`, ADR-027), componenti React (`src/components/`), README con ordine di costruzione
- Requisiti RF-137..RF-142 (addendum alla specifica)

### Modificato
- Navigazione del backoffice: sei gruppi (Amministrazione, Generale, Moduli loyalty, Concorsi e programma, Contenuti, Decisioni)
- Piano di rilascio fase Core: design system prima dei moduli (+3 settimane/persona)

### Da fare nel repository dopo il merge
- Sostituire in `cms/` i campi `active: boolean` isolati con il gruppo workflow (RF-137) dove non già presente
- Allineare `bi/dashboards/` ai nomi KPI di LG-40 e al confronto periodo precedente (LG-41)
