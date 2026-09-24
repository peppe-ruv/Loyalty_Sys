package io.loyaltyhub.engagement.api;

import io.loyaltyhub.engagement.application.ContentService;
import io.loyaltyhub.engagement.application.ContentService.ContentDisplay;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Contenuti del portale (docs/servizi/engagement-service.md §3; PT-01, PT-03, PT-05, PT-06): elenco già ordinato e
 * limitato per il membro; per {@code WIN} {@code prizeCode} sceglie la card del premio vinto.
 */
@RestController
@RequestMapping("/v1/portal/content")
public class PortalContentController {

    private final ContentService service;

    public PortalContentController(ContentService service) {
        this.service = service;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<ContentDisplay> content(@RequestParam(required = false) String memberId,
                                        @RequestParam(required = false) String placement,
                                        @RequestParam(required = false) String prizeCode) {
        return service.portal(memberId, placement, prizeCode);
    }
}
