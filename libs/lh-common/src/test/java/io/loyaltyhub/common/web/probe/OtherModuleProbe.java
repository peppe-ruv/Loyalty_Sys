package io.loyaltyhub.common.web.probe;

import io.loyaltyhub.common.web.MemberEndpoint;
import io.loyaltyhub.common.web.MemberPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Handler di un altro «modulo» (package più specifico) per provare la scelta della lookup per prefisso più lungo. */
@RestController
public class OtherModuleProbe {

    @GetMapping("/v1/portal/other")
    @MemberEndpoint
    public Map<String, Object> other(MemberPrincipal principal) {
        return Map.of("member", String.valueOf(principal.idOrNull()));
    }
}
