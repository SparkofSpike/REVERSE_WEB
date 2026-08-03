package com.test.engine.model;

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
 * Loads card pack definitions from classpath resources/cards/*.json at
 * startup. Adding a new pack only requires dropping a JSON file in.
 */
@Component
public class CardPackLoader {

    private final Map<String, CardPack> packs = new LinkedHashMap<>();

    public CardPackLoader(ObjectMapper mapper) throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath*:cards/*.json");
        for (Resource resource : resources) {
            CardPack pack = mapper.readValue(resource.getInputStream(), CardPack.class);
            packs.put(pack.getId(), pack);
        }
    }

    public CardPack get(String id) {
        CardPack pack = packs.get(id);
        if (pack == null) {
            throw new IllegalArgumentException("unknown card pack: " + id);
        }
        return pack;
    }

    public List<CardPack> all() {
        return new ArrayList<>(packs.values());
    }
}
