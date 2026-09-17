# Contratti · Contracts

> 🇮🇹 Documento bilingue. 🇬🇧 Bilingual document.

Due tipi di contratto, con due destini diversi.

**EN** — Two kinds of contract, with two different purposes.

| File | Cos'è · What it is | Chi lo mantiene · Maintained by |
| --- | --- | --- |
| `openapi-ingress.yaml` | Contratto curato per chi integra dall'esterno: autenticazione, semantica degli esiti, esempi, casi d'errore. Dice cose che il codice non sa dire · Curated contract for external integrators: authentication, outcome semantics, examples, error cases | A mano, insieme alla modifica · by hand, together with the change |
| `asyncapi.yaml` | Topic, buste CloudEvents e payload degli eventi di dominio · topics, CloudEvents envelopes and domain event payloads | A mano, insieme alla modifica · by hand, together with the change |
| `generated/openapi-<servizio>.yaml` | Specifica REST di ogni servizio, esattamente come il servizio la espone · each service's REST spec, exactly as the service exposes it | Generata dal codice · generated from the code |

## Specifiche generate · Generated specs

Le specifiche in `generated/` non si modificano a mano: le scrive `OpenApiContractTest`, che a ogni esecuzione dei test
confronta il file committato con quella che il servizio espone davvero. Se un'API cambia e il contratto non viene
aggiornato, il test fallisce: è la rete che impedisce a un cambio di contratto di passare inosservato.

Per rigenerarle dopo una modifica voluta:

```sh
mvn -f services/pom.xml -pl <servizio> test -Dopenapi.write=true   # un servizio
mvn -f services/pom.xml test -Dtest='*OpenApiTest' -Dopenapi.write=true   # tutti
```

L'indirizzo del server non fa parte del contratto (lo decide chi installa) e viene tolto dal file; la versione
dichiarata è quella dell'**API** (`v1`, il prefisso dei percorsi), non quella del rilascio, così le specifiche non si
sporcano a ogni build.

**EN** — Specs under `generated/` are never edited by hand: `OpenApiContractTest` writes them and, on every test run,
compares the committed file with what the service actually exposes. If an API changes and the contract is not updated,
the test fails — the safety net that stops a contract change from slipping through unnoticed. Regenerate with the
commands above after an intentional change. The server address is not part of the contract (the installer decides it)
and is stripped from the file; the declared version is the **API** version (`v1`, the path prefix), not the release,
so specs do not churn on every build.
