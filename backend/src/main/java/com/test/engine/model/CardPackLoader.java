package com.test.engine.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads card pack and puppet definitions from classpath resources/cards/*.json
 * at startup. JSON with a top-level "characters" array is a card pack; JSON
 * with a top-level "maxHp" field is a puppet (training dummy) template.
 * Adding new definitions only requires dropping a JSON file in.
 */
@Component
public class CardPackLoader implements PuppetTemplateProvider {

    private final Map<String, CardPack> packs = new LinkedHashMap<>();
    private final Map<String, PuppetTemplate> puppets = new LinkedHashMap<>();

    public CardPackLoader(ObjectMapper mapper) throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath*:cards/*.json");
        for (Resource resource : resources) {
            JsonNode root = mapper.readTree(resource.getInputStream());
            if (root.has("maxHp")) {
                PuppetTemplate puppet = mapper.readValue(root.toString(), PuppetTemplate.class);
                puppets.put(puppet.getId(), puppet);
            } else {
                CardPack pack = mapper.readValue(root.toString(), CardPack.class);
                packs.put(pack.getId(), pack);
            }
        }
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
}
