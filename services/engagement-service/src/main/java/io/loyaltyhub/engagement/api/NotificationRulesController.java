package io.loyaltyhub.engagement.api;

import io.loyaltyhub.common.web.RequiresRole;
import io.loyaltyhub.common.web.Role;
import io.loyaltyhub.engagement.application.RuleAdminService;
import io.loyaltyhub.engagement.application.RuleAdminService.RuleRequest;
import io.loyaltyhub.engagement.domain.NotificationRule;
import io.loyaltyhub.engagement.infra.RuleRepository;
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
 * Regole di notifica fatto → template (docs/servizi/engagement-service.md §3; BO-19, F-MSG-01). Scritture: capacità
 * {@code content.write} (ADMIN, MARKETING; docs/08 §2). Nei path si accetta {@code id} o {@code code} (docs/06 §2).
 */
@RestController
@RequestMapping("/v1/notification-rules")
public class NotificationRulesController {

    private final RuleAdminService admin;
    private final RuleRepository rules;

    public NotificationRulesController(RuleAdminService admin, RuleRepository rules) {
        this.admin = admin;
        this.rules = rules;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<NotificationRule> list(@RequestParam(required = false) String factType,
                                       @RequestParam(required = false) String templateCode) {
        return rules.findAll(RuleAdminService.normalizeFactType(factType), templateCode);
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public NotificationRule get(@PathVariable String id) {
        return admin.get(id);
    }

    @PostMapping
    @RequiresRole({Role.ADMIN, Role.MARKETING})
    public ResponseEntity<NotificationRule> create(@RequestBody RuleRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(admin.create(r));
    }

    @PutMapping("/{id}")
    @RequiresRole({Role.ADMIN, Role.MARKETING})
    public NotificationRule update(@PathVariable String id, @RequestBody RuleRequest r) {
        return admin.update(id, r);
    }
}
