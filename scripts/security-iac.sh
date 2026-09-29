#!/usr/bin/env bash
# Scansione IaC del job `security` (F2-SEC-03, ADR-042, ADR-044; docs/security/ci-security.md).
# Trivy `config` su: chart Helm reso con `helm template` (gli stessi scenari del job `helm`), compose di riferimento e
# locale (controlli del progetto in .trivy/checks, perché Trivy non conosce Docker Compose) e Dockerfile dell'immagine
# unica, più i Dockerfile di Fase 1 (deploy/hub e services/*: bloccanti solo se hanno `USER`, Q-504). Fallisce su HIGH e
# CRITICAL, salvo le eccezioni con scadenza di .trivyignore.yaml e .trivy/ignore-policy.rego.
#
# Uso (dalla radice del repository):   bash scripts/security-iac.sh
# Variabili: TRIVY (default trivy), HELM (default helm), KUBE_VERSION (default 1.31.0, come il job `helm`),
#            LH_IAC_OUT (cartella dei manifest resi; default una cartella temporanea).
# Le regole di Trivy sono quelle incorporate nella versione fissata (--skip-check-update): il risultato non cambia
# senza cambiare versione dello strumento.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

TRIVY="${TRIVY:-trivy}"
HELM="${HELM:-helm}"
KUBE_VERSION="${KUBE_VERSION:-1.31.0}"
OUT="${LH_IAC_OUT:-$(mktemp -d)}"
mkdir -p "$OUT"
chart=deploy/helm/loyaltyhub

common=(--severity HIGH,CRITICAL --skip-check-update --ignorefile .trivyignore.yaml --ignore-policy .trivy/ignore-policy.rego)
custom=(--config-check .trivy/checks --check-namespaces user --misconfig-scanners yaml,dockerfile,kubernetes
        --file-patterns 'yaml:.*\.ya?ml$')

echo "::group::Controlli del progetto sulla fixture (devono segnalare)"
# Un controllo che non segnala mai nulla è un controllo rotto: la fixture viola di proposito 6 volte LH-DC-0001 e 5
# volte LH-DC-0002 (porta senza indirizzo, 0.0.0.0, [::], default aperto, host_ip jolly).
report=$("$TRIVY" config "${custom[@]}" --severity HIGH,CRITICAL --skip-check-update --format json .trivy/tests)
# `|| true` dentro il gruppo: con zero segnalazioni grep esce con 1 e, con `set -e -o pipefail`, abortirebbe lo script
# prima del messaggio; il confronto sotto dà l'errore giusto.
n1=$({ grep -Eo '"ID": *"LH-DC-0001"' <<< "$report" || true; } | wc -l)
n2=$({ grep -Eo '"ID": *"LH-DC-0002"' <<< "$report" || true; } | wc -l)
echo "LH-DC-0001: $n1 (attese 6), LH-DC-0002: $n2 (attese 5)"
if [ "$n1" -ne 6 ] || [ "$n2" -ne 5 ]; then
  echo "I controlli Compose del progetto non segnalano la fixture come previsto" >&2
  exit 1
fi
echo "::endgroup::"

echo "::group::Chart Helm reso (helm template, valori di CI)"
"$HELM" template lh "$chart" --kube-version "$KUBE_VERSION" -f "$chart/ci/lint-values.yaml" \
  --set gateway.enabled=true --set 'gateway.parentRefs[0].name=gw' > "$OUT/managed.yaml"
"$HELM" template lh "$chart" --kube-version "$KUBE_VERSION" -n lh -f "$chart/ci/kind-values.yaml" > "$OUT/kind.yaml"
"$HELM" template lh "$chart" --kube-version "$KUBE_VERSION" -f "$chart/ci/lint-values.yaml" \
  --set postgres.mode=external --set postgres.external.host=pg.example.internal \
  --set kafka.mode=external --set kafka.external.bootstrapServers=kafka.example.internal:9093 \
  --set kafka.external.sasl.username.name=lh-kafka --set kafka.external.sasl.password.name=lh-kafka \
  > "$OUT/external.yaml"
"$TRIVY" config "${common[@]}" --exit-code 1 --format table "$OUT"
echo "::endgroup::"

echo "::group::Compose (riferimento e locale) e Dockerfile dell'immagine unica"
# deploy/helm è già reso sopra; deploy/hub ha un passo suo (sotto), perché dipende da Q-504.
"$TRIVY" config "${common[@]}" "${custom[@]}" --exit-code 1 --format table \
  --skip-dirs deploy/helm --skip-dirs deploy/hub deploy
echo "::endgroup::"

# Dockerfile di Fase 1 (deploy/hub, che sta dietro la demo ospitata su Render, e services/*, usati dal compose):
# Trivy DS-0002 (HIGH) se manca un `USER` non root (Q-504, deciso: M8.1c aggiunge `USER` a tutti). Finché un Dockerfile
# non ha `USER` si riferisce senza bloccare (--exit-code 0); appena lo ha, la scansione blocca (--exit-code 1). Così,
# quale delle due PR (questa o M8.1c) si unisce per seconda, la scansione diventa bloccante da sola.
scan_dockerfile_dir() {
  local dir="$1" code=0
  if grep -q '^USER ' "$dir/Dockerfile"; then
    code=1
  else
    echo "Q-504 in sospeso: $dir/Dockerfile senza USER, scansione solo informativa (--exit-code 0)."
  fi
  "$TRIVY" config "${common[@]}" --misconfig-scanners dockerfile --exit-code "$code" --format table "$dir"
}

echo "::group::Dockerfile della demo ospitata (deploy/hub) e dei servizi (services/*), Q-504"
scan_dockerfile_dir deploy/hub
for dockerfile in services/*/Dockerfile; do
  scan_dockerfile_dir "$(dirname "$dockerfile")"
done
echo "::endgroup::"

echo "Scansione IaC conclusa senza segnalazioni HIGH o CRITICAL bloccanti."
