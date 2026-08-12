package com.test.engine.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Avatar upload: storage, validation and public serving.
 * Files land in a test-scoped dir and are cleaned between tests.
 */
@SpringBootTest(properties = "app.avatar-dir=./target/test-avatars")
@AutoConfigureMockMvc
@Transactional
class AuthAvatarTest {

    private static final Path AVATAR_DIR = Path.of("./target/test-avatars");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    @AfterEach
    void cleanAvatarDir() throws Exception {
        if (Files.exists(AVATAR_DIR)) {
            try (var stream = Files.walk(AVATAR_DIR)) {
                stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }
    }

    private String registerAndLogin() throws Exception {
        String username = "avatar" + System.nanoTime();
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

    private String upload(String token, MockMultipartFile file) throws Exception {
        String response = mockMvc.perform(multipart("/api/auth/avatar")
                        .file(file)
                        .header("Authorization", "Bearer " + token)
                        .with(request -> {
                            request.setMethod("PUT");
                            return request;
                        }))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("avatarUrl").asText();
    }

    @Test
    void uploadAndServeAvatar() throws Exception {
        String token = registerAndLogin();
        String avatarUrl = upload(token, new MockMultipartFile(
                "file", "a.png", MediaType.IMAGE_PNG_VALUE, new byte[]{1, 2, 3}));

        org.assertj.core.api.Assertions.assertThat(avatarUrl).startsWith("/api/avatars/");
        mockMvc.perform(get(avatarUrl))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG));
    }

    @Test
    void meIncludesAvatarUrl() throws Exception {
        String token = registerAndLogin();
        String avatarUrl = upload(token, new MockMultipartFile(
                "file", "a.png", MediaType.IMAGE_PNG_VALUE, new byte[]{1, 2, 3}));

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarUrl").value(avatarUrl));
    }

    @Test
    void replacingAvatarRemovesOldFile() throws Exception {
        String token = registerAndLogin();
        String pngUrl = upload(token, new MockMultipartFile(
                "file", "a.png", MediaType.IMAGE_PNG_VALUE, new byte[]{1, 2, 3}));
        String jpgUrl = upload(token, new MockMultipartFile(
                "file", "a.jpg", MediaType.IMAGE_JPEG_VALUE, new byte[]{4, 5}));

        mockMvc.perform(get(pngUrl)).andExpect(status().isNotFound());
        mockMvc.perform(get(jpgUrl)).andExpect(status().isOk());
    }

    @Test
    void invalidExtensionRejected() throws Exception {
        String token = registerAndLogin();
        mockMvc.perform(multipart("/api/auth/avatar")
                        .file(new MockMultipartFile("file", "a.txt", MediaType.TEXT_PLAIN_VALUE,
                                "hello".getBytes()))
                        .header("Authorization", "Bearer " + token)
                        .with(request -> {
                            request.setMethod("PUT");
                            return request;
                        }))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("仅支持 png/jpg/webp/gif 格式"));
    }

    @Test
    void missingFileRejected() throws Exception {
        String token = registerAndLogin();
        mockMvc.perform(multipart("/api/auth/avatar")
                        .header("Authorization", "Bearer " + token)
                        .with(request -> {
                            request.setMethod("PUT");
                            return request;
                        }))
                .andExpect(status().isBadRequest());
    }

    @Test
    void avatarRequiresAuth() throws Exception {
        mockMvc.perform(multipart("/api/auth/avatar")
                        .file(new MockMultipartFile("file", "a.png", MediaType.IMAGE_PNG_VALUE,
                                new byte[]{1}))
                        .with(request -> {
                            request.setMethod("PUT");
                            return request;
                        }))
                .andExpect(status().isUnauthorized());
    }
}
