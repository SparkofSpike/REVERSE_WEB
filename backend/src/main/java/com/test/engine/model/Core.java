package com.test.engine.model;

import lombok.Data;

/**
 * The core (核心) of a card pack - a passive rule library granting base
 * actions and defining the pack identity.
 */
@Data
public class Core {

    private String id;
    private String name;
}
