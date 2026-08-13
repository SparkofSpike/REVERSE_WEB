package com.test.engine.dto;

import com.test.engine.entity.Build;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/**
 * Guards against LazyInitializationException regressions: BuildResponse.from
 * must copy the lazy @ElementCollection instead of handing out the persistent
 * collection reference (which breaks JSON serialization after the session
 * closes and turns /api/builds into a 500).
 */
class BuildResponseTest {

    @Test
    void fromCopiesCharacterIdsIntoPlainArrayList() {
        Build build = new Build();
        build.setName("deck");
        build.setPackId("test-1");
        build.setCharacterIds(List.of("warrior", "mage"));

        BuildResponse response = BuildResponse.from(build);

        assertEquals(List.of("warrior", "mage"), response.getCharacterIds());
        assertInstanceOf(ArrayList.class, response.getCharacterIds());
        assertNotSame(build.getCharacterIds(), response.getCharacterIds(),
                "response must not keep the persistent collection reference");
    }

    @Test
    void responseIsolatedFromSourceMutation() {
        Build build = new Build();
        build.setCharacterIds(new ArrayList<>(List.of("warrior")));

        BuildResponse response = BuildResponse.from(build);
        build.getCharacterIds().add("mage");

        assertEquals(1, response.getCharacterIds().size());
        assertEquals(List.of("warrior"), response.getCharacterIds());
    }
}
