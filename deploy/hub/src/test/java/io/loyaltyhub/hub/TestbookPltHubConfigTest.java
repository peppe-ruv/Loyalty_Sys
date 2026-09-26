package io.loyaltyhub.hub;

import io.loyaltyhub.common.kafka.LoyaltyHubProperties;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TB-PLT §HCF e §FRE — configurazione verificabile senza avviare nulla: i topic che l'hub dichiara su un broker reale
 * (docs/05 §1, ADR-004, ADR-025), i listener e gli scheduler pronti all'inizializzazione lazy (docs/06 §5) e il profilo
 * {@code free} di ogni servizio per 512 MB / 0,1 CPU (docs/06 §6, docs/11 §4, §6). Un caso per riga di
 * {@code /testbook/plt/hub-config.csv}; i profili si leggono dai file dei moduli.
 */
class TestbookPltHubConfigTest {

    private static final List<String> SERVICES = List.of("ingestion", "member", "campaign", "wallet", "reward",
            "gamification", "engagement", "insight");
    private static final Path ROOT = Paths.get("../..").toAbsolutePath().normalize();

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/plt/hub-config.csv", numLinesToSkip = 1)
    void config(String id, String description, String kase, String expected) throws Exception {
        assertThat(run(kase)).as("%s: %s", id, description).isEqualTo(expected);
    }

    private String run(String kase) throws Exception {
        return switch (kase) {
            case "topics.names" -> topics().stream().map(NewTopic::name).collect(Collectors.joining(" "));
            case "topics.partitions" -> topics().stream().map(t -> String.valueOf(t.numPartitions())).distinct()
                    .collect(Collectors.joining(","));
            case "topics.replicas" -> topics().stream().map(t -> String.valueOf(t.replicationFactor())).distinct()
                    .collect(Collectors.joining(","));
            case "topics.retention" -> topics().stream().map(t -> t.configs().get("retention.ms")).distinct()
                    .collect(Collectors.joining(","));
            case "topics.profile" -> String.join(",", HubKafkaTopics.class.getAnnotation(Profile.class).value());
            case "lazy.listeners" -> missingLazy(KafkaListener.class);
            case "lazy.schedulers" -> missingLazy(Scheduled.class);
            case "lazy.beanMethods" -> missingLazyBeanMethods();
            default -> {
                if (kase.startsWith("free:") || kase.startsWith("base:")) {
                    yield perService(kase);
                }
                throw new IllegalArgumentException(kase);
            }
        };
    }

    // ---------- topic dell'hub su broker reale ----------

    @SuppressWarnings("unchecked")
    private static List<NewTopic> topics() throws Exception {
        KafkaAdmin.NewTopics topics = new HubKafkaTopics().hubTopics(new LoyaltyHubProperties());
        Method get = KafkaAdmin.NewTopics.class.getDeclaredMethod("getNewTopics");
        get.setAccessible(true);
        return List.copyOf((Collection<NewTopic>) get.invoke(topics));
    }

    // ---------- @Lazy(false) su listener e scheduler (docs/06 §5) ----------

    private static List<Class<?>> projectClasses() throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        List<Class<?>> out = new ArrayList<>();
        for (Resource r : resolver.getResources("classpath*:io/loyaltyhub/**/*.class")) {
            String url = r.getURL().toString();
            if (url.contains("/test-classes/")) {
                continue;
            }
            String name = url.substring(url.lastIndexOf("io/loyaltyhub/")).replace(".class", "").replace('/', '.');
            if (name.contains("$")) {
                continue;
            }
            try {
                out.add(Class.forName(name, false, TestbookPltHubConfigTest.class.getClassLoader()));
            } catch (Throwable ignored) {
                // classi non caricabili senza il loro contesto: non sono componenti
            }
        }
        return out;
    }

    private static boolean hasMethodWith(Class<?> c, Class<? extends java.lang.annotation.Annotation> a) {
        return Arrays.stream(c.getDeclaredMethods()).anyMatch(m -> m.isAnnotationPresent(a));
    }

    /** Componenti ({@code @Component} e derivate) con un metodo annotato ma senza {@code @Lazy(false)}. */
    private static String missingLazy(Class<? extends java.lang.annotation.Annotation> a) throws Exception {
        TreeSet<String> missing = new TreeSet<>();
        for (Class<?> c : projectClasses()) {
            boolean component = org.springframework.core.annotation.AnnotatedElementUtils.hasAnnotation(c, Component.class);
            if (component && hasMethodWith(c, a)) {
                Lazy lazy = c.getAnnotation(Lazy.class);
                if (lazy == null || lazy.value()) {
                    missing.add(c.getSimpleName());
                }
            }
        }
        return missing.isEmpty() ? "nessuno" : String.join(" ", missing);
    }

    /** Metodi {@code @Bean} che creano oggetti con {@code @Scheduled}/{@code @KafkaListener} senza {@code @Lazy(false)}. */
    private static String missingLazyBeanMethods() throws Exception {
        TreeSet<String> missing = new TreeSet<>();
        for (Class<?> c : projectClasses()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.isAnnotationPresent(Bean.class)) {
                    continue;
                }
                Class<?> type = m.getReturnType();
                if (hasMethodWith(type, Scheduled.class) || hasMethodWith(type, KafkaListener.class)) {
                    Lazy lazy = m.getAnnotation(Lazy.class);
                    if (lazy == null || lazy.value()) {
                        missing.add(c.getSimpleName() + "#" + m.getName());
                    }
                }
            }
        }
        return missing.isEmpty() ? "nessuno" : String.join(" ", missing);
    }

    // ---------- profilo free e pool di ogni servizio ----------

    /** {@code free:<chiave>} o {@code base:<chiave>}: valore della chiave in ogni servizio, raggruppato. */
    private static String perService(String kase) throws Exception {
        boolean free = kase.startsWith("free:");
        String key = kase.substring(5);
        Map<String, List<String>> byValue = new LinkedHashMap<>();
        for (String s : SERVICES) {
            Path file = ROOT.resolve("services/" + s + "-service/src/main/resources/"
                    + (free ? "application-free.yml" : "application.yml"));
            String value;
            if (!Files.exists(file)) {
                value = "file assente";
            } else if (key.equals("exists")) {
                value = "presente";
            } else {
                Object v = flatten(file).get(key);
                value = v == null ? "assente" : String.valueOf(v);
            }
            byValue.computeIfAbsent(value, k -> new ArrayList<>()).add(s);
        }
        if (byValue.size() == 1) {
            return byValue.keySet().iterator().next();
        }
        return byValue.entrySet().stream().map(e -> e.getKey() + " " + e.getValue()).collect(Collectors.joining("; "));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> flatten(Path file) throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        try (InputStream in = Files.newInputStream(file)) {
            for (Object doc : new Yaml().loadAll(in)) {
                if (doc instanceof Map<?, ?> map) {
                    flatten("", (Map<String, Object>) map, out);
                }
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void flatten(String prefix, Map<String, Object> map, Map<String, Object> out) {
        for (Map.Entry<String, Object> e : map.entrySet()) {
            String k = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            if (e.getValue() instanceof Map<?, ?> nested) {
                flatten(k, (Map<String, Object>) nested, out);
            } else {
                out.put(k, e.getValue());
            }
        }
    }
}
