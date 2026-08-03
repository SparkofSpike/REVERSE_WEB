package com.test.engine.dto;

import com.test.engine.entity.Build;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;
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
        return new BuildResponse(build.getId(), build.getName(), build.getPackId(),
                build.getCharacterIds(), build.getInitialPerkId(),
                build.getCreatedAt(), build.getUpdatedAt());
    }
}
