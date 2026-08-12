package com.test.engine.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Loads card pack and puppet definitions from classpath resources/cards/*.json
 * plus the writable data dir (app.cards-dir, default ./data/cards). Files in
 * the data dir shadow classpath files with the same id, which is how admin
 * edits to built-in definitions are persisted. JSON with a top-level
 * "characters" array is a card pack; JSON with a top-level "maxHp" field is a
 * puppet (training dummy) template.
 */
@Component
public class CardPackLoader implements PuppetTemplateProvider {

    private final ObjectMapper mapper;
    private final Path dataDir;
    private final Map<String, CardPack> packs = new LinkedHashMap<>();
    private final Map<String, PuppetTemplate> puppets = new LinkedHashMap<>();
    private final Map<String, JsonNode> rawPacks = new LinkedHashMap<>();
    private final Map<String, JsonNode> rawPuppets = new LinkedHashMap<>();

    public CardPackLoader(ObjectMapper mapper,
                          @Value("${app.cards-dir:./data/cards}") String cardsDir) throws IOException {
        this.mapper = mapper;
        this.dataDir = Path.of(cardsDir);
        reload();
    }

    /** Re-scans classpath then the data dir; data-dir files shadow builtins. */
    public synchronized void reload() throws IOException {
        packs.clear();
        puppets.clear();
        rawPacks.clear();
        rawPuppets.clear();

        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath*:cards/*.json");
        for (Resource resource : resources) {
            load(mapper.readTree(resource.getInputStream()));
        }
        if (Files.isDirectory(dataDir)) {
            try (Stream<Path> files = Files.list(dataDir)) {
                List<Path> jsonFiles = files.filter(p -> p.getFileName().toString().endsWith(".json"))
                        .sorted().toList();
                for (Path file : jsonFiles) {
                    load(mapper.readTree(file.toFile()));
                }
            }
        }
    }

    /** True when the given id has an override file in the data dir. */
    public boolean isCustom(String id) {
        return Files.isRegularFile(dataDir.resolve(id + ".json"));
    }

    /** Raw built-in pack JSON; null when the id is not a classpath pack. */
    public JsonNode rawPack(String id) {
        return rawPacks.get(id);
    }

    /** Raw built-in puppet JSON; null when the id is not a classpath puppet. */
    public JsonNode rawPuppet(String id) {
        return rawPuppets.get(id);
    }

    public CardPack get(String id) {
        CardPack pack = packs.get(id);
        if (pack == null) {
            throw new IllegalArgumentException("unknown card pack: " + id);
        }
        return pack;
    }

    @Override
    public PuppetTemplate getPuppet(String id) {
        PuppetTemplate puppet = puppets.get(id);
        if (puppet == null) {
            throw new IllegalArgumentException("unknown puppet template: " + id);
        }
        return puppet;
    }

    @Override
    public List<PuppetTemplate> list() {
        return new ArrayList<>(puppets.values());
    }

    public List<CardPack> all() {
        return new ArrayList<>(packs.values());
    }

    private void load(JsonNode root) {
        if (root.has("maxHp")) {
            PuppetTemplate puppet = mapper.convertValue(root, PuppetTemplate.class);
            puppets.put(puppet.getId(), puppet);
            rawPuppets.put(puppet.getId(), root.deepCopy());
        } else {
            CardPack pack = mapper.convertValue(root, CardPack.class);
            packs.put(pack.getId(), pack);
            rawPacks.put(pack.getId(), root.deepCopy());
        }
    }
}
