package io.loyaltyhub.memberservice.domain;

import java.util.List;

/** Porta verso il backoffice: schemi dei campi custom (RF-99) e configurazione identificatori (RF-108). */
public interface CustomFieldSource {
    List<CustomFieldSchema> schemasFor(CustomFieldSchema.Entity entity);
    MemberIdentifiers identifiers();
}
