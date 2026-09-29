{{/* Nomi, etichette e blocchi comuni del chart loyaltyhub (F2-DIST-02). */}}

{{- define "loyaltyhub.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "loyaltyhub.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "loyaltyhub.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/* Etichette comuni; con .role anche il componente. Uso: include "loyaltyhub.labels" (dict "ctx" $ "role" "hub") */}}
{{- define "loyaltyhub.labels" -}}
helm.sh/chart: {{ include "loyaltyhub.chart" .ctx }}
{{ include "loyaltyhub.selectorLabels" . }}
app.kubernetes.io/version: {{ default .ctx.Chart.AppVersion .ctx.Values.image.tag | trimPrefix "sha256:" | trunc 63 | quote }}
app.kubernetes.io/managed-by: {{ .ctx.Release.Service }}
app.kubernetes.io/part-of: loyaltyhub
{{- with .ctx.Values.commonLabels }}
{{ toYaml . }}
{{- end }}
{{- end -}}

{{- define "loyaltyhub.selectorLabels" -}}
app.kubernetes.io/name: {{ include "loyaltyhub.name" .ctx }}
app.kubernetes.io/instance: {{ .ctx.Release.Name }}
{{- if .role }}
app.kubernetes.io/component: {{ .role }}
{{- end }}
{{- end -}}

{{- define "loyaltyhub.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "loyaltyhub.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{/* Porte dei ruoli: le fissa l'immagine unica (entrypoint.sh: hub 8080, web 3000) e Keycloak (8080, gestione 9000). */}}
{{- define "loyaltyhub.port.hub" -}}8080{{- end -}}
{{- define "loyaltyhub.port.web" -}}3000{{- end -}}
{{- define "loyaltyhub.port.idp" -}}8080{{- end -}}
{{- define "loyaltyhub.port.idpManagement" -}}9000{{- end -}}
{{/* Porte del collector OpenTelemetry (osservabilità, M8.6a): OTLP HTTP e sonda di salute. Le usano Service, NetworkPolicy, sonde e URL dell'hub. */}}
{{- define "loyaltyhub.port.otlpHttp" -}}4318{{- end -}}
{{- define "loyaltyhub.port.otelHealth" -}}13133{{- end -}}

{{/* Immagine unica (ADR-037): repository e tag obbligatori, nessun nome inventato (finirebbe in ImagePullBackOff). */}}
{{- define "loyaltyhub.image" -}}
{{- $repo := .Values.image.repository | default "<image.repository>" -}}
{{- $tag := .Values.image.tag | default "<image.tag>" -}}
{{- if hasPrefix "sha256:" $tag -}}
{{- printf "%s@%s" $repo $tag -}}
{{- else -}}
{{- printf "%s:%s" $repo $tag -}}
{{- end -}}
{{- end -}}

{{- define "loyaltyhub.imagePullPolicy" -}}
{{- $tag := .Values.image.tag -}}
{{- if .Values.image.pullPolicy -}}
{{- .Values.image.pullPolicy -}}
{{- else if eq $tag "latest" -}}
Always
{{- else -}}
IfNotPresent
{{- end -}}
{{- end -}}

{{/* Sicurezza compatibile con Pod Security `restricted` (docs/18 §3.10 punto 10). */}}
{{- define "loyaltyhub.podSecurityContext" -}}
{{- toYaml .Values.podSecurityContext -}}
{{- end -}}

{{/* Uso: include "loyaltyhub.containerSecurityContext" (dict "ctx" $ "readOnlyRootFilesystem" true) */}}
{{- define "loyaltyhub.containerSecurityContext" -}}
{{- $sc := deepCopy .ctx.Values.containerSecurityContext -}}
{{- if hasKey . "readOnlyRootFilesystem" -}}
{{- $_ := set $sc "readOnlyRootFilesystem" .readOnlyRootFilesystem -}}
{{- end -}}
{{- toYaml $sc -}}
{{- end -}}

{{/* Campi comuni della spec dei Pod: identità senza token, pull secret, sicurezza `restricted`, scheduling.
Uso: include "loyaltyhub.podCommon" (dict "ctx" $ "role" "hub" "serviceAccount" true) */}}
{{- define "loyaltyhub.podCommon" -}}
{{- if .serviceAccount }}
serviceAccountName: {{ include "loyaltyhub.serviceAccountName" .ctx }}
{{- end }}
automountServiceAccountToken: false
{{- with .ctx.Values.image.pullSecrets }}
imagePullSecrets:
{{- toYaml . | nindent 2 }}
{{- end }}
securityContext:
{{- include "loyaltyhub.podSecurityContext" .ctx | nindent 2 }}
{{- if .role }}
{{- include "loyaltyhub.scheduling" . }}
{{- end }}
{{- end -}}

{{/* Vincoli di scheduling comuni; uso: include "loyaltyhub.scheduling" (dict "ctx" $ "role" "hub") */}}
{{- define "loyaltyhub.scheduling" -}}
{{- with .ctx.Values.nodeSelector }}
nodeSelector:
{{- toYaml . | nindent 2 }}
{{- end }}
{{- with .ctx.Values.tolerations }}
tolerations:
{{- toYaml . | nindent 2 }}
{{- end }}
{{- if .ctx.Values.topologySpread.enabled }}
topologySpreadConstraints:
  - maxSkew: 1
    topologyKey: {{ .ctx.Values.topologySpread.topologyKey }}
    whenUnsatisfiable: {{ .ctx.Values.topologySpread.whenUnsatisfiable }}
    labelSelector:
      matchLabels:
        {{- include "loyaltyhub.selectorLabels" (dict "ctx" .ctx "role" .role) | nindent 8 }}
{{- end }}
{{- end -}}

{{/* ---------- Verifiche dei valori: il chart rifiuta configurazioni non supportate o insicure ---------- */}}
{{- define "loyaltyhub.validate" -}}
{{- /* `fail` e non `required`: helm lint non fa fallire `required`, e un nome d'immagine vuoto diventerebbe YAML rotto. */ -}}
{{- if not .Values.image.repository -}}
{{- fail "image.repository è obbligatorio: l'immagine unica pubblicata da .github/workflows/image.yml, es. ghcr.io/<owner>/loyaltyhub (nessun default: un nome inventato finirebbe in ImagePullBackOff)" -}}
{{- end -}}
{{- if not .Values.image.tag -}}
{{- fail "image.tag è obbligatorio: una versione pubblicata (tag git v*, es. v0.7.0) o un digest sha256:…" -}}
{{- end -}}
{{- if not (has .Values.global.profile (list "enterprise" "demo")) -}}
{{- fail (printf "global.profile deve essere enterprise o demo: %s" .Values.global.profile) -}}
{{- end -}}
{{- if ne .Values.global.mode "external" -}}
{{- fail "global.mode: il chart installa solo LH_MODE=external; embedded è l'appliance a un container (ADR-037)" -}}
{{- end -}}
{{- if and .Values.roles.hub.services (ne .Values.roles.hub.services "all") -}}
{{- fail "roles.hub.services: l'immagine accetta solo vuoto o `all` (selezione dei moduli non ancora disponibile, Q-376)" -}}
{{- end -}}
{{- if .Values.roles.cms.enabled -}}
{{- fail "roles.cms: il ruolo cms (Directus) non è ancora nell'immagine unica (M10.2)" -}}
{{- end -}}
{{- if .Values.roles.jobs.enabled -}}
{{- fail "roles.jobs: il ruolo jobs non è ancora nell'immagine unica" -}}
{{- end -}}
{{- if not (has .Values.postgres.mode (list "cloudnativepg" "external")) -}}
{{- fail (printf "postgres.mode deve essere cloudnativepg o external: %s" .Values.postgres.mode) -}}
{{- end -}}
{{- if and (eq .Values.postgres.mode "external") (not .Values.postgres.external.host) -}}
{{- fail "postgres.external.host è obbligatorio con postgres.mode=external" -}}
{{- end -}}
{{- if not (has .Values.kafka.mode (list "strimzi" "external")) -}}
{{- fail (printf "kafka.mode deve essere strimzi o external: %s" .Values.kafka.mode) -}}
{{- end -}}
{{- if and (eq .Values.kafka.mode "external") (not .Values.kafka.external.bootstrapServers) -}}
{{- fail "kafka.external.bootstrapServers è obbligatorio con kafka.mode=external" -}}
{{- end -}}
{{- if and (eq .Values.kafka.mode "external") (not (has .Values.kafka.external.security (list "PLAINTEXT" "SSL_PEM" "SASL_SSL"))) -}}
{{- fail "kafka.external.security deve essere PLAINTEXT, SSL_PEM o SASL_SSL" -}}
{{- end -}}
{{- if eq .Values.global.profile "enterprise" -}}
{{- /* Regola 22: in enterprise niente collegamenti esterni in chiaro, salvo deroga esplicita e documentata. */ -}}
{{- if and (eq .Values.kafka.mode "external") (eq .Values.kafka.external.security "PLAINTEXT") (not .Values.kafka.external.allowInsecure) -}}
{{- fail "INSECURE_CONFIG: kafka.external.security=PLAINTEXT nel profilo enterprise; usare SSL_PEM o SASL_SSL, oppure kafka.external.allowInsecure=true solo su una rete già cifrata (regola 22)" -}}
{{- end -}}
{{- if and (eq .Values.postgres.mode "external") (not .Values.postgres.external.allowInsecure) (not (regexMatch "(^|&)sslmode=(require|verify-ca|verify-full)(&|$)" (default "" .Values.postgres.external.jdbcParams))) -}}
{{- fail "INSECURE_CONFIG: postgres.external.jdbcParams senza sslmode=require, verify-ca o verify-full nel profilo enterprise; oppure postgres.external.allowInsecure=true solo su una rete già cifrata (regola 22)" -}}
{{- end -}}
{{- end -}}
{{- /* Anche con --skip-schema-validation: le risorse hanno la forma dell'API v1 (Q-490). */ -}}
{{- if and (eq .Values.kafka.mode "strimzi") (ne .Values.kafka.strimzi.apiVersion "kafka.strimzi.io/v1") -}}
{{- fail (printf "kafka.strimzi.apiVersion deve essere kafka.strimzi.io/v1 (%s non è supportata): il chart scrive le risorse Strimzi con l'API v1 e richiede Strimzi 0.51 o successivo (Q-490)" .Values.kafka.strimzi.apiVersion) -}}
{{- end -}}
{{- if and (eq .Values.kafka.mode "strimzi") (gt (int .Values.kafka.topics.replicas) (int .Values.kafka.strimzi.replicas)) -}}
{{- fail (printf "kafka.topics.replicas (%d) non può superare i broker di kafka.strimzi.replicas (%d)" (int .Values.kafka.topics.replicas) (int .Values.kafka.strimzi.replicas)) -}}
{{- end -}}
{{- if eq .Values.kafka.mode "strimzi" -}}
{{- /* Aumento di partizioni su KafkaTopic esistenti (lookup con l'API v1: vale in install/upgrade, non in `helm template`). */ -}}
{{- $root := . -}}
{{- $cluster := include "loyaltyhub.kafka.clusterName" . -}}
{{- range $key := list "actions" "effects" "facts" "audit" "dlq" -}}
{{- $existing := lookup $root.Values.kafka.strimzi.apiVersion "KafkaTopic" $root.Release.Namespace (printf "%s-%s" $cluster $key) -}}
{{- if and $existing $existing.spec (lt (int $existing.spec.partitions) (int $root.Values.kafka.topics.partitions)) (not $root.Values.kafka.topics.allowPartitionIncrease) -}}
{{- fail (printf "PARTITION_INCREASE_NOT_ACKNOWLEDGED: il topic %s passerebbe da %d a %d partizioni. L'aumento rimappa le chiavi memberId: fermare i produttori, attendere lag 0, poi ripetere con kafka.topics.allowPartitionIncrease=true" $existing.spec.topicName (int $existing.spec.partitions) (int $root.Values.kafka.topics.partitions)) -}}
{{- end -}}
{{- end -}}
{{- end -}}
{{- if and (eq .Values.global.profile "enterprise") (not .Values.roles.idp.enabled) (not .Values.oidc.issuer) -}}
{{- fail "profilo enterprise senza ruolo idp: impostare oidc.issuer dell'IdP aziendale (ADR-027)" -}}
{{- end -}}
{{- if and (or .Values.roles.web.enabled .Values.roles.idp.enabled) (not .Values.publicUrls.web) -}}
{{- fail "publicUrls.web è obbligatorio: origine pubblica del web (es. https://loyalty.example.org), LH_WEB_URL del BFF e base delle redirect URI del realm" -}}
{{- end -}}
{{- if eq .Values.global.profile "enterprise" -}}
{{- include "loyaltyhub.validate.identity" . -}}
{{- end -}}
{{- $t := .Values.kafka.topics -}}
{{- if lt (int $t.partitions) 1 -}}{{- fail "kafka.topics.partitions deve essere almeno 1" -}}{{- end -}}
{{- if lt (int $t.replicas) 1 -}}{{- fail "kafka.topics.replicas deve essere almeno 1" -}}{{- end -}}
{{- if or (lt (int $t.minInsyncReplicas) 1) (gt (int $t.minInsyncReplicas) (int $t.replicas)) -}}
{{- fail "kafka.topics.minInsyncReplicas deve stare tra 1 e kafka.topics.replicas" -}}
{{- end -}}
{{- range $k, $v := $t.retentionMsByTopic -}}
{{- if not (has $k (list "actions" "effects" "facts" "audit" "dlq")) -}}
{{- fail (printf "kafka.topics.retentionMsByTopic: chiave sconosciuta %s (ammesse: actions, effects, facts, audit, dlq)" $k) -}}
{{- end -}}
{{- end -}}
{{- if lt (int .Values.kafka.consumer.concurrency) 1 -}}{{- fail "kafka.consumer.concurrency deve essere almeno 1" -}}{{- end -}}
{{- include "loyaltyhub.validate.observability" . -}}
{{- end -}}

{{/* ---------- Postgres ---------- */}}
{{- define "loyaltyhub.pg.clusterName" -}}
{{- default (printf "%s-pg" (include "loyaltyhub.fullname" .)) .Values.postgres.cloudnativepg.clusterName -}}
{{- end -}}

{{- define "loyaltyhub.pg.host" -}}
{{- if eq .Values.postgres.mode "cloudnativepg" -}}
{{- printf "%s-rw" (include "loyaltyhub.pg.clusterName" .) -}}
{{- else -}}
{{- .Values.postgres.external.host -}}
{{- end -}}
{{- end -}}

{{- define "loyaltyhub.pg.port" -}}
{{- if eq .Values.postgres.mode "cloudnativepg" -}}5432{{- else -}}{{- .Values.postgres.external.port -}}{{- end -}}
{{- end -}}

{{/* URL JDBC; uso: include "loyaltyhub.pg.jdbcUrl" (dict "ctx" $ "database" "loyaltyhub") */}}
{{- define "loyaltyhub.pg.jdbcUrl" -}}
{{- $base := printf "jdbc:postgresql://%s:%s/%s" (include "loyaltyhub.pg.host" .ctx) (include "loyaltyhub.pg.port" .ctx) .database -}}
{{- if eq .ctx.Values.postgres.mode "cloudnativepg" -}}
{{- /* CloudNativePG serve sempre TLS: il client lo esige (verify-full con la CA del cluster in M8.5). */ -}}
{{- printf "%s?sslmode=require" $base -}}
{{- else if .ctx.Values.postgres.external.jdbcParams -}}
{{- printf "%s?%s" $base .ctx.Values.postgres.external.jdbcParams -}}
{{- else -}}
{{- $base -}}
{{- end -}}
{{- end -}}

{{/* Credenziali del database applicativo come env con secretKeyRef (nessun valore in chiaro). */}}
{{- define "loyaltyhub.pg.appCredentialsEnv" -}}
{{- if eq .Values.postgres.mode "cloudnativepg" }}
- name: DB_USERNAME
  valueFrom:
    secretKeyRef:
      name: {{ include "loyaltyhub.pg.clusterName" . }}-app
      key: username
- name: DB_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ include "loyaltyhub.pg.clusterName" . }}-app
      key: password
{{- else }}
- name: DB_USERNAME
  valueFrom:
    secretKeyRef:
      name: {{ required "postgres.external.credentials.username.name" .Values.postgres.external.credentials.username.name }}
      key: {{ .Values.postgres.external.credentials.username.key }}
- name: DB_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ required "postgres.external.credentials.password.name" .Values.postgres.external.credentials.password.name }}
      key: {{ .Values.postgres.external.credentials.password.key }}
{{- end }}
{{- end -}}

