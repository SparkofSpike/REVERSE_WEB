package com.test.engine.service;

import com.test.engine.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

/**
 * Avatar upload/storage. Files live under app.avatar-dir (default
 * ./data/avatars) named {userId}.{ext}; only whitelisted image extensions are
 * accepted and the size is capped at 2MB.
 */
@Service
public class AvatarService {

    private static final long MAX_BYTES = 2 * 1024 * 1024;
    private static final Set<String> ALLOWED_EXT = Set.of("png", "jpg", "jpeg", "webp", "gif");

    private final Path avatarDir;

    public AvatarService(@Value("${app.avatar-dir:./data/avatars}") String avatarDir) {
        this.avatarDir = Path.of(avatarDir);
    }

    /** Stores the file and returns the normalized extension. */
    public String save(Long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("请选择图片文件");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new BusinessException("头像大小不能超过 2MB");
        }
        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String ext = original.contains(".")
                ? original.substring(original.lastIndexOf('.') + 1).toLowerCase() : "";
        if (!ALLOWED_EXT.contains(ext)) {
            throw new BusinessException("仅支持 png/jpg/webp/gif 格式");
        }
        try {
            Files.createDirectories(avatarDir);
            deleteExisting(userId);
            Path target = avatarDir.resolve(userId + "." + ext);
            file.transferTo(target.toAbsolutePath());
            return ext;
        } catch (IOException e) {
            throw new BusinessException("头像保存失败");
        }
    }

    /** Reads an avatar; empty when missing or the extension is unknown. */
    public Optional<byte[]> load(Long userId, String ext) {
        if (!ALLOWED_EXT.contains(ext)) {
            return Optional.empty();
        }
        Path file = avatarDir.resolve(userId + "." + ext);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(file));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private void deleteExisting(Long userId) throws IOException {
        try (var files = Files.list(avatarDir)) {
            for (Path p : files.toList()) {
                if (p.getFileName().toString().startsWith(userId + ".")) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }
}
