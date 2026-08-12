package com.test.engine.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.test.engine.dto.DesignEntry;
import com.test.engine.exception.BusinessException;
import com.test.engine.model.CardPackLoader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Content design (ADMIN/OP): card packs, enemies and playable characters.
 * Definitions are persisted as JSON under app.cards-dir (default ./data/cards)
 * and hot-reloaded, so saved content is available to battles immediately and
 * survives restarts. Built-in classpath definitions can be edited (via a data
 * override file) but not deleted.
 */
@Service
public class DesignService {

    /** Restricts ids to safe file names and blocks path traversal. */
    private static final Pattern ID_PATTERN = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$");

    private final CardPackLoader cardPackLoader;
    private final ObjectMapper mapper;
    private final Path dataDir;

    public DesignService(CardPackLoader cardPackLoader, ObjectMapper mapper,
                         @Value("${app.cards-dir:./data/cards}") String cardsDir) {
        this.cardPackLoader = cardPackLoader;
        this.mapper = mapper;
        this.dataDir = Path.of(cardsDir);
    }

    // ---------- card packs ----------

    public List<DesignEntry> listPacks() {
        return cardPackLoader.all().stream()
                .map(p -> entry(p.getId(), p.getName()))
                .toList();
    }

    public JsonNode getPack(String id) {
        return readFile(id);
    }

    public JsonNode savePack(String id, JsonNode root) {
        return save(id, root, false);
    }

    public void deletePack(String id) {
        deleteFile(id);
    }

    // ---------- enemies ----------

    public List<DesignEntry> listEnemies() {
        return cardPackLoader.list().stream()
                .map(p -> entry(p.getId(), p.getName()))
                .toList();
    }

    public JsonNode getEnemy(String id) {
        return readFile(id);
    }

    public JsonNode saveEnemy(String id, JsonNode root) {
        return save(id, root, true);
    }

    public void deleteEnemy(String id) {
        deleteFile(id);
    }

    // ---------- playable characters (stored inside their pack) ----------

    public List<DesignEntry> listCharacters(String packId) {
        JsonNode characters = requireCharacters(readFile(packId), packId);
        List<DesignEntry> result = new ArrayList<>();
        for (JsonNode character : characters) {
            result.add(entry(character.path("id").asText(), character.path("name").asText()));
        }
        return result;
    }

    public JsonNode addCharacter(String packId, JsonNode character) {
        JsonNode root = readFile(packId);
        ArrayNode characters = requireCharacters(root, packId);
        String id = requireId(character, null);
        for (JsonNode existing : characters) {
            if (existing.path("id").asText().equals(id)) {
                throw new BusinessException("角色已存在: " + id);
            }
        }
        characters.add(character);
        return save(packId, root, false);
    }

    public JsonNode saveCharacter(String packId, String characterId, JsonNode character) {
        JsonNode root = readFile(packId);
        ArrayNode characters = requireCharacters(root, packId);
        String id = requireId(character, characterId);
        boolean replaced = false;
        for (int i = 0; i < characters.size(); i++) {
            if (characters.get(i).path("id").asText().equals(id)) {
                characters.set(i, character);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            throw new BusinessException("角色不存在: " + id);
        }
        return save(packId, root, false);
    }

    public void deleteCharacter(String packId, String characterId) {
        JsonNode root = readFile(packId);
        ArrayNode characters = requireCharacters(root, packId);
        int index = -1;
        for (int i = 0; i < characters.size(); i++) {
            if (characters.get(i).path("id").asText().equals(characterId)) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            throw new BusinessException("角色不存在: " + characterId);
        }
        characters.remove(index);
        save(packId, root, false);
    }

    // ---------- internals ----------

    private JsonNode readFile(String id) {
        validateId(id);
        Path file = dataDir.resolve(id + ".json");
        if (Files.isRegularFile(file)) {
            try {
                return mapper.readTree(file.toFile());
            } catch (IOException e) {
                throw new BusinessException("读取文件失败: " + id);
            }
        }
        // fall back to the classpath built-in definition
        JsonNode builtin = cardPackLoader.rawPack(id);
        if (builtin == null) {
            builtin = cardPackLoader.rawPuppet(id);
        }
        if (builtin == null) {
            throw new BusinessException("内容不存在: " + id);
        }
        return builtin.deepCopy();
    }

    private JsonNode save(String id, JsonNode root, boolean isPuppet) {
        validateId(id);
        requireId(root, id);
        if (isPuppet) {
            if (!root.has("maxHp")) {
                throw new BusinessException("敌人模板缺少 maxHp 字段");
            }
        } else {
            requireCharacters(root, id);
        }
        if (!root.path("name").isTextual() || root.path("name").asText().isBlank()) {
            throw new BusinessException("name 不能为空");
        }
        try {
            Files.createDirectories(dataDir);
            mapper.writerWithDefaultPrettyPrinter()
                    .writeValue(dataDir.resolve(id + ".json").toFile(), root);
            cardPackLoader.reload();
            return root;
        } catch (IOException e) {
            throw new BusinessException("保存失败: " + id);
        }
    }

    private void deleteFile(String id) {
        validateId(id);
        Path file = dataDir.resolve(id + ".json");
        if (!Files.isRegularFile(file)) {
            throw new BusinessException("内置内容不可删除");
        }
        try {
            Files.delete(file);
            cardPackLoader.reload();
        } catch (IOException e) {
            throw new BusinessException("删除失败: " + id);
        }
    }

    private void validateId(String id) {
        if (id == null || !ID_PATTERN.matcher(id).matches()) {
            throw new BusinessException("非法 ID（仅允许字母数字 ._-）");
        }
    }

    private String requireId(JsonNode root, String expected) {
        String id = root.path("id").asText();
        if (id.isBlank() || !ID_PATTERN.matcher(id).matches()) {
            throw new BusinessException("id 不能为空且仅允许字母数字 ._-");
        }
        if (expected != null && !id.equals(expected)) {
            throw new BusinessException("id 与路径不一致");
        }
        return id;
    }

    private ArrayNode requireCharacters(JsonNode root, String packId) {
        JsonNode characters = root.path("characters");
        if (!characters.isArray()) {
            throw new BusinessException("卡牌包缺少 characters 数组: " + packId);
        }
        return (ArrayNode) characters;
    }

    private DesignEntry entry(String id, String name) {
        return new DesignEntry(id, name == null ? "" : name, cardPackLoader.isCustom(id));
    }
}
