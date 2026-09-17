# Loyalty Hub - comandi di installazione (RP-03) e sviluppo locale (RP-05)
ENV        ?= dev
REGION     ?= eu-south-1
NAMESPACE  ?= loyalty
TF_DIR      = deploy/terraform
CHART       = deploy/helm/loyalty-hub
VERSION    ?= $(shell git describe --tags --abbrev=0 2>/dev/null | sed 's/^v//' || echo 0.5.0)

.PHONY: help build test images up down seed-local infra install seed uninstall destroy lint observability bi up-observability up-bi

help: ## Elenca i comandi
	@grep -E '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN{FS=":.*?## "}{printf "  \033[36m%-14s\033[0m %s\n", $$1, $$2}'

# ---- sviluppo ----
build: ## Compila i servizi Java
	mvn -B -f services/pom.xml -DskipTests package

test: ## Test unitari Java
	mvn -B -f services/pom.xml test

images: build ## Immagini locali di tutti i servizi
	docker compose build

up: images ## Ambiente locale completo (Postgres, Kafka, Redis, servizi, CMS, sito)
	docker compose up -d

down: ## Ferma l'ambiente locale
	docker compose down

seed-local: ## Dati di esempio nell'ambiente locale
	SEED_INGRESS=http://localhost:8081 SEED_LEDGER=http://localhost:8083 scripts/seed.sh

up-observability: ## Profilo locale di monitoraggio: Prometheus, Alertmanager, Grafana (:3005), Loki, Tempo, OTel Collector
	docker compose --profile observability up -d

up-bi: ## Profilo locale di BI: ClickHouse (:8123) e Apache Superset (:8088) incorporato nel backoffice
	docker compose --profile bi up -d

lint: ## Lint chart e terraform
	helm lint $(CHART) -f $(CHART)/values-$(ENV).yaml
	terraform -chdir=$(TF_DIR) fmt -check -recursive

# ---- installazione su AWS EKS: tre comandi ----
infra: ## 1/3  terraform apply: VPC, EKS, RDS, MSK, Redis, S3, segreti (~25 min)
	terraform -chdir=$(TF_DIR) init
	terraform -chdir=$(TF_DIR) apply -var-file=environments/$(ENV).tfvars
	$$(terraform -chdir=$(TF_DIR) output -raw kubeconfig_command)

install: ## 2/3  helm install: tutti i servizi dalle immagini pubblicate
	helm upgrade --install loyalty-hub $(CHART) -n $(NAMESPACE) --create-namespace \
	  -f $(CHART)/values-$(ENV).yaml \
	  --set global.tag=$(VERSION) \
	  --set global.kafka.bootstrap=$$(terraform -chdir=$(TF_DIR) output -raw kafka_bootstrap_iam) \
	  --set global.redis.host=$$(terraform -chdir=$(TF_DIR) output -raw redis_endpoint) \
	  --set global.secretsStore.region=$(REGION) \
	  --wait --timeout 15m

seed: ## 3/3  tipi azione, tier e regole di esempio nel cluster
	NAMESPACE=$(NAMESPACE) scripts/seed.sh

