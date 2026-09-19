package io.loyaltyhub.member.api;

import io.loyaltyhub.member.infra.MemberRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Selettore demo (docs/servizi/member-service.md §3): {@code GET /v1/demo/personas}. */
@RestController
@RequestMapping("/v1/demo")
public class PersonasController {

    private final MemberRepository members;

    public PersonasController(MemberRepository members) {
        this.members = members;
    }

    @GetMapping("/personas")
    public List<PersonaView> personas() {
        return members.personas();
    }
}
