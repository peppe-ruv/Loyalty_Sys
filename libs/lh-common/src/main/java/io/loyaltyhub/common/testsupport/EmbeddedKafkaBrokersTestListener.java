package io.loyaltyhub.common.testsupport;

import org.springframework.context.ApplicationContext;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

/**
 * Riallinea {@code spring.embedded.kafka.brokers} al broker del contesto di test in uso.
 * <p>
 * Ogni contesto con {@code @EmbeddedKafka} avvia un proprio broker e scrive il suo indirizzo in quella proprietà di
 * sistema, che è globale alla JVM: quando la cache dei contesti riusa un contesto avviato prima di un altro, i test che
 * leggono la proprietà pubblicherebbero sul broker sbagliato e i loro eventi non verrebbero mai consumati (dipende
 * dall'ordine delle classi, quindi verde in locale e rosso in CI). Prima di ogni classe e di ogni metodo la proprietà
 * torna a indicare il broker del contesto corrente.
 * <p>
 * Registrato in {@code META-INF/spring.factories}: la chiave è letta solo da spring-test, quindi a runtime la classe
 * non viene mai caricata (spring-test e spring-kafka-test sono dipendenze {@code provided}).
 */
public class EmbeddedKafkaBrokersTestListener extends AbstractTestExecutionListener {

    static final String BROKERS_PROPERTY = EmbeddedKafkaBroker.SPRING_EMBEDDED_KAFKA_BROKERS;

    /** Subito dopo l'iniezione delle dipendenze (2000), quindi a contesto già caricato. */
    @Override
    public int getOrder() {
        return 2050;
    }

    @Override
    public void prepareTestInstance(TestContext testContext) {
        realign(testContext);
    }

    @Override
    public void beforeTestMethod(TestContext testContext) {
        realign(testContext);
    }

    private static void realign(TestContext testContext) {
        if (!testContext.hasApplicationContext()) {
            return;
        }
        ApplicationContext context = testContext.getApplicationContext();
        EmbeddedKafkaBroker broker = context.getBeanProvider(EmbeddedKafkaBroker.class).getIfUnique();
        if (broker != null) {
            System.setProperty(BROKERS_PROPERTY, broker.getBrokersAsString());
        }
    }
}
