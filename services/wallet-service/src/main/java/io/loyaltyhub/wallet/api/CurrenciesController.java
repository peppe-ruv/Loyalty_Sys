package io.loyaltyhub.wallet.api;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.loyaltyhub.common.web.RequiresRole;
import io.loyaltyhub.common.web.Role;
import io.loyaltyhub.wallet.application.CurrencyService;
import io.loyaltyhub.wallet.infra.CurrencyRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/v1")
public class CurrenciesController {

    private final CurrencyService currencyService;
    private final ObjectMapper mapper;

    public CurrenciesController(CurrencyService currencyService, ObjectMapper mapper) {
        this.currencyService = currencyService;
        this.mapper = mapper;
    }

    public record CurrencyView(String code, String name, boolean spendable, JsonNode expiryPolicy) {}
    public record CurrencyUpdateDto(JsonNode expiryPolicy) {}

    @GetMapping("/currencies")
    public List<CurrencyView> list() {
        return currencyService.list().stream()
                .map(this::toView)
                .collect(Collectors.toList());
    }

    @GetMapping("/currencies/{code}")
    public CurrencyView get(@PathVariable String code) {
        return currencyService.list().stream()
                .filter(c -> c.code().equals(code.toUpperCase()))
                .findFirst()
                .map(this::toView)
                .orElseThrow(() -> io.loyaltyhub.common.web.LhException.notFound("Valuta non trovata: " + code));
    }

    @PutMapping("/currencies/{code}")
    @RequiresRole({Role.ADMIN})
    public CurrencyView update(@PathVariable String code, @RequestBody CurrencyUpdateDto body) {
        String policyStr = body.expiryPolicy() != null ? body.expiryPolicy().toString() : null;
        return toView(currencyService.updatePolicy(code.toUpperCase(), new CurrencyService.CurrencyUpdate(policyStr)));
    }

    private CurrencyView toView(CurrencyRepository.CurrencyRow row) {
        JsonNode policyNode = null;
        try {
            if (row.expiryPolicyJson() != null && !row.expiryPolicyJson().isBlank()) {
                 policyNode = mapper.readTree(row.expiryPolicyJson());
            }
        } catch (Exception e) {
            // ignore
        }
        return new CurrencyView(row.code(), row.name(), row.spendable(), policyNode);
    }
}
