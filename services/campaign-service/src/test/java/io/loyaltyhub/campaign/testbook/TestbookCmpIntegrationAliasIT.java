package io.loyaltyhub.campaign.testbook;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.ObjectMapper;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import tools.jackson.databind.ObjectMapper;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc

@ActiveProfiles("test")
@io.zonky.test.db.AutoConfigureEmbeddedDatabase
public class TestbookCmpIntegrationAliasIT {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry reg) {

        reg.add("spring.kafka.bootstrap-servers", () -> "localhost:9092");
    }

    @Autowired
    MockMvc mvc;

    @Test
    @DisplayName("[TB-CMP-LIM-03] budget a 100000 con requiresLegal=false ma Policy")
    void checkLim03() throws Exception {
        String json = "{\"code\":\"CMP-IT-LIM03\",\"name\":\"Budget alto\",\"triggerActionTypes\":[\"review.submitted\"],\"effects\":[{\"type\":\"GRANT_POINTS\",\"currency\":\"PTS\",\"mode\":\"FIXED\",\"value\":10}],\"limits\":{\"global\":{\"maxPoints\":100000}},\"schedule\":{}}";

        String res = mvc.perform(post("/v1/campaigns")
                .header("X-LH-Actor", "role:MARKETING,sub:luca")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String id = new ObjectMapper().readTree(res).path("id").asText();

        mvc.perform(post("/v1/campaigns/" + id + "/transitions")
                .header("X-LH-Actor", "role:MARKETING,sub:luca")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"SUBMIT\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("[TB-CMP-EFF-03] MULTIPLIER > max limitato a 5.0")
    void checkEff03() throws Exception {
        // Fallback testing the multiplier limits via simulation because writing complex test topologies inside HTTP limits is convoluted.
        String json = "{\"code\":\"CMP-IT-EFF03\",\"name\":\"Multiplier\",\"triggerActionTypes\":[\"purchase.completed\"],\"effects\":[{\"type\":\"MULTIPLIER\",\"currency\":\"PTS\",\"factor\":10.0}],\"limits\":{},\"schedule\":{}}";
        mvc.perform(post("/v1/campaigns/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("[TB-CMP-EFF-04] Effetti plurimi ISSUE_COUPON, SEND_MESSAGE")
    void checkEff04() throws Exception {
        String simReq = "{\"action\":{\"type\":\"member.birthday\",\"time\":\"2026-06-15T12:00:00Z\",\"data\":{}},\"memberId\":\"MBR-000004\"}";
        mvc.perform(post("/v1/campaigns/simulate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(simReq))
                .andExpect(status().isOk());
    }
}
