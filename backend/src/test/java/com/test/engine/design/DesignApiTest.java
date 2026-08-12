package com.test.engine.design;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.test.engine.model.CardPackLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ADMIN/OP content design API: card pack, enemy and character CRUD persisted
 * under a test-scoped data dir. File writes are physical (not transactional),
 * so every test starts from a clean dir and reloads the loader.
 */
@SpringBootTest(properties = {"app.admin-ids=1000000", "app.cards-dir=./target/test-cards"})
@AutoConfigureMockMvc
@Transactional
class DesignApiTest {

    private static final long OP_ID = 1000000L;
    private static final Path DATA_DIR = Path.of("./target/test-cards");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private CardPackLoader cardPackLoader;

    @BeforeEach
    void cleanDataDir() throws Exception {
        deleteRecursively(DATA_DIR);
        cardPackLoader.reload();
    }

    @AfterEach
    void tearDown() throws Exception {
        deleteRecursively(DATA_DIR);
        cardPackLoader.reload();
    }

    private void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    private String loginAs(String username, String role) throws Exception {
        jdbcTemplate.update(
                "INSERT INTO users (username, password_hash, role, enabled, created_at) "
                        + "VALUES (?, ?, ?, true, ?)",
                username, passwordEncoder.encode("secret123"), role, Instant.now());
        return login(username);
    }

    private String loginAsOp(String username) throws Exception {
        jdbcTemplate.update(
                "INSERT INTO users (id, username, password_hash, role, enabled, created_at) "
                        + "VALUES (?, ?, ?, 'USER', true, ?)",
                OP_ID, username, passwordEncoder.encode("secret123"), Instant.now());
        return login(username);
    }

    private String login(String username) throws Exception {
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", username, "password", "secret123"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("token").asText();
    }

    @Test
    void adminCanCreateAndDeletePack() throws Exception {
        String token = loginAs("designer1", "ADMIN");
        mockMvc.perform(post("/api/design/packs").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"t-pack\",\"name\":\"T Pack\",\"characters\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("t-pack"));

        mockMvc.perform(get("/api/packs").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 't-pack')].name").value("T Pack"));

        mockMvc.perform(delete("/api/design/packs/t-pack").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/packs").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 't-pack')]").isEmpty());
    }

    @Test
    void opCanDesignEvenWithoutAdminRole() throws Exception {
        String token = loginAsOp("op-designer");
        mockMvc.perform(post("/api/design/packs").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"op-pack\",\"name\":\"OP Pack\",\"characters\":[]}"))
                .andExpect(status().isOk());
    }

    @Test
    void normalUserIsForbidden() throws Exception {
        String token = loginAs("plain-user", "USER");
        mockMvc.perform(get("/api/design/packs").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidIdIsRejected() throws Exception {
        String token = loginAs("hacker1", "ADMIN");
        mockMvc.perform(post("/api/design/packs").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"../../evil\",\"name\":\"Evil\",\"characters\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void builtinPackCannotBeDeleted() throws Exception {
        String token = loginAs("designer2", "ADMIN");
        mockMvc.perform(delete("/api/design/packs/test-1").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("内置内容不可删除"));
    }

    @Test
    void updateBuiltinCreatesOverride() throws Exception {
        String token = loginAs("designer3", "ADMIN");
        String raw = mockMvc.perform(get("/api/design/packs/test-1").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode pack = objectMapper.readTree(raw);
        ((com.fasterxml.jackson.databind.node.ObjectNode) pack).put("name", "TEST.1 Modified");

        mockMvc.perform(put("/api/design/packs/test-1").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pack.toString()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/packs").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 'test-1')].name").value("TEST.1 Modified"));
    }

    @Test
    void characterCrudInsidePack() throws Exception {
        String token = loginAs("designer4", "ADMIN");
        String character = "{\"id\":\"t-hero\",\"name\":\"Test Hero\",\"maxHp\":50,\"maxEnergy\":50,"
                + "\"speedDice\":\"1d5\",\"physicalResistance\":1.0,\"magicResistance\":1.0,"
                + "\"baseDamageDice\":\"1d5\",\"baseDamageType\":\"PHYSICAL\",\"blockDice\":\"1d5\","
                + "\"dodgePenalty\":\"0d3\",\"baseActions\":[\"ATTACK\"],\"skills\":[]}";

        mockMvc.perform(get("/api/design/packs/test-1/characters").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5));

        mockMvc.perform(post("/api/design/packs/test-1/characters").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(character))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.characters.length()").value(6));

        // duplicate id is rejected
        mockMvc.perform(post("/api/design/packs/test-1/characters").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(character))
                .andExpect(status().isBadRequest());

        // update renames the character
        JsonNode node = objectMapper.readTree(character);
        ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("name", "Test Hero II");
        mockMvc.perform(put("/api/design/packs/test-1/characters/t-hero").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(node.toString()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/design/packs/test-1/characters").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 't-hero')].name").value("Test Hero II"));

        // delete removes it again
        mockMvc.perform(delete("/api/design/packs/test-1/characters/t-hero").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/design/packs/test-1/characters").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5));
    }

    @Test
    void enemyCrud() throws Exception {
        String token = loginAs("designer5", "ADMIN");
        String enemy = "{\"id\":\"t-enemy\",\"name\":\"Test Enemy\",\"maxHp\":120,\"maxEnergy\":60,"
                + "\"speedDice\":\"1d6\",\"baseDamageDice\":\"1d6\",\"baseDamageType\":\"PHYSICAL\","
                + "\"physicalResistance\":1.0,\"magicResistance\":1.0,\"blockDice\":\"1d6\","
                + "\"dodgePenalty\":\"0d3\",\"baseActions\":[\"ATTACK\",\"DEFEND\"]}";

        mockMvc.perform(post("/api/design/enemies").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(enemy))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("t-enemy"));

        mockMvc.perform(get("/api/design/enemies").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 't-enemy')].name").value("Test Enemy"));

        mockMvc.perform(delete("/api/design/enemies/t-enemy").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/design/enemies").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 't-enemy')]").isEmpty());
    }

    @Test
    void malformedJsonIsRejected() throws Exception {
        String token = loginAs("designer6", "ADMIN");
        mockMvc.perform(post("/api/design/packs").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not-json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void packWithoutCharactersIsRejected() throws Exception {
        String token = loginAs("designer7", "ADMIN");
        mockMvc.perform(post("/api/design/packs").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"no-char\",\"name\":\"No Char\"}"))
                .andExpect(status().isBadRequest());
    }
}
