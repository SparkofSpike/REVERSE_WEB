package com.test.engine.model;

import java.util.List;

/**
 * Source of enemy (puppet) templates. The classpath JSON loader implements
 * this today; a future account system can add an admin-DIY implementation
 * (e.g. database-backed) without touching the battle engine or room lobby.
 */
public interface PuppetTemplateProvider {

    /** All known enemy templates. */
    List<PuppetTemplate> list();

    /** Template by id; throws IllegalArgumentException when unknown. */
    PuppetTemplate getPuppet(String id);
}
