package com.test.engine.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.test.engine.dto.DesignEntry;
import com.test.engine.service.DesignService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Content design (ADMIN/OP): card packs, enemies and playable characters.
 * Guarded by hasAnyRole("ADMIN", "OP") in SecurityConfig.
 */
@RestController
@RequestMapping("/api/design")
public class DesignController {

    private final DesignService designService;

    public DesignController(DesignService designService) {
        this.designService = designService;
    }

    // ---------- card packs ----------

    @GetMapping("/packs")
    public List<DesignEntry> listPacks() {
        return designService.listPacks();
    }

    @GetMapping("/packs/{id}")
    public JsonNode getPack(@PathVariable String id) {
        return designService.getPack(id);
    }

    @PostMapping("/packs")
    public JsonNode createPack(@RequestBody JsonNode body) {
        return designService.savePack(body.path("id").asText(), body);
    }

    @PutMapping("/packs/{id}")
    public JsonNode updatePack(@PathVariable String id, @RequestBody JsonNode body) {
        return designService.savePack(id, body);
    }

    @DeleteMapping("/packs/{id}")
    public Map<String, Object> deletePack(@PathVariable String id) {
        designService.deletePack(id);
        return Map.of("ok", true);
    }

    // ---------- enemies ----------

    @GetMapping("/enemies")
    public List<DesignEntry> listEnemies() {
        return designService.listEnemies();
    }

    @GetMapping("/enemies/{id}")
    public JsonNode getEnemy(@PathVariable String id) {
        return designService.getEnemy(id);
    }

    @PostMapping("/enemies")
    public JsonNode createEnemy(@RequestBody JsonNode body) {
        return designService.saveEnemy(body.path("id").asText(), body);
    }

    @PutMapping("/enemies/{id}")
    public JsonNode updateEnemy(@PathVariable String id, @RequestBody JsonNode body) {
        return designService.saveEnemy(id, body);
    }

    @DeleteMapping("/enemies/{id}")
    public Map<String, Object> deleteEnemy(@PathVariable String id) {
        designService.deleteEnemy(id);
        return Map.of("ok", true);
    }

    // ---------- playable characters (inside a pack) ----------

    @GetMapping("/packs/{packId}/characters")
    public List<DesignEntry> listCharacters(@PathVariable String packId) {
        return designService.listCharacters(packId);
    }

    @PostMapping("/packs/{packId}/characters")
    public JsonNode addCharacter(@PathVariable String packId, @RequestBody JsonNode body) {
        return designService.addCharacter(packId, body);
    }

    @PutMapping("/packs/{packId}/characters/{characterId}")
    public JsonNode updateCharacter(@PathVariable String packId, @PathVariable String characterId,
                                    @RequestBody JsonNode body) {
        return designService.saveCharacter(packId, characterId, body);
    }

    @DeleteMapping("/packs/{packId}/characters/{characterId}")
    public Map<String, Object> deleteCharacter(@PathVariable String packId,
                                               @PathVariable String characterId) {
        designService.deleteCharacter(packId, characterId);
        return Map.of("ok", true);
    }
}
