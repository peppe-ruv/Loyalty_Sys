package io.loyaltyhub.hub;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLMapper;
import tools.jackson.dataformat.yaml.YAMLGenerator;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.test.context.EmbeddedKafka;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

@SpringBootTest(
        classes = {HubApplication.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.name=hub"}
)
@ActiveProfiles({"demo", "inproc"})
@EmbeddedKafka(partitions = 1, topics = {"lh.actions.v1", "lh.effects.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class OpenApiExportIT {
    private static final EmbeddedPostgres PG = startPg();

    private static EmbeddedPostgres startPg() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @DynamicPropertySource
    static void pgProperties(DynamicPropertyRegistry registry) {
        String base = PG.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url", () -> base + "&currentSchema=ingestion,member,campaign,wallet,insight,reward,gamification,engagement");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("spring.kafka.bootstrap-servers", () -> System.getProperty("spring.embedded.kafka.brokers"));
    }

    @AfterAll
    void tearDown() throws Exception {
        PG.close();
    }

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private ObjectMapper mapper;

    @Test
    void exportApi() throws Exception {
        RestClient client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        String json = client.get().uri("/v3/api-docs").retrieve().body(String.class);
        JsonNode root = mapper.readTree(json);

        List<String> owners = List.of("ingestion", "member", "campaign", "wallet", "insight", "reward", "gamification", "engagement", "portal");

        Map<String, ObjectNode> apis = new HashMap<>();
        for (String owner : owners) {
            ObjectNode apiNode = (ObjectNode) root.deepCopy();
            ObjectNode pathsNode = (ObjectNode) apiNode.path("paths");
            pathsNode.removeAll();
            apis.put(owner, apiNode);
        }

        JsonNode allPaths = root.path("paths");
        for (Map.Entry<String, JsonNode> pathEntry : allPaths.properties()) {
            String path = pathEntry.getKey();
            String owner = null;
            if (path.startsWith("/v1/portal/")) {
                owner = "portal";
            } else if (path.startsWith("/v1/events") || path.startsWith("/v1/event-types") || path.startsWith("/v1/inbound-events") || path.startsWith("/v1/scenarios") || path.startsWith("/v1/simulator") || path.startsWith("/v1/transactions") || path.startsWith("/v1/internal-mappings") || path.startsWith("/v1/registry")) {
                owner = "ingestion";
            } else if (path.startsWith("/v1/members") || path.startsWith("/v1/segments") || path.startsWith("/v1/personas") || path.startsWith("/v1/attribute-definitions") || path.startsWith("/v1/referral") || path.startsWith("/v1/member-jobs")) {
                owner = "member";
            } else if (path.startsWith("/v1/campaigns") || path.startsWith("/v1/evaluations")) {
                owner = "campaign";
            } else if (path.startsWith("/v1/wallets") || path.startsWith("/v1/editions") || path.startsWith("/v1/tiers") || path.startsWith("/v1/liability") || path.startsWith("/v1/currencies") || path.startsWith("/v1/wallet-jobs")) {
                owner = "wallet";
            } else if (path.startsWith("/v1/kpi") || path.startsWith("/v1/pipeline") || path.startsWith("/v1/traces") || path.startsWith("/v1/dlq") || path.startsWith("/v1/stream") || path.startsWith("/v1/audit")) {
                owner = "insight";
            } else if (path.startsWith("/v1/catalog") || path.startsWith("/v1/reward-stats") || path.startsWith("/v1/reward-jobs") || path.startsWith("/v1/redemptions") || path.startsWith("/v1/coupons")) {
                owner = "reward";
            } else if (path.startsWith("/v1/leaderboards") || path.startsWith("/v1/contests") || path.startsWith("/v1/achievements") || path.startsWith("/v1/gamification-demo")) {
                owner = "gamification";
            } else if (path.startsWith("/v1/engagement-jobs") || path.startsWith("/v1/contents") || path.startsWith("/v1/theme") || path.startsWith("/v1/inbox") || path.startsWith("/v1/webhooks") || path.startsWith("/v1/notification-rules") || path.startsWith("/v1/messages") || path.startsWith("/v1/message-templates") || path.startsWith("/v1/popups")) {
                owner = "engagement";
            } else if (path.equals("/v1/demo/reset") || path.equals("/v1/demo/info")) {
                continue;
            } else {
                continue;
            }

            ((ObjectNode)apis.get(owner).path("paths")).set(path, pathEntry.getValue().deepCopy());
        }

        String shouldWrite = System.getProperty("lh.openapi.write");
        Path apiDir = Paths.get("../../contracts/api").toAbsolutePath().normalize();
        if (!Files.exists(apiDir)) {
            Files.createDirectories(apiDir);
        }

        YAMLMapper yamlMapper = YAMLMapper.builder()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .build();

        for (Map.Entry<String, ObjectNode> entry : apis.entrySet()) {
            String owner = entry.getKey();
            ObjectNode apiNode = entry.getValue();

            ObjectNode sortedRoot = sortObjectNode(apiNode, mapper);

            Path yamlFile = apiDir.resolve(owner + ".openapi.yaml");
            String outputYaml = yamlMapper.writeValueAsString(sortedRoot);

            if (outputYaml.startsWith("---")) {
                outputYaml = outputYaml.substring(outputYaml.indexOf('\n') + 1);
            }
            outputYaml = outputYaml.replace("\r\n", "\n");

            if ("true".equals(shouldWrite)) {
                Files.writeString(yamlFile, outputYaml, StandardCharsets.UTF_8);
            } else {
                if (!Files.exists(yamlFile)) {
                    fail("File " + yamlFile.getFileName() + " non esiste. Rigenera con -Dlh.openapi.write=true");
                }
                String existing = Files.readString(yamlFile, StandardCharsets.UTF_8);
                existing = existing.replace("\r\n", "\n");
                assertThat(outputYaml).as("Differenze in " + yamlFile.getFileName() + ". Rigenera con -Dlh.openapi.write=true").isEqualTo(existing);
            }
        }
    }

    private ObjectNode sortObjectNode(ObjectNode node, ObjectMapper mapper) {
        ObjectNode sorted = mapper.createObjectNode();
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, JsonNode> prop : node.properties()) {
            keys.add(prop.getKey());
        }
        Collections.sort(keys);
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (key.equals("servers")) {
                continue;
            }
            if (value.isObject()) {
                sorted.set(key, sortObjectNode((ObjectNode) value, mapper));
            } else if (value.isArray()) {
                sorted.set(key, sortArrayNode((ArrayNode) value, mapper));
            } else {
                sorted.set(key, value);
            }
        }
        return sorted;
    }

    private ArrayNode sortArrayNode(ArrayNode node, ObjectMapper mapper) {
        ArrayNode sorted = mapper.createArrayNode();
        for (int i = 0; i < node.size(); i++) {
            JsonNode value = node.get(i);
            if (value.isObject()) {
                sorted.add(sortObjectNode((ObjectNode) value, mapper));
            } else if (value.isArray()) {
                sorted.add(sortArrayNode((ArrayNode) value, mapper));
            } else {
                sorted.add(value);
            }
        }
        return sorted;
    }
}
