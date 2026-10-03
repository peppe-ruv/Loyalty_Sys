# Journey di regressione

Un seme di una journey casuale che ha fallito diventa qui una journey fissa (ADR-053 decisione 4, Q-693, F2-QA-07). La
cartella è vuota finché nessun seme ha fallito: senza file `.json` la prova `tests/journey-regressioni.spec.ts` non
esegue nulla e passa.

## Formato

Un file JSON per seme, chiamato `<seme>.json` (il seme della journey, non quello principale della corsa):

```json
{
  "seed": 1234567890,
  "failure": "passo 7 (credit m1 120 TEST): saldo letto 100, atteso 220",
  "steps": [
    { "kind": "registerMember", "ref": "m1" },
    { "kind": "credit", "member": "m1", "amount": 120, "reason": "TEST" },
    { "kind": "checkpoint" }
  ]
}
```

| Campo | Significato |
| --- | --- |
| `seed` | Intero da 1 a 4294967295. Serve a ritrovare la journey nel registro della corsa. |
| `failure` | Motivo breve scritto dall'harness (al più 300 caratteri). Solo informativo: non è letto dai test. |
| `steps` | Passi eseguiti, nell'ordine. Sono i dati che si rigiocano: non si rigenerano dal seme, così la journey resta uguale anche se il generatore cambia. |

I passi ammessi sono quelli di `journeys/generator.ts` (`registerMember`, `credit`, `debit`, `overdraw`, `readWallet`,
`createCategory`, `renameCategory`, `createReward`, `restockReward`, `checkpoint`). Un file con un passo sconosciuto, un
riferimento fuori forma o un importo fuori limite fa fallire il caricamento: non si ignora. I file non contengono dati
personali né credenziali (i riferimenti `m1`, `c1`, `r1` sono locali; gli indirizzi dei membri di prova si calcolano a
partire dal seme).

## Come nasce un file

1. Una journey casuale fallisce in CI (job `e2e-pr` o workflow `e2e-nightly`) o in locale. L'harness scrive
   `journeys/regressioni/<seme>.json` e stampa seme e passi nel registro. In CI il file va a finire nell'artefatto della
   corsa (`e2e-pr-report`, `e2e-nightly-seed-<run_id>`) e il seme è elencato nel riepilogo del job.
2. Si scarica il file, si capisce il difetto e lo si corregge. Nella stessa pull request si committa il file in questa
   cartella (anche rinominandolo in `<seme>-<descrizione>.json`): da quel momento ogni PR rigioca quella journey.
3. Una regressione si toglie solo con una decisione esplicita nella pull request (il test non si indebolisce).

Una journey che fallisce per un guasto dell'ambiente (stack non pronto, timeout) produce comunque il suo file: prima di
committarlo si verifica che il seme fallisca di nuovo su uno stack sano.

## Come si rigioca

I file di questa cartella girano con le altre prove (`pnpm test`, oppure `pnpm exec playwright test tests/journey-regressioni.spec.ts`)
sullo stack di `bash scripts/smoke-enterprise.sh up`. Per ripetere un'intera corsa casuale si imposta lo stesso seme
principale: `LH_JOURNEY_SEED=<seme principale> pnpm test`.
