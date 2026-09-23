package io.loyaltyhub.reward.api;

import io.loyaltyhub.common.web.RequiresRole;
import io.loyaltyhub.common.web.Role;
import io.loyaltyhub.reward.application.CatalogAdminService;
import io.loyaltyhub.reward.application.CatalogAdminService.RewardRequest;
import io.loyaltyhub.reward.domain.Band;
import io.loyaltyhub.reward.domain.Category;
import io.loyaltyhub.reward.domain.Reward;
import io.loyaltyhub.reward.infra.CatalogRepository;
import io.loyaltyhub.reward.infra.RewardRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * Gestione del catalogo premi (docs/servizi/reward-service.md §3; BO-10 catalogo, BO-11 fasce). Scritture riservate a
 * {@code object.edit} (ADMIN, MARKETING); le fasce sono configurazione di programma (ADMIN).
 */
@RestController
@RequestMapping("/v1")
public class CatalogController {

    public record TransitionRequest(String action, String comment) {
    }

    private final CatalogAdminService admin;
    private final CatalogRepository catalog;
    private final RewardRepository rewards;

    public CatalogController(CatalogAdminService admin, CatalogRepository catalog, RewardRepository rewards) {
        this.admin = admin;
        this.catalog = catalog;
        this.rewards = rewards;
    }

    @GetMapping("/reward-categories")
    public List<Category> categories() {
        return catalog.categories();
    }

    @PostMapping("/reward-categories")
    @RequiresRole({Role.ADMIN, Role.MARKETING})
    public ResponseEntity<Category> createCategory(@RequestBody Category c) {
        return ResponseEntity.status(HttpStatus.CREATED).body(admin.saveCategory(c));
    }

    @PutMapping("/reward-categories/{code}")
    @RequiresRole({Role.ADMIN, Role.MARKETING})
    public Category updateCategory(@PathVariable String code, @RequestBody Category c) {
        return admin.saveCategory(new Category(code, c.name(), c.icon(), c.sortOrder()));
    }

    @GetMapping("/reward-bands")
    public List<Band> bands() {
        return catalog.bands();
    }

    @PostMapping("/reward-bands")
    @RequiresRole({Role.ADMIN, Role.MARKETING})
    public ResponseEntity<Band> createBand(@RequestBody Band b) {
        return ResponseEntity.status(HttpStatus.CREATED).body(admin.saveBand(b));
    }

    @PutMapping("/reward-bands/{code}")
    @RequiresRole({Role.ADMIN, Role.MARKETING})
    public Band updateBand(@PathVariable String code, @RequestBody Band b) {
        return admin.saveBand(new Band(code, b.name(), b.pointsThreshold(), b.color(), b.sortOrder()));
    }

    @DeleteMapping("/reward-bands/{code}")
    @RequiresRole({Role.ADMIN, Role.MARKETING})
    public ResponseEntity<Void> deleteBand(@PathVariable String code) {
        admin.deleteBand(code);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/rewards")
    public List<Reward> rewards(@RequestParam(required = false) String status, @RequestParam(required = false) String band,
                                @RequestParam(required = false) String category, @RequestParam(required = false) String type,
                                @RequestParam(required = false) String q) {
        return rewards.search(status, band, category, type, q);
    }

    @GetMapping("/rewards/{id}")
    public Reward reward(@PathVariable String id) {
        return admin.get(id);
    }

    @PostMapping("/rewards")
    @RequiresRole({Role.ADMIN, Role.MARKETING})
    public ResponseEntity<Reward> create(@RequestBody RewardRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(admin.create(r));
    }

    @PutMapping("/rewards/{id}")
    @RequiresRole({Role.ADMIN, Role.MARKETING})
    public Reward update(@PathVariable String id, @RequestBody RewardRequest r) {
        return admin.update(id, r);
    }

    @PostMapping("/rewards/{id}/transitions")
    @RequiresRole({Role.ADMIN, Role.MARKETING, Role.LEGAL})
    public Reward transition(@PathVariable String id, @RequestBody TransitionRequest t) {
        return admin.transition(id, t.action(), t.comment());
    }

    @PostMapping("/rewards/{id}/duplicate")
    @RequiresRole({Role.ADMIN, Role.MARKETING})
    public ResponseEntity<Reward> duplicate(@PathVariable String id) {
        return ResponseEntity.status(HttpStatus.CREATED).body(admin.duplicate(id));
    }
}
