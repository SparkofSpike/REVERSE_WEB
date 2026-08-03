package com.test.engine.build;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Build (deck) CRUD with JWT authentication and ownership isolation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BuildIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String register(String username) throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("username", username, "password", "secret123"));
        String response = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("token").asText();
    }

    private String buildBody() throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "name", "我的构筑",
                "packId", "test-1",
                "characterIds", List.of("warrior", "mage"),
                "initialPerkId", "perk-faithful-heart"));
    }

    @Test
    void createListAndGetBuild() throws Exception {
        String token = register("builder1");
        String body = buildBody();

        mockMvc.perform(post("/api/builds").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("我的构筑"))
                .andExpect(jsonPath("$.packId").value("test-1"))
                .andExpect(jsonPath("$.characterIds[0]").value("warrior"));

        mockMvc.perform(get("/api/builds").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void updateAndDeleteBuild() throws Exception {
        String token = register("builder2");
        String created = mockMvc.perform(post("/api/builds").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(buildBody()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(created).get("id").asLong();

        String updateBody = objectMapper.writeValueAsString(Map.of(
                "name", "改过的构筑",
                "packId", "test-1",
                "characterIds", List.of("crab-dwarf")));
        mockMvc.perform(put("/api/builds/" + id).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("改过的构筑"))
                .andExpect(jsonPath("$.characterIds[0]").value("crab-dwarf"));

        mockMvc.perform(delete("/api/builds/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/builds/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ownershipIsIsolated() throws Exception {
        String tokenA = register("ownerA");
        String tokenB = register("ownerB");
        String created = mockMvc.perform(post("/api/builds").header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON).content(buildBody()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(created).get("id").asLong();

        mockMvc.perform(get("/api/builds/" + id).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("无权访问该构筑"));
    }

    @Test
    void rejectsUnknownCharacter() throws Exception {
        String token = register("builder3");
        String badBody = objectMapper.writeValueAsString(Map.of(
                "name", "坏构筑",
                "packId", "test-1",
                "characterIds", List.of("unknown-char")));
        mockMvc.perform(post("/api/builds").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(badBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("角色 unknown-char 不属于卡包 test-1"));
    }

    @Test
    void requiresAuth() throws Exception {
        mockMvc.perform(get("/api/builds"))
                .andExpect(status().isUnauthorized());
    }
}