{{- define "loyaltyhub.hubDbEnv" -}}
- name: DB_URL
  value: {{ include "loyaltyhub.pg.jdbcUrl" (dict "ctx" . "database" .Values.postgres.database) | quote }}
{{- include "loyaltyhub.pg.appCredentialsEnv" . }}
{{- end -}}

{{/* ---------- Kafka ---------- */}}
{{- define "loyaltyhub.kafka.clusterName" -}}
{{- default (printf "%s-kafka" (include "loyaltyhub.fullname" .)) .Values.kafka.strimzi.clusterName -}}
{{- end -}}

{{- define "loyaltyhub.kafka.bootstrap" -}}
{{- if eq .Values.kafka.mode "strimzi" -}}
{{- printf "%s-kafka-bootstrap:%d" (include "loyaltyhub.kafka.clusterName" .) (int .Values.kafka.strimzi.listener.port) -}}
{{- else -}}
{{- .Values.kafka.external.bootstrapServers -}}
{{- end -}}
{{- end -}}

{{/* Riferimento opzionale a un Secret: emette l'env solo se name è valorizzato. */}}
{{- define "loyaltyhub.optionalSecretEnv" -}}
{{- if .ref.name }}
- name: {{ .env }}
  valueFrom:
    secretKeyRef:
      name: {{ .ref.name }}
      key: {{ .ref.key }}
{{- end }}
{{- end -}}

