package io.loyaltyhub.hub;

import java.util.Map;

/**
 * Solo migrazioni, poi uscita: il Job di migrazione del chart Helm (F2-DIST-02, ADR-038) esegue gli stessi Flyway di
 * {@link HubDatabase} senza avviare l'applicazione, prima che i Pod della nuova versione si aggiornino. Non è un nuovo
 * ruolo dell'immagine: il Job usa l'immagine unica e chiama questa classe dal jar dell'hub.
 *
 * <pre>
 * java -cp /opt/lh/hub/hub.jar -Dloader.main=io.loyaltyhub.hub.HubMigrate \
 *      org.springframework.boot.loader.launch.PropertiesLauncher
 * </pre>
 *
 * Legge {@code DB_URL} (o {@code SPRING_DATASOURCE_URL}), {@code DB_USERNAME} (o {@code DB_USER},
 * {@code SPRING_DATASOURCE_USERNAME}) e {@code DB_PASSWORD} (o {@code SPRING_DATASOURCE_PASSWORD}); nessun default:
 * senza configurazione esce con errore invece di migrare un database locale per sbaglio.
 */
public final class HubMigrate {

    private HubMigrate() {
    }

    public static void main(String[] args) {
        try {
            run(System.getenv());
        } catch (IllegalStateException e) {
            System.err.println("Errore: " + e.getMessage());
            System.exit(2);
        }
    }

    static void run(Map<String, String> env) {
        String url = first(env, "SPRING_DATASOURCE_URL", "DB_URL");
        String username = first(env, "SPRING_DATASOURCE_USERNAME", "DB_USERNAME", "DB_USER");
        String password = first(env, "SPRING_DATASOURCE_PASSWORD", "DB_PASSWORD");
        HubDatabase.migrate(url, username, password);
    }

    private static String first(Map<String, String> env, String... names) {
        for (String name : names) {
            String v = env.get(name);
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        throw new IllegalStateException("variabile mancante: " + String.join(" o ", names));
    }
}
