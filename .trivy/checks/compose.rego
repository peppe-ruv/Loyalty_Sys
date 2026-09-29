# METADATA
# title: Servizio Docker Compose con privilegi eccessivi
# description: >-
#   Trivy non conosce Docker Compose: questo controllo del progetto lo legge come YAML generico (docs/18 §3.10
#   punto 10, ADR-042, ADR-044). Segnala i servizi che tolgono l'isolamento del container (modalità privilegiata,
#   rete o PID dell'host, socket di Docker, capability pericolose, profili seccomp o AppArmor disattivati, utente
#   root). Le porte pubblicate su tutte le interfacce hanno un controllo a parte (LH-DC-0002).
# custom:
#   id: LH-DC-0001
#   avd_id: LH-DC-0001
#   severity: HIGH
#   short_code: compose-privilegi
#   recommended_action: >-
#     Togli l'impostazione, oppure isola il servizio.
#   input:
#     selector:
#       - type: yaml
package user.compose.lh0001

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

deny contains res if {
	some name, svc in services
	svc.privileged == true
	res := result.new(sprintf("Il servizio '%s' gira in modalità privilegiata (privileged: true).", [name]), svc)
}

deny contains res if {
	some name, svc in services
	some key in ["network_mode", "pid", "ipc", "userns_mode", "uts"]
	svc[key] == "host"
	res := result.new(sprintf("Il servizio '%s' condivide con l'host il namespace '%s'.", [name, key]), svc)
}

deny contains res if {
	some name, svc in services
	some vol in svc.volumes
	contains(volume_text(vol), "docker.sock")
	res := result.new(sprintf("Il servizio '%s' monta il socket di Docker: equivale a root sull'host.", [name]), svc)
}

deny contains res if {
	some name, svc in services
	some cap in svc.cap_add
	upper(cap) in {"ALL", "SYS_ADMIN", "NET_ADMIN", "SYS_PTRACE", "SYS_MODULE", "DAC_READ_SEARCH", "NET_RAW"}
	res := result.new(sprintf("Il servizio '%s' aggiunge la capability pericolosa '%s'.", [name, cap]), svc)
}

deny contains res if {
	some name, svc in services
	some opt in svc.security_opt
	regex.match(`^(seccomp|apparmor)[=:]unconfined$`, lower(opt))
	res := result.new(sprintf("Il servizio '%s' disattiva il profilo di sicurezza ('%s').", [name, opt]), svc)
}

deny contains res if {
	some name, svc in services
	is_root(svc.user)
	res := result.new(sprintf("Il servizio '%s' gira come root (user: %v).", [name, svc.user]), svc)
}

is_root(user) if {
	is_string(user)
	regex.match(`^(root|0)(:.*)?$`, user)
}

is_root(user) if {
	is_number(user)
	user == 0
}

volume_text(vol) := vol if is_string(vol)

volume_text(vol) := object.get(vol, "source", "") if is_object(vol)
