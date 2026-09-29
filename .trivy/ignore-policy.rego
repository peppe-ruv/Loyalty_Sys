# Eccezioni con ambito preciso per la scansione IaC del job `security` (docs/security/ci-security.md).
# Trivy le applica con `--ignore-policy`. Ogni eccezione ha: la ragione, il riferimento a docs/15 o docs/19 e una
# scadenza; scaduta, la segnalazione torna e il job fallisce, così la decisione non resta dimenticata.
# Le eccezioni sui pacchetti e sui file di configurazione con ambito per percorso sono in `.trivyignore.yaml`.
package trivy

import rego.v1

default ignore := false

# Keycloak (ruolo `idp`) senza filesystem in sola lettura: `kc.sh start` senza `--optimized` ricompila la propria
# configurazione all'avvio e scrive nella sua cartella; si chiude con un'immagine ottimizzata (Q-374, TOBE-007).
# Ambito: solo il container `idp` (la stessa segnalazione su hub, web o migrazioni resta un errore).
# Scadenza: 2027-03-31.
ignore if {
	input.ID == "KSV-0014"
	contains(input.Message, "Container 'idp' of Deployment")
	time.now_ns() < time.parse_rfc3339_ns("2027-03-31T23:59:59Z")
}
