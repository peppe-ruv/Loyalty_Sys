package io.loyaltyhub.hub.bus;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.Environment;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.SendResult;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Cablaggio del bus in-process per il profilo {@code inproc} (demo ospitata a costo zero, docs/13 ADR-024).
 * Due punti di innesto, dietro le stesse astrazioni del resto del modello:
 * <ul>
 *   <li><strong>produzione</strong>: un {@link KafkaTemplate} che invece del broker consegna al
 *       {@link HubInProcessBus}. È l'unica via di uscita: tutto passa dall'outbox → {@code OutboxRelay} →
 *       {@code kafka.send(...)}. Vince su quello auto-configurato di lh-common ({@code @ConditionalOnMissingBean}).</li>
 *   <li><strong>consumo</strong>: a startup si registrano sul bus tutti i metodi {@code @KafkaListener} dei 4
 *       servizi (topic + gruppo consumer risolti dall'annotazione), riusando esattamente il loro codice. I
 *       container Kafka reali restano spenti ({@code spring.kafka.listener.auto-startup=false} nel profilo).</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@Profile("inproc")
public class HubInProcessMessaging {

    /** Ack senza effetto: nel bus in-process la consegna è già "at-least-once" col ritentativo. */
    private static final Acknowledgment NO_OP_ACK = () -> {
    };

    @Bean
    public HubInProcessBus hubInProcessBus() {
        return new HubInProcessBus();
    }

    /**
     * {@code KafkaTemplate} che pubblica sul bus in-process. Il {@code ProducerFactory} passato al costruttore
     * non viene mai usato (sovrascriviamo {@code send}), quindi nessun produttore Kafka è creato.
     */
    @Bean
    public KafkaTemplate<String, String> kafkaTemplate(HubInProcessBus bus) {
        Map<String, Object> unusedCfg = Map.of(
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(unusedCfg)) {
            @Override
            public CompletableFuture<SendResult<String, String>> send(ProducerRecord<String, String> record) {
                bus.publish(record);
                return CompletableFuture.completedFuture(null);
            }
        };
    }

    /**
     * A singleton pronti, registra sul bus ogni {@code @KafkaListener} dei servizi: per ciascun topic
     * dichiarato dal metodo crea una sottoscrizione col suo gruppo consumer, che ne invoca il codice originale.
     */
    @Bean
    public SmartInitializingSingleton hubListenerRegistrar(HubInProcessBus bus, ApplicationContext context,
                                                           Environment env) {
        return () -> {
            for (String name : context.getBeanDefinitionNames()) {
                Object bean;
                try {
                    bean = context.getBean(name);
                } catch (RuntimeException notResolvable) {
                    continue;
                }
                Class<?> targetClass = org.springframework.aop.support.AopUtils.getTargetClass(bean);
                ReflectionUtils.doWithMethods(targetClass, method -> register(bus, env, bean, method));
            }
        };
    }

    private void register(HubInProcessBus bus, Environment env, Object bean, Method method) {
        KafkaListener ann = AnnotatedElementUtils.findMergedAnnotation(method, KafkaListener.class);
        if (ann == null) {
            return;
        }
        ReflectionUtils.makeAccessible(method);
        for (String topicExpr : ann.topics()) {
            String topic = env.resolvePlaceholders(topicExpr);
            bus.subscribe(topic, ann.groupId(), record -> invoke(bean, method, record));
        }
    }

    /** Invoca il metodo listener passando gli argomenti per tipo (ConsumerRecord / Acknowledgment). */
    private void invoke(Object bean, Method method, ConsumerRecord<String, String> record) throws Exception {
        Class<?>[] types = method.getParameterTypes();
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            if (types[i].isAssignableFrom(ConsumerRecord.class)) {
                args[i] = record;
            } else if (types[i].isAssignableFrom(Acknowledgment.class)) {
                args[i] = NO_OP_ACK;
            }
        }
        try {
            method.invoke(bean, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) {
                throw ex; // il bus ritenta come farebbe l'error handler Kafka
            }
            throw new IllegalStateException(cause);
        }
    }
}
