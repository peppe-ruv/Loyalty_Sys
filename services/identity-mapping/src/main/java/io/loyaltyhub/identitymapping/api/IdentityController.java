package io.loyaltyhub.identitymapping.api;

import io.loyaltyhub.identitymapping.app.IdentityService;
import io.loyaltyhub.identitymapping.domain.IdentityGraph;
import io.loyaltyhub.identitymapping.domain.IdentityGraph.Identifier;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * API di identity resolution (RF-136): risoluzione multi-identificatore (app, sito, POS, e-commerce, call center),
 * collegamento/scollegamento, merge e unmerge con storico, id canonico per gli alias.
 */
@RestController
@RequestMapping("/v1/identities")
public class IdentityController {
    public record ResolveRequest(List<Identifier> identifiers, String source, String createAs, String channel) {}
    public record LinkRequest(Identifier identifier, String source, Integer confidence) {}
    public record MergeRequest(String fromMemberId, String intoMemberId, String reason, String actor) {}

    private final IdentityService identities;
    public IdentityController(IdentityService identities) { this.identities = identities; }

    @PostMapping("/resolve")
    public IdentityGraph.Resolution resolve(@RequestBody ResolveRequest r) { return identities.resolve(r.identifiers(), r.source(), r.createAs(), r.channel()); }

    @GetMapping("/members/{memberId}")
    public Map<String, Object> links(@PathVariable String memberId) {
        return Map.of("memberId", identities.canonical(memberId), "links", identities.linksOf(memberId), "merges", identities.mergeHistory(memberId));
    }

    @PostMapping("/members/{memberId}/links")
    public IdentityGraph.Link link(@PathVariable String memberId, @RequestBody LinkRequest r) {
        return identities.link(identities.canonical(memberId), r.identifier(), r.source(), r.confidence() == null ? IdentityGraph.defaultConfidence(r.identifier().kind()) : r.confidence());
    }

    @DeleteMapping("/members/{memberId}/links/{kind}/{value}")
    public Map<String, Boolean> unlink(@PathVariable String memberId, @PathVariable String kind, @PathVariable String value) {
        return Map.of("removed", identities.unlink(identities.canonical(memberId), new Identifier(kind, value)));
    }

    @PostMapping("/merges")
    public IdentityGraph.Merge merge(@RequestBody MergeRequest r) { return identities.merge(r.fromMemberId(), r.intoMemberId(), r.reason(), r.actor()); }

    @PostMapping("/merges/{mergeId}/unmerge")
    public IdentityGraph.Merge unmerge(@PathVariable String mergeId, @RequestBody(required = false) Map<String, String> body) {
        return identities.unmerge(mergeId, body == null ? null : body.get("reason"));
    }

    @GetMapping("/canonical/{memberId}")
    public Map<String, String> canonical(@PathVariable String memberId) { return Map.of("memberId", identities.canonical(memberId)); }
}
