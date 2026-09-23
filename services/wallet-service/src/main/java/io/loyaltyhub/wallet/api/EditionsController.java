package io.loyaltyhub.wallet.api;

import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.common.web.RequiresRole;
import io.loyaltyhub.common.web.Role;
import io.loyaltyhub.common.web.ActorHolder;
import io.loyaltyhub.wallet.application.EditionService;
import io.loyaltyhub.wallet.domain.Edition;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/v1")
public class EditionsController {

    private final EditionService editionService;

    public EditionsController(EditionService editionService) {
        this.editionService = editionService;
    }

    @GetMapping("/editions")
    public List<Edition> list() {
        return editionService.list();
    }

    @PostMapping("/editions")
    @RequiresRole({Role.ADMIN})
    public Edition create(@RequestBody EditionService.EditionUpdate body) {
        return editionService.create(body);
    }

    @PutMapping("/editions/{code}")
    @RequiresRole({Role.ADMIN})
    public Edition update(@PathVariable String code, @RequestBody EditionService.EditionUpdate body) {
        return editionService.update(code.toUpperCase(), body);
    }

    @PostMapping("/editions/{code}/close")
    public EditionService.ClosePreviewResult close(
            @PathVariable String code,
            @RequestParam(defaultValue = "true") boolean dryRun) {
        if (!dryRun) {
            Role role = ActorHolder.get().role();
            if (role != Role.ADMIN) {
                throw LhException.forbiddenRole("La chiusura effettiva richiede il ruolo ADMIN.");
            }
        }
        return editionService.closeEdition(code.toUpperCase(), dryRun);
    }
}
