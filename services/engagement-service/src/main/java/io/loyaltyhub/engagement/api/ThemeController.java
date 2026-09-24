package io.loyaltyhub.engagement.api;

import io.loyaltyhub.common.web.RequiresRole;
import io.loyaltyhub.common.web.Role;
import io.loyaltyhub.engagement.application.ThemeService;
import io.loyaltyhub.engagement.application.ThemeService.ThemeRequest;
import io.loyaltyhub.engagement.domain.Theme;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * Tema del portale (docs/servizi/engagement-service.md §3, F-THM-01): gestione per BO-20 ({@code content.write}) e
 * lettura del portale con cache di 60 s.
 */
@RestController
public class ThemeController {

    private final ThemeService service;

    public ThemeController(ThemeService service) {
        this.service = service;
    }

    @GetMapping("/v1/theme")
    @Transactional(readOnly = true)
    public Theme get() {
        return service.get();
    }

    @PutMapping("/v1/theme")
    @RequiresRole({Role.ADMIN, Role.MARKETING})
    public Theme update(@RequestBody ThemeRequest r) {
        return service.update(r);
    }

    @GetMapping("/v1/portal/theme")
    @Transactional(readOnly = true)
    public ResponseEntity<Theme> portal() {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic()).body(service.get());
    }
}
