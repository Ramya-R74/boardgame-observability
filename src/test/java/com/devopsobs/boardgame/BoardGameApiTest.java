package com.devopsobs.boardgame;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class BoardGameApiTest {

    @Autowired MockMvc mvc;

    @Test
    void listsSeededCatalogue() throws Exception {
        mvc.perform(get("/api/boardgames"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThan(0)));
    }

    @Test
    void returnsCorrelationHeader() throws Exception {
        mvc.perform(get("/api/boardgames"))
           .andExpect(header().exists("X-Request-Id"));
    }

    @Test
    void unknownGameIs404() throws Exception {
        mvc.perform(get("/api/boardgames/999999"))
           .andExpect(status().isNotFound());
    }

    @Test
    void rejectsInvalidReview() throws Exception {
        mvc.perform(post("/api/boardgames/1/reviews")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rating\":9,\"comment\":\"\"}"))
           .andExpect(status().isBadRequest());
    }

    @Test
    void actuatorHealthIsUp() throws Exception {
        mvc.perform(get("/actuator/health"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.status").value("UP"));
    }
}
