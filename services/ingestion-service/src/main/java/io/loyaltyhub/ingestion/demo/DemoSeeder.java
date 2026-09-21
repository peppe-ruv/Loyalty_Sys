package io.loyaltyhub.ingestion.demo;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.loyaltyhub.common.demo.DemoResettable;
import io.loyaltyhub.common.demo.SeedLoader;
import io.loyaltyhub.ingestion.domain.Scenario;
import io.loyaltyhub.ingestion.domain.Source;
import io.loyaltyhub.ingestion.infra.EventTypeRepository;
import io.loyaltyhub.ingestion.infra.InternalMappingRepository;
import io.loyaltyhub.ingestion.infra.MemberIndexRepository;
import io.loyaltyhub.ingestion.infra.ScenarioRepository;
import io.loyaltyhub.ingestion.infra.ScenarioRunRepository;
import io.loyaltyhub.ingestion.infra.SourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Carica i registri di ingestion dai seed canonici (docs/10 §3, docs/servizi/ingestion-service.md §6):
 * fonti, tipi azione con JSON Schema, ponte interno e indice membri. Attivo col profilo {@code demo},
 * idempotente (upsert) e ripetibile via {@code POST /v1/demo/reset} ({@link DemoResettable}).
 */
@Component
@Profile("demo")
public class DemoSeeder implements ApplicationRunner, DemoResettable {

    private static final Logger log = LoggerFactory.getLogger(DemoSeeder.class);

    private final SeedLoader seed;
    private final ObjectMapper mapper;
    private final SourceRepository sources;
    private final EventTypeRepository types;
    private final MemberIndexRepository members;
    private final InternalMappingRepository mappings;
    private final ScenarioRepository scenarios;
    private final ScenarioRunRepository scenarioRuns;

    public DemoSeeder(SeedLoader seed, ObjectMapper mapper, SourceRepository sources,
                      EventTypeRepository types, MemberIndexRepository members,
                      InternalMappingRepository mappings, ScenarioRepository scenarios,
                      ScenarioRunRepository scenarioRuns) {
        this.seed = seed;
        this.mapper = mapper;
        this.sources = sources;
        this.types = types;
        this.members = members;
        this.mappings = mappings;
        this.scenarios = scenarios;
        this.scenarioRuns = scenarioRuns;
    }

    @Override
    public void run(ApplicationArguments args) {
        resetToSeed();
    }

    @Override
    public String demoComponent() {
        return "ingestion";
    }

    @Override
    @Transactional
    public void resetToSeed() {
        seedSources();
        seedEventTypes();
        seedMembers();
        seedMappings();
        seedScenarios();
        log.info("Seed ingestion caricato (profilo demo): fonti, tipi azione, membri, ponte interno, scenari");
    }

    private void seedScenarios() {
        scenarioRuns.deleteAll();
        for (JsonNode s : seed.readTree("scenarios.json")) {
            scenarios.upsert(new Scenario(
                    s.path("code").asString(),
                    s.path("name").asString(),
                    textOrNull(s.get("description")),
                    textOrNull(s.get("protagonist")),
                    textOrNull(s.get("watch")),
                    s.get("steps")));
        }
    }

    private void seedSources() {
        List<Source> list = seed.readList("sources.json", Source.class);
        for (Source s : list) {
            sources.upsert(s);
        }
    }

    private void seedEventTypes() {
        for (JsonNode t : seed.readTree("event-types.json")) {
            types.upsert(
                    t.path("code").asString(),
                    t.path("name").asString(),
                    t.path("origin").asString("SYSTEM"),
                    t.path("category").asString(null),
                    jsonOrNull(t.get("dataSchema")),
                    jsonOrNull(t.get("sampleData")),
                    t.path("enabled").asBoolean(true),
                    t.path("icon").asString(null));
        }
    }

    private void seedMembers() {
        for (JsonNode m : seed.readTree("members.json")) {
            members.upsert(
                    m.path("id").asString(),
                    textOrNull(m.get("externalId")),
                    textOrNull(m.get("email")),
                    m.path("status").asString("ACTIVE"));
        }
    }

    private void seedMappings() {
        for (JsonNode m : seed.readTree("internal-mappings.json")) {
            mappings.upsert(
                    m.path("factType").asString(),
                    m.path("actionType").asString(),
                    m.path("enabled").asBoolean(true));
        }
    }

    private String jsonOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.toString();
    }

    private String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asString();
    }
}
