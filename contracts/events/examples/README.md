# Esempi di eventi — un file valido per ogni `type` (docs/05 §9)

Nome file: `<famiglia>.<nome>.json` (es. `action.purchase.completed.json`). Ogni file è un envelope completo,
valido contro `../envelope.schema.json` e contro `../<famiglia>/<nome>.schema.json`.

Formano una catena coerente (stesso `lhcorrelationid`): acquisto → valutazione campagne → effetto punti →
accredito a wallet. Verificati da `ContractsTest` in `libs/lh-common`.
