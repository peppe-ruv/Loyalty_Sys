package io.loyaltyhub.common.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiConventionsTest {

    @Test
    void areaIsTheControllerNameInKebabCase() {
        assertThat(OpenApiConventions.area("PortalWalletController")).isEqualTo("portal-wallet");
        assertThat(OpenApiConventions.area("DlqController")).isEqualTo("dlq");
        assertThat(OpenApiConventions.area("HubInfo")).isEqualTo("hub-info");
    }

    @Test
    void summaryIsTheReadableMethodName() {
        assertThat(OpenApiConventions.summary("listCampaigns", "campaigns")).isEqualTo("List campaigns");
        assertThat(OpenApiConventions.summary("getDLQEntry", "dlq")).isEqualTo("Get DLQ entry");
    }

    @Test
    void singleWordSummaryNamesTheArea() {
        assertThat(OpenApiConventions.summary("get", "portal-wallets")).isEqualTo("Get — portal wallets");
    }
}
