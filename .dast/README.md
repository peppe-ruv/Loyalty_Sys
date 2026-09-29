# .dast — configurazione della verifica dinamica notturna (M8.11c)

Cartella nascosta come `.semgrep` e `.trivy`: non si chiama `.schemathesis` perché quel nome è la cartella di cache di
Schemathesis. La spiegazione completa, con cosa blocca e come si riproduce in locale, è in
[`docs/security/dast.md`](../docs/security/dast.md). Il workflow è `.github/workflows/security-nightly.yml`.

| File | Cosa contiene |
|---|---|
| `schemathesis.toml` | Configurazione di Schemathesis: identità demo, solo il controllo sui 5xx, limite di frequenza sull'ingresso, baseline |
| `schemathesis-baseline.json` | 5xx già noti e accettati. Ogni voce ha `expires` (entro 90 giorni), `reason` (causa verificata) e `ticket` (`Q-nnn` o `TOBE-nnn`) |
| `zap-exceptions.json` | Avvisi High di ZAP accettati: `pluginId`, `reason`, `ref` (`Q-nnn` o `TOBE-nnn`) e `expires` (entro 90 giorni); facoltativi `method` e `uriRegex` |
| `exclusions.json` | Cosa non si prova, e perché: specifiche saltate, operazioni escluse, media type di risposta (flussi SSE) |

Regola: **nessuna eccezione senza scadenza, motivo e Q/TOBE**. `node scripts/security-dast.mjs baseline-check` e
`zap-gate` rifiutano una voce che non li ha; `prepare` rifiuta un'esclusione senza motivo o che non corrisponde più a nulla.