{{/* Env Kafka dell'hub: bootstrap, sicurezza, forma dei topic e concorrenza (F2-EVT-04). */}}
{{- define "loyaltyhub.hubKafkaEnv" -}}
{{- $t := .Values.kafka.topics -}}
- name: SPRING_KAFKA_BOOTSTRAP_SERVERS
  value: {{ include "loyaltyhub.kafka.bootstrap" . | quote }}
{{- if eq .Values.kafka.mode "strimzi" }}
# SPEC-GAP: Q-375 — listener interno senza TLS fino a M8.5.
- name: KAFKA_SECURITY
  value: "PLAINTEXT"
# I 5 topic li possiede Strimzi (KafkaTopic): l'hub non li dichiara.
- name: LH_KAFKA_TOPICS_CREATE
  value: "false"
{{- else }}
- name: KAFKA_SECURITY
  value: {{ .Values.kafka.external.security | quote }}
- name: LH_KAFKA_TOPICS_CREATE
  value: {{ .Values.kafka.external.createTopics | quote }}
{{- if eq .Values.kafka.external.security "SASL_SSL" }}
- name: KAFKA_SASL_MECHANISM
  value: {{ .Values.kafka.external.sasl.mechanism | quote }}
{{- end }}
{{- include "loyaltyhub.optionalSecretEnv" (dict "env" "KAFKA_SASL_USERNAME" "ref" .Values.kafka.external.sasl.username) }}
{{- include "loyaltyhub.optionalSecretEnv" (dict "env" "KAFKA_SASL_PASSWORD" "ref" .Values.kafka.external.sasl.password) }}
{{- include "loyaltyhub.optionalSecretEnv" (dict "env" "KAFKA_SSL_CA_B64" "ref" .Values.kafka.external.ssl.caB64) }}
{{- include "loyaltyhub.optionalSecretEnv" (dict "env" "KAFKA_SSL_CERT_B64" "ref" .Values.kafka.external.ssl.certB64) }}
{{- include "loyaltyhub.optionalSecretEnv" (dict "env" "KAFKA_SSL_KEY_B64" "ref" .Values.kafka.external.ssl.keyB64) }}
{{- end }}
- name: LH_KAFKA_TOPIC_PARTITIONS
  value: {{ $t.partitions | quote }}
