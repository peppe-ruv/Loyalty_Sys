package io.loyaltyhub.hub;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Nel profilo {@code enterprise} l'hub non parte se invia le metriche in OTLP con {@code http} verso una destinazione
 * fuori dal cluster: rifiuto all'avvio con {@code INSECURE_CONFIG}, mai un avviso (CLAUDE.md regola 22, ADR-044,
 * Q-520, Q-521). Difesa in profondità per le installazioni che non passano dal chart, dove lo stesso controllo è in
 * {@code loyaltyhub.validate.observability}: la destinazione ammessa in chiaro è il nome di un Service del cluster
 * ({@code <nome>} oppure {@code <nome>.<namespace>.svc[.cluster.local]}), lo stesso schema del chart. Con
 * l'esportazione spenta (default, ADR-044) o con {@code https} non controlla nulla; il profilo {@code demo} non ha
 * l'obbligo di cifratura.
 */
@Component
public class HubObservabilityGuard {

    /** Stesso schema di {@code loyaltyhub.validate.observability} (deploy/helm/loyaltyhub/templates/_helpers.tpl). */
    private static final Pattern CLUSTER_LOCAL_HTTP = Pattern.compile(
            "^http://[a-z0-9]([-a-z0-9]*[a-z0-9])?(\\.[a-z0-9]([-a-z0-9]*[a-z0-9])?\\.svc(\\.cluster\\.local)?)?"
                    + "(:[0-9]+)?(/[^?#\\s]*)?$");

    public HubObservabilityGuard(Environment environment) {
        check(environment);
    }

    /** Lancia {@link IllegalStateException} ({@code INSECURE_CONFIG}) se la configurazione OTLP è insicura. */
    static void check(Environment env) {
        boolean enterprise = env.acceptsProfiles(Profiles.of("enterprise"));
        boolean exporting = Boolean.TRUE.equals(env.getProperty("management.otlp.metrics.export.enabled", Boolean.class));
        if (!enterprise || !exporting) {
            return;
        }
        String url = env.getProperty("management.otlp.metrics.export.url", "");
        if (url.regionMatches(true, 0, "http://", 0, "http://".length()) && !CLUSTER_LOCAL_HTTP.matcher(url).matches()) {
            throw new IllegalStateException("INSECURE_CONFIG: il profilo enterprise non invia le metriche in http fuori "
                    + "dal cluster (LH_OTEL_METRICS_URL): usare https oppure il Service del collector "
                    + "(<nome> o <nome>.<namespace>.svc) (regola 22, Q-520).");
        }
    }
}
