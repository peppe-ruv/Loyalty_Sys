# contracts/events — schemi degli eventi (docs/05)

JSON Schema **2020-12**. Contratto immutabile tra i servizi (`CLAUDE.md §6`, docs/05 §9):
un campo opzionale nuovo resta nella stessa versione; un cambio incompatibile è `:<n+1>` nel `dataschema` + ADR.

```
envelope.schema.json          envelope CloudEvents 1.0 (attributi lh*)
<famiglia>/<nome>.schema.json schema del payload data per ogni type (action | effect | fact | audit)
examples/<famiglia>.<nome>.json  un esempio valido per ogni type
```

Il test di contratto `ContractsTest` (in `libs/lh-common`) verifica che ogni esempio validi contro l'envelope
e contro lo schema del proprio `type`, e che ogni schema abbia un esempio.

Copertura attuale: gli eventi di **M1** (core loop). Le famiglie si estendono con le milestone successive.

> Nota (SPEC-GAP Q-42): docs/05 §9 cita `contracts/examples/`; qui si segue la struttura di `CLAUDE.md §3`
> (`contracts/events/examples/`) mantenendo il nome file `<famiglia>.<nome>.json` di §9.