observability: ## 4/3 (opzionale) stack di monitoraggio in HA: kube-prometheus-stack + Thanos, Loki, Tempo, OTel Collector, alert e dashboard (RF-117..RF-120)
	helm repo add prometheus-community https://prometheus-community.github.io/helm-charts >/dev/null; helm repo add grafana https://grafana.github.io/helm-charts >/dev/null
	helm repo add open-telemetry https://open-telemetry.github.io/opentelemetry-helm-charts >/dev/null; helm repo add bitnami https://charts.bitnami.com/bitnami >/dev/null; helm repo update >/dev/null
	kubectl create namespace observability --dry-run=client -o yaml | kubectl apply -f -
	kubectl -n observability create secret generic thanos-objstore --from-literal=objstore.yml="type: S3`printf '\n'`config:`printf '\n'`  bucket: $$(terraform -chdir=$(TF_DIR) output -json observability_buckets | jq -r .thanos)`printf '\n'`  endpoint: s3.$(REGION).amazonaws.com" --dry-run=client -o yaml | kubectl apply -f -
	kubectl -n observability create secret generic grafana-db --from-literal=placeholder=1 --dry-run=client -o yaml | kubectl apply -f -   # sostituito da External Secrets (observability-db)
	helm upgrade --install monitoring prometheus-community/kube-prometheus-stack -n observability -f deploy/observability/kube-prometheus-stack.values.yaml --wait --timeout 15m
	helm upgrade --install thanos bitnami/thanos -n observability -f deploy/observability/thanos.values.yaml --wait
	OBSERVABILITY_ROLE_ARN=$$(terraform -chdir=$(TF_DIR) output -raw observability_role_arn) LOKI_BUCKET=$$(terraform -chdir=$(TF_DIR) output -json observability_buckets | jq -r .loki) TEMPO_BUCKET=$$(terraform -chdir=$(TF_DIR) output -json observability_buckets | jq -r .tempo) ENVIRONMENT=$(ENV) KAFKA_BOOTSTRAP=$$(terraform -chdir=$(TF_DIR) output -raw kafka_bootstrap_iam) \
	  sh -c 'for f in loki tempo otel-collector kafka-exporter; do envsubst < deploy/observability/$$f.values.yaml > /tmp/$$f.values.yaml; done; \
	  helm upgrade --install loki grafana/loki -n observability -f /tmp/loki.values.yaml --wait; \
	  helm upgrade --install tempo grafana/tempo-distributed -n observability -f /tmp/tempo.values.yaml --wait; \
	  helm upgrade --install otel-collector open-telemetry/opentelemetry-collector -n observability -f /tmp/otel-collector.values.yaml --wait; \
	  helm upgrade --install kafka-exporter prometheus-community/prometheus-kafka-exporter -n observability -f /tmp/kafka-exporter.values.yaml --wait'
	kubectl apply -f deploy/observability/alerts/loyalty-rules.yaml
	kubectl apply -k deploy/observability/dashboards

bi: ## 5/3 (opzionale) warehouse ClickHouse (3 repliche) + Apache Superset in HA, cruscotti importati (RF-121..RF-123)
	helm repo add superset https://apache.github.io/superset >/dev/null; helm repo update >/dev/null
	kubectl create namespace analytics --dry-run=client -o yaml | kubectl apply -f -
	kubectl apply -f https://raw.githubusercontent.com/Altinity/clickhouse-operator/master/deploy/operator/clickhouse-operator-install-bundle.yaml
	CLICKHOUSE_BACKUP_BUCKET=$$(terraform -chdir=$(TF_DIR) output -json observability_buckets | jq -r .clickhouse) CLICKHOUSE_SUPERSET_PASSWORD_SHA256=$$(printf '%s' "$$CLICKHOUSE_SUPERSET_PASSWORD" | sha256sum | cut -d' ' -f1) CLICKHOUSE_GRAFANA_PASSWORD_SHA256=$$(printf '%s' "$$CLICKHOUSE_GRAFANA_PASSWORD" | sha256sum | cut -d' ' -f1) \
	  envsubst < deploy/bi/clickhouse.values.yaml | kubectl apply -f -
	kubectl -n analytics wait --for=condition=Ready pod -l clickhouse.altinity.com/chi=loyalty --timeout=15m
	for f in analytics/clickhouse/schema/*.sql; do sed "s/{kafka_bootstrap}/$$(terraform -chdir=$(TF_DIR) output -raw kafka_bootstrap_iam)/" $$f | kubectl -n analytics exec -i chi-loyalty-loyalty-0-0-0 -- clickhouse-client --multiquery; done
	kubectl -n analytics create configmap superset-dashboards --from-file=analytics/superset/ --dry-run=client -o yaml | kubectl apply -f -
	OBSERVABILITY_ROLE_ARN=$$(terraform -chdir=$(TF_DIR) output -raw observability_role_arn) REDIS_HOST=$$(terraform -chdir=$(TF_DIR) output -raw redis_endpoint) envsubst < deploy/bi/superset.values.yaml > /tmp/superset.values.yaml
	helm upgrade --install superset superset/superset -n analytics -f /tmp/superset.values.yaml --wait --timeout 15m

uninstall: ## Rimuove il rilascio Helm
	helm uninstall loyalty-hub -n $(NAMESPACE)

destroy: ## Distrugge l'infrastruttura AWS (irreversibile)
	terraform -chdir=$(TF_DIR) destroy -var-file=environments/$(ENV).tfvars