- name: LH_KAFKA_TOPIC_REPLICAS
  value: {{ $t.replicas | quote }}
- name: LH_KAFKA_TOPIC_MIN_INSYNC_REPLICAS
  value: {{ $t.minInsyncReplicas | quote }}
- name: LH_KAFKA_TOPIC_RETENTION_MS
  value: {{ $t.retentionMs | int64 | quote }}
{{- range $k, $v := $t.retentionMsByTopic }}
- name: LH_KAFKA_{{ upper $k }}_RETENTION_MS
  value: {{ $v | int64 | quote }}
{{- end }}
- name: LH_KAFKA_CONSUMER_CONCURRENCY
  value: {{ .Values.kafka.consumer.concurrency | quote }}
- name: LH_KAFKA_TOPICS_MODIFY_CONFIGS
  value: {{ .Values.kafka.topics.modifyConfigs | quote }}
- name: LH_KAFKA_TOPICS_ALLOW_PARTITION_INCREASE
  value: {{ .Values.kafka.topics.allowPartitionIncrease | quote }}
{{- end -}}

{{/* ---------- Identità ---------- */}}
{{- define "loyaltyhub.oidc.issuer" -}}
{{- default (printf "%s/realms/%s" (trimSuffix "/" .Values.publicUrls.idp) .Values.global.realm) .Values.oidc.issuer -}}
{{- end -}}

