package io.loyaltyhub.common.approval;

import java.util.List;

/**
 * Fonte della coda approvazioni di un servizio proprietario di oggetti governati (docs/06 §7). Il controller comune
 * {@link ApprovalsController} espone {@code GET /v1/approvals} con le fonti presenti nel processo: una sola in un
 * servizio a sé, tutte nell'hub consolidato (dove i servizi condividono il server web).
 */
public interface ApprovalSource {

    /** In revisione ({@code submittedBy} nullo) oppure inviati da {@code submittedBy}, in qualunque stato. */
    List<ApprovalItem> approvals(String submittedBy);
}
