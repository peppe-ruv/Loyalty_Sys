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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import tools.jackson.databind.ObjectMapper;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc

@ActiveProfiles("test")
@io.zonky.test.db.AutoConfigureEmbeddedDatabase
public class TestbookCmpLifecycleIT {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry reg) {

        reg.add("spring.kafka.bootstrap-servers", () -> "localhost:9092");
    }

    @Autowired
    MockMvc mvc;

    @Test
    @DisplayName("[TB-CMP-LC-01] Modifica su DRAFT")
    void checkLc01() throws Exception {
        String json = "{\"code\":\"CMP-LC-01\",\"name\":\"Draft\",\"triggerActionTypes\":[\"review.submitted\"],\"effects\":[{\"type\":\"GRANT_POINTS\",\"currency\":\"PTS\",\"mode\":\"FIXED\",\"value\":10}],\"limits\":{},\"schedule\":{}}";
        String res = mvc.perform(post("/v1/campaigns")
                .header("X-LH-Actor", "role:MARKETING,sub:luca")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String id = new ObjectMapper().readTree(res).path("id").asText();

        String updated = "{\"code\":\"CMP-LC-01\",\"name\":\"Draft Updated\",\"triggerActionTypes\":[\"survey.completed\"],\"effects\":[{\"type\":\"GRANT_POINTS\",\"currency\":\"PTS\",\"mode\":\"FIXED\",\"value\":20}],\"limits\":{},\"schedule\":{},\"version\":0}";
        mvc.perform(put("/v1/campaigns/" + id)
                .header("X-LH-Actor", "role:MARKETING,sub:luca")
                .contentType(MediaType.APPLICATION_JSON)
                .content(updated))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("[TB-CMP-LC-02] Modifica su LIVE a campi non sicuri")
    void checkLc02() throws Exception {
        String json = "{\"code\":\"CMP-LC-02\",\"name\":\"Live\",\"triggerActionTypes\":[\"review.submitted\"],\"effects\":[{\"type\":\"GRANT_POINTS\",\"currency\":\"PTS\",\"mode\":\"FIXED\",\"value\":10}],\"limits\":{},\"schedule\":{}}";
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
                .content("{\"action\":\"PUBLISH\"}"))
                .andExpect(status().isOk());

        String updated = "{\"code\":\"CMP-LC-02\",\"name\":\"Live Updated\",\"triggerActionTypes\":[\"survey.completed\"],\"effects\":[{\"type\":\"GRANT_POINTS\",\"currency\":\"PTS\",\"mode\":\"FIXED\",\"value\":20}],\"limits\":{},\"schedule\":{},\"version\":1}";
        mvc.perform(put("/v1/campaigns/" + id)
                .header("X-LH-Actor", "role:MARKETING,sub:luca")
                .contentType(MediaType.APPLICATION_JSON)
                .content(updated))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("[TB-CMP-LC-03] Conflitto di versione su DRAFT")
    void checkLc03() throws Exception {
        String json = "{\"code\":\"CMP-LC-03\",\"name\":\"Version Conf\",\"triggerActionTypes\":[\"review.submitted\"],\"effects\":[{\"type\":\"GRANT_POINTS\",\"currency\":\"PTS\",\"mode\":\"FIXED\",\"value\":10}],\"limits\":{},\"schedule\":{}}";
        String res = mvc.perform(post("/v1/campaigns")
                .header("X-LH-Actor", "role:MARKETING,sub:luca")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String id = new ObjectMapper().readTree(res).path("id").asText();

        String updated = "{\"code\":\"CMP-LC-03\",\"name\":\"Draft Updated\",\"triggerActionTypes\":[\"survey.completed\"],\"effects\":[{\"type\":\"GRANT_POINTS\",\"currency\":\"PTS\",\"mode\":\"FIXED\",\"value\":20}],\"limits\":{},\"schedule\":{},\"version\":5}";
        mvc.perform(put("/v1/campaigns/" + id)
                .header("X-LH-Actor", "role:MARKETING,sub:luca")
                .contentType(MediaType.APPLICATION_JSON)
                .content(updated))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("[TB-CMP-LC-04] Duplicazione campagna")
    void checkLc04() throws Exception {
        String json = "{\"code\":\"CMP-LC-04\",\"name\":\"To Dup\",\"triggerActionTypes\":[\"review.submitted\"],\"effects\":[{\"type\":\"GRANT_POINTS\",\"currency\":\"PTS\",\"mode\":\"FIXED\",\"value\":10}],\"limits\":{},\"schedule\":{}}";
        String res = mvc.perform(post("/v1/campaigns")
                .header("X-LH-Actor", "role:MARKETING,sub:luca")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String id = new ObjectMapper().readTree(res).path("id").asText();

        mvc.perform(post("/v1/campaigns/" + id + "/duplicate")
                .header("X-LH-Actor", "role:MARKETING,sub:luca")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated());
    }
}