{{- define "loyaltyhub.oidc.jwksUri" -}}
{{- if .Values.oidc.jwksUri -}}
{{- .Values.oidc.jwksUri -}}
{{- else if .Values.roles.idp.enabled -}}
{{- printf "http://%s-idp:%d/realms/%s/protocol/openid-connect/certs" (include "loyaltyhub.fullname" .) (int (include "loyaltyhub.port.idp" .)) .Values.global.realm -}}
{{- end -}}
{{- end -}}

{{/* Origine pubblica del web (LH_WEB_URL del BFF e del realm), nella stessa forma che ne ricava il BFF con `new URL()`:
senza barra finale, schema e host in minuscolo, senza porta di default (:443, :80). Keycloak confronta alla lettera
`${LH_WEB_URL}/api/auth/callback` con la redirect URI del BFF: una forma diversa farebbe fallire il login in silenzio. */}}
{{- define "loyaltyhub.web.publicUrl" -}}
{{- $u := trimSuffix "/" .Values.publicUrls.web -}}
{{- if regexMatch "^(?i)https?://[^/?#@\\s]+$" $u -}}
{{- $u = lower $u -}}
{{- $u = hasPrefix "https://" $u | ternary (trimSuffix ":443" $u) (trimSuffix ":80" $u) -}}
{{- end -}}
{{- $u -}}
{{- end -}}

