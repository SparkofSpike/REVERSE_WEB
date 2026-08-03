package com.test.engine.combat;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end dummy battle over the HTTP API, including record persistence.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CombatApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String registerAndToken(String username) throws Exception {
        String response = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", username, "password", "secret123"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("token").asText();
    }

    @Test
    void dummyBattleFlowPersistsRecord() throws Exception {
        String token = registerAndToken("fighter");

        // create battle with two characters
        String createBody = objectMapper.writeValueAsString(Map.of(
                "packId", "test-1",
                "characterIds", List.of("warrior", "mage")));
        String battleJson = mockMvc.perform(post("/api/combat/dummy")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("INITIAL_PERK"))
                .andExpect(jsonPath("$.combatants.length()").value(3))
                .andReturn().getResponse().getContentAsString();
        JsonNode battle = objectMapper.readTree(battleJson);
        String battleId = battle.get("id").asText();

        // select initial perk
        String perkId = battle.get("initialPerkOptions").get(0).get("id").asText();
        battle = objectMapper.readTree(mockMvc.perform(post("/api/combat/" + battleId + "/initial-perk")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("perkId", perkId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("DECISION"))
                .andReturn().getResponse().getContentAsString());

        // loop until the battle finishes
        int safety = 0;
        while (!"FINISHED".equals(battle.get("phase").asText()) && safety < 150) {
            safety++;
            String phase = battle.get("phase").asText();
            if ("SPECIAL_PERK".equals(phase)) {
                JsonNode options = battle.get("specialPerkOptions");
                String body = options.isEmpty()
                        ? objectMapper.writeValueAsString(Map.of())
                        : objectMapper.writeValueAsString(
                        Map.of("perkId", options.get(0).get("id").asText()));
                String url = options.isEmpty()
                        ? "/api/combat/" + battleId + "/skip-perk"
                        : "/api/combat/" + battleId + "/special-perk";
                battle = objectMapper.readTree(mockMvc.perform(post(url)
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON).content(body))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());
                continue;
            }
            if (!"DECISION".equals(phase)) {
                break;
            }
            // build decisions for all alive player characters
            List<Map<String, String>> decisions = new java.util.ArrayList<>();
            for (JsonNode c : battle.get("combatants")) {
                if ("PLAYER".equals(c.get("side").asText()) && !c.get("dead").asBoolean()) {
                    decisions.add(Map.of(
                            "combatantId", c.get("id").asText(),
                            "actionType", "ATTACK",
                            "targetId", "dummy"));
                }
            }
            battle = objectMapper.readTree(mockMvc.perform(post("/api/combat/" + battleId + "/decide")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(decisions)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());
        }

        assertThat(battle.get("phase").asText()).isEqualTo("FINISHED");
        assertThat(battle.get("winner").asText()).isIn("PLAYER", "ENEMY");

        // record should be persisted
        String recordsJson = mockMvc.perform(get("/api/records")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode records = objectMapper.readTree(recordsJson);
        assertThat(records.size()).isGreaterThanOrEqualTo(1);
        JsonNode record = records.get(0);
        assertThat(record.get("winner").asText()).isEqualTo(battle.get("winner").asText());
        assertThat(record.get("totalDamageDealt").asInt()).isGreaterThan(0);

        // detail includes logs
        long recordId = record.get("id").asLong();
        mockMvc.perform(get("/api/records/" + recordId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logs").isArray());
    }

    @Test
    void battleOwnershipIsIsolated() throws Exception {
        String tokenA = registerAndToken("fighterA");
        String tokenB = registerAndToken("fighterB");

        String createBody = objectMapper.writeValueAsString(Map.of(
                "packId", "test-1",
                "characterIds", List.of("warrior")));
        String battleJson = mockMvc.perform(post("/api/combat/dummy")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String battleId = objectMapper.readTree(battleJson).get("id").asText();

        mockMvc.perform(get("/api/combat/" + battleId).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("无权访问该战斗"));
    }
}
