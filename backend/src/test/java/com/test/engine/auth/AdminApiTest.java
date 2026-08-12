package com.test.engine.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OP-only account administration: user list, role grants, enable/disable.
 * OP ids are injected via properties; matching rows are inserted with explicit
 * ids through JdbcTemplate (high ids keep the IDENTITY sequence untouched).
 */
@SpringBootTest(properties = "app.admin-ids=1000000,1000001")
@AutoConfigureMockMvc
@Transactional
class AdminApiTest {

    private static final long OP_ID = 1000000L;
    private static final long OP2_ID = 1000001L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private void insertUser(long id, String username) {
        jdbcTemplate.update(
                "INSERT INTO users (id, username, password_hash, role, enabled, created_at) "
                        + "VALUES (?, ?, ?, 'USER', true, ?)",
                id, username, passwordEncoder.encode("secret123"), Instant.now());
    }

    private void insertUser(String username, String role) {
        jdbcTemplate.update(
                "INSERT INTO users (username, password_hash, role, enabled, created_at) "
                        + "VALUES (?, ?, ?, true, ?)",
                username, passwordEncoder.encode("secret123"), role, Instant.now());
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
    void opCanListUsersWithEffectiveRoles() throws Exception {
        insertUser(OP_ID, "op-one");
        insertUser(OP2_ID, "op-two");
        insertUser("normal-user", "USER");

        String token = login("op-one");
        mockMvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.username == 'normal-user')].role").value("USER"))
                .andExpect(jsonPath("$[?(@.username == 'op-one')].role").value("OP"));
    }

    @Test
    void normalUserIsForbidden() throws Exception {
        insertUser(OP_ID, "op-one");
        insertUser("plain", "USER");
        String token = login("plain");
        mockMvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminRoleIsNotEnoughForAdminArea() throws Exception {
        insertUser(OP_ID, "op-one");
        insertUser("admin-guy", "ADMIN");
        String token = login("admin-guy");
        mockMvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void opCanGrantAdminRole() throws Exception {
        insertUser(OP_ID, "op-one");
        insertUser("promotee", "USER");
        Long targetId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE username = 'promotee'", Long.class);

        String token = login("op-one");
        mockMvc.perform(patch("/api/admin/users/" + targetId + "/role")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));

        // promoted user sees the new role in /me
        String promotedToken = login("promotee");
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + promotedToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void opCannotTouchOwnAccount() throws Exception {
        insertUser(OP_ID, "op-one");
        String token = login("op-one");
        mockMvc.perform(patch("/api/admin/users/" + OP_ID + "/enabled")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("不能操作自己的账号"));
    }

    @Test
    void opCannotModifyAnotherOpAccount() throws Exception {
        insertUser(OP_ID, "op-one");
        insertUser(OP2_ID, "op-two");
        String token = login("op-one");
        mockMvc.perform(patch("/api/admin/users/" + OP2_ID + "/role")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"USER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("不能修改超级管理员"));
    }

    @Test
    void disableUserKillsLoginAndOldToken() throws Exception {
        insertUser(OP_ID, "op-one");
        insertUser("victim", "USER");
        Long victimId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE username = 'victim'", Long.class);
        String victimToken = login("victim");

        String token = login("op-one");
        mockMvc.perform(patch("/api/admin/users/" + victimId + "/enabled")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", "victim", "password", "secret123"))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + victimToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidRoleValueRejected() throws Exception {
        insertUser(OP_ID, "op-one");
        insertUser("someone", "USER");
        Long targetId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE username = 'someone'", Long.class);
        String token = login("op-one");
        mockMvc.perform(patch("/api/admin/users/" + targetId + "/role")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"OP\"}"))
                .andExpect(status().isBadRequest());
    }
}
