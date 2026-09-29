# METADATA
# title: Servizio Docker Compose con porte pubblicate su tutte le interfacce
# description: >-
#   Una porta pubblicata senza indirizzo di ascolto (`8080:8080`) è raggiungibile da qualunque interfaccia
#   dell'host, non solo da localhost (docs/18 §3.10 punto 10, ADR-042, ADR-044). Trivy non conosce Docker Compose:
#   il controllo del progetto lo legge come YAML generico.
# custom:
#   id: LH-DC-0002
#   avd_id: LH-DC-0002
#   severity: HIGH
#   short_code: compose-porte
#   recommended_action: >-
#     Indica l'indirizzo di ascolto: `127.0.0.1:porta:porta` o una variabile con default `127.0.0.1`
#     (`${LH_BIND_ADDRESS:-127.0.0.1}:porta:porta`).
#   input:
#     selector:
#       - type: yaml
package user.compose.lh0002

import rego.v1

# Solo i file Compose: `services` è un oggetto di servizi con `image` o `build`.
is_service(svc) if {
	is_object(svc)
	object.get(svc, "image", null) != null
}

is_service(svc) if {
	is_object(svc)
	object.get(svc, "build", null) != null
}

services[name] := svc if {
	is_object(input.services)
	some name, svc in input.services
	is_service(svc)
}

# Forme ammesse: `127.0.0.1:80:80`, `127.0.0.1::80` (porta dell'host scelta da Docker, sempre su loopback),
# `${VAR:-127.0.0.1}:80:80`, `[::1]:80:80`, un altro indirizzo esplicito, oppure la forma estesa con `host_ip`.
# Sono pubbliche, quindi segnalate, anche le forme con indirizzo che vuol dire «tutte le interfacce»: `0.0.0.0:`,
# `[::]:`, una variabile con default `0.0.0.0` o `::` e `host_ip: 0.0.0.0` o `::`.
deny contains res if {
	some name, svc in services
	some port in svc.ports
	not port_has_address(port)
	res := result.new(
		sprintf("Il servizio '%s' pubblica la porta %v su tutte le interfacce: indica l'indirizzo di ascolto.", [name, port]),
		svc,
	)
}

# Forma estesa: `host_ip` presente e non jolly.
port_has_address(port) if {
	is_object(port)
	host_ip := object.get(port, "host_ip", "")
	host_ip != ""
	not host_ip in {"0.0.0.0", "::", "[::]"}
}

# Forma breve: `<indirizzo>:<porta host, anche vuota>:<porta container>[/protocollo]`, con indirizzo non jolly.
port_has_address(port) if {
	is_string(port)
	regex.match(`^(\$\{[^}]+\}|\[[0-9a-fA-F:]+\]|[0-9]{1,3}(\.[0-9]{1,3}){3}):[0-9-]*:[0-9-]+(/(tcp|udp))?$`, port)
	not wildcard_address(port)
}

wildcard_address(port) if regex.match(`^(0\.0\.0\.0|\[::\]):`, port)

wildcard_address(port) if regex.match(`^\$\{[^}]*:?-(0\.0\.0\.0|\[::\]|::)\}:`, port)
