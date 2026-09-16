{{- define "lh.name" -}}{{ .Chart.Name }}{{- end -}}
{{- define "lh.labels" -}}
app.kubernetes.io/part-of: {{ include "lh.name" . }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end -}}
{{- define "lh.image" -}}
{{- $root := index . 0 -}}{{- $name := index . 1 -}}{{- $svc := index . 2 -}}
{{- printf "%s/%s:%s" $root.Values.global.registry $name (default $root.Values.global.tag $svc.tag) -}}
{{- end -}}
