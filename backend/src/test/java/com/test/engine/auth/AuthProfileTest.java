package com.test.engine.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import jakarta.persistence.EntityManager;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Self-service profile: nickname updates, password changes and
 * disabled-account behaviour.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthProfileTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    private String registerAndLogin(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", username, "password", "secret123"))))
                .andExpect(status().isOk());
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", username, "password", "secret123"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("token").asText();
    }

    @Test
    void registerReturnsRole() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", "rolecheck", "password", "secret123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    void updateProfileChangesNickname() throws Exception {
        String token = registerAndLogin("nickuser");
        mockMvc.perform(put("/api/auth/profile").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("nickname", "Ace"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("Ace"));
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("Ace"))
                .andExpect(jsonPath("$.role").value("USER"));

        // blank nickname clears it back to null
        mockMvc.perform(put("/api/auth/profile").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("nickname", "  "))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value(nullValue()));
    }

    @Test
    void changePasswordRejectsWrongOldPassword() throws Exception {
        String token = registerAndLogin("pwduser1");
        mockMvc.perform(put("/api/auth/password").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("oldPassword", "wrong-old", "newPassword", "newpass123"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("原密码错误"));
    }

    @Test
    void changePasswordThenOldLoginFails() throws Exception {
        String token = registerAndLogin("pwduser2");
        mockMvc.perform(put("/api/auth/password").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("oldPassword", "secret123", "newPassword", "newpass123"))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", "pwduser2", "password", "secret123"))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", "pwduser2", "password", "newpass123"))))
                .andExpect(status().isOk());
    }

    @Test
    void disabledAccountLosesLoginAndTokenValidity() throws Exception {
        String token = registerAndLogin("banuser");
        // drop cached entities so the raw UPDATE is visible to later queries
        entityManager.clear();
        jdbcTemplate.update("UPDATE users SET enabled = false WHERE username = 'banuser'");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", "banuser", "password", "secret123"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("账号已被禁用"));
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }
}
