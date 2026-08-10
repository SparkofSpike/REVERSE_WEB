package com.test.engine.pvp;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end PVP flow over the HTTP API: room lobby, password gate, battle
 * start, per-side views (fog of war), two-sided decisions and records for
 * both humans.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PvpApiTest {

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
    void fullPvpBattleFlowPersistsRecordsForBothSides() throws Exception {
        String hostToken = registerAndToken("pvp_host");
        String guestToken = registerAndToken("pvp_guest");

        // host creates a locked room
        JsonNode room = postJson("/api/pvp/rooms", hostToken,
                Map.of("packId", "test-1", "password", "pw123",
                        "hostCharacterIds", List.of("warrior", "mage")));
        String roomId = room.get("id").asText();
        assertThat(room.get("locked").asBoolean()).isTrue();
        assertThat(room.get("guestUsername").isNull()).isTrue();

        // wrong password is rejected
        mockMvc.perform(post("/api/pvp/rooms/" + roomId + "/join")
                        .header("Authorization", "Bearer " + guestToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "password", "bad", "guestCharacterIds", List.of("priest")))))
                .andExpect(status().isBadRequest());

        // guest joins with the right password
        JsonNode joined = postJson("/api/pvp/rooms/" + roomId + "/join", guestToken,
                Map.of("password", "pw123", "guestCharacterIds", List.of("priest", "crab-dwarf")));
        assertThat(joined.get("guestUsername").asText()).isEqualTo("pvp_guest");

        // the lobby lists the room; the host starts the battle
        JsonNode lobby = getJson("/api/pvp/rooms", hostToken);
        assertThat(lobby).anyMatch(r -> r.get("id").asText().equals(roomId));
        JsonNode started = postJson("/api/pvp/rooms/" + roomId + "/start", hostToken, Map.of());
        String battleId = started.get("battleId").asText();
        assertThat(battleId).isNotBlank();

        // both players see the battle from their own side (fog of war)
        JsonNode hostView = getJson("/api/combat/" + battleId, hostToken);
        JsonNode guestView = getJson("/api/combat/" + battleId, guestToken);
        assertThat(hostView.get("mySide").asText()).isEqualTo("PLAYER");
        assertThat(guestView.get("mySide").asText()).isEqualTo("ENEMY");
        assertThat(hostView.get("guestUsername").asText()).isEqualTo("pvp_guest");
        assertThat(guestView.get("ownerUsername").asText()).isEqualTo("pvp_host");
        // each side only sees its own hand
        assertThat(hostView.get("playerHand").size()).isEqualTo(2);
        assertThat(guestView.get("playerHand").size()).isEqualTo(2);
        assertThat(hostView.get("phase").asText()).isEqualTo("INITIAL_PERK");

        // initial perk gates: each side picks its own
        String hostPerk = hostView.get("initialPerkOptions").get(0).get("id").asText();
        String guestPerk = guestView.get("initialPerkOptions").get(1).get("id").asText();
        JsonNode afterHostPerk = postJson("/api/combat/" + battleId + "/initial-perk", hostToken,
                Map.of("perkId", hostPerk));
        assertThat(afterHostPerk.get("phase").asText()).isEqualTo("INITIAL_PERK");
        assertThat(afterHostPerk.get("opponentSubmitted").asBoolean()).isFalse();
        JsonNode afterGuestPerk = postJson("/api/combat/" + battleId + "/initial-perk", guestToken,
                Map.of("perkId", guestPerk));
        assertThat(afterGuestPerk.get("phase").asText()).isEqualTo("DECISION");

        // decisions wait for both sides; the first submitter sees the wait
        List<Map<String, String>> hostDecisions = decisionsFor(hostView, "PLAYER");
        JsonNode afterHostDecide = postJson("/api/combat/" + battleId + "/decide", hostToken, hostDecisions);
        assertThat(afterHostDecide.get("phase").asText()).isEqualTo("DECISION");
        assertThat(afterHostDecide.get("mySubmitted").asBoolean()).isTrue();
        assertThat(afterHostDecide.get("opponentSubmitted").asBoolean()).isFalse();
        // no speed events yet (fog of war)
        assertThat(afterHostDecide.get("logs")).noneMatch(log -> "speed".equals(log.get("type").asText()));

        JsonNode guestFresh = getJson("/api/combat/" + battleId, guestToken);
        assertThat(guestFresh.get("opponentSubmitted").asBoolean()).isTrue();
        JsonNode afterGuestDecide = postJson("/api/combat/" + battleId + "/decide", guestToken,
                decisionsFor(guestFresh, "ENEMY"));
        // both submitted: the round resolved and round 2 started
        assertThat(afterGuestDecide.get("round").asInt()).isGreaterThan(1);

        // drive the battle to completion with both sides attacking
        int safety = 0;
        while (!"FINISHED".equals(afterGuestDecide.get("phase").asText()) && safety < 200) {
            safety++;
            String phase = afterGuestDecide.get("phase").asText();
            if ("SPECIAL_PERK".equals(phase)) {
                JsonNode options = afterGuestDecide.get("specialPerkOptions");
                if (!options.isEmpty()) {
                    postJson("/api/combat/" + battleId + "/special-perk", hostToken,
                            Map.of("perkId", options.get(0).get("id").asText()));
                } else {
                    postJson("/api/combat/" + battleId + "/skip-perk", hostToken, Map.of());
                }
                JsonNode opts2 = getJson("/api/combat/" + battleId, guestToken).get("specialPerkOptions");
                if (!opts2.isEmpty()) {
                    afterGuestDecide = postJson("/api/combat/" + battleId + "/special-perk", guestToken,
                            Map.of("perkId", opts2.get(0).get("id").asText()));
                } else {
                    afterGuestDecide = postJson("/api/combat/" + battleId + "/skip-perk", guestToken, Map.of());
                }
                continue;
            }
            if (!"DECISION".equals(phase)) {
                break;
            }
            if (afterGuestDecide.get("extraActionRound").asBoolean()) {
                // both sides skip their extra windows
                postJson("/api/combat/" + battleId + "/skip-extra", hostToken, Map.of());
                afterGuestDecide = postJson("/api/combat/" + battleId + "/skip-extra", guestToken, Map.of());
                continue;
            }
            JsonNode view = getJson("/api/combat/" + battleId, guestToken);
            postJson("/api/combat/" + battleId + "/decide", hostToken, decisionsFor(view, "PLAYER"));
            afterGuestDecide = postJson("/api/combat/" + battleId + "/decide", guestToken, decisionsFor(view, "ENEMY"));
        }

        assertThat(afterGuestDecide.get("phase").asText()).isEqualTo("FINISHED");
        String winner = afterGuestDecide.get("winner").asText();

        // both humans got their own record with their own perspective
        JsonNode hostRecords = getJson("/api/records", hostToken);
        JsonNode guestRecords = getJson("/api/records", guestToken);
        assertThat(hostRecords.size()).isGreaterThanOrEqualTo(1);
        assertThat(guestRecords.size()).isGreaterThanOrEqualTo(1);
        JsonNode hostRecord = hostRecords.get(0);
        JsonNode guestRecord = guestRecords.get(0);
        assertThat(hostRecord.get("mySide").asText()).isEqualTo("PLAYER");
        assertThat(guestRecord.get("mySide").asText()).isEqualTo("ENEMY");
        assertThat(hostRecord.get("opponentUsername").asText()).isEqualTo("pvp_guest");
        assertThat(guestRecord.get("opponentUsername").asText()).isEqualTo("pvp_host");
        assertThat(hostRecord.get("winner").asText()).isEqualTo(winner);
        assertThat(guestRecord.get("winner").asText()).isEqualTo(winner);
    }

    @Test
    void hostCanDeleteWaitingRoom() throws Exception {
        String hostToken = registerAndToken("pvp_host2");
        JsonNode room = postJson("/api/pvp/rooms", hostToken,
                Map.of("packId", "test-1", "password", "",
                        "hostCharacterIds", List.of("warrior")));
        String roomId = room.get("id").asText();

        mockMvc.perform(delete("/api/pvp/rooms/" + roomId)
                        .header("Authorization", "Bearer " + hostToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/pvp/rooms/" + roomId).header("Authorization", "Bearer " + hostToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void battleAccessIsRestrictedToTheTwoPlayers() throws Exception {
        String hostToken = registerAndToken("pvp_host3");
        String guestToken = registerAndToken("pvp_guest3");
        String intruderToken = registerAndToken("pvp_intruder3");

        JsonNode room = postJson("/api/pvp/rooms", hostToken,
                Map.of("packId", "test-1", "password", "",
                        "hostCharacterIds", List.of("warrior")));
        postJson("/api/pvp/rooms/" + room.get("id").asText() + "/join", guestToken,
                Map.of("password", "", "guestCharacterIds", List.of("mage")));
        String battleId = postJson("/api/pvp/rooms/" + room.get("id").asText() + "/start",
                hostToken, Map.of()).get("battleId").asText();

        mockMvc.perform(get("/api/combat/" + battleId).header("Authorization", "Bearer " + intruderToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("无权访问该战斗"));
    }

    @Test
    void surrenderEndsBattleAndPersistsBothRecords() throws Exception {
        String hostToken = registerAndToken("pvp_host_sur");
        String guestToken = registerAndToken("pvp_guest_sur");
        JsonNode room = postJson("/api/pvp/rooms", hostToken,
                Map.of("packId", "test-1", "password", "",
                        "hostCharacterIds", List.of("warrior")));
        postJson("/api/pvp/rooms/" + room.get("id").asText() + "/join", guestToken,
                Map.of("password", "", "guestCharacterIds", List.of("mage")));
        String battleId = postJson("/api/pvp/rooms/" + room.get("id").asText() + "/start",
                hostToken, Map.of()).get("battleId").asText();

        // the host surrenders: the guest wins immediately
        JsonNode surrendered = postJson("/api/combat/" + battleId + "/surrender", hostToken, Map.of());
        assertThat(surrendered.get("phase").asText()).isEqualTo("FINISHED");
        assertThat(surrendered.get("winner").asText()).isEqualTo("ENEMY");

        // the guest's view shows the win and the surrender log
        JsonNode guestView = getJson("/api/combat/" + battleId, guestToken);
        assertThat(guestView.get("phase").asText()).isEqualTo("FINISHED");
        assertThat(guestView.get("winner").asText()).isEqualTo("ENEMY");
        assertThat(guestView.get("logs")).anyMatch(log -> "surrender".equals(log.get("type").asText()));

        // both records persisted with the surrender outcome
        JsonNode hostRecords = getJson("/api/records", hostToken);
        JsonNode guestRecords = getJson("/api/records", guestToken);
        assertThat(hostRecords.size()).isGreaterThanOrEqualTo(1);
        assertThat(guestRecords.size()).isGreaterThanOrEqualTo(1);
        assertThat(hostRecords.get(0).get("winner").asText()).isEqualTo("ENEMY");
        assertThat(guestRecords.get(0).get("winner").asText()).isEqualTo("ENEMY");
    }

    private List<Map<String, String>> decisionsFor(JsonNode view, String side) throws Exception {
        List<Map<String, String>> decisions = new ArrayList<>();
        String foeSide = "PLAYER".equals(side) ? "ENEMY" : "PLAYER";
        JsonNode foe = null;
        for (JsonNode c : view.get("combatants")) {
            if (foeSide.equals(c.get("side").asText()) && !c.get("dead").asBoolean()) {
                foe = c;
                break;
            }
        }
        assertThat(foe).as("enemy side must have an alive target").isNotNull();
        String targetId = foe.get("id").asText();
        for (JsonNode c : view.get("combatants")) {
            if (side.equals(c.get("side").asText()) && !c.get("dead").asBoolean()) {
                decisions.add(Map.of(
                        "combatantId", c.get("id").asText(),
                        "actionType", "ATTACK",
                        "targetId", targetId));
            }
        }
        return decisions;
    }
}
