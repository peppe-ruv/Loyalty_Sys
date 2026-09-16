# Loyalty Hub - comandi di installazione (RP-03) e sviluppo locale (RP-05)
ENV        ?= dev
REGION     ?= eu-south-1
NAMESPACE  ?= loyalty
TF_DIR      = deploy/terraform
CHART       = deploy/helm/loyalty-hub
VERSION    ?= $(shell git describe --tags --abbrev=0 2>/dev/null | sed 's/^v//' || echo 0.1.0)

.PHONY: help build test images up down seed-local infra install seed uninstall destroy lint

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

uninstall: ## Rimuove il rilascio Helm
	helm uninstall loyalty-hub -n $(NAMESPACE)

destroy: ## Distrugge l'infrastruttura AWS (irreversibile)
	terraform -chdir=$(TF_DIR) destroy -var-file=environments/$(ENV).tfvars
