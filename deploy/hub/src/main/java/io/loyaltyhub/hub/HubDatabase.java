package io.loyaltyhub.hub;

import org.flywaydb.core.Flyway;
import org.postgresql.Driver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.stereotype.Component;

/**
 * Migrazioni per schema del deployable consolidato (docs/13 ADR-023). Spring Boot ha un solo bean Flyway con
 * uno schema di default; qui ogni servizio tiene il proprio schema, quindi eseguiamo Flyway una volta per
 * schema (comune {@code V0} + le migrazioni del servizio). Gira come {@link BeanFactoryPostProcessor} ad alta
 * priorità, <em>prima</em> di qualsiasi bean applicativo (alcuni interrogano il DB già nel costruttore): legge
 * i parametri della sorgente dati direttamente dall'{@link Environment} e usa una connessione usa-e-getta, così
 * non forza la creazione anticipata del {@code DataSource} dell'app. Il Flyway automatico è spento in {@code hub.yml}.
 */
@Component
public class HubDatabase implements BeanFactoryPostProcessor, EnvironmentAware, PriorityOrdered {

    private static final Logger log = LoggerFactory.getLogger(HubDatabase.class);
    private static final String[] SCHEMAS = {"ingestion", "member", "campaign", "wallet", "insight", "reward", "gamification"};

    private Environment env;

    @Override
    public void setEnvironment(Environment environment) {
        this.env = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        SimpleDriverDataSource ds = new SimpleDriverDataSource(
                new Driver(),
                env.getProperty("spring.datasource.url", "jdbc:postgresql://localhost:5432/loyaltyhub"),
                env.getProperty("spring.datasource.username", "loyaltyhub"),
                env.getProperty("spring.datasource.password", "loyaltyhub"));
        for (String schema : SCHEMAS) {
            Flyway.configure()
                    .dataSource(ds)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .createSchemas(true)
                    .locations("classpath:db/migration/common", "classpath:db/migration/" + schema)
                    .load()
                    .migrate();
        }
        log.info("Migrazioni hub completate per gli schemi: {}", String.join(", ", SCHEMAS));
    }

    @Override
    public int getOrder() {
        return PriorityOrdered.HIGHEST_PRECEDENCE;
    }
}