{{/* Segreto del client `web` per il BFF, come JSON {name, key}: il riferimento proprio del web oppure, con il ruolo idp,
lo stesso Secret dato a Keycloak (i due valori devono coincidere). Vuoto se non c'è nessuno dei due. */}}
{{- define "loyaltyhub.web.clientSecretRef" -}}
{{- if .Values.roles.web.bff.clientSecret.name -}}
{{- toJson .Values.roles.web.bff.clientSecret -}}
{{- else if .Values.roles.idp.enabled -}}
{{- toJson .Values.roles.idp.clientSecrets.web -}}
{{- else -}}
{{- toJson (dict "name" "" "key" "") -}}
{{- end -}}
{{- end -}}

{{/* Verifiche d'identità del profilo enterprise (ADR-027, regola 22, F2-SEC-06): emittente https e, con il ruolo idp,
proprio quello di Keycloak; origine del web https senza percorso (la usa anche il realm); host coerenti con l'Ingress;
client e segreti del BFF; una sola replica del web, senza PDB (Q-409, Q-419). */}}
{{- define "loyaltyhub.validate.identity" -}}
{{- $issuer := include "loyaltyhub.oidc.issuer" . -}}
{{- if not (regexMatch "^https://[^/?#@\\s]+(/[^?#\\s]*)?$" $issuer) -}}
{{- fail (printf "INSECURE_CONFIG: emittente OIDC %q non https nel profilo enterprise: il browser vi fa login e il BFF del web lo raggiunge con lo stesso URL; impostare publicUrls.idp (ruolo idp) o oidc.issuer con https:// (regola 22, Q-420)" $issuer) -}}
{{- end -}}
{{- if .Values.roles.idp.enabled -}}
{{- $idpIssuer := printf "%s/realms/%s" (trimSuffix "/" .Values.publicUrls.idp) .Values.global.realm -}}
{{- if and .Values.oidc.issuer (ne .Values.oidc.issuer $idpIssuer) -}}
{{- fail (printf "oidc.issuer %q diverso dall'emittente del ruolo idp %q: Keycloak firma i token con <publicUrls.idp>/realms/<global.realm> e hub e BFF li rifiuterebbero; lasciare oidc.issuer vuoto, oppure spegnere roles.idp per un IdP aziendale (ADR-027)" .Values.oidc.issuer $idpIssuer) -}}
{{- end -}}
{{- if .Values.ingress.enabled -}}
{{- $idpHost := (urlParse .Values.publicUrls.idp).hostname -}}
{{- if ne $idpHost .Values.ingress.hosts.idp -}}
{{- fail (printf "publicUrls.idp (host %s) e ingress.hosts.idp (%s) devono indicare lo stesso host: Keycloak si presenta con publicUrls.idp e l'Ingress serve solo ingress.hosts.idp" $idpHost .Values.ingress.hosts.idp) -}}
{{- end -}}
{{- end -}}
{{- end -}}
{{- $webUrl := include "loyaltyhub.web.publicUrl" . -}}
{{- /* Anche con il web spento: il realm del ruolo idp costruisce redirect e back-channel da LH_WEB_URL. */ -}}
{{- if and (or .Values.roles.web.enabled .Values.roles.idp.enabled) (not (regexMatch "^https://[^/?#@\\s]+$" $webUrl)) -}}
{{- fail (printf "INSECURE_CONFIG: publicUrls.web %q deve essere un'origine https senza percorso (es. https://loyalty.example.org): è LH_WEB_URL del BFF e del realm, base delle redirect URI e del controllo Origin (regola 22)" .Values.publicUrls.web) -}}
{{- end -}}
{{- if .Values.roles.web.enabled -}}
{{- $r := .Values.roles.web -}}
{{- if and .Values.ingress.enabled (ne (urlParse $webUrl).hostname .Values.ingress.hosts.web) -}}
{{- fail (printf "publicUrls.web (host %s) e ingress.hosts.web (%s) devono indicare lo stesso host: l'IdP rimanda il browser a publicUrls.web e l'Ingress serve solo ingress.hosts.web" (urlParse $webUrl).hostname .Values.ingress.hosts.web) -}}
{{- end -}}
{{- if .Values.roles.idp.enabled -}}
{{- /* Il client del BFF nel realm è quello che riceve LH_WEB_CLIENT_SECRET (files/realm.json). */ -}}
{{- $realmClient := "" -}}
{{- range (.Files.Get "files/realm.json" | fromJson).clients -}}
{{- if eq (toString .secret) "${LH_WEB_CLIENT_SECRET}" -}}{{- $realmClient = .clientId -}}{{- end -}}
{{- end -}}
{{- if ne $r.bff.clientId $realmClient -}}
{{- fail (printf "roles.web.bff.clientId %q non è il client del BFF nel realm del ruolo idp (%q, files/realm.json): con un altro client il login fallisce" $r.bff.clientId $realmClient) -}}
{{- end -}}
{{- end -}}
{{- if not (include "loyaltyhub.web.clientSecretRef" . | fromJson).name -}}
{{- fail "roles.web.bff.clientSecret.name è obbligatorio nel profilo enterprise senza ruolo idp: Secret con il segreto del client `web` registrato nell'IdP aziendale (F2-SEC-06)" -}}
{{- end -}}
{{- if not $r.bff.sessionKey.name -}}
{{- fail "roles.web.bff.sessionKey.name è obbligatorio nel profilo enterprise: Secret con 32 byte casuali in base64 (openssl rand -base64 32), chiave delle sessioni del BFF (F2-SEC-06)" -}}
{{- end -}}
{{- if gt (int $r.bff.sessionIdleSeconds) (int $r.bff.sessionMaxSeconds) -}}
{{- fail "roles.web.bff.sessionIdleSeconds non può superare roles.web.bff.sessionMaxSeconds" -}}
{{- end -}}
{{- if or (and $r.autoscaling.enabled (gt (int $r.autoscaling.maxReplicas) 1)) (and (not $r.autoscaling.enabled) (gt (int $r.replicas) 1)) -}}
{{- fail "WEB_SINGLE_REPLICA: nel profilo enterprise il web gira con una sola replica, perché le sessioni del BFF stanno nella memoria del Pod (Q-409, Q-419): roles.web.replicas=1 e roles.web.autoscaling.enabled=false (o maxReplicas=1)" -}}
{{- end -}}
{{- if $r.pdb.enabled -}}
{{- fail "WEB_SINGLE_REPLICA: roles.web.pdb.enabled con una sola replica del web blocca lo svuotamento dei nodi (kubectl drain) senza proteggere nulla; nel profilo enterprise lasciarlo spento (Q-419)" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{/* ---------- Osservabilità (F2-OBS-01, M8.6a; ADR-012, ADR-036, ADR-044) ---------- */}}
{{/* Nome del collector: il nome completo è accorciato perché il suffisso resti dentro i 63 caratteri di un'etichetta DNS. */}}
{{- define "loyaltyhub.otelCollector.name" -}}
{{- printf "%s-otel-collector" (include "loyaltyhub.fullname" . | trunc 47 | trimSuffix "-") -}}
{{- end -}}

{{/* Configurazione del collector (immagine core: ricevitore otlp, esportatore otlphttp, memory_limiter, batch, health_check).
Solo metriche e solo il ricevitore HTTP: niente gRPC, niente esportatore di debug. Il compose di riferimento ha la stessa
pipeline in deploy/compose/observability/otel-collector.yaml (scripts/check-helm.mjs verifica la destinazione). */}}
{{- define "loyaltyhub.otelCollector.config" -}}
{{- $p := .Values.observability.prometheus -}}
extensions:
  health_check:
    endpoint: ${env:LH_POD_IP}:{{ include "loyaltyhub.port.otelHealth" . }}
receivers:
  otlp:
    protocols:
      http:
        endpoint: ${env:LH_POD_IP}:{{ include "loyaltyhub.port.otlpHttp" . }}
processors:
  memory_limiter:
    check_interval: 1s
    limit_percentage: 80
    spike_limit_percentage: 20
  batch: {}
exporters:
  otlphttp/prometheus:
    endpoint: {{ $p.otlpEndpoint | quote }}
    {{- if $p.caBundle.name }}
    tls:
      ca_file: /etc/lh/prometheus-ca/{{ $p.caBundle.key }}
    {{- end }}
    {{- if $p.bearerToken.name }}
    headers:
      Authorization: "Bearer ${env:LH_PROMETHEUS_TOKEN}"
    {{- end }}
service:
  extensions: [health_check]
  pipelines:
    metrics:
      receivers: [otlp]
      processors: [memory_limiter, batch]
      exporters: [otlphttp/prometheus]
{{- end -}}

{{/* Verifiche dell'osservabilità: la destinazione delle metriche è una scelta esplicita di chi installa (in ogni profilo) e,
in enterprise, cifrata o dentro il cluster (regola 22, ADR-044); il profilo demo non ha l'obbligo di cifratura. */}}
{{- define "loyaltyhub.validate.observability" -}}
{{- if .Values.observability.enabled -}}
{{- $p := .Values.observability.prometheus -}}
{{- $ep := $p.otlpEndpoint -}}
{{- if not $ep -}}
{{- fail "observability.prometheus.otlpEndpoint è obbligatorio con observability.enabled=true: URL del ricevitore OTLP del Prometheus di chi installa, senza /v1/metrics, es. http://prometheus-operated.monitoring.svc:9090/api/v1/otlp (nessuna destinazione di default: nessuna telemetria esce senza una scelta esplicita, ADR-044)" -}}
{{- end -}}
{{- if not (regexMatch "^https?://[^/?#@\\s]+(/[^?#\\s]*)?$" $ep) -}}
{{- fail (printf "observability.prometheus.otlpEndpoint %q non è un URL http(s) valido: schema, host ed eventuale percorso, senza credenziali, query o frammento" $ep) -}}
{{- end -}}
{{- if regexMatch "/v1/metrics/?$" $ep -}}
{{- fail (printf "observability.prometheus.otlpEndpoint %q: indicare l'URL senza /v1/metrics (il collector lo aggiunge), es. http://prometheus-operated.monitoring.svc:9090/api/v1/otlp" $ep) -}}
{{- end -}}
{{- if and (eq .Values.global.profile "enterprise") (hasPrefix "http://" $ep) (not $p.allowInsecure) (not (regexMatch "^http://[a-z0-9]([-a-z0-9]*[a-z0-9])?(\\.[a-z0-9]([-a-z0-9]*[a-z0-9])?\\.svc(\\.cluster\\.local)?)?(:[0-9]+)?(/[^?#\\s]*)?$" $ep)) -}}
{{- fail (printf "INSECURE_CONFIG: observability.prometheus.otlpEndpoint %q in http fuori dal cluster nel profilo enterprise: usare https, un Service del cluster (<nome> o <nome>.<namespace>.svc) oppure observability.prometheus.allowInsecure=true solo su una rete già cifrata (regola 22, Q-521)" $ep) -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{/* Env dell'hub con l'osservabilità accesa: metriche OTLP verso il collector del chart (hub.yml: LH_OTEL_*). */}}
{{- define "loyaltyhub.hubOtelEnv" -}}
- name: LH_OTEL_METRICS_ENABLED
  value: "true"
- name: LH_OTEL_METRICS_URL
  value: {{ printf "http://%s:%s/v1/metrics" (include "loyaltyhub.otelCollector.name" .) (include "loyaltyhub.port.otlpHttp" .) | quote }}
- name: LH_OTEL_METRICS_STEP
  value: {{ .Values.observability.metricsStep | quote }}
- name: LH_OTEL_SERVICE_NAMESPACE
  value: {{ .Values.observability.serviceNamespace | quote }}
- name: LH_OTEL_INSTANCE_ID
  valueFrom:
    fieldRef:
      fieldPath: metadata.name
{{- end -}}
