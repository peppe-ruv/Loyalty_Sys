package io.loyaltyhub.wallet.application;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.loyaltyhub.common.audit.AuditEntry;
import io.loyaltyhub.common.audit.AuditPublisher;
import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.wallet.infra.CurrencyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class CurrencyService {

    private final CurrencyRepository currencies;
    private final AuditPublisher audit;
    private final ObjectMapper mapper;

    public CurrencyService(CurrencyRepository currencies, AuditPublisher audit, ObjectMapper mapper) {
        this.currencies = currencies;
        this.audit = audit;
        this.mapper = mapper;
    }

    public List<CurrencyRepository.CurrencyRow> list() {
        return currencies.findAll();
    }

    public record CurrencyUpdate(String expiryPolicyJson) {}

    @Transactional
    public CurrencyRepository.CurrencyRow updatePolicy(String code, CurrencyUpdate req) {
        CurrencyRepository.CurrencyRow existing = currencies.findByCode(code)
                .orElseThrow(() -> LhException.notFound("Valuta non trovata: " + code));

        if (req.expiryPolicyJson() != null) {
            validatePolicy(req.expiryPolicyJson());
            currencies.upsert(code, existing.name(), existing.spendable(), req.expiryPolicyJson());

            audit.record("currency", code, AuditEntry.Action.UPDATE, "Aggiornata policy di scadenza per " + code,
                    Map.of("expiryPolicy", existing.expiryPolicyJson()),
                    Map.of("expiryPolicy", req.expiryPolicyJson()));
        }

        return currencies.findByCode(code).orElseThrow();
    }

    private void validatePolicy(String policyJson) {
        try {
            JsonNode policy = mapper.readTree(policyJson);
            String type = policy.path("type").asText();
            if ("ROLLING_MONTHS".equals(type)) {
                int months = policy.path("months").asInt();
                if (months < 1 || months > 60) {
                     throw LhException.validation("INVALID_EXPIRY_POLICY", "I mesi per ROLLING_MONTHS devono essere tra 1 e 60.");
                }
            } else if ("END_OF_EDITION_PLUS_GRACE".equals(type)) {
                int graceDays = policy.path("graceDays").asInt(0);
                if (graceDays < 0) {
                     throw LhException.validation("INVALID_EXPIRY_POLICY", "graceDays non può essere negativo.");
                }
            } else if (!"NEVER".equals(type) && !"EDITION".equals(type)) {
                throw LhException.validation("INVALID_EXPIRY_POLICY", "Tipo policy non supportato: " + type);
            }
        } catch (Exception e) {
             if (e instanceof LhException) {
                 throw (LhException) e;
             }
             throw LhException.validation("INVALID_EXPIRY_POLICY", "Formato policy non valido.");
        }
    }
}
