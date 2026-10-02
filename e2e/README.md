# e2e — harness Playwright

Cancello end-to-end di Loyalty Hub (ADR-052 livello 1, M9.1, F2-QA-01). Un browser vero (Chromium) fa il login OIDC reale del backoffice, con la MFA del realm, sullo stesso stack del job CI `smoke enterprise (compose, OIDC)`: compose di riferimento in profilo `enterprise`, proxy TLS di prova, overlay di test del realm.

Pacchetto a sé (pnpm, con il suo `pnpm-lock.yaml`): non c'è un workspace radice, quindi si lancia da `e2e/` o con `pnpm --dir e2e`.

## Prove

| File | Cosa verifica |
| --- | --- |
| `tests/accesso-operatore.setup.ts` | `marta.admin` entra dal login di Keycloak con cambio della password temporanea, OTP e arriva alla dashboard (BO-01). Salva la sessione per le altre prove. |
| `tests/senza-sessione.spec.ts` | Senza sessione le API del BFF rispondono `401 UNAUTHENTICATED` e `/backoffice` porta al login (client `web`, PKCE S256). |
| `tests/audit-configurazione.spec.ts` | Una scrittura di configurazione lascia una voce in `GET /v1/audit` con l'attore reale (regola 21), visibile anche nella schermata Audit. |
| `tests/membro-backoffice.spec.ts` | `testmember` non apre il backoffice né le sue API (rifiutato dal BFF, oppure `403 FORBIDDEN_ROLE`). |

La scrittura di configurazione della terza prova è «crea categoria premi». Il backoffice non ha una schermata per crearla, quindi parte dal contesto della pagina autenticata, dal proxy del BFF e con il token CSRF, come le chiamate del frontend. La verifica dell'audit è invece anche nell'interfaccia.

## Come si lancia in locale

Servono Docker, Node 22, pnpm 10, l'immagine unica e i nomi `web.lh.test` e `idp.lh.test` verso `127.0.0.1` in `/etc/hosts`.

```bash
docker build -f deploy/image/Dockerfile -t lh-image:ci .
echo "127.0.0.1 web.lh.test idp.lh.test" | sudo tee -a /etc/hosts
bash scripts/smoke-enterprise.sh up        # segreti, TLS di prova, stack, overlay del realm
cd e2e && pnpm install --frozen-lockfile
pnpm exec playwright install chromium      # una volta sola
pnpm test
bash ../scripts/smoke-enterprise.sh down   # smonta e cancella segreti e certificati
```

Il primo accesso dell'operatore cambia la sua password e configura l'OTP: dopo una corsa lo stack va ricreato (`down` poi `up`) prima di rilanciare i test. Il report HTML è in `playwright-report/`.

## Variabili d'ambiente

| Variabile | Predefinito | Significato |
| --- | --- | --- |
| `LH_E2E_WEB_URL` | `https://web.lh.test:8443` | Origine del web (BFF). |
| `LH_CI_DIR` | `$RUNNER_TEMP/lh-smoke-enterprise` o `/tmp/lh-smoke-enterprise` | Cartella dello smoke con `secrets/` e `tls/`. |
| `LH_E2E_OPERATOR`, `LH_E2E_MEMBER` | `marta.admin`, `testmember` | Utenti di prova. |
| `LH_E2E_OPERATOR_CREDENTIALS`, `LH_E2E_MEMBER_CREDENTIALS` | `<LH_CI_DIR>/secrets/operatori.txt`, `membro.txt` | File `utente: password` con permessi 0600. |
| `LH_E2E_SERVER_CERT` | `<LH_CI_DIR>/tls/server.crt` | Certificato del proxy di prova per la fiducia TLS. |

## Scelte di sicurezza

- **Credenziali.** Non stanno nel codice, negli argomenti o nei log: si leggono dai file 0600 dello smoke (stesso formato di `deploy/idp/bootstrap.sh`). La nuova password dell'operatore è casuale e resta in memoria; il seme OTP si legge dalla pagina di Keycloak e il codice si calcola nel processo. Gli errori del login non riportano mai i valori digitati.
- **Trace, video e screenshot spenti** dove si digita una password o si vede il segreto OTP: il progetto `setup` e la prova del membro. Una traccia registra il corpo delle richieste. In caso di errore del login la pagina viene svuotata prima dell'istantanea.
- **Sessione dell'operatore** (cookie) in una cartella `0700` di `os.tmpdir()`, fuori da `test-results/` e da `playwright-report/`; la cancella `global-teardown.ts`.
- **TLS di prova.** Chromium non legge `NODE_EXTRA_CA_CERTS`. Il browser accetta solo la chiave del certificato del proxy di prova (`--ignore-certificate-errors-spki-list` con l'impronta SPKI di `server.crt`): ogni altro certificato resta rifiutato e la verifica non si disattiva in generale. Le chiamate HTTP delle prove partono dal browser, quindi Node non deve fidarsi di nessuna CA.
- **Nessun retry** (`retries: 0`): un test rosso non si nasconde. Un solo worker, perché le prove condividono l'operatore.

## Matrice

Oggi c'è il solo progetto `desktop-chromium`. La matrice di browser e viewport è M9.3.
