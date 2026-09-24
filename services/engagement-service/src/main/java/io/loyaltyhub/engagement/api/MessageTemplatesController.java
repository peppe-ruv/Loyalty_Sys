package io.loyaltyhub.engagement.api;

import io.loyaltyhub.common.web.RequiresRole;
import io.loyaltyhub.common.web.Role;
import io.loyaltyhub.engagement.application.TemplateAdminService;
import io.loyaltyhub.engagement.application.TemplateAdminService.RenderRequest;
import io.loyaltyhub.engagement.application.TemplateAdminService.RenderResult;
import io.loyaltyhub.engagement.application.TemplateAdminService.TemplateRequest;
import io.loyaltyhub.engagement.domain.MessageTemplate;
import io.loyaltyhub.engagement.infra.TemplateRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Template dei messaggi (docs/servizi/engagement-service.md §3; BO-19, F-MSG-02). Scritture: capacità
 * {@code content.write} (ADMIN, MARKETING; docs/08 §2). L'anteprima non scrive e resta aperta a tutti.
 */
@RestController
@RequestMapping("/v1/message-templates")
public class MessageTemplatesController {

    private final TemplateAdminService admin;
    private final TemplateRepository templates;

    public MessageTemplatesController(TemplateAdminService admin, TemplateRepository templates) {
        this.admin = admin;
        this.templates = templates;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<MessageTemplate> list(@RequestParam(required = false) String category,
                                      @RequestParam(required = false) String channel) {
        return templates.findAll(category, channel);
    }

    @GetMapping("/{code}")
    @Transactional(readOnly = true)
    public MessageTemplate get(@PathVariable String code) {
        return admin.get(code);
    }

    @PostMapping
    @RequiresRole({Role.ADMIN, Role.MARKETING})
    public ResponseEntity<MessageTemplate> create(@RequestBody TemplateRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(admin.create(r));
    }

    @PutMapping("/{code}")
    @RequiresRole({Role.ADMIN, Role.MARKETING})
    public MessageTemplate update(@PathVariable String code, @RequestBody TemplateRequest r) {
        return admin.update(code, r);
    }

    /** {@code {sampleEvent}} → titolo e testo con i segnaposto risolti, più i percorsi non risolti. */
    @PostMapping("/{code}/render")
    public RenderResult render(@PathVariable String code, @RequestBody(required = false) RenderRequest r) {
        return admin.render(code, r);
    }
}
