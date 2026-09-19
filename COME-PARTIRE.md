# Come partire con Claude Code

1. **Crea il repository nuovo** (vuoto, pubblico) — nome proposto `loyalty-hub` (`docs/15`, Q-02).
2. **Copia questa cartella alla radice** del repo così com'è: `CLAUDE.md`, `README.md`, `docs/`. Primo commit: `docs: specifiche iniziali del PoC`.
3. **Rispondi alle domande bloccanti** in `docs/15-DOMANDE-APERTE.md`: licenza (Q-01) e nome repo (Q-02) bastano per partire.
4. Apri Claude Code nella radice del repo e incolla il **primo prompt**:

> Leggi `CLAUDE.md`, poi `docs/00-INDICE.md`, `docs/04-ARCHITETTURA.md`, `docs/12-PIANO-DI-SVILUPPO.md` e `docs/14-STATO-AVANZAMENTO.md`. Non scrivere codice applicativo in questa sessione. Realizza solo la fetta **M0.1**: parent `pom.xml` (Java 25, Spring Boot 4.1.x, moduli vuoti per `libs/lh-common` e per gli 8 servizi), Maven wrapper, `.editorconfig`, `.gitignore`, `.dockerignore`, `LICENSE`, struttura delle cartelle di `CLAUDE.md §3` con un `README.md` di una riga in ogni cartella vuota. Verifica con `./mvnw -q verify`. Alla fine aggiorna `docs/14` e proponi il messaggio di commit. Se trovi ambiguità, aggiungi una voce in `docs/15` e applica il default proposto.

5. Procedi **una fetta per sessione**, nell'ordine di `docs/12`. A inizio sessione il prompt è sempre dello stesso tipo:

> Leggi `CLAUDE.md` e `docs/14`. Realizza la fetta **M‹x.y›** come descritta in `docs/12`, leggendo prima i documenti che indica. Test prima del codice dove la fetta tocca regole di dominio o consumer. Chiudi aggiornando `docs/14`.

6. **Primo deploy** alla fetta `M1.8`, seguendo `docs/11 §2`. L'unico passo manuale è la creazione del Kafka gratuito con i suoi 5 topic (`docs/11 §3`); Neon, Render e Vercel si possono creare dai connettori.

**Tre cose da ricordare**
- Le specifiche sono la fonte di verità: se il codice deve discostarsene, si cambia **prima** la specifica (o si apre una `Q-nn`).
- Niente schermate "vuote in attesa del backend": una voce di menu compare solo quando la sua milestone è chiusa.
- Solo dati fittizi, sempre: la demo non ha autenticazione.
