Sommario
Aggiunti test di unità per coprire il componente web `GeneratedSentence` e la funzione helper `GeneratedText`.

Test aggiunti
- `GeneratedText`: Verifica il corretto rendering dei tag bold (`**testo**`), l'assenza di render bold senza i tag, e il comportamento in caso di testo vuoto.
- `GeneratedSentence`: Verifica i flussi (happy path, `needsContests`, default overrides) assicurandosi che le query e la funzione di default per il `describeCampaign` vengano chiamate in maniera corretta con e senza i parametri opzionali.

Mutation check eseguiti
- Modificato temporaneamente il rendering del bold, cambiandolo da `<strong>` a `<b>` nel testo generato del componente (e rimossi i successivi attributi di classe), verificando il conseguente fallimento dei nuovi test, e quindi risistemando al funzionamento originale.

Bug trovati
- Nessuno

Da decidere
- Nessuno

Comandi eseguiti con esito e durata
- `pnpm test` → Eseguito con successo su tutti i check test.
- `pnpm lint` → Eseguito con successo.
- `pnpm typecheck` → Eseguito con successo.
