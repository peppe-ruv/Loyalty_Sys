package io.loyaltyhub.insight.api;

import io.loyaltyhub.insight.infra.TopicStatRepository;
import io.loyaltyhub.insight.infra.TopicStatRepository.TopicStat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Stato della pipeline per topic (docs/servizi/insight-service.md §3, {@code GET /v1/pipeline/status}).
 * M2.1: ultimo evento e volume per topic dall'event store; il ritardo stimato e l'ultimo fatto per servizio
 * arrivano con le metriche (M2.4).
 */
@RestController
@RequestMapping("/v1/pipeline")
public class PipelineController {

    private final TopicStatRepository topicStats;

    public PipelineController(TopicStatRepository topicStats) {
        this.topicStats = topicStats;
    }

    public record PipelineStatus(List<TopicStat> topics) {
    }

    @GetMapping("/status")
    public PipelineStatus status() {
        return new PipelineStatus(topicStats.findAll());
    }
}
