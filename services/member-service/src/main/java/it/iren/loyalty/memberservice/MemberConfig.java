package it.iren.loyalty.memberservice;

import it.iren.loyalty.memberservice.domain.ReferralPolicy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Politica referral di default; in produzione arriva dalle impostazioni del backoffice (collezione settings). */
@Configuration
public class MemberConfig {
    @Bean
    @ConditionalOnMissingBean
    ReferralPolicy referralPolicy() { return ReferralPolicy.example(); }

    /** Finalità di consenso dal backoffice (collezione {@code consent-purposes}), con cache e fallback al catalogo di default. */
    @Bean
    @ConditionalOnMissingBean
    @SuppressWarnings("unchecked")
    it.iren.loyalty.memberservice.domain.Consent.PurposeSource purposeSource(org.springframework.web.client.RestClient.Builder builder) {
        var cms = builder.baseUrl(System.getenv().getOrDefault("CMS_URL", "http://cms:3000")).build();
        long ttl = Long.parseLong(System.getenv().getOrDefault("CMS_CACHE_MS", "60000"));
        return new it.iren.loyalty.memberservice.domain.Consent.PurposeSource() {
            private volatile java.util.List<it.iren.loyalty.memberservice.domain.Consent.Purpose> last = it.iren.loyalty.memberservice.domain.Consent.defaultPurposes();
            private volatile long at = 0;
            @Override public java.util.List<it.iren.loyalty.memberservice.domain.Consent.Purpose> purposes() {
                long now = System.currentTimeMillis();
                if (now - at < ttl) return last;
                try {
                    java.util.Map<String, Object> body = cms.get().uri("/api/consent-purposes?limit=100&depth=0").retrieve().body(java.util.Map.class);
                    var docs = body == null ? java.util.List.<java.util.Map<String, Object>>of() : (java.util.List<java.util.Map<String, Object>>) body.getOrDefault("docs", java.util.List.of());
                    if (!docs.isEmpty()) last = docs.stream().map(d -> new it.iren.loyalty.memberservice.domain.Consent.Purpose(String.valueOf(d.get("code")), String.valueOf(d.get("name")),
                            String.valueOf(d.getOrDefault("legalBasis", "consent")), d.get("validityMonths") instanceof Number n ? n.intValue() : null, Boolean.TRUE.equals(d.get("required")), String.valueOf(d.getOrDefault("version", "1")))).toList();
                } catch (Exception ignored) { /* backoffice non raggiungibile: si tiene l'ultimo catalogo */ }
                at = now;
                return last;
            }
        };
    }

    /** Schemi campi custom di esempio (RF-99) e identificatori (RF-108); in produzione dal CMS. */
    @Bean
    @ConditionalOnMissingBean
    it.iren.loyalty.memberservice.domain.CustomFieldSource customFieldSource() {
        var profilo = new it.iren.loyalty.memberservice.domain.CustomFieldSchema(it.iren.loyalty.memberservice.domain.CustomFieldSchema.Entity.MEMBER, "profilo", "Profilo", java.util.List.of(
                new it.iren.loyalty.memberservice.domain.CustomFieldSchema.Field("provincia", it.iren.loyalty.memberservice.domain.CustomFieldSchema.Type.SINGLE_SELECT, false, null, null, null, null, "province"),
                new it.iren.loyalty.memberservice.domain.CustomFieldSchema.Field("dataCompleanno", it.iren.loyalty.memberservice.domain.CustomFieldSchema.Type.DATE, false, null, null, null, null, null),
                new it.iren.loyalty.memberservice.domain.CustomFieldSchema.Field("interessi", it.iren.loyalty.memberservice.domain.CustomFieldSchema.Type.MULTI_SELECT, false, null, null, null, null, "interessi")), false, "customer_care");
        var contratti = new it.iren.loyalty.memberservice.domain.CustomFieldSchema(it.iren.loyalty.memberservice.domain.CustomFieldSchema.Entity.MEMBER, "contratti", "Contratti", java.util.List.of(
                new it.iren.loyalty.memberservice.domain.CustomFieldSchema.Field("tipo", it.iren.loyalty.memberservice.domain.CustomFieldSchema.Type.STRING, true, 16, "GAS|LUCE|ACQUA|TLR", null, null, null),
                new it.iren.loyalty.memberservice.domain.CustomFieldSchema.Field("scadenza", it.iren.loyalty.memberservice.domain.CustomFieldSchema.Type.DATE, false, null, null, null, null, null),
                new it.iren.loyalty.memberservice.domain.CustomFieldSchema.Field("consumoAnnuo", it.iren.loyalty.memberservice.domain.CustomFieldSchema.Type.NUMBER, false, null, null, 0d, 1_000_000d, null)), true, "platform_admin");
        return new it.iren.loyalty.memberservice.domain.CustomFieldSource() {
            @Override public java.util.List<it.iren.loyalty.memberservice.domain.CustomFieldSchema> schemasFor(it.iren.loyalty.memberservice.domain.CustomFieldSchema.Entity e) { return e == it.iren.loyalty.memberservice.domain.CustomFieldSchema.Entity.MEMBER ? java.util.List.of(profilo, contratti) : java.util.List.of(); }
            @Override public it.iren.loyalty.memberservice.domain.MemberIdentifiers identifiers() { return it.iren.loyalty.memberservice.domain.MemberIdentifiers.example(); }
        };
    }
}
