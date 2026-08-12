package com.test.engine.controller;

import com.test.engine.service.AvatarService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Serves uploaded avatar images. Public on purpose: <img> tags cannot carry a
 * Bearer header, and avatar files contain no private data.
 */
@RestController
@RequestMapping("/api/avatars")
public class AvatarController {

    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "png", MediaType.IMAGE_PNG_VALUE,
            "jpg", MediaType.IMAGE_JPEG_VALUE,
            "jpeg", MediaType.IMAGE_JPEG_VALUE,
            "webp", "image/webp",
            "gif", MediaType.IMAGE_GIF_VALUE);

    private final AvatarService avatarService;

    public AvatarController(AvatarService avatarService) {
        this.avatarService = avatarService;
    }

    @GetMapping("/{userId}.{ext}")
    public ResponseEntity<byte[]> get(@PathVariable Long userId, @PathVariable String ext) {
        return avatarService.load(userId, ext)
                .map(bytes -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(
                                CONTENT_TYPES.getOrDefault(ext, MediaType.APPLICATION_OCTET_STREAM_VALUE)))
                        .body(bytes))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
