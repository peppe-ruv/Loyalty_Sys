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
app.kubernetes.io/version: {{ .ctx.Chart.AppVersion | quote }}
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

{{/* Immagine unica (ADR-037). */}}
{{- define "loyaltyhub.image" -}}
{{- $tag := default .Chart.AppVersion .Values.image.tag -}}
{{- printf "%s:%s" .Values.image.repository $tag -}}
{{- end -}}

{{- define "loyaltyhub.imagePullPolicy" -}}
{{- $tag := default .Chart.AppVersion .Values.image.tag -}}
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
{{- if not (has .Values.global.profile (list "enterprise" "demo")) -}}
{{- fail (printf "global.profile deve essere enterprise o demo: %s" .Values.global.profile) -}}
{{- end -}}
{{- if ne .Values.global.mode "external" -}}
{{- fail "global.mode: il chart installa solo LH_MODE=external; embedded è l'appliance a un container (ADR-037)" -}}
{{- end -}}
{{- if and .Values.roles.hub.services (ne .Values.roles.hub.services "all") -}}
{{- fail "roles.hub.services: l'immagine accetta solo vuoto o `all` (selezione dei moduli non ancora disponibile, Q-365)" -}}
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
{{- if and (eq .Values.global.profile "enterprise") (not .Values.roles.idp.enabled) (not .Values.oidc.issuer) -}}
{{- fail "profilo enterprise senza ruolo idp: impostare oidc.issuer dell'IdP aziendale (ADR-027)" -}}
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
{{- if and (eq .ctx.Values.postgres.mode "external") .ctx.Values.postgres.external.jdbcParams -}}
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
# SPEC-GAP: Q-372 — listener interno senza TLS fino a M8.5.
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
{{- end -}}

{{/* ---------- Identità ---------- */}}
{{- define "loyaltyhub.oidc.issuer" -}}
{{- default (printf "%s/realms/%s" (trimSuffix "/" .Values.publicUrls.idp) .Values.global.realm) .Values.oidc.issuer -}}
{{- end -}}

{{- define "loyaltyhub.oidc.jwksUri" -}}
{{- if .Values.oidc.jwksUri -}}
{{- .Values.oidc.jwksUri -}}
{{- else if .Values.roles.idp.enabled -}}
{{- printf "http://%s-idp:%d/realms/%s/protocol/openid-connect/certs" (include "loyaltyhub.fullname" .) (int .Values.roles.idp.port) .Values.global.realm -}}
{{- end -}}
{{- end -}}
