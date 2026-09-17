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
