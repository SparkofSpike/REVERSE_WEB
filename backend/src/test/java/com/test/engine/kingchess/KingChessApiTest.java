package com.test.engine.kingchess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract-level test for the King's Chess API (contract §3 + §4): the six
 * endpoints answer under {@code /api/kingchess} behind JWT, illegal states come
 * back as 400 {@link com.test.engine.exception.BusinessException}s, and the view
 * JSON uses the frozen field names verbatim.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class KingChessApiTest {

    private static final List<String> SIDES = List.of("NORTH", "EAST", "SOUTH", "WEST");

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

    private static Set<String> keysOf(JsonNode node) {
        Set<String> keys = new HashSet<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    private static List<Map<String, Object>> deployment(String side, int cell, String kind) {
        return List.of(Map.of("side", side, "cellIndex", cell, "pieceKind", kind));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/kingchess/games")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"playerCount\":2}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/kingchess/games/whatever"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createGameReturnsTheFrozenViewShape() throws Exception {
        String token = registerAndToken("kc_api_shape");

        JsonNode view = postJson("/api/kingchess/games", token, Map.of("playerCount", 2));

        assertThat(keysOf(view)).containsExactlyInAnyOrder("gameId", "phase", "roundNo", "finished",
                "winnerSeat", "scoreToWin", "players", "fields", "court", "submittedSeats",
                "submittedEffectSeats", "lastRolls", "lastDropOrder", "events", "eliminatedSeats");
        assertThat(view.get("phase").asText()).isEqualTo("PLACING");
        assertThat(view.get("roundNo").asInt()).isEqualTo(1);
        assertThat(view.get("finished").asBoolean()).isFalse();
        assertThat(view.get("winnerSeat").isNull()).isTrue();
        assertThat(view.get("scoreToWin").asInt()).isEqualTo(50);
        assertThat(view.get("submittedSeats").size()).isZero();
        assertThat(view.get("submittedEffectSeats").size()).isZero();
        assertThat(view.get("lastRolls").size()).isZero();
        assertThat(view.get("lastDropOrder").size()).isZero();
        assertThat(view.get("eliminatedSeats").size()).isZero();
        assertThat(view.get("events").size()).isPositive();

        assertThat(view.get("players").size()).isEqualTo(2);
        JsonNode player = view.get("players").get(0);
        assertThat(keysOf(player)).containsExactlyInAnyOrder("seat", "side", "score", "kingLives",
                "eliminated", "hand");
        assertThat(player.get("side").asText()).isEqualTo("NORTH");
        assertThat(player.get("kingLives").asInt()).isEqualTo(5);
        assertThat(player.get("eliminated").asBoolean()).isFalse();
        assertThat(player.get("score").asInt()).isZero();
        assertThat(player.get("hand").size()).isEqualTo(4);
        assertThat(player.get("hand").get(0).asText()).isEqualTo("KING");

        assertThat(view.get("fields").size()).isEqualTo(4);
        JsonNode firstPiece = null;
        for (String side : SIDES) {
            JsonNode cells = view.get("fields").get(side);
            assertThat(cells.size()).isEqualTo(4);
            for (JsonNode cell : cells) {
                if (firstPiece == null && !cell.isNull()) {
                    firstPiece = cell;
                }
            }
        }
        assertThat(view.get("court").size()).isEqualTo(5);
        assertThat(view.get("court").get("PROVISION").asInt()).isBetween(0, 8);
        assertThat(view.get("court").get("SOLDIER").asInt()).isBetween(0, 6);
        assertThat(view.get("court").get("HORSE").asInt()).isBetween(0, 4);
        assertThat(view.get("court").get("CHARIOT").asInt()).isBetween(0, 4);
        assertThat(view.get("court").get("KNIGHT").asInt()).isBetween(0, 2);

        // contract §2.5-1: at least one public piece was refreshed onto the board
        assertThat(firstPiece).isNotNull();
        assertThat(keysOf(firstPiece)).containsExactlyInAnyOrder("id", "kind", "ownerSeat",
                "dropOrdinal");
        assertThat(firstPiece.get("ownerSeat").isNull()).isTrue();
        assertThat(firstPiece.get("id").asLong()).isPositive();
        assertThat(firstPiece.get("dropOrdinal").asLong()).isPositive();
    }

    @Test
    void sixEndpointFlowRunsOverHttp() throws Exception {
        String token = registerAndToken("kc_api_flow");
        String gameId = postJson("/api/kingchess/games", token, Map.of("playerCount", 2))
                .get("gameId").asText();

        // 3.3 deployments (covering) for both seats
        JsonNode afterFirstSeat = postJson("/api/kingchess/games/" + gameId + "/deployments", token,
                Map.of("seat", 0, "deployments", deployment("NORTH", 0, "KING")));
        assertThat(afterFirstSeat.get("submittedSeats").get(0).asInt()).isZero();
        JsonNode afterBoth = postJson("/api/kingchess/games/" + gameId + "/deployments", token,
                Map.of("seat", 1, "deployments", List.of()));
        assertThat(afterBoth.get("submittedSeats").size()).isEqualTo(2);

        // 3.4 resolve (d20 + drops)
        JsonNode resolved = postJson("/api/kingchess/games/" + gameId + "/resolve", token, Map.of());
        assertThat(resolved.get("phase").asText()).isEqualTo("ROUND_END");
        assertThat(resolved.get("lastRolls").size()).isEqualTo(2);
        assertThat(resolved.get("lastDropOrder").size()).isEqualTo(2);
        assertThat(resolved.get("submittedSeats").size()).isZero();

        // 3.2 read back the authoritative state
        JsonNode fetched = getJson("/api/kingchess/games/" + gameId, token);
        assertThat(fetched.get("phase").asText()).isEqualTo("ROUND_END");
        assertThat(fetched.get("roundNo").asInt()).isEqualTo(1);

        // 3.6 next round
        JsonNode nextRound = postJson("/api/kingchess/games/" + gameId + "/next-round", token, Map.of());
        assertThat(nextRound.get("phase").asText()).isEqualTo("PLACING");
        assertThat(nextRound.get("roundNo").asInt()).isEqualTo(2);
        assertThat(nextRound.get("submittedSeats").size()).isZero();
    }

    @Test
    void illegalStatesReturnFourHundredWithAChineseMessage() throws Exception {
        String token = registerAndToken("kc_api_errors");

        // 3.4 before every seat submitted
        String gameId = postJson("/api/kingchess/games", token, Map.of("playerCount", 2))
                .get("gameId").asText();
        mockMvc.perform(post("/api/kingchess/games/" + gameId + "/resolve")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("座位 0 尚未提交部署"));

        // 3.3 with a piece the seat does not hold
        mockMvc.perform(post("/api/kingchess/games/" + gameId + "/deployments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("seat", 0, "deployments", deployment("NORTH", 0, "SOLDIER")))))
                .andExpect(status().isBadRequest());

        // 3.3 with a cell index outside 0..3
        mockMvc.perform(post("/api/kingchess/games/" + gameId + "/deployments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("seat", 0, "deployments", deployment("NORTH", 9, "KING")))))
                .andExpect(status().isBadRequest());

        // 3.5 while the phase is not EFFECTS
        mockMvc.perform(post("/api/kingchess/games/" + gameId + "/effects")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("seat", 0, "actions", List.of()))))
                .andExpect(status().isBadRequest());

        // 3.6 while the phase is not ROUND_END
        mockMvc.perform(post("/api/kingchess/games/" + gameId + "/next-round")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        // unknown game
        mockMvc.perform(get("/api/kingchess/games/missing-game")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("对局不存在"));

        // player count outside 2..4
        mockMvc.perform(post("/api/kingchess/games")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"playerCount\":5}"))
                .andExpect(status().isBadRequest());
    }
}
