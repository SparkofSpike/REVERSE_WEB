package com.test.engine.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * A design-managed definition: card pack, enemy or character.
 */
@Data
@AllArgsConstructor
public class DesignEntry {

    private String id;
    private String name;
    /** True when an override file exists in the data dir (deletable). */
    private boolean custom;
}
