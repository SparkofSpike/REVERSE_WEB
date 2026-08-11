package com.test.engine.pve;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end PVE flow over the HTTP API: enemy templates, room lobby with the
 * ready gate (auto-start), per-player battle views (own hand only), drafts
 * feeding the timeout and records for every player.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PveApiTest {

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

    private JsonNode postJson(String url, String token, Object body) throws Exception {
        String response = mockMvc.perform(post(url)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private JsonNode getJson(String url, String token) throws Exception {
        String response = mockMvc.perform(get(url).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    @Test
    void enemiesEndpointListsTheBuiltInTemplates() throws Exception {
        String token = registerAndToken("pve_enemies");
        JsonNode enemies = getJson("/api/pve/enemies", token);
        assertThat(enemies).anyMatch(e -> e.get("id").asText().equals("scout"));
        assertThat(enemies).anyMatch(e -> e.get("id").asText().equals("guard"));
        assertThat(enemies).anyMatch(e -> e.get("id").asText().equals("warlord"));
        JsonNode scout = enemies.findValue("scout");
        assertThat(scout).isNull(); // list, not a map
        assertThat(enemies.get(0).get("name").asText()).isNotBlank();
    }

    @Test
    void fullPveFlowAutoStartsWhenEveryoneIsReady() throws Exception {
        String hostToken = registerAndToken("pve_host");
        String guestToken = registerAndToken("pve_guest");

        // host creates a locked PVE room with two enemies
        JsonNode room = postJson("/api/pve/rooms", hostToken,
                Map.of("packId", "test-1", "password", "pw123",
                        "enemyIds", List.of("scout", "guard")));
        String roomId = room.get("id").asText();
        assertThat(room.get("locked").asBoolean()).isTrue();
        assertThat(room.get("enemyIds").size()).isEqualTo(2);
        assertThat(room.get("seats").get(0).get("username").asText()).isEqualTo("pve_host");
        assertThat(room.get("seats").get(0).get("ready").asBoolean()).isFalse();

        // wrong password is rejected
        mockMvc.perform(post("/api/pve/rooms/" + roomId + "/join")
                        .header("Authorization", "Bearer " + guestToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("password", "bad"))))
                .andExpect(status().isBadRequest());

        // guest joins and both ready up; the second ready auto-starts the battle
        JsonNode joined = postJson("/api/pve/rooms/" + roomId + "/join", guestToken,
                Map.of("password", "pw123"));
        assertThat(joined.get("seats").size()).isEqualTo(2);

        JsonNode afterHostReady = postJson("/api/pve/rooms/" + roomId + "/ready", hostToken,
                Map.of("characterIds", List.of("warrior", "mage")));
        assertThat(afterHostReady.get("status").asText()).isEqualTo("WAITING");
        assertThat(afterHostReady.get("seats").get(0).get("ready").asBoolean()).isTrue();

        JsonNode started = postJson("/api/pve/rooms/" + roomId + "/ready", guestToken,
                Map.of("characterIds", List.of("priest")));
        assertThat(started.get("status").asText()).isEqualTo("PLAYING");
        String battleId = started.get("battleId").asText();
        assertThat(battleId).isNotBlank();

        // both players share the PLAYER side; each sees only their own hand
        JsonNode hostView = getJson("/api/combat/" + battleId, hostToken);
        JsonNode guestView = getJson("/api/combat/" + battleId, guestToken);
        assertThat(hostView.get("pve").asBoolean()).isTrue();
        assertThat(hostView.get("mySide").asText()).isEqualTo("PLAYER");
        assertThat(guestView.get("mySide").asText()).isEqualTo("PLAYER");
        assertThat(hostView.get("players").size()).isEqualTo(2);
        assertThat(hostView.get("playerHand").size()).isEqualTo(2);
        assertThat(guestView.get("playerHand").size()).isEqualTo(2);
        // host owns 2 combatants, guest owns 1 (ownerUsername is exposed)
        assertThat(hostView.get("combatants").findValues("ownerUsername")).anyMatch(
                n -> n.asText().equals("pve_host"));
        assertThat(hostView.get("combatants").findValues("ownerUsername")).anyMatch(
                n -> n.asText().equals("pve_guest"));

        // everyone picks an initial perk; both gates are per-player
        String perkId = hostView.get("initialPerkOptions").get(0).get("id").asText();
        postJson("/api/combat/" + battleId + "/initial-perk", hostToken, Map.of("perkId", perkId));
        JsonNode afterHostPerk = getJson("/api/combat/" + battleId, guestToken);
        assertThat(afterHostPerk.get("submittedUsers").size()).isEqualTo(1);
        assertThat(afterHostPerk.get("phase").asText()).isEqualTo("INITIAL_PERK");
        postJson("/api/combat/" + battleId + "/initial-perk", guestToken, Map.of("perkId", perkId));
        JsonNode round1 = getJson("/api/combat/" + battleId, hostToken);
        assertThat(round1.get("phase").asText()).isEqualTo("DECISION");

        // decisions resolve only after BOTH players submitted. Combatant ids
        // are read from the combatants array directly (findValues recurses
        // into skills/perks/cards and would also collect their ids)
        String[] targetId = new String[1];
        List<Map<String, String>> hostDecisions = new ArrayList<>();
        for (JsonNode unit : hostView.get("combatants")) {
            String id = unit.get("id").asText();
            if (id.startsWith("enemy-")) {
                targetId[0] = id;
            } else if (id.startsWith("warrior-") || id.startsWith("mage-")) {
                hostDecisions.add(Map.of("combatantId", id, "actionType", "ATTACK", "targetId", ""));
            }
        }
        hostDecisions = hostDecisions.stream()
                .map(m -> Map.of("combatantId", m.get("combatantId"), "actionType", "ATTACK",
                        "targetId", targetId[0]))
                .toList();
        postJson("/api/combat/" + battleId + "/decide", hostToken, hostDecisions);
        JsonNode afterHostDecide = getJson("/api/combat/" + battleId, guestToken);
        assertThat(afterHostDecide.get("submittedUsers").size()).isEqualTo(1);
        assertThat(afterHostDecide.get("phase").asText()).isEqualTo("DECISION");

        String guestUnit = null;
        for (JsonNode unit : guestView.get("combatants")) {
            if (unit.get("id").asText().startsWith("priest-")) {
                guestUnit = unit.get("id").asText();
            }
        }
        postJson("/api/combat/" + battleId + "/decide", guestToken,
                List.of(Map.of("combatantId", guestUnit, "actionType", "ATTACK", "targetId", targetId[0])));
        JsonNode afterBoth = getJson("/api/combat/" + battleId, hostToken);
        assertThat(afterBoth.get("round").asInt()).isGreaterThanOrEqualTo(2);

        // the draft endpoint stores selections for the timeout path
        JsonNode draftView = postJson("/api/combat/" + battleId + "/draft", hostToken, hostDecisions);
        assertThat(draftView.get("id").asText()).isEqualTo(battleId);
    }

    @Test
    void pveBattleRejectsOutsiders() throws Exception {
        String hostToken = registerAndToken("pve_out_host");
        String outsiderToken = registerAndToken("pve_out_snoop");

        JsonNode room = postJson("/api/pve/rooms", hostToken,
                Map.of("packId", "test-1", "enemyIds", List.of("scout")));
        String roomId = room.get("id").asText();
        postJson("/api/pve/rooms/" + roomId + "/ready", hostToken,
                Map.of("characterIds", List.of("warrior")));
        String battleId = roomsBattleId(roomId, hostToken);

        // a non-member cannot view the battle (BusinessException -> 400)
        mockMvc.perform(get("/api/combat/" + battleId)
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isBadRequest());
    }

    private String roomsBattleId(String roomId, String token) throws Exception {
        return getJson("/api/pve/rooms/" + roomId, token).get("battleId").asText();
    }
}
