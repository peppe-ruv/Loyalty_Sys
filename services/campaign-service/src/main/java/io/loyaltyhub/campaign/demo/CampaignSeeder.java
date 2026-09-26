package io.loyaltyhub.campaign.demo;

import io.loyaltyhub.common.approval.ApprovalAction;
import io.loyaltyhub.common.approval.ApprovalHistoryStore;
import io.loyaltyhub.common.approval.ApprovalPolicy;
import io.loyaltyhub.common.approval.ApprovalStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.loyaltyhub.campaign.application.CampaignCache;
import io.loyaltyhub.campaign.domain.Campaign;
import io.loyaltyhub.campaign.domain.CampaignStatus;
import io.loyaltyhub.campaign.infra.CampaignRepository;
import io.loyaltyhub.campaign.infra.CounterRepository;
import io.loyaltyhub.campaign.infra.MemberSnapshotRepository;
import io.loyaltyhub.common.demo.DemoResettable;
import io.loyaltyhub.common.demo.SeedLoader;
import io.loyaltyhub.common.ids.Ulid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * Carica le campagne canoniche (docs/10 §4) e lo snapshot dei membri (docs/servizi/campaign-service.md §6)
 * dai seed. Attivo col profilo {@code demo}, idempotente, ripetibile via {@code POST /v1/demo/reset}.
 */
@Component
@Profile("demo")
public class CampaignSeeder implements ApplicationRunner, DemoResettable {

    private static final Logger log = LoggerFactory.getLogger(CampaignSeeder.class);

    private final SeedLoader seed;
    private final ObjectMapper mapper;
    private final CampaignRepository campaigns;
    private final MemberSnapshotRepository snapshots;
    private final CounterRepository counters;
    private final CampaignCache cache;
    private final ApprovalHistoryStore approvalHistory;
    private final Clock clock;

    private final io.loyaltyhub.campaign.infra.EvaluationLogRepository evaluations;

    public CampaignSeeder(SeedLoader seed, ObjectMapper mapper, CampaignRepository campaigns,
                          MemberSnapshotRepository snapshots, CounterRepository counters,
                          CampaignCache cache, ApprovalHistoryStore approvalHistory, Clock clock,
                          io.loyaltyhub.campaign.infra.EvaluationLogRepository evaluations) {
        this.seed = seed;
        this.mapper = mapper;
        this.campaigns = campaigns;
        this.snapshots = snapshots;
        this.counters = counters;
        this.cache = cache;
        this.approvalHistory = approvalHistory;
        this.clock = clock;
        this.evaluations = evaluations;
    }

    @Override
    public void run(ApplicationArguments args) {
        resetToSeed();
    }

    @Override
    public String demoComponent() {
        return "campaign";
    }

    @Override
    @Transactional
    public void resetToSeed() {
        counters.deleteAll();
        // docs/06 §10: il registro delle valutazioni fa parte dello stato del servizio (restava dopo il reset).
        evaluations.deleteAll();
        approvalHistory.deleteAll(ApprovalPolicy.CAMPAIGN);
        campaigns.deleteAll();
        snapshots.deleteAll();

        for (JsonNode c : seed.readTree("campaigns.json")) {
            Campaign campaign = toCampaign(c);
            campaigns.insert(campaign);
            if (campaign.status() == CampaignStatus.IN_REVIEW) {
                // In coda approvazioni (BO-21) dal giorno prima, inviata dal marketing.
                approvalHistory.record(ApprovalPolicy.CAMPAIGN, campaign.id(), ApprovalStatus.DRAFT,
                        ApprovalStatus.IN_REVIEW, ApprovalAction.SUBMIT, "MARKETING:luca.marketing", null,
                        clock.instant().minus(java.time.Duration.ofDays(1)));
            }
        }
        for (JsonNode m : seed.readTree("members.json")) {
            // Data di nascita e attributi personalizzati come nello snapshot dei fatti member.* (member.age,
            // member.attributes.<k>: M6.7).
            snapshots.upsertIdentity(m.path("id").asString(), m.path("status").asString("ACTIVE"),
                    m.path("tier").asString("BASE"), null,
                    m.hasNonNull("birthDate") ? java.time.LocalDate.parse(m.get("birthDate").asString()) : null,
                    m.path("attributes").isObject() ? m.get("attributes").toString() : "{}");
            // Etichette del seed (docs/10 §3: SEG-DIGITAL); i segmenti arrivano dai fatti member.segment.* (M6.6).
            if (m.path("labels").isArray() && !m.path("labels").isEmpty()) {
                java.util.List<String> labels = new java.util.ArrayList<>();
                m.path("labels").forEach(l -> labels.add(l.asString()));
                snapshots.updateLabels(m.path("id").asString(), labels);
            }
        }
        cache.reload();
        log.info("Seed campaign caricato (profilo demo): campagne + snapshot membri");
    }

    private Campaign toCampaign(JsonNode c) {
        return new Campaign(
                Ulid.next(clock),
                c.path("code").asString(),
                c.path("name").asString(),
                textOrNull(c, "description"),
                textOrNull(c, "memberDescription"),
                textOrNull(c, "icon"),
                stringList(c.get("triggerActionTypes")),
                nodeOrEmptyObject(c.get("audience")),
                nodeOrEmptyObject(c.get("conditions")),
                nodeOrEmptyArray(c.get("effects")),
                nodeOrEmptyObject(c.get("limits")),
                nodeOrEmptyObject(c.get("schedule")),
                c.path("priority").asInt(100),
                textOrNull(c, "exclusiveGroup"),
                c.path("visibleInPortal").asBoolean(false),
                c.path("system").asBoolean(false),
                c.path("requiresLegal").asBoolean(false),
                stringList(c.get("labels")),
                CampaignStatus.valueOf(c.path("status").asString("DRAFT")),
                0, null, null);
    }

    private static String textOrNull(JsonNode n, String field) {
        return n.has(field) && !n.get(field).isNull() ? n.get(field).asString() : null;
    }

    private static List<String> stringList(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            arr.forEach(e -> out.add(e.asString()));
        }
        return out;
    }

    private JsonNode nodeOrEmptyObject(JsonNode n) {
        return n == null || n.isNull() ? mapper.createObjectNode() : n;
    }

    private JsonNode nodeOrEmptyArray(JsonNode n) {
        return n == null || n.isNull() ? mapper.createArrayNode() : n;
    }
}
