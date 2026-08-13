package com.test.engine.dto;

import com.test.engine.entity.Build;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@AllArgsConstructor
public class BuildResponse {

    private Long id;
    private String name;
    private String packId;
    private List<String> characterIds;
    private String initialPerkId;
    private Instant createdAt;
    private Instant updatedAt;

    public static BuildResponse from(Build build) {
        // Copy the lazy @ElementCollection inside the transaction: keeping the
        // PersistentBag reference alive would fail JSON serialization after the
        // session closes (LazyInitializationException -> 500 on list/get).
        return new BuildResponse(build.getId(), build.getName(), build.getPackId(),
                new ArrayList<>(build.getCharacterIds()), build.getInitialPerkId(),
                build.getCreatedAt(), build.getUpdatedAt());
    }
}
