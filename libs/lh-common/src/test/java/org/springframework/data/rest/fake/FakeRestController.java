package org.springframework.data.rest.fake;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller di un package {@code org.springframework.*} che non è tra i framework esentati dal deny by default
 * (per esempio Spring Data REST): senza dichiarazione di accesso deve essere rifiutato (F2-SEC-09).
 */
@RestController
public class FakeRestController {

    @GetMapping("/v1/fake/undeclared")
    public String undeclared() {
        return "x";
    }
}
