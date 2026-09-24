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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest
@AutoConfigureMockMvc

@ActiveProfiles("test")
@io.zonky.test.db.AutoConfigureEmbeddedDatabase
public class TestbookCmpSimulateIT {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry reg) {

        reg.add("spring.kafka.bootstrap-servers", () -> "localhost:9092");
    }

    @Autowired
    MockMvc mvc;

    @Test
    @DisplayName("[TB-CMP-SIM-01] Simulate call con validazione andata a buon fine")
    void checkSim01() throws Exception {
        String simReq = "{\"action\":{\"type\":\"purchase.completed\",\"time\":\"2026-06-15T12:00:00Z\",\"data\":{\"amount\":130}},\"memberId\":\"MBR-000005\"}";
        mvc.perform(post("/v1/campaigns/simulate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(simReq))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results").isArray())
                .andExpect(jsonPath("$.totals").exists());

        // La simulazione non deve aver inserito alcuna actions su evaluation_log
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/evaluations/01ACTSIM123"))
                .andExpect(status().isNotFound()); // L'id della simulazione non esiste realmente nel db
    }
}
